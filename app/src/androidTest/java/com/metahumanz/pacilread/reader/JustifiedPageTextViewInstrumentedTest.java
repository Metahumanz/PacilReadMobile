package com.metahumanz.pacilread.reader;

import android.content.Context;
import android.text.Layout;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class JustifiedPageTextViewInstrumentedTest {
    @Test
    public void topGravityKeepsShortBodyPageAtTopOfReadableArea() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            JustifiedPageTextView view = new JustifiedPageTextView(context);
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, 32f);
            view.setLineSpacing(0f, 1f);
            view.setPadding(0, 0, 0, 0);
            view.setGravity(Gravity.START | Gravity.TOP);
            view.setText("短页正文");

            int widthSpec = View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY);
            int heightSpec = View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY);
            view.measure(widthSpec, heightSpec);
            view.layout(0, 0, 400, 600);

            assertNotNull(view.getLayout());
            assertTrue(view.getLayout().getHeight() < view.getHeight());
            assertEquals(0, view.getExtendedPaddingTop());
            assertEquals(0, view.getLayout().getLineTop(0));
        });
    }

    @Test
    public void bottomJustifyKeepsFirstLineTopAndMovesLastLineToBottom() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            JustifiedPageTextView view = newReaderTextView(context);
            view.setBottomJustifyEnabled(true);
            view.setText("第一行文字\n第二行文字\n第三行文字");

            layoutAt(view, 480, 600);
            Layout layout = view.getLayout();
            assertNotNull(layout);
            int lastLine = layout.getLineCount() - 1;
            int naturalBottom = layout.getLineBottom(lastLine);
            int lastLineHeight = naturalBottom - layout.getLineTop(lastLine);
            int targetHeight = naturalBottom + Math.max(1, Math.min(12, lastLineHeight - 1));

            layoutAt(view, 480, targetHeight);

            assertEquals(0f, view.adjustedLineTopForTest(0), 0.01f);
            assertTrue(view.getLayout().getLineBottom(lastLine) < targetHeight);
            assertEquals(targetHeight, view.adjustedVisualLineBottomForTest(lastLine), 1f);
        });
    }

    @Test
    public void bottomJustifyDoesNotVerticallyCenterShortPages() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            JustifiedPageTextView view = newReaderTextView(context);
            view.setBottomJustifyEnabled(true);
            view.setText("短页第一行\n短页第二行");

            layoutAt(view, 480, 600);
            Layout layout = view.getLayout();
            assertNotNull(layout);
            int lastLine = layout.getLineCount() - 1;
            int naturalBottom = layout.getLineBottom(lastLine);
            int lastLineHeight = naturalBottom - layout.getLineTop(lastLine);
            int shortPageHeight = naturalBottom + lastLineHeight + 8;

            layoutAt(view, 480, shortPageHeight);

            assertEquals(0f, view.adjustedLineTopForTest(0), 0.01f);
            assertEquals(view.getLayout().getLineBottom(lastLine),
                    view.adjustedVisualLineBottomForTest(lastLine),
                    0.01f);
            assertTrue(view.adjustedVisualLineBottomForTest(lastLine) < shortPageHeight);
        });
    }

    private JustifiedPageTextView newReaderTextView(Context context) {
        JustifiedPageTextView view = new JustifiedPageTextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, 32f);
        view.setLineSpacing(0f, 1f);
        view.setPadding(0, 0, 0, 0);
        view.setGravity(Gravity.START | Gravity.TOP);
        return view;
    }

    private void layoutAt(JustifiedPageTextView view, int width, int height) {
        int widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY);
        view.measure(widthSpec, heightSpec);
        view.layout(0, 0, width, height);
    }
}
