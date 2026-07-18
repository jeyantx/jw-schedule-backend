package com.zoho.jw.schedule.pdf;

import com.zc.component.smartbrowz.ZCSmartBrowz;
import com.zc.component.smartbrowz.ZCSmartBrowzNavigationOptions;
import com.zc.component.smartbrowz.ZCSmartBrowzPDFOptions;
import com.zoho.jw.schedule.catalyst.Catalyst;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.logging.Logger;

/**
 * Turns a self-contained HTML document into a PDF using Catalyst SmartBrowz — a real server-side
 * Chrome, so Tamil (Noto Sans Tamil) shaping is pixel-perfect and identical on every device. The
 * SDK handles auth internally from the project's OAuth config (env vars); we never touch a token.
 */
@Service
public class PdfService
{
    private static final Logger LOGGER = Logger.getLogger(PdfService.class.getName());

    /**
     * @param html      a complete, self-contained HTML document (inline CSS; web fonts via a
     *                  {@code <link>} — SmartBrowz fetches them before printing)
     * @param landscape true for the CLM month sheet (wide, 5 columns)
     * @param format    paper size, e.g. "A4" (default) or "A3"
     * @param marginMm  even margin (mm) on all four sides; null → 10mm default; clamped to 0..50
     */
    public byte[] htmlToPdf(String html, boolean landscape, String format, Integer marginMm) throws Exception
    {
        if (html == null || html.isBlank())
        {
            throw new IllegalArgumentException("html is required");
        }

        ZCSmartBrowzPDFOptions pdf = ZCSmartBrowzPDFOptions.getInstance();
        pdf.setLandscape(landscape);
        pdf.setPrintBackground(true);                 // keep the section colours
        pdf.setDisplayHeaderFooter(false);            // no browser header/footer
        pdf.setFormat(format == null || format.isBlank() ? "A4" : format.trim());

        // SmartBrowz exposes no preferCSSPageSize; set the page margin explicitly so the output is
        // a true A4 sheet with even margins regardless of the HTML's @page rules. The server rejects
        // unit suffixes ("10mm" -> "right cannot be less than 0"), so we send a unitless value — same
        // format as the known-good "0" — converting mm to CSS px (96dpi): px = mm * 96 / 25.4.
        int mm = marginMm == null ? 10 : Math.max(0, Math.min(50, marginMm));
        String m = String.valueOf(Math.round(mm * 96.0 / 25.4));
        ZCSmartBrowzPDFOptions.MarginDetails margin = new ZCSmartBrowzPDFOptions.MarginDetails();
        margin.setTop(m);
        margin.setRight(m);
        margin.setBottom(m);
        margin.setLeft(m);
        pdf.setMargin(margin);

        // Wait for the network to go idle so web fonts (Noto Sans Tamil) load before the print.
        ZCSmartBrowzNavigationOptions nav = new ZCSmartBrowzNavigationOptions();
        nav.setWaitUntil("networkidle0");
        nav.setTimeout(30000);

        ZCSmartBrowz smartBrowz = Catalyst.smartBrowz();

        // A full HTML-document string is treated as HTML (not a URL) by SmartBrowz's source detection.
        try (InputStream in = smartBrowz.convertToPdf(html, pdf, null, nav))
        {
            byte[] bytes = in.readAllBytes();
            LOGGER.info("Generated PDF: " + bytes.length + " bytes (landscape=" + landscape
                    + ", format=" + pdf.getFormat() + ", margin=" + m + ")");
            return bytes;
        }
    }
}
