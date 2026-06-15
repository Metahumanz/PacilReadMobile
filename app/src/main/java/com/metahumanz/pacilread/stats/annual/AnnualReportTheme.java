package com.metahumanz.pacilread.stats.annual;

public enum AnnualReportTheme {
    LIGHT("light", "浅色"),
    DARK("dark", "深色");

    public final String slug;
    public final String label;

    AnnualReportTheme(String slug, String label) {
        this.slug = slug;
        this.label = label;
    }
}
