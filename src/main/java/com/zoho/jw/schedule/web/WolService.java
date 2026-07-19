package com.zoho.jw.schedule.web;

import com.zoho.jw.schedule.catalyst.Catalyst;
import com.zoho.jw.schedule.catalyst.Stratus;
import org.json.JSONObject;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.logging.Logger;

/**
 * All wol.jw.org network work lives here so the controller stays a thin HTTP shell.
 *
 * Hardening (see WolController for the endpoint contracts):
 *  - Generous upstream timeouts (25s) + ONE retry on timeout/5xx, desktop UA, follow redirects.
 *  - An in-memory page cache (immutable-per-URL workbook pages) so repeat fetches never re-hit wol.
 *  - A background batch engine: a request thread submits a job and returns immediately (Catalyst caps
 *    a UI-invoked request at ~60s), a single background thread does the fetching, the client polls.
 *  - A small image cache + proxy so the server-side PDF renderer gets embeddable bytes.
 */
@Service
public class WolService
{
    private static final Logger LOGGER = Logger.getLogger(WolService.class.getName());

    private static final String HOST = "https://wol.jw.org/";
    // Hosts we will read bytes FROM (the initial request is always wol.jw.org; images may redirect
    // to jw-cdn.org / jw.org asset domains — those redirect targets are allowed).
    private static final long MAX_HTML_BYTES = 3L * 1024 * 1024;    // 3MB page cap
    private static final long MAX_IMAGE_BYTES = 2L * 1024 * 1024;   // 2MB image cap
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(25);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(25);
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";

    private static final long PAGE_TTL_MS = 7L * 24 * 60 * 60 * 1000;   // 7 days
    private static final int PAGE_CACHE_CAP = 100;
    private static final int IMAGE_CACHE_CAP = 50;
    private static final int MAX_BATCH_PATHS = 20;
    private static final long JOB_TTL_MS = 5L * 60 * 1000;   // jobs expire 5 min after finishing

    // L2 (Stratus) cache prefixes. Catalyst AppSail shuts an idle JVM down after ~1 min, so the
    // in-memory L1 cache is lost on every cold start; the workbook pages/thumbnails are immutable
    // per URL, so we mirror them into object storage keyed by the path's sha-256.
    private static final String L2_PAGE_PREFIX = "wolcache/pages/";
    private static final String L2_IMAGE_PREFIX = "wolcache/images/";

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    // path -> cached page. LinkedHashMap-free: eviction picks the oldest fetchedAt when over cap.
    private final Map<String, Page> pageCache = new ConcurrentHashMap<>();
    private final Map<String, Image> imageCache = new ConcurrentHashMap<>();
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    // Survives past the 60s request limit: the request thread submits here and returns immediately.
    private final ExecutorService batchExecutor = Executors.newSingleThreadExecutor(new ThreadFactory()
    {
        @Override public Thread newThread(Runnable r)
        {
            Thread t = new Thread(r, "wol-batch");
            t.setDaemon(true);
            return t;
        }
    });

    // ------------------------------------------------------------------ cached HTML fetch

