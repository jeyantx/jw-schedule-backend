package com.zoho.jw.schedule.pdf;

import lombok.Data;

/** Body of {@code POST /pdf}. */
@Data
public class PdfRequest
{
    /** A complete, self-contained HTML document to render. Required. */
    private String html;

    /** Landscape orientation. Defaults to true (the CLM sheet is wide). */
    private boolean landscape = true;

    /** Paper size. Defaults to A4. */
    private String format = "A4";

    /**
     * Even page margin in millimetres, applied to all four sides. Defaults to 10mm. Clamped to
     * 0..50 by {@link PdfService}. (SmartBrowz has no preferCSSPageSize option, so the server sets
     * the margin explicitly rather than deferring to the HTML's {@code @page} rules.)
     */
    private Integer marginMm = 10;

    /** Suggested download filename (without extension). Optional. */
    private String filename = "schedule";
}
