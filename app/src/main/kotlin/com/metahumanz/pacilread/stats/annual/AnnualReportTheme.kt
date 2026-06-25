package com.metahumanz.pacilread.stats.annual

enum class AnnualReportTheme(
    @JvmField val slug: String,
    @JvmField val label: String,
) {
    LIGHT("light", "浅色"),
    DARK("dark", "深色"),
}
