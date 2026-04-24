package com.metahumanz.pacilread.reader;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.StyleSpan;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class ReaderPaginatorInstrumentedTest {
    @Test
    public void findPageForOffset_skipsTitleOnlyPagesWhenOffsetIsZero() {
        SpannableStringBuilder source = new SpannableStringBuilder()
                .append("标题一\n")
                .append("标题二\n")
                .append("标题三\n")
                .append("标题四\n")
                .append(" \n");
        int bodyStartIndex = source.length();
        source.append("正文甲\n正文乙");

        List<PageSlice> pages = ReaderPaginator.paginate(source, newPaint(), 400, 48, 48, 0f, bodyStartIndex);

        int firstBodyPageIndex = -1;
        for (int i = 0; i < pages.size(); i++) {
            if (pages.get(i).hasBodyText()) {
                firstBodyPageIndex = i;
                break;
            }
        }

        assertTrue(firstBodyPageIndex > 0);
        assertFalse(pages.get(0).hasBodyText());
        assertFalse(pages.get(1).hasBodyText());
        assertEquals(firstBodyPageIndex, ReaderPaginator.findPageForOffset(pages, 0));
        assertEquals(0, pages.get(firstBodyPageIndex).start);
    }

    @Test
    public void pageSlice_tracksBodyStartInsideMixedTitleAndBodyPage() {
        SpannableStringBuilder source = new SpannableStringBuilder()
                .append("章节标题\n")
                .append(" \n");
        int bodyStartIndex = source.length();
        source.append("正文第一段内容，长度足够让它留在第一页。");

        List<PageSlice> pages = ReaderPaginator.paginate(source, newPaint(), 800, 160, 160, 0f, bodyStartIndex);

        assertEquals(1, pages.size());
        PageSlice firstPage = pages.get(0);
        assertTrue(firstPage.hasBodyText());
        assertEquals(0, firstPage.start);
        assertTrue(firstPage.bodyStartInSlice > 0);
        assertEquals(firstPage.text.length(), firstPage.bodyEndInSlice);
    }

    @Test
    public void paginate_keepsParagraphEndLineWhenOnlyBottomSpacingOverflows() {
        SpannableStringBuilder source = new SpannableStringBuilder()
                .append("第一行\n");
        source.append("第二行");
        int secondLineVisibleEnd = source.length();
        source.append("\n");
        int secondLineEnd = source.length();
        source.setSpan(
                new ReaderParagraphBottomSpacingSpan(120),
                secondLineVisibleEnd,
                secondLineEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        );
        source.append("第三行");

        List<PageSlice> pages = ReaderPaginator.paginate(source, newPaint(), 800, 100, 100, 0f, 0);

        assertTrue(pages.size() > 1);
        assertTrue(pages.get(0).text.toString().contains("第二行"));
        assertFalse(pages.get(0).text.toString().contains("第三行"));
    }

    @Test
    public void paginate_preservesTitleSpansOnTitlePages() {
        SpannableStringBuilder source = new SpannableStringBuilder();
        int titleStart = source.length();
        source.append("带样式标题\n");
        int titleEnd = source.length();
        source.setSpan(new StyleSpan(Typeface.BOLD), titleStart, titleEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        source.append(" \n");
        int bodyStartIndex = source.length();
        source.append("正文内容");

        List<PageSlice> pages = ReaderPaginator.paginate(source, newPaint(), 400, 48, 48, 0f, bodyStartIndex);

        assertTrue(pages.get(0).text instanceof Spanned);
        assertTrue(((Spanned) pages.get(0).text).getSpans(0, pages.get(0).text.length(), StyleSpan.class).length > 0);
        int lastBodyPageIndex = -1;
        for (int i = 0; i < pages.size(); i++) {
            if (pages.get(i).hasBodyText()) {
                lastBodyPageIndex = i;
            }
        }
        assertEquals(lastBodyPageIndex, ReaderPaginator.findPageForOffset(pages, 999));
    }

    private TextPaint newPaint() {
        TextPaint paint = new TextPaint();
        paint.setTextSize(32f);
        return paint;
    }
}
