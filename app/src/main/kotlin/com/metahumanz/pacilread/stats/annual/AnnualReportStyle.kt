package com.metahumanz.pacilread.stats.annual

enum class AnnualReportStyle(
    @JvmField val slug: String,
    @JvmField val label: String,
) {
    QUIET("quiet", "静读留白"),
    HIGHLIGHT("highlight", "高光节奏"),
}
