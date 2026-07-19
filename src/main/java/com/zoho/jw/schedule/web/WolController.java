package com.zoho.jw.schedule.web;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Server-side proxy for wol.jw.org, so the front-end can read Meeting-Workbook pages (to scrape the
 * weekly thumbnail image URLs) and thumbnails without hitting the browser's CORS wall. All network +
 * caching work lives in {@link WolService}; this class is the thin HTTP shell.
 *
 *   GET  /wol/fetch?path=<url-encoded path>  -> the upstream page body as text/plain; charset=utf-8
 *   POST /wol/batch  {paths:[...max 20...]}  -> {jobId, total} (fetches run on a background thread,
 *                                               surviving the ~60s request cap; client then polls)
 *   GET  /wol/batch/{jobId}                  -> {done,total,finished,errors, results-when-finished}
 *                                               410 when the job is unknown (instance restarted)
 *   GET  /wol/image?path=<url-encoded path>  -> the upstream image bytes with its content-type
 *
 * The host is ALWAYS wol.jw.org — never taken from the caller. {@code path} is the part after the
 * host (leading slashes stripped); anything with a scheme ("://") or "../" traversal is rejected.
 */
@RestController
public class WolController
{
    private final AccessGuard guard;
    private final WolService wol;

    public WolController(AccessGuard guard, WolService wol)
    {
        this.guard = guard;
        this.wol = wol;
    }

    @GetMapping("/wol/fetch")
    public ResponseEntity<String> fetch(
            @RequestHeader(value = "X-User-Email", required = false) String email,
            @RequestParam("path") String path)
    {
        guard.requireEmail(email);   // any signed-in user may proxy; just require identity
        String body = wol.fetchHtml(path);
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }

    @PostMapping("/wol/batch")
    public ResponseEntity<Map<String, Object>> batch(
            @RequestHeader(value = "X-User-Email", required = false) String email,
            @RequestBody BatchRequest req)
    {
        guard.requireEmail(email);
        List<String> paths = req == null ? null : req.paths;
        WolService.Job job = wol.startBatch(paths);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jobId", job.id);
        out.put("total", job.total);
        return ResponseEntity.ok(out);
    }

    @GetMapping("/wol/batch/{jobId}")
    public ResponseEntity<Map<String, Object>> batchStatus(
            @RequestHeader(value = "X-User-Email", required = false) String email,
            @PathVariable("jobId") String jobId)
    {
        guard.requireEmail(email);
        WolService.Job job = wol.getJob(jobId);
        if (job == null)
        {
            // The JVM was recycled (idle shutdown) or the job expired — tell the client to resubmit.
            throw new ApiException(HttpStatus.GONE, "job not found (instance restarted)");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("done", job.done);
        out.put("total", job.total);
        out.put("finished", job.finished);
        out.put("errors", job.errors);
        // Keep the polling payload tiny: only ship the page bodies once, when the job is finished.
        out.put("results", job.finished ? job.results : null);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(out);
    }

    @GetMapping("/wol/image")
    public ResponseEntity<byte[]> image(
            @RequestHeader(value = "X-User-Email", required = false) String email,
            @RequestParam("path") String path)
    {
        guard.requireEmail(email);
        WolService.Image img = wol.fetchImage(path);
        MediaType type;
        try { type = MediaType.parseMediaType(img.contentType); }
        catch (Exception ex) { type = MediaType.IMAGE_JPEG; }
        return ResponseEntity.ok()
                .contentType(type)
                .cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS))
                .body(img.bytes);
    }

    /** POST /wol/batch body. */
    public static final class BatchRequest
    {
        public List<String> paths;
    }
}
