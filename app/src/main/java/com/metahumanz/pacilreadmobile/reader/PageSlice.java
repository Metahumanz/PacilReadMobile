package com.metahumanz.pacilread.reader;

public class PageSlice {
    public final int start;
    public final int end;
    public final CharSequence text;

    public PageSlice(int start, int end, CharSequence text) {
        this.start = start;
        this.end = end;
        this.text = text;
    }
}
