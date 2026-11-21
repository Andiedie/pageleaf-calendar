package io.github.pageleaf.calendar;

import android.test.ActivityInstrumentationTestCase2;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the actual view hierarchy on the device without adding demo sessions to the app. */
@SuppressWarnings("deprecation")
public class CalendarScreenTest extends ActivityInstrumentationTestCase2<MainActivity> {
    public CalendarScreenTest() { super(MainActivity.class); }

    public void testLaunchAndRenderCalendarWeekWithLocalData() throws Throwable {
        MainActivity activity = getActivity();
        AtomicReference<LocalDate> first = new AtomicReference<>();
        AtomicReference<LocalDate> end = new AtomicReference<>();
        runTestOnUiThread(() -> {
            CalendarScreen screen = new CalendarScreen(activity, new CalendarScreen.Actions() {
                public void rangeChanged(LocalDate start, LocalDate until) { first.set(start); end.set(until); }
                public void toggle(String id, boolean enabled) { }
                public void connect() { }
                public void refresh() { }
            });
            activity.setContentView(screen.view());
            screen.start();
            LocalDate today = LocalDate.now();
            long start = today.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            screen.setData(Arrays.asList(new Data.Source("team", "团队日历", 0xff54869b, true, true, ""),
                            new Data.Source("family", "家庭", 0xffab7c30, false, true, "")),
                    Collections.singletonList(new Data.Entry("one", "team", "日程测试", start,
                            start + 3_600_000, false)), "上次同步 12:00", true);
            screen.tick();
        });
        getInstrumentation().waitForIdleSync();
        assertNotNull(first.get());
        assertFalse(LocalDate.now().isBefore(first.get()));
        assertTrue(LocalDate.now().isBefore(end.get()));
        assertTrue(activity.findViewById(android.R.id.content).getWidth() > 0);
        assertTrue("Expected date navigation or calendar controls", hasLabel(activity.getWindow().getDecorView(), "今天"));
    }

    private static boolean hasLabel(View view, String label) {
        if (view instanceof TextView && ((TextView) view).getText().toString().contains(label)) return true;
        if (view.getContentDescription() != null && view.getContentDescription().toString().contains(label)) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i=0; i < group.getChildCount(); i++) if (hasLabel(group.getChildAt(i), label)) return true;
        }
        return false;
    }
}
