package com.metahumanz.pacilread.reader

class PageSlice(start: Int, end: Int, bodyStartInSlice: Int, bodyEndInSlice: Int, text: CharSequence?) {
    @JvmField val start: Int = start.coerceAtLeast(0)
    @JvmField val end: Int = end.coerceAtLeast(this.start)
    @JvmField val bodyStartInSlice: Int
    @JvmField val bodyEndInSlice: Int
    @JvmField val text: CharSequence = text ?: ""

    init {
        if (bodyStartInSlice < 0 || bodyEndInSlice < 0) {
            this.bodyStartInSlice = -1
            this.bodyEndInSlice = -1
        } else {
            this.bodyStartInSlice = bodyStartInSlice.coerceIn(0, this.text.length)
            this.bodyEndInSlice = bodyEndInSlice.coerceIn(this.bodyStartInSlice, this.text.length)
        }
    }

    fun hasBodyText(): Boolean =
        bodyStartInSlice >= 0 && bodyEndInSlice > bodyStartInSlice && end > start
}
