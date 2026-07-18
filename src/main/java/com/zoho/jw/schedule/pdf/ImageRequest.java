package com.zoho.jw.schedule.pdf;

import lombok.Data;

/** Body of {@code POST /image}. */
@Data
public class ImageRequest
{
    /** A complete, self-contained HTML document to render. Required. */
    private String html;

    /**
     * Viewport width in CSS px. Board HTML is sized ~1040-1120px, so this defaults to 1120.
     * Clamped to 320..2400 by {@link ImageService}.
     */
    private Integer width = 1120;

    /** Capture the full scroll height (not just the viewport). Defaults to true. */
    private boolean fullPage = true;

    /** Image type. Only "png" is supported; defaults to "png". */
    private String type = "png";

    /** Suggested download filename (without extension). Optional. */
    private String filename = "schedule";
}
