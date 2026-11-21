package io.github.pageleaf.calendar;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static io.github.pageleaf.calendar.CalendarUi.*;

/** The date strip and all-day ribbon stay fixed while the 24-hour grid scrolls. */
final class CalendarWeekBoard {
    private final CalendarScreen ui;
    final LinearLayout view;
    private final LocalDate monday;
    private final int railWidth, daysWidth, hourHeight, inset, gridHeight, stripHeight, dayIndex;
    private final HorizontalScrollView headerX, bodyX;
    private final ScrollView hours;
    private final Grid nowLine;
    private final FrameLayout ribbons;
    private boolean restored;
    private boolean positioningHours, touchingHours;

    CalendarWeekBoard(CalendarScreen ui) {
        this.ui = ui;
        view = ui.column();
        ui.pad(view, 0, 0, 0, ui.choice(10, 4));
        monday = ui.first();
        dayIndex = (int) ChronoUnit.DAYS.between(monday, ui.now.toLocalDate());
        railWidth = ui.u(ui.choice(56, 43));
        int visibleDaysWidth = ui.contentWidth - railWidth;
        daysWidth = ui.wide ? visibleDaysWidth : Math.max(visibleDaysWidth, ui.u(104) * 7);
        int dateHeight = ui.u(ui.choice(55, 44));
        ribbons = new FrameLayout(ui.activity);
        int laneHeight = ui.u(ui.choice(34, 28));
        int lanes = allDay(ribbons, laneHeight);
        int ribbonContent = Math.max(1, lanes) * laneHeight + ui.u(7);
        int ribbonHeight = Math.min(ribbonContent, laneHeight * 2 + ui.u(7));
        stripHeight = dateHeight + ribbonHeight;
        int availableHeight = ui.root.getHeight() - ui.u(ui.choice(174, 179)) - stripHeight;
        hourHeight = ui.wide ? Math.max(ui.u(43), Math.round(availableHeight / 10.55f)) : ui.u(60);
        inset = ui.u(13);
        gridHeight = 24 * hourHeight + 2 * inset;
        LinearLayout header = ui.row();
        view.addView(header, lp(MATCH, stripHeight));
        LinearLayout rail = ui.column();
        header.addView(rail, lp(railWidth, MATCH));
        rail.addView(ui.space(), lp(MATCH, dateHeight - ui.hair()));
        ui.rule(rail, LINE, ui.hair());
        rail.addView(ui.text("全天", ui.choice(17, 13), MUTED, false), lp(MATCH, ribbonHeight));
        headerX = horizontal();
        header.addView(headerX, growX());
        LinearLayout dates = ui.column();
        headerX.addView(dates, new FrameLayout.LayoutParams(daysWidth, stripHeight));
        LinearLayout labels = ui.row();
        dates.addView(labels, lp(daysWidth, dateHeight - ui.hair()));
        for (int i = 0; i < 7; i++) labels.addView(dateLabel(i), lp(edge(i + 1) - edge(i), MATCH));
        ui.rule(dates, LINE, ui.hair());
        ScrollView ribbonScroll = new ScrollView(ui.activity);
        dates.addView(ribbonScroll, lp(daysWidth, ribbonHeight));
        ribbons.setMinimumHeight(ribbonContent);
        ribbonScroll.addView(ribbons, new FrameLayout.LayoutParams(daysWidth, ribbonContent));
        hours = new ScrollView(ui.activity) {
            @Override public boolean dispatchTouchEvent(MotionEvent event) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) touchingHours = true;
                boolean handled = super.dispatchTouchEvent(event);
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) touchingHours = false;
                return handled;
            }
        };
        hours.setOnScrollChangeListener((v, x, y, oldX, oldY) -> {
            if (restored && !positioningHours && !hours.isInLayout() && y != oldY) ui.followWeekTime = false;
        });
        hours.setVerticalScrollBarEnabled(true);
        view.addView(hours, growY());
        LinearLayout table = ui.row();
        hours.addView(table);
        table.addView(new Grid(Layer.HOURS), lp(railWidth, gridHeight));
        bodyX = horizontal();
        table.addView(bodyX, new LinearLayout.LayoutParams(0, gridHeight, 1f));
        FrameLayout plane = new FrameLayout(ui.activity);
        bodyX.addView(plane, new FrameLayout.LayoutParams(daysWidth, gridHeight));
        plane.addView(new Grid(Layer.GRID), at(0, 0, daysWidth, gridHeight));
        for (int i = 0; i < 7; i++) {
            LocalDate day = monday.plusDays(i);
            for (CalendarGeometry.Slot slot : CalendarGeometry.timedSlots(ui.entries(day), day, ui.zone)) {
                timedCard(plane, i, day, slot);
            }
        }
        nowLine = new Grid(Layer.NOW);
        plane.addView(nowLine, at(0, 0, daysWidth, gridHeight));
        mirror(headerX, bodyX);
        mirror(bodyX, headerX);
        // Views posted from a layout callback can run before the new child has a viewport.
        // Restore only after the scroll range has actually been measured.
        hours.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (restored || hours.getHeight() == 0) return;
            restored = true;
            double hour = Double.isNaN(ui.topHour) ? defaultHour() : ui.topHour;
            positionHours((int) Math.round(hour * hourHeight));
            followNowIfNeeded();
            double left = Double.isNaN(ui.leftDay) ? Math.max(0, ui.focus.getDayOfWeek().getValue() - 2) : ui.leftDay;
            bodyX.scrollTo((int) Math.round(left * daysWidth / 7.0), 0);
        });
    }

    private HorizontalScrollView horizontal() {
        HorizontalScrollView v = new HorizontalScrollView(ui.activity);
        v.setHorizontalScrollBarEnabled(false);
        return v;
    }

    private void mirror(HorizontalScrollView source, HorizontalScrollView target) {
        source.setOnScrollChangeListener((v, x, y, oldX, oldY) -> {
            if (target.getScrollX() != x) target.scrollTo(x, 0);
            if (source == headerX) positionRibbonLabels(x);
        });
    }

    private void positionRibbonLabels(int viewportLeft) {
        for (int i = 0; i < ribbons.getChildCount(); i++) {
            View chip = ribbons.getChildAt(i);
            if (!(chip instanceof LinearLayout)) continue;
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) chip.getLayoutParams();
            int offset = Math.max(0, Math.min(viewportLeft - p.leftMargin, p.width - ui.u(24)));
            int pad = offset + ui.u(9);
            if (chip.getPaddingLeft() != pad) chip.setPadding(pad, 0, ui.u(5), 0);
        }
    }

    private int edge(int day) { return Math.round(day * daysWidth / 7f); }

    private double defaultHour() {
        if (dayIndex < 0 || dayIndex > 6) return 8;
        double current = ui.now.getHour() + ui.now.getMinute() / 60.0;
        double visible = hours.getHeight() / (double) hourHeight;
        return Math.max(0, Math.min(Math.max(8, current - visible + 1.25), current - .6));
    }

    void rememberScroll() {
        if (!restored) return;
        ui.topHour = hours.getScrollY() / (double) hourHeight;
        if (daysWidth > bodyX.getWidth()) ui.leftDay = bodyX.getScrollX() * 7.0 / daysWidth;
    }


    void updateNow() {
        nowLine.invalidate();
        followNowIfNeeded();
    }

    private void followNowIfNeeded() {
        if (!restored || !ui.followWeekTime || touchingHours || dayIndex < 0 || dayIndex >= 7) return;
        int height = hours.getHeight();
        int marker = y(ui.now.getHour() * 60 + ui.now.getMinute());
        int offset = marker - hours.getScrollY();
        if (offset < ui.u(ui.choice(13, 9)) || offset >= height * .8f) {
            positionHours(marker - Math.round(height * .45f));
        }
    }

    private void positionHours(int y) {
        // Restores and clock-driven movement must not be mistaken for manual browsing.
        positioningHours = true;
        try { hours.scrollTo(0, y); }
        finally { positioningHours = false; }
    }

    private View dateLabel(int index) {
        LocalDate date = monday.plusDays(index);
        boolean today = index == dayIndex;
        LinearLayout label = ui.row();
        ui.pad(label, ui.choice(12, 4), 0, 0, 0);
        if (today) label.setBackgroundColor(TODAY);
        int color = today ? GREEN : INK;
        label.addView(ui.text(String.valueOf(date.getDayOfMonth()), ui.choice(32, 21), color, true));
        label.addView(ui.space(), lp(ui.u(ui.choice(7, 4)), 1));
        label.addView(ui.text("周" + CalendarScreen.weekday(date), ui.choice(19, 13), today ? GREEN : MUTED, false));
        if (today) {
            label.addView(ui.space(), lp(ui.u(ui.choice(8, 4)), 1));
            TextView badge = ui.text(ui.wide ? "今天" : "今", ui.choice(16, 11), -1, true);
            badge.setGravity(Gravity.CENTER);
            badge.setBackground(ui.shape(GREEN, 5, 0));
            label.addView(badge, lp(ui.u(ui.choice(47, 19)), ui.u(ui.choice(27, 19))));
        }
        ui.clickable(label, CalendarScreen.md(date) + "，星期" + CalendarScreen.weekday(date) + (today ? "，今天" : ""),
                () -> ui.select(CalendarGeometry.Mode.DAY, date));
        return label;
    }

    private int allDay(FrameLayout parent, int laneHeight) {
        if (dayIndex >= 0 && dayIndex < 7) {
            View today = ui.space();
            today.setBackgroundColor(TODAY);
            parent.addView(today, at(edge(dayIndex), 0, edge(dayIndex + 1) - edge(dayIndex), MATCH));
        }
        Set<Data.Entry> seen = new HashSet<>();
        List<Data.Entry> entries = new ArrayList<>();
        for (int d = 0; d < 7; d++) for (Data.Entry event : ui.entries(monday.plusDays(d))) {
            if (event.allDay && seen.add(event)) entries.add(event);
        }
        Collections.sort(entries, CalendarGeometry.AGENDA_ORDER);
        List<Integer> ends = new ArrayList<>();
        for (Data.Entry event : entries) {
            int firstDay = (int) Math.max(0L, ChronoUnit.DAYS.between(monday, ui.localDate(event.start)));
            int afterDay = (int) Math.min(7L, ChronoUnit.DAYS.between(monday, ui.localDate(event.end - 1)) + 1);
            int lane = 0;
            while (lane < ends.size() && ends.get(lane) > firstDay) lane++;
            if (lane == ends.size()) ends.add(afterDay);
            else ends.set(lane, afterDay);
            int color = ui.source(event).color;
            LinearLayout chip = ui.tag(CalendarScreen.title(event), color, ui.choice(18, 14), ui.choice(9, 6), eventInk(color), true);
            ui.pad(chip, 9, 0, 5, 0);
            chip.setBackground(ui.shape(eventFill(color), 5, 0));
            ui.clickable(chip, CalendarScreen.title(event) + "，全天", () -> ui.eventDetails(event));
            int gap = ui.u(4);
            parent.addView(chip, at(edge(firstDay) + gap, ui.u(5) + lane * laneHeight,
                    edge(afterDay) - edge(firstDay) - 2 * gap, laneHeight - ui.u(3)));
        }
        return ends.size();
    }

    private int y(double minute) { return inset + (int) Math.round(hourHeight * minute / 60.0); }

    private void timedCard(FrameLayout plane, int index, LocalDate day, CalendarGeometry.Slot slot) {
        int column = edge(index + 1) - edge(index);
        int laneStart = edge(index) + column * slot.lane / slot.lanes;
        int laneEnd = edge(index) + column * (slot.lane + 1) / slot.lanes;
        int gap = Math.min(ui.u(ui.choice(4, 2)), Math.max(0, (laneEnd - laneStart - 1) / 4));
        int height = Math.max(ui.hair() * 2, y(slot.end) - y(slot.start) - ui.u(3));
        int width = Math.max(1, laneEnd - laneStart - 2 * gap);
        int color = ui.source(slot.event).color;
        int ink = eventInk(color);
        LinearLayout card = ui.column();
        boolean small = height < ui.u(ui.choice(45, 33));
        ui.pad(card, small || slot.lanes > 1 ? 3 : ui.choice(10, 5), small ? 0 : 3, 2, 0);
        card.setBackground(ui.shape(eventFill(color), 5, 0));
        String name = CalendarScreen.title(slot.event);
        String when = ui.startOn(slot.event, day) + "–" + ui.endOn(slot.event, day);
        card.addView(ui.text(name, small ? ui.choice(16, 13) : ui.choice(22, 16), ink, true), lp(MATCH, WRAP));
        if (!small) card.addView(ui.text(when, ui.choice(15, 12), ink, false), lp(MATCH, WRAP));
        ui.clickable(card, name + "，" + when + "，" + ui.source(slot.event).name, () -> ui.eventDetails(slot.event));
        plane.addView(card, at(laneStart + gap, y(slot.start) + ui.hair(), width, height));
    }

    private enum Layer { HOURS, GRID, NOW }
    private final class Grid extends View {
        private final Layer layer;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String[] markers = new String[25];
        Grid(Layer layer) {
            super(ui.activity);
            this.layer = layer;
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            for (int i = 0; i <= 24; i++) markers[i] = String.format(Locale.ROOT, "%02d:00", i);
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (layer == Layer.NOW) { drawNow(canvas); return; }
            if (layer == Layer.HOURS) {
                paint.setColor(MUTED);
                paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                paint.setTextSize(ui.u(ui.choice(15, 12)));
                for (int i = 0; i <= 24; i++) canvas.drawText(markers[i], ui.u(2), y(60.0 * i) - (paint.ascent() + paint.descent()) / 2, paint);
                return;
            }
            if (dayIndex >= 0 && dayIndex < 7) {
                paint.setColor(TODAY);
                canvas.drawRect(edge(dayIndex), 0, edge(dayIndex + 1), gridHeight, paint);
            }
            paint.setColor(LINE);
            paint.setStrokeWidth(ui.hair());
            for (int i = 0; i <= 24; i++) canvas.drawLine(0, y(i * 60.0), daysWidth, y(i * 60.0), paint);
            for (int i = 0; i <= 7; i++) canvas.drawLine(edge(i), 0, edge(i), gridHeight, paint);
        }
        private void drawNow(Canvas canvas) {
            if (dayIndex < 0 || dayIndex >= 7) return;
            int y = y(ui.now.getHour() * 60 + ui.now.getMinute());
            int a = edge(dayIndex) + ui.u(4), b = edge(dayIndex + 1) - ui.u(3);
            int height = ui.u(ui.choice(25, 18));
            paint.setColor(RED);
            paint.setStrokeWidth(Math.max(1, ui.u(2)));
            canvas.drawLine(a, y, b, y, paint);
            canvas.drawCircle(a, y, ui.u(ui.choice(5, 3)), paint);
            String stamp = ui.now.format(CalendarScreen.CLOCK);
            paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            paint.setTextSize(ui.u(ui.choice(15, 11)));
            float w = paint.measureText(stamp) + ui.u(12);
            canvas.drawRoundRect(b - w, y - height / 2f, b, y + height / 2f, ui.u(4), ui.u(4), paint);
            paint.setColor(-1);
            canvas.drawText(stamp, b - w + ui.u(6), y - (paint.ascent() + paint.descent()) / 2, paint);
        }
    }
}