    /** Fetch a wol page as text: L1 memory → L2 Stratus → upstream. Throws ApiException on failure. */
    public String fetchHtml(String rawPath)
    {
        String path = normalize(rawPath);
        long now = System.currentTimeMillis();

        // L1 — warm-instance memory cache.
        Page cached = pageCache.get(path);
        if (cached != null && (now - cached.fetchedAt) < PAGE_TTL_MS) return cached.body;

        // L2 — Stratus object (survives cold starts). Promote a fresh hit back into L1.
        String l2 = readPageL2(path, now);
        if (l2 != null)
        {
            pageCache.put(path, new Page(l2, now));
            return l2;
        }

        HttpResponse<byte[]> resp = sendWithRetry(URI.create(HOST + path),
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8", path);

        if (resp.statusCode() < 200 || resp.statusCode() >= 300)
        {
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Upstream returned HTTP " + resp.statusCode() + " for " + path);
        }
        byte[] body = resp.body();
        if (body.length > MAX_HTML_BYTES)
        {
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Upstream response exceeds " + (MAX_HTML_BYTES / (1024 * 1024)) + "MB cap");
        }
        String text = new String(body, StandardCharsets.UTF_8);
        putPage(path, text);
        writePageL2(path, text);
        LOGGER.info("wol fetch ok: " + path + " (" + body.length + " bytes)");
        return text;
    }

    // ------------------------------------------------------------------ cached image proxy

    /** Fetch a wol image (follows redirects to CDN): L1 memory → L2 Stratus → upstream. */
    public Image fetchImage(String rawPath)
    {
        String path = normalize(rawPath);
        Image cached = imageCache.get(path);
        if (cached != null) return cached;

        Image l2 = readImageL2(path);
        if (l2 != null) { putImage(path, l2); return l2; }

        HttpResponse<byte[]> resp = sendWithRetry(URI.create(HOST + path), "image/*,*/*;q=0.8", path);
        if (resp.statusCode() < 200 || resp.statusCode() >= 300)
        {
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Upstream returned HTTP " + resp.statusCode() + " for " + path);
        }
        byte[] body = resp.body();
        if (body.length > MAX_IMAGE_BYTES)
        {
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Image exceeds " + (MAX_IMAGE_BYTES / (1024 * 1024)) + "MB cap");
        }
        String type = resp.headers().firstValue("content-type").orElse("image/jpeg");
        Image img = new Image(body, type);
        putImage(path, img);
        writeImageL2(path, img);
        return img;
    }

    // ------------------------------------------------------------------ async batch

    /** Validate + start a background batch job for these paths; returns the freshly-created job. */
    public Job startBatch(List<String> rawPaths)
    {
        sweepExpiredJobs();
        if (rawPaths == null || rawPaths.isEmpty())
        {
            throw ApiException.badRequest("paths is required (non-empty array)");
        }
        if (rawPaths.size() > MAX_BATCH_PATHS)
        {
            throw ApiException.badRequest("Too many paths (max " + MAX_BATCH_PATHS + ")");
        }
        // Normalize + dedupe up front so validation errors surface on the submit request, not later.
        List<String> paths = new ArrayList<>();
        for (String p : rawPaths)
        {
            String clean = normalize(p);
            if (!paths.contains(clean)) paths.add(clean);
        }

        Job job = new Job(UUID.randomUUID().toString(), paths);
        jobs.put(job.id, job);
        batchExecutor.submit(() -> runBatch(job));
        return job;
    }

    /** Look up a job (returns null if unknown/expired). Sweeps expired jobs as a side effect. */
    public Job getJob(String id)
    {
        sweepExpiredJobs();
        return jobs.get(id);
    }

    private void runBatch(Job job)
    {
        for (String path : job.paths)
        {
            try
            {
                job.results.put(path, fetchHtml(path));
            }
            catch (Exception ex)
            {
                job.errors.put(path, ex.getMessage() == null ? ex.toString() : ex.getMessage());
                LOGGER.warning("wol batch path failed '" + path + "': " + ex);
            }
            finally
            {
                job.done++;
            }
        }
        job.finishedAt = System.currentTimeMillis();
        job.finished = true;
    }

    private void sweepExpiredJobs()
    {
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<String, Job>> it = jobs.entrySet().iterator(); it.hasNext(); )
        {
            Job j = it.next().getValue();
            if (j.finished && (now - j.finishedAt) > JOB_TTL_MS) it.remove();
        }
    }

    // ------------------------------------------------------------------ HTTP + caches

    /** One retry on timeout / 5xx, then give up with a 502. */
    private HttpResponse<byte[]> sendWithRetry(URI uri, String accept, String label)
    {
        Exception last = null;
        for (int attempt = 1; attempt <= 2; attempt++)
        {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(REQUEST_TIMEOUT)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", accept)
                    .GET()
                    .build();
            try
            {
                HttpResponse<byte[]> resp = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (resp.statusCode() >= 500 && attempt == 1)
                {
                    LOGGER.warning("wol " + label + " HTTP " + resp.statusCode() + " — retrying");
                    continue;   // one retry on a 5xx
                }
                return resp;
            }
            catch (Exception ex)
            {
                last = ex;
                LOGGER.warning("wol " + label + " attempt " + attempt + " failed: " + ex);
                // fall through to retry (attempt 1) or throw (attempt 2)
            }
        }
        throw new ApiException(HttpStatus.BAD_GATEWAY,
                "Upstream fetch failed: " + (last == null ? "unknown error" : last.getMessage()));
    }

    private void putPage(String path, String body)
    {
        pageCache.put(path, new Page(body, System.currentTimeMillis()));
        if (pageCache.size() > PAGE_CACHE_CAP) evictOldestPage();
    }

    private void evictOldestPage()
    {
        String oldestKey = null;
        long oldest = Long.MAX_VALUE;
        for (Map.Entry<String, Page> e : pageCache.entrySet())
        {
            if (e.getValue().fetchedAt < oldest) { oldest = e.getValue().fetchedAt; oldestKey = e.getKey(); }
        }
        if (oldestKey != null) pageCache.remove(oldestKey);
    }

