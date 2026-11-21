package io.github.pageleaf.calendar;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.SystemClock;
import android.test.ActivityInstrumentationTestCase2;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ScrollView;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;

@SuppressWarnings("deprecation")
public class WeekTimeFollowTest extends ActivityInstrumentationTestCase2<MainActivity> {
    private CalendarScreen screen;
    private LocalDate today;

    public WeekTimeFollowTest() { super(MainActivity.class); }

    private void openWeek() throws Throwable {
        MainActivity host = getActivity();
        runTestOnUiThread(() -> {
            screen = new CalendarScreen(host, new CalendarScreen.Actions() {
                public void rangeChanged(LocalDate first, LocalDate after) { }
                public void toggle(String id, boolean enabled) { }
                public void connect() { }
                public void refresh() { }
            });
            today = LocalDate.now(screen.zone);
            screen.select(CalendarGeometry.Mode.WEEK, today);
            screen.tick(time(today, 8));
            host.setContentView(screen.view());
            screen.start();
        });
        getInstrumentation().waitForIdleSync();
    }

    private ZonedDateTime time(LocalDate day, int hour) {
        return day.atTime(hour, 0).atZone(screen.zone);
    }

    private ScrollView hours(View v) {
        if (v instanceof ScrollView && (v.canScrollVertically(1) || v.canScrollVertically(-1))) return (ScrollView) v;
        if (v instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) v;
            for (int i = 0; i < group.getChildCount(); i++) {
                ScrollView match = hours(group.getChildAt(i));
                if (match != null) return match;
            }
        }
        return null;
    }

    private void press(String label) {
        ArrayList<View> matches = new ArrayList<>();
        screen.root.findViewsWithText(matches, label, View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
        for (View v : matches) {
            if (label.contentEquals(v.getContentDescription()) && v.isClickable()) {
                assertTrue(v.performClick());
                return;
            }
        }
        fail("Missing control: " + label);
    }

    // Render only the visible viewport; an off-screen red line must not count as a pass.
    private boolean markerVisible(ScrollView view) {
        int width = 64, height = view.getHeight();
        Bitmap image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(image);
            canvas.scale(width / (float) view.getWidth(), 1f);
            int[] origin = new int[2], viewport = new int[2];
            screen.root.getLocationInWindow(origin);
            view.getLocationInWindow(viewport);
            canvas.translate(origin[0] - viewport[0], origin[1] - viewport[1]);
            screen.root.draw(canvas);
            int[] row = new int[width];
            for (int y = 0; y < height; y++) {
                image.getPixels(row, 0, width, 0, y, width, 1);
                for (int color : row) if (color == CalendarUi.RED) return true;
            }
            return false;
        } finally { image.recycle(); }
    }

    public void testMinuteTicksKeepMarkerVisibleWithoutContinuousScrolling() throws Throwable {
        openWeek();
        runTestOnUiThread(() -> {
            ScrollView view = hours(screen.root);
            assertNotNull(view);
            int changes = 0, previous = view.getScrollY();
            ZonedDateTime start = time(today, 8);
            for (int minute = 0; minute < 16 * 60; minute++) {
                screen.tick(start.plusMinutes(minute));
                int y = view.getScrollY();
                if (y != previous) {
                    assertTrue(y > previous);
                    changes++;
                }
                if (minute % 60 == 0 || minute == 959) assertTrue("Minute " + minute, markerVisible(view));
                previous = y;
            }
            assertTrue("Expected occasional moves, got " + changes, changes >= 1 && changes < 20);
        });
    }

    private void touch(ScrollView view, long down, int offset, int action, float y) {
        MotionEvent event = MotionEvent.obtain(down, down + offset, action, 8f, y, 0);
        try { view.dispatchTouchEvent(event); } finally { event.recycle(); }
    }

    public void testManualScrollSurvivesRefreshAndTodayResumesFollowing() throws Throwable {
        openWeek();
        runTestOnUiThread(() -> {
            ScrollView view = hours(screen.root);
            int before = view.getScrollY();
            long down = SystemClock.uptimeMillis();
            touch(view, down, 0, MotionEvent.ACTION_DOWN, 40);
            touch(view, down, 40, MotionEvent.ACTION_MOVE, 100);
            touch(view, down, 80, MotionEvent.ACTION_MOVE, 170);
            touch(view, down, 90, MotionEvent.ACTION_CANCEL, 170);
            int manual = view.getScrollY();
            assertTrue(manual < before);
            assertFalse(screen.followWeekTime);
            screen.tick(time(today, 21));
            assertEquals(manual, view.getScrollY());
            screen.setData(Collections.emptyList(), Collections.emptyList(), "", false);
        });
        getInstrumentation().waitForIdleSync();
        runTestOnUiThread(() -> {
            ScrollView view = hours(screen.root);
            assertFalse(screen.followWeekTime);
            int y = view.getScrollY();
            screen.tick(time(today, 22));
            assertEquals(y, view.getScrollY());
            press("今天");
        });
        getInstrumentation().waitForIdleSync();
        runTestOnUiThread(() -> {
            ScrollView view = hours(screen.root);
            assertTrue(screen.followWeekTime);
            assertTrue(markerVisible(view));
            screen.tick(time(today, 4));
            assertTrue(markerVisible(view));
            press("下一周");
        });
        getInstrumentation().waitForIdleSync();
        runTestOnUiThread(() -> {
            ScrollView view = hours(screen.root);
            int y = view.getScrollY();
            screen.tick(time(today, 22));
            assertEquals(y, view.getScrollY());
            assertFalse(markerVisible(view));
        });
    }

    public void testSundayMidnightFollowsIntoNewWeek() throws Throwable {
        openWeek();
        LocalDate sunday = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
        runTestOnUiThread(() -> screen.tick(time(sunday, 23).plusMinutes(59)));
        getInstrumentation().waitForIdleSync();
        runTestOnUiThread(() -> {
            ScrollView view = hours(screen.root);
            assertTrue(view.getScrollY() > 0);
            assertTrue(markerVisible(view));
            screen.tick(time(sunday.plusDays(1), 0));
        });
        getInstrumentation().waitForIdleSync();
        runTestOnUiThread(() -> {
            ScrollView view = hours(screen.root);
            assertEquals(sunday.plusDays(1), screen.first());
            assertEquals(0, view.getScrollY());
            assertTrue(markerVisible(view));
        });
    }

    public void testSavedSundayViewportRestoresVisibleMondayOnNarrowScreen() throws Throwable {
        openWeek();
        MainActivity host = getActivity();
        int width = Math.round(360 * host.getResources().getDisplayMetrics().density);
        LocalDate sunday = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
        LocalDate monday = sunday.plusDays(1);
        runTestOnUiThread(() -> {
            screen.root.setLayoutParams(new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT));
            screen.tick(time(sunday, 23));
        });
        getInstrumentation().waitForIdleSync();
        runTestOnUiThread(() -> {
            assertFalse(screen.wide);
            assertTrue(markerVisible(hours(screen.root)));
            Bundle saved = (Bundle) screen.root.onSaveInstanceState();
            assertTrue("Sunday was horizontally scrolled", saved.getDouble("dayOffset") > 1);
            screen = new CalendarScreen(host, screen.actions);
            screen.tick(time(monday, 8));
            host.setContentView(screen.root, new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT));
            screen.root.onRestoreInstanceState(saved);
            screen.start();
        });
        getInstrumentation().waitForIdleSync();
        runTestOnUiThread(() -> {
            assertEquals(monday, screen.focus);
            assertTrue(markerVisible(hours(screen.root)));
            screen.tick(time(monday, 20));
            assertTrue(markerVisible(hours(screen.root)));
        });
    }
}
