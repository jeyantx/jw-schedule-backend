package com.zoho.jw.schedule.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.logging.Logger;

/**
 * Server-side proxy for wol.jw.org, so the front-end can read Meeting-Workbook pages (to scrape the
 * weekly thumbnail image URLs) without hitting the browser's CORS wall.
 *
 *   GET /wol/fetch?path=<url-encoded path> -> the upstream page body as text/plain; charset=utf-8
 *
 * The host is ALWAYS wol.jw.org — never taken from the caller. {@code path} is the part after the
 * host (leading slashes stripped); anything with a scheme ("://") or "../" traversal is rejected.
 */
@RestController
public class WolController
{
    private static final Logger LOGGER = Logger.getLogger(WolController.class.getName());

    private static final String HOST = "https://wol.jw.org/";
    private static final long MAX_BYTES = 3L * 1024 * 1024;   // 3MB response cap
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";

    // followRedirects(NORMAL) chases redirects; HttpClient caps a single request chain, but we also
    // bound total time via the per-request timeout above.
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(TIMEOUT)
            .build();

    private final AccessGuard guard;

    public WolController(AccessGuard guard)
    {
        this.guard = guard;
    }

    @GetMapping("/wol/fetch")
    public ResponseEntity<String> fetch(
            @RequestHeader(value = "X-User-Email", required = false) String email,
            @RequestParam("path") String path) throws Exception
    {
        guard.requireEmail(email);   // any signed-in user may proxy; just require identity

        String clean = normalize(path);
        URI uri = URI.create(HOST + clean);

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .GET()
                .build();

        HttpResponse<InputStream> resp;
        try
        {
            resp = CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
        }
        catch (Exception ex)
        {
            LOGGER.warning("wol fetch failed for '" + clean + "': " + ex);
            throw new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                    "Upstream fetch failed: " + ex.getMessage());
        }

        if (resp.statusCode() < 200 || resp.statusCode() >= 300)
        {
            resp.body().close();
            throw new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                    "Upstream returned HTTP " + resp.statusCode() + " for " + clean);
        }

        byte[] body;
        try (InputStream in = resp.body())
        {
            body = readCapped(in);
        }

        LOGGER.info("wol fetch ok: " + clean + " (" + body.length + " bytes)");
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new String(body, StandardCharsets.UTF_8));
    }

    /** Strip leading slashes; reject schemes and path traversal. The host is never caller-supplied. */
    private static String normalize(String path)
    {
        if (path == null || path.isBlank())
        {
            throw ApiException.badRequest("path is required");
        }
        String p = path.trim();
        while (p.startsWith("/")) p = p.substring(1);
        if (p.contains("://") || p.contains(".."))
        {
            throw ApiException.badRequest("Invalid path (no scheme or '..' allowed): " + path);
        }
        return p;
    }

    /** Reads at most MAX_BYTES, throwing if the upstream body is larger. */
    private static byte[] readCapped(InputStream in) throws Exception
    {
        byte[] out = in.readNBytes((int) MAX_BYTES + 1);
        if (out.length > MAX_BYTES)
        {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                    "Upstream response exceeds " + (MAX_BYTES / (1024 * 1024)) + "MB cap");
        }
        return out;
    }
}
