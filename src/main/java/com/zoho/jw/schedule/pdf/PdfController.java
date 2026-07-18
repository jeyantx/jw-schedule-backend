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
 * PDF endpoints.
 *
 *   POST /pdf         — body {@link PdfRequest} (html + options) -> application/pdf
 *   GET  /pdf/sample  — a built-in Tamil CLM sheet, so you can confirm rendering in a browser
 *                       just by opening the URL (no client needed).
 */
@RestController
public class PdfController
{
    private static final Logger LOGGER = Logger.getLogger(PdfController.class.getName());

    private final PdfService pdfService;

    public PdfController(PdfService pdfService)
    {
        this.pdfService = pdfService;
    }

    @PostMapping(value = "/pdf", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> generate(@RequestBody PdfRequest req)
    {
        try
        {
            byte[] pdf = pdfService.htmlToPdf(req.getHtml(), req.isLandscape(), req.getFormat(), req.getMarginMm());
            return pdfResponse(pdf, req.getFilename());
        }
        catch (IllegalArgumentException bad)
        {
            return ResponseEntity.badRequest().body(bad.getMessage());
        }
        catch (Exception ex)
        {
            LOGGER.log(Level.SEVERE, "PDF generation failed", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(stackTrace(ex));
        }
    }

    @GetMapping(value = "/pdf/sample")
    public ResponseEntity<?> sample()
    {
        try
        {
            byte[] pdf = pdfService.htmlToPdf(SampleSheet.HTML, true, "A4", 0); // full-bleed reference render
            return pdfResponse(pdf, "clm-sample");
        }
        catch (Exception ex)
        {
            LOGGER.log(Level.SEVERE, "Sample PDF failed", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(stackTrace(ex));
        }
    }

    private static ResponseEntity<byte[]> pdfResponse(byte[] pdf, String filename)
    {
        String name = (filename == null || filename.isBlank() ? "schedule" : filename.trim()) + ".pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + name + "\"")
                .body(pdf);
    }

    private static byte[] stackTrace(Throwable t)
    {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString().getBytes(StandardCharsets.UTF_8);
    }
}
