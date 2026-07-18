package com.zoho.jw.schedule.pdf;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Image (PNG) endpoints — the screenshot sibling of {@link PdfController}.
 *
 *   POST /image         — body {@link ImageRequest} (html + options) -> image/png
 *   GET  /image/sample  — the built-in Tamil CLM sheet as a PNG, so you can confirm rendering in a
 *                         browser just by opening the URL (no client needed).
 */
@RestController
public class ImageController
{
    private static final Logger LOGGER = Logger.getLogger(ImageController.class.getName());

    private final ImageService imageService;

    public ImageController(ImageService imageService)
    {
        this.imageService = imageService;
    }

    @PostMapping(value = "/image", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> generate(@RequestBody ImageRequest req)
    {
        try
        {
            byte[] png = imageService.htmlToPng(req.getHtml(), req.getWidth(), req.isFullPage());
            return pngResponse(png, req.getFilename());
        }
        catch (IllegalArgumentException bad)
        {
            return ResponseEntity.badRequest().body(bad.getMessage());
        }
        catch (Exception ex)
        {
            LOGGER.log(Level.SEVERE, "Image generation failed", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(stackTrace(ex));
        }
    }

    @GetMapping(value = "/image/sample")
    public ResponseEntity<?> sample()
    {
        try
        {
            byte[] png = imageService.htmlToPng(SampleSheet.HTML, 1120, true);
            return pngResponse(png, "clm-sample");
        }
        catch (Exception ex)
        {
            LOGGER.log(Level.SEVERE, "Sample image failed", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(stackTrace(ex));
        }
    }

    private static ResponseEntity<byte[]> pngResponse(byte[] png, String filename)
    {
        String name = (filename == null || filename.isBlank() ? "schedule" : filename.trim()) + ".png";
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + name + "\"")
                .body(png);
    }

    private static byte[] stackTrace(Throwable t)
    {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString().getBytes(StandardCharsets.UTF_8);
    }
}
