package com.zoho.jw.schedule.pdf;

import com.zc.component.smartbrowz.ZCSmartBrowz;
import com.zc.component.smartbrowz.ZCSmartBrowzNavigationOptions;
import com.zc.component.smartbrowz.ZCSmartBrowzPageOptions;
import com.zc.component.smartbrowz.ZCSmartBrowzScreenshotOptions;
import com.zoho.jw.schedule.catalyst.Catalyst;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.logging.Logger;

/**
 * Turns a self-contained HTML document into a PNG using Catalyst SmartBrowz — a real server-side
 * Chrome, so Tamil (Noto Sans Tamil) shaping is pixel-perfect and identical on every device. The
 * SDK handles auth internally from the project's OAuth config (env vars); we never touch a token.
 *
 * <p>Sibling to {@link PdfService}: same SmartBrowz handle, same {@code networkidle0} wait so web
 * fonts finish loading, but takes a screenshot rather than printing a PDF. The viewport width is set
 * explicitly (via {@link ZCSmartBrowzPageOptions}) so board HTML sized ~1040-1120px renders
 * unclipped; {@code fullPage} then captures the entire scroll height.
 */
@Service
public class ImageService
{
    private static final Logger LOGGER = Logger.getLogger(ImageService.class.getName());

    /**
     * @param html     a complete, self-contained HTML document (inline CSS; web fonts via a
     *                 {@code <link>} — SmartBrowz fetches them before capturing)
     * @param width    viewport width in CSS px; null → 1120; clamped to 320..2400
     * @param fullPage true captures the full scroll height, not just the viewport
     * @return PNG bytes
     */
    public byte[] htmlToPng(String html, Integer width, boolean fullPage) throws Exception
    {
        if (html == null || html.isBlank())
        {
            throw new IllegalArgumentException("html is required");
        }

        int w = width == null ? 1120 : Math.max(320, Math.min(2400, width));

        ZCSmartBrowzScreenshotOptions shot = new ZCSmartBrowzScreenshotOptions();
        shot.setType("png");                          // only PNG is supported here
        shot.setFullPage(fullPage);                   // capture the whole board, not just the viewport
        shot.setOmitBackground(false);                // keep the section colours
        shot.setCaptureBeyondViewport(true);          // don't clip content past the viewport

        // Set the viewport width so board HTML (~1040-1120px) lays out at its intended width and
        // isn't squeezed into a default-narrow viewport. Height is a sensible starting point; with
        // fullPage the capture extends to the document's real height.
        ZCSmartBrowzPageOptions page = new ZCSmartBrowzPageOptions();
        ZCSmartBrowzPageOptions.ViewportDetails viewport = new ZCSmartBrowzPageOptions.ViewportDetails();
        viewport.setWidth(w);
        viewport.setHeight(800);
        page.setViewport(viewport);

        // Wait for the network to go idle so web fonts (Noto Sans Tamil) load before the capture.
        ZCSmartBrowzNavigationOptions nav = new ZCSmartBrowzNavigationOptions();
        nav.setWaitUntil("networkidle0");
        nav.setTimeout(30000);

        ZCSmartBrowz smartBrowz = Catalyst.smartBrowz();

        // A full HTML-document string is treated as HTML (not a URL) by SmartBrowz's source detection.
        try (InputStream in = smartBrowz.takeScreenshot(html, shot, page, nav))
        {
            byte[] bytes = in.readAllBytes();
            LOGGER.info("Generated PNG: " + bytes.length + " bytes (width=" + w
                    + ", fullPage=" + fullPage + ")");
            return bytes;
        }
    }
}
