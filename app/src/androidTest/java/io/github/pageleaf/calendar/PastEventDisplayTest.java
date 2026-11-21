package io.github.pageleaf.calendar;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.test.ActivityInstrumentationTestCase2;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

@SuppressWarnings("deprecation")
public class PastEventDisplayTest extends ActivityInstrumentationTestCase2<MainActivity> {
    private final LocalDate wednesday = LocalDate.of(2025, 4, 9);
    private final ZoneId zone = ZoneId.of("Asia/Shanghai");
    private CalendarScreen screen;
    private int requests;

    public PastEventDisplayTest() { super(MainActivity.class); }

    private ZonedDateTime at(int day, int hour) {
        return wednesday.plusDays(day).atTime(hour, 0).atZone(zone);
    }

    private Data.Entry event(String title, ZonedDateTime start, ZonedDateTime end, boolean allDay) {
        return new Data.Entry(title, "team", title, start.toInstant().toEpochMilli(), end.toInstant().toEpochMilli(), allDay);
    }

    private void open(CalendarGeometry.Mode mode, Data.Entry... entries) throws Throwable {
        MainActivity activity = getActivity();
        runTestOnUiThread(() -> {
            screen = new CalendarScreen(activity, new CalendarScreen.Actions() {
                public void rangeChanged(LocalDate start, LocalDate end) { requests++; }
                public void toggle(String id, boolean enabled) { }
                public void connect() { }
                public void refresh() { }
            });
            screen.tick(at(0, 9));
            screen.select(mode, wednesday);
            activity.setContentView(screen.view());
            screen.start();
            screen.setData(Collections.singletonList(new Data.Source("team", "团队", 0xff467b99, true, true, "")),
                    Arrays.asList(entries), "", true, 0);
        });
        getInstrumentation().waitForIdleSync();
    }

