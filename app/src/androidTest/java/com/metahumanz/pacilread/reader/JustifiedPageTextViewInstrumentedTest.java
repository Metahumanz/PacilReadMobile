package com.metahumanz.pacilread.reader;

import android.content.Context;
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
}