    private void putImage(String path, Image img)
    {
        imageCache.put(path, img);
        if (imageCache.size() > IMAGE_CACHE_CAP)
        {
            String oldestKey = null;
            long oldest = Long.MAX_VALUE;
            for (Map.Entry<String, Image> e : imageCache.entrySet())
            {
                if (e.getValue().fetchedAt < oldest) { oldest = e.getValue().fetchedAt; oldestKey = e.getKey(); }
            }
            if (oldestKey != null) imageCache.remove(oldestKey);
        }
    }

    // ------------------------------------------------------------------ L2 (Stratus) cache
    // All best-effort: a Stratus hiccup must never break a fetch, only cost a cache miss. Each call
    // sets the admin thread-local because the batch worker runs off the request thread.

    /** Page envelope in Stratus = "<fetchedAtMillis>\n<body>" (avoids JSON-escaping big HTML). */
    private String readPageL2(String path, long now)
    {
        try
        {
            Catalyst.asAdmin();
            String raw = Stratus.get(L2_PAGE_PREFIX + sha256(path));
            if (raw == null) return null;
            int nl = raw.indexOf('\n');
            if (nl <= 0) return null;
            long fetchedAt = Long.parseLong(raw.substring(0, nl).trim());
            if ((now - fetchedAt) >= PAGE_TTL_MS) return null;   // stale — refetch
            return raw.substring(nl + 1);
        }
        catch (Exception ex)
        {
            LOGGER.warning("wol L2 page read failed for '" + path + "': " + ex);
            return null;
        }
    }

    private void writePageL2(String path, String body)
    {
        try
        {
            Catalyst.asAdmin();
            Stratus.put(L2_PAGE_PREFIX + sha256(path), System.currentTimeMillis() + "\n" + body);
        }
        catch (Exception ex)
        {
            LOGGER.warning("wol L2 page write failed for '" + path + "': " + ex);
        }
    }

    /** Image envelope in Stratus = JSON {"ct":<content-type>,"b64":<base64 bytes>}. */
    private Image readImageL2(String path)
    {
        try
        {
            Catalyst.asAdmin();
            String raw = Stratus.get(L2_IMAGE_PREFIX + sha256(path));
            if (raw == null) return null;
            JSONObject o = new JSONObject(raw);
            byte[] bytes = Base64.getDecoder().decode(o.getString("b64"));
            return new Image(bytes, o.optString("ct", "image/jpeg"));
        }
        catch (Exception ex)
        {
            LOGGER.warning("wol L2 image read failed for '" + path + "': " + ex);
            return null;
        }
    }

    private void writeImageL2(String path, Image img)
    {
        try
        {
            Catalyst.asAdmin();
            JSONObject o = new JSONObject();
            o.put("ct", img.contentType);
            o.put("b64", Base64.getEncoder().encodeToString(img.bytes));
            Stratus.put(L2_IMAGE_PREFIX + sha256(path), o.toString());
        }
        catch (Exception ex)
        {
            LOGGER.warning("wol L2 image write failed for '" + path + "': " + ex);
        }
    }

    private static String sha256(String s)
    {
        try
        {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        }
        catch (Exception ex)
        {
            throw new IllegalStateException("SHA-256 unavailable", ex);   // never happens on a JRE
        }
    }

    /** Strip leading slashes; reject schemes and path traversal. The host is never caller-supplied. */
    static String normalize(String path)
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

    // ------------------------------------------------------------------ value types

    private static final class Page
    {
        final String body;
        final long fetchedAt;
        Page(String body, long fetchedAt) { this.body = body; this.fetchedAt = fetchedAt; }
    }

    public static final class Image
    {
        public final byte[] bytes;
        public final String contentType;
        final long fetchedAt;
        Image(byte[] bytes, String contentType)
        {
            this.bytes = bytes;
            this.contentType = contentType;
            this.fetchedAt = System.currentTimeMillis();
        }
    }

    /** A background batch job. Counters are written only on the single batch thread. */
    public static final class Job
    {
        public final String id;
        final List<String> paths;
        public final int total;
        public final Map<String, String> results = new ConcurrentHashMap<>();
        public final Map<String, String> errors = new ConcurrentHashMap<>();
        public volatile int done;
        public volatile boolean finished;
        volatile long finishedAt;

        Job(String id, List<String> paths)
        {
            this.id = id;
            this.paths = paths;
            this.total = paths.size();
        }
    }
}