    private View find(View root, String value, boolean description) {
        if (description && root.getContentDescription() != null && root.getContentDescription().toString().startsWith(value)) return root;
        if (!description && root instanceof TextView && value.contentEquals(((TextView) root).getText())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View match = find(group.getChildAt(i), value, description);
                if (match != null) return match;
            }
        }
        return null;
    }

    private View control(String prefix) {
        View v = find(screen.root, prefix, true);
        assertNotNull(prefix, v);
        return v;
    }

    private View label(View root, String text) {
        View v = find(root, text, false);
        assertNotNull(text, v);
        return v;
    }

    private void assertPast(View v, boolean past) {
        float alpha = v.getAlpha();
        while (v.getParent() instanceof View) {
            v = (View) v.getParent();
            alpha *= v.getAlpha();
        }
        if (past) assertTrue("Expected readable dimmed content, alpha=" + alpha, alpha > .5f && alpha < 1f);
        else assertEquals(1f, alpha, .001f);
    }

    public void testWeekSegmentsAndPausedClockAtExclusiveEnd() throws Throwable {
        open(CalendarGeometry.Mode.WEEK, event("夜班", at(-1, 22), at(0, 10), false),
                event("明日", at(1, 9), at(1, 11), false));
        runTestOnUiThread(() -> {
            View ended = control("夜班，22:00–24:00");
            View current = control("夜班，00:00–10:00");
            assertWeekCard(ended, true);
            assertWeekCard(current, false);
            assertWeekCard(control("明日，"), false);
            screen.followWeekTime = false;
            screen.tick(at(0, 10).minusNanos(1_000_000));
            assertWeekCard(current, false);
            screen.tick(at(0, 10));
            assertWeekCard(current, true);
            assertTrue(current.performClick());
            screen.dismissEventDetails();
            screen.tick(at(0, 8));
            assertWeekCard(current, false);
            assertFalse(screen.followWeekTime);
            assertEquals(1, requests);
        });
    }

    private static void assertSourceFill(int pixel, boolean elapsed) {
        int fill = CalendarUi.eventFill(0xff467b99);
        // Drawing onto transparent pixels exposes both the opacity and the original RGB.
        assertEquals("Fill opacity", elapsed ? 102 : 255, Color.alpha(pixel), 1);
        assertEquals("Red channel", Color.red(fill), Color.red(pixel), 2);
        assertEquals("Green channel", Color.green(fill), Color.green(pixel), 2);
        assertEquals("Blue channel", Color.blue(fill), Color.blue(pixel), 2);
    }

    private void assertWeekCard(View card, boolean elapsed) {
        Bitmap bitmap = Bitmap.createBitmap(card.getWidth(), card.getHeight(), Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap);
            card.draw(canvas);
            int fill = bitmap.getPixel(bitmap.getWidth() / 2, bitmap.getHeight() - 4);
            assertSourceFill(fill, elapsed);
            TextView title = (TextView) ((ViewGroup) card).getChildAt(0);
            assertEquals(CalendarUi.eventInk(0xff467b99), title.getCurrentTextColor());
            assertEquals(elapsed ? .86f : 1f, title.getAlpha(), .005f);
        } finally { bitmap.recycle(); }
    }

    public void testAllDayRibbonFadesOnlyElapsedColumnsAtMidnight() throws Throwable {
        open(CalendarGeometry.Mode.WEEK, event("跨日", at(-1, 0), at(1, 10), true));
        AtomicReference<View> original = new AtomicReference<>();
        runTestOnUiThread(() -> {
            View ribbon = control("跨日，全天");
            original.set(ribbon);
            int[] before = ribbonColors(ribbon);
            assertSourceFill(before[0], true);
            assertSourceFill(before[1], false);
            assertEquals(before[1], before[2]);
            screen.tick(at(1, 0));
            assertSame(ribbon, control("跨日，全天"));
            int[] after = ribbonColors(ribbon);
            assertEquals(before[0], after[1]);
            assertEquals(before[2], after[2]);
            screen.tick(at(1, 10));
            assertEquals(before[0], ribbonColors(ribbon)[2]);
            screen.tick(at(0, 9));
            assertEquals(before[1], ribbonColors(ribbon)[1]);
        });
        getInstrumentation().waitForIdleSync();
        runTestOnUiThread(() -> {
            View ribbon = control("跨日，全天");
            assertSame(original.get(), ribbon);
            assertEquals(1, requests);
            assertTrue(ribbon.performClick());
            screen.dismissEventDetails();
        });
    }

    private int[] ribbonColors(View ribbon) {
        Bitmap bitmap = Bitmap.createBitmap(ribbon.getWidth(), ribbon.getHeight(), Bitmap.Config.ARGB_8888);
        try {
            ribbon.draw(new Canvas(bitmap));
            int y = ribbon.getHeight() - 2;
            return new int[]{bitmap.getPixel(ribbon.getWidth() / 6, y), bitmap.getPixel(ribbon.getWidth() / 2, y),
                    bitmap.getPixel(5 * ribbon.getWidth() / 6, y)};
        } finally { bitmap.recycle(); }
    }

    public void testMonthPreviewUpdatesInPlaceAcrossMidnightAndClockRewind() throws Throwable {
        open(CalendarGeometry.Mode.MONTH, event("连续", at(-1, 0), at(2, 0), true),
                event("限时", at(0, 8), at(0, 10), false));
        AtomicReference<View> original = new AtomicReference<>();
        runTestOnUiThread(() -> {
            View yesterday = control(wednesday.minusDays(1).toString());
            View today = control(wednesday.toString());
            original.set(today);
            View tomorrow = control(wednesday.plusDays(1).toString());
            View timed = label(today, "限时");
            assertPast(label(yesterday, "连续"), true);
            assertPast(label(today, "连续"), false);
            screen.tick(at(0, 10));
            assertPast(timed, true);
            screen.tick(at(0, 9));
            assertPast(timed, false);
            screen.tick(at(1, 0));
            assertPast(label(today, "连续"), true);
            assertPast(label(tomorrow, "连续"), false);
            assertTrue(tomorrow.getContentDescription().toString().contains("今天"));
        });
        getInstrumentation().waitForIdleSync();
        runTestOnUiThread(() -> {
            View today = control(wednesday.toString());
            assertSame(original.get(), today);
            assertEquals(1, requests);
            assertTrue(today.performClick());
            assertEquals(CalendarGeometry.Mode.DAY, screen.mode);
        });
    }

    public void testDayAgendaAndSummaryAdvanceWithoutReplacingRows() throws Throwable {
        open(CalendarGeometry.Mode.DAY, event("全天事项", at(0, 0), at(1, 0), true),
                event("第一项", at(0, 8), at(0, 10), false), event("第二项", at(0, 11), at(0, 12), false),
                event("昨日摘要", at(-1, 8), at(-1, 9), false));
        AtomicReference<View> original = new AtomicReference<>();
        runTestOnUiThread(() -> {
            View row = control("第一项，");
            original.set(row);
            screen.tick(at(0, 10));
            assertPast(row, true);
            assertPast(control("全天事项，"), false);
            assertPast(control("第二项，"), false);
            label(screen.root, "下一项");
            label(screen.root, "11:00 开始");
            screen.tick(at(0, 11));
            label(screen.root, "进行中");
            label(screen.root, "12:00 结束");
            screen.tick(at(0, 9));
            label(screen.root, "10:00 结束");
            assertPast(row, false);
        });
        getInstrumentation().waitForIdleSync();
        runTestOnUiThread(() -> {
            assertSame(original.get(), control("第一项，"));
            assertEquals(1, requests);
            screen.select(CalendarGeometry.Mode.DAY, wednesday.minusDays(1));
        });
        getInstrumentation().waitForIdleSync();
        runTestOnUiThread(() -> {
            assertPast(label(screen.root, "昨日摘要"), true);
            assertPast(control("昨日摘要，"), true);
        });
    }
}
