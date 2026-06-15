package com.metahumanz.pacilread.stats.annual;

public enum AnnualReportStyle {
    QUIET("quiet", "静读留白"),
    HIGHLIGHT("highlight", "高光节奏");

    public final String slug;
    public final String label;

    AnnualReportStyle(String slug, String label) {
        this.slug = slug;
        this.label = label;
    }
}
