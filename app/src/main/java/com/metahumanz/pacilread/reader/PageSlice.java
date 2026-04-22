package com.metahumanz.pacilread.reader;

public class PageSlice {
    public final int start;
    public final int end;
    public final int bodyStartInSlice;
    public final int bodyEndInSlice;
    public final CharSequence text;

    public PageSlice(int start, int end, int bodyStartInSlice, int bodyEndInSlice, CharSequence text) {
        CharSequence safeText = text == null ? "" : text;
        int textLength = safeText.length();
        this.start = Math.max(start, 0);
        this.end = Math.max(end, this.start);
        if (bodyStartInSlice < 0 || bodyEndInSlice < 0) {
            this.bodyStartInSlice = -1;
            this.bodyEndInSlice = -1;
        } else {
            this.bodyStartInSlice = Math.max(0, Math.min(bodyStartInSlice, textLength));
            this.bodyEndInSlice = Math.max(this.bodyStartInSlice, Math.min(bodyEndInSlice, textLength));
        }
        this.text = safeText;
    }

    public boolean hasBodyText() {
        return bodyStartInSlice >= 0
                && bodyEndInSlice > bodyStartInSlice
                && end > start;
    }
}
