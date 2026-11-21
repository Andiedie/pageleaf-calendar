package io.github.pageleaf.calendar;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Parcelable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.github.pageleaf.calendar.CalendarGeometry.Mode;

public final class CalendarScreen extends CalendarUi {
    public interface Actions {
        void rangeChanged(LocalDate startInclusive, LocalDate endExclusive);
        void toggle(String id, boolean enabled);
        void connect();
        void refresh();
    }

    static final String[] WEEKDAYS = {"一", "二", "三", "四", "五", "六", "日"};
    static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    final Actions actions;
    final ScreenRoot root;
    final SharedPreferences preferences;
    Mode mode;
    ZoneId zone = ZoneId.systemDefault();
    ZonedDateTime now = ZonedDateTime.now(zone);
    LocalDate focus = now.toLocalDate();
    boolean followToday = true;
    List<Data.Source> sources = Collections.emptyList();
    private List<Data.Entry> events = Collections.emptyList();
    private final Map<String, Data.Source> byId = new HashMap<>();
    final Map<LocalDate, List<Data.Entry>> byDate = new HashMap<>();
    boolean connected;
    String status = "";
    private boolean started;
    private final Runnable rebuild = this::render;
    private CalendarWeekBoard board;
    boolean followWeekTime = true;
    double topHour = Double.NaN, leftDay = Double.NaN;
    int agendaY;
    private ScrollView agendaScroll;
    private CalendarSourcesPanel drawer;
    private AlertDialog eventDialog;
    private Data.Entry detailedEvent;
    private boolean reopenDrawer;
    int contentWidth;

    public CalendarScreen(Activity activity, Actions actions) {
        super(activity);
        this.actions = actions;
        preferences = activity.getSharedPreferences("calendar_screen", Activity.MODE_PRIVATE);
        mode = readMode(preferences.getString("mode", Mode.WEEK.name()));
        root = new ScreenRoot();
        root.setId(R.id.calendar_screen);
        root.setBackgroundColor(PAPER);
    }

    public View view() { return root; }

    public void start() {
        if (started) return;
        started = true;
        requestRange();
    }

    public void setData(List<Data.Source> sources, List<Data.Entry> events, String status, boolean connected) {
        this.sources = sources == null ? Collections.emptyList() : new ArrayList<>(sources);
        this.events = events == null ? Collections.emptyList() : new ArrayList<>(events);
        this.status = status == null ? "" : status;
        this.connected = connected;
        if (detailedEvent != null && this.events.stream().noneMatch(e -> e.id.equals(detailedEvent.id)
                && e.calendarId.equals(detailedEvent.calendarId))) dismissEventDetails();
        byId.clear();
        for (Data.Source source : this.sources) byId.put(source.id, source);
        indexEvents();
        scheduleRender();
    }

    public void tick() {
        tick(ZonedDateTime.now(ZoneId.systemDefault()));
    }

    void tick(ZonedDateTime time) {
        LocalDate previousDay = now.toLocalDate();
        ZoneId previousZone = zone;
        now = time;
        zone = time.getZone();
        if (!previousDay.equals(now.toLocalDate()) || !previousZone.equals(zone)) {
            if (followToday) {
                focus = now.toLocalDate();
                if (mode == Mode.WEEK && followWeekTime) {
                    // Start the new day at its time and column, including on a narrow screen.
                    board = null;
                    topHour = leftDay = Double.NaN;
                }
            }
            requestRange();
        } else if (mode == Mode.DAY) {
            scheduleRender();
        } else if (board != null) {
            board.updateNow();
        }
    }

    private void requestRange() {
        if (started) actions.rangeChanged(first(), after());
        indexEvents();
        scheduleRender();
    }

    LocalDate first() { return CalendarGeometry.first(mode, focus); }
    LocalDate after() { return CalendarGeometry.after(mode, focus); }

    private void indexEvents() {
        byDate.clear();
        LocalDate first = first(), after = after();
        long windowStart = CalendarGeometry.midnight(first, zone);
        long windowEnd = CalendarGeometry.midnight(after, zone);
        for (Data.Entry event : events) {
            Data.Source source = byId.get(event.calendarId);
            if (source == null || !source.enabled || !source.readable || event.end <= event.start
                    || event.end <= windowStart || event.start >= windowEnd) continue;
            LocalDate start = localDate(Math.max(windowStart, event.start));
            LocalDate last = localDate(Math.min(windowEnd, event.end) - 1);
            for (LocalDate day = start; !day.isAfter(last); day = day.plusDays(1)) {
                byDate.computeIfAbsent(day, unused -> new ArrayList<>()).add(event);
            }
        }
        for (List<Data.Entry> day : byDate.values()) day.sort(CalendarGeometry.AGENDA_ORDER);
    }

    List<Data.Entry> entries(LocalDate date) { return byDate.getOrDefault(date, Collections.emptyList()); }
    Data.Source source(Data.Entry e) { return byId.get(e.calendarId); }
    LocalDate localDate(long ms) { return Instant.ofEpochMilli(ms).atZone(zone).toLocalDate(); }
    String time(long ms) { return Instant.ofEpochMilli(ms).atZone(zone).format(CLOCK); }
    static String md(LocalDate date) { return date.getMonthValue() + "月" + date.getDayOfMonth() + "日"; }
    static String weekday(LocalDate date) { return WEEKDAYS[date.getDayOfWeek().getValue() - 1]; }
    static String title(Data.Entry e) { return blank(e.title) ? "无标题日程" : e.title; }
    static boolean blank(String value) { return value == null || value.trim().isEmpty(); }

    String startOn(Data.Entry e, LocalDate day) {
        return e.start <= CalendarGeometry.midnight(day, zone) ? "00:00" : time(e.start);
    }

    String endOn(Data.Entry e, LocalDate day) {
        return e.end >= CalendarGeometry.midnight(day.plusDays(1), zone) ? "24:00" : time(e.end);
    }

    static Mode readMode(String name) {
        try { return Mode.valueOf(name); }
        catch (RuntimeException ignored) { return Mode.WEEK; }
    }

    void select(Mode nextMode, LocalDate nextDate) {
        boolean different = mode != nextMode || !focus.equals(nextDate);
        focus = nextDate;
        followToday = focus.equals(now.toLocalDate());
        if (mode != nextMode) preferences.edit().putString("mode", nextMode.name()).apply();
        mode = nextMode;
        if (different) {
            followWeekTime = true;
            board = null;
            agendaScroll = null;
            agendaY = 0;
            topHour = Double.NaN;
            leftDay = Double.NaN;
            requestRange();
        }
    }

    private void move(int step) {
        LocalDate date = mode == Mode.MONTH ? focus.plusMonths(step)
                : mode == Mode.WEEK ? focus.plusWeeks(step) : focus.plusDays(step);
        select(mode, date);
    }

    private void pickDate() {
        new DatePickerDialog(activity, (picker, year, month, day) -> select(mode, LocalDate.of(year, month + 1, day)),
                focus.getYear(), focus.getMonthValue() - 1, focus.getDayOfMonth()).show();
    }

    void dismissEventDetails() {
        if (eventDialog != null) {
            eventDialog.dismiss();
            eventDialog = null;
            detailedEvent = null;
        }
    }

    void eventDetails(Data.Entry e) {
        if (!events.contains(e)) return;
        String when;
        if (e.allDay) {
            LocalDate last = localDate(e.end - 1);
            when = md(localDate(e.start)) + (last.equals(localDate(e.start)) ? "" : " – " + md(last)) + " · 全天";
        } else {
            when = md(localDate(e.start)) + " " + time(e.start) + " – "
                    + (localDate(e.start).equals(localDate(e.end)) ? "" : md(localDate(e.end)) + " ") + time(e.end);
        }
        Data.Source src = source(e);
        dismissEventDetails();
        detailedEvent = e;
        eventDialog = new AlertDialog.Builder(activity).setTitle(title(e)).setMessage(when + (src == null ? "" : "\n" + src.name))
                .setPositiveButton("完成", null).create();
        eventDialog.setOnDismissListener(dialog -> {
            if (eventDialog == dialog) { eventDialog = null; detailedEvent = null; }
        });
        eventDialog.show();
    }

    private void scheduleRender() {
        root.removeCallbacks(rebuild);
        root.post(rebuild);
    }

    private void rememberScroll() {
        if (board != null) board.rememberScroll();
        if (agendaScroll != null && agendaScroll.isLaidOut()) agendaY = agendaScroll.getScrollY();
    }

    private void render() {
        if (root.getWidth() <= 0 || root.getHeight() <= 0) return;
        rememberScroll();
        board = null;
        agendaScroll = null;
        size(root.getWidth(), root.getHeight());
        root.removeAllViews();
        LinearLayout page = column();
        int margin = choice(31, 12);
        pad(page, margin, 0, margin, 0);
        contentWidth = root.getWidth() - u(margin) * 2;
        root.addView(page, new FrameLayout.LayoutParams(MATCH, MATCH));
        header(page);
        rule(page, 0xff4e5b56, Math.max(1, u(2)));
        if (mode == Mode.MONTH) page.addView(month(), growY());
        else if (mode == Mode.DAY) page.addView(day(), growY());
        else {
            board = new CalendarWeekBoard(this);
            page.addView(board.view, growY());
        }
        footer(page);
        if (drawer != null && drawer.isShowing()) drawer.update();
        else if (reopenDrawer && root.isAttachedToWindow()) openDrawer();
        reopenDrawer = false;
    }

    private void header(LinearLayout page) {
        LocalDate monday = CalendarGeometry.monday(focus), sunday = monday.plusDays(6);
        String eyebrow = focus.getYear() + " 年";
        String heading;
        if (mode == Mode.WEEK) {
            eyebrow = monday.get(IsoFields.WEEK_BASED_YEAR) + " 年 / 第 " + monday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) + " 周";
            heading = md(monday) + " – " + (monday.getMonth() == sunday.getMonth() ? sunday.getDayOfMonth() + "日" : md(sunday));
        } else if (mode == Mode.DAY) {
            eyebrow += " / 星期" + weekday(focus);
            heading = md(focus);
        } else heading = focus.getMonthValue() + "月";
        LinearLayout titles = column();
        titles.setGravity(Gravity.CENTER_VERTICAL);
        titles.addView(text(eyebrow, choice(17, 13), MUTED, false));
        titles.addView(text(heading, choice(40, 26), INK, true));
        clickable(titles, heading + "，选择日期", this::pickDate);
        LinearLayout bar = row();
        page.addView(bar, lp(MATCH, u(choice(106, 72))));
        bar.addView(titles, growX());
        LinearLayout controls = wide ? bar : row();
        if (!wide) {
            bar.addView(sourceButton(), lp(u(108), u(44)));
            page.addView(controls, lp(MATCH, u(52)));
        }
        LinearLayout nav = row();
        nav.setBackground(shape(PAPER, 8, LINE));
        controls.addView(nav, lp(u(choice(138, 138)), u(choice(50, 44))));
        nav.addView(button("‹", 29, MUTED, false, "上一" + modeLabel(mode), () -> move(-1)), growX());
        nav.addView(button("今天", choice(19, 16), INK, false, "今天", () -> {
            select(mode, LocalDate.now(zone));
            if (mode == Mode.WEEK) {
                // Discard the old scroller as it may still be flinging from a swipe.
                board = null;
                topHour = leftDay = Double.NaN;
                followWeekTime = true;
                scheduleRender();
            }
        }), lp(u(choice(53, 50)), MATCH));
        nav.addView(button("›", 29, MUTED, false, "下一" + modeLabel(mode), () -> move(1)), growX());
        controls.addView(space(), lp(u(choice(24, 10)), 1));
        LinearLayout modes = row();
        pad(modes, 3, 3, 3, 3);
        modes.setBackground(shape(PALE, 8, 0));
        controls.addView(modes, wide ? lp(u(240), u(49)) : new LinearLayout.LayoutParams(0, u(44), 1f));
        for (Mode m : Mode.values()) {
            boolean active = mode == m;
            TextView item = button(modeLabel(m), choice(22, 17), active ? -1 : MUTED, true, modeLabel(m) + "视图", () -> select(m, focus));
            item.setSelected(active);
            if (active) item.setBackground(shape(GREEN, 6, 0));
            modes.addView(item, growX());
        }
        if (wide) {
            controls.addView(space(), lp(u(28), 1));
            controls.addView(sourceButton(), lp(u(176), u(50)));
        }
    }

    private static String modeLabel(Mode mode) { return mode == Mode.MONTH ? "月" : mode == Mode.WEEK ? "周" : "日"; }

    private View sourceButton() {
        LinearLayout button = row();
        button.setGravity(Gravity.CENTER);
        button.setBackground(shape(PAPER, 8, LINE));
        FrameLayout icon = new FrameLayout(activity);
        int pitch = u(choice(6, 5));
        for (int i = 0; i < 3; i++) {
            icon.addView(dot(GREEN), at(0, i * pitch, u(3), u(3)));
            View dash = space();
            dash.setBackgroundColor(GREEN);
            icon.addView(dash, at(u(6), i * pitch + u(1), u(choice(13, 10)), Math.max(1, u(1.5f))));
        }
        button.addView(icon, lp(u(choice(20, 17)), 2 * pitch + u(3)));
        button.addView(space(), lp(u(choice(10, 6)), 1));
        button.addView(text("显示日历", choice(20, 15), INK, true), lp(WRAP, MATCH));
        clickable(button, "显示日历", this::openDrawer);
        return button;
    }

    private void openDrawer() {
        if (drawer != null && drawer.isShowing()) return;
        drawer = new CalendarSourcesPanel(this);
        drawer.show();
    }

    private void footer(LinearLayout page) {
        rule(page, LINE, hair());
        LinearLayout row = row();
        page.addView(row, lp(MATCH, u(choice(56, 47))));
        if (!connected) {
            TextView connect = button("连接飞书", choice(18, 15), -1, true, "连接飞书", actions::connect);
            connect.setBackground(shape(GREEN, 7, 0));
            row.addView(connect, lp(u(choice(104, 84)), u(choice(38, 35))));
            row.addView(space(), lp(u(10), 1));
        }
        HorizontalScrollView scroller = new HorizontalScrollView(activity);
        scroller.setHorizontalScrollBarEnabled(false);
        LinearLayout legend = row();
        scroller.addView(legend);
        for (Data.Source src : sources) {
            if (!src.readable || !src.enabled) continue;
            LinearLayout key = row();
            pad(key, 4, 0, choice(38, 14), 0);
            key.addView(dot(src.color), lp(u(choice(11, 8)), u(choice(11, 8))));
            key.addView(space(), lp(u(choice(8, 5)), 1));
            TextView name = text(src.name, choice(19, 14), MUTED, false);
            name.setMaxWidth(u(200));
            key.addView(name);
            legend.addView(key, lp(WRAP, MATCH));
        }
        row.addView(scroller, growX());
        String label = blank(status) ? (connected ? "已连接飞书" : "尚未连接飞书") : status;
        TextView sync = text(label, choice(17, 12), MUTED, false);
        sync.setMaxWidth(contentWidth * (legend.getChildCount() == 0 ? 2 : 1) / 3);
        clickable(sync, label + "，显示日历", this::openDrawer);
        row.addView(sync, lp(WRAP, MATCH));
    }

    private View month() {
        LinearLayout body = column();
        pad(body, 0, 0, 0, choice(12, 6));
        LinearLayout days = row();
        body.addView(days, lp(MATCH, u(choice(43, 34))));
        for (String label : WEEKDAYS) {
            TextView v = text("周" + label, choice(19, 13), MUTED, true);
            pad(v, choice(12, 3), 0, 0, 0);
            days.addView(v, growX());
        }
        LinearLayout grid = column();
        grid.setBackgroundColor(LINE);
        grid.setPadding(hair(), hair(), 0, 0);
        body.addView(grid, growY());
        LocalDate firstDay = first();
        int weeks = (int) ChronoUnit.WEEKS.between(firstDay, after());
        for (int r = 0; r < weeks; r++) {
            LinearLayout line = row();
            grid.addView(line, growY());
            for (int c = 0; c < 7; c++) {
                LocalDate date = firstDay.plusDays(r * 7L + c);
                LinearLayout.LayoutParams cell = growX();
                cell.setMargins(0, 0, hair(), hair());
                line.addView(monthCell(date), cell);
            }
        }
        return body;
    }

    private LinearLayout monthCell(LocalDate date) {
        boolean isToday = date.equals(now.toLocalDate());
        LinearLayout cell = column();
        pad(cell, choice(11, 3), choice(6, 5), choice(8, 2), 0);
        cell.setBackgroundColor(isToday ? SAGE : PAPER);
        LinearLayout head = row();
        cell.addView(head, lp(MATCH, u(choice(30, 24))));
        boolean currentMonth = date.getMonth() == focus.getMonth();
        TextView number = text(String.valueOf(date.getDayOfMonth()), choice(23, 16), isToday ? GREEN : currentMonth ? INK : MUTED, currentMonth);
        head.addView(number, lp(WRAP, MATCH));
        if (isToday && wide) {
            head.addView(space(), lp(u(6), 1));
            head.addView(text("今天", 16, GREEN, true), lp(WRAP, MATCH));
        }
        head.addView(space(), growX());
        List<Data.Entry> day = entries(date);
        int preview = 2;
        if (day.size() > preview) head.addView(text("+" + (day.size() - preview), choice(16, 10), MUTED, true), lp(WRAP, MATCH));
        for (int i = 0; i < Math.min(preview, day.size()); i++) {
            Data.Entry e = day.get(i);
            cell.addView(tag(title(e), source(e).color, choice(18, 11), choice(8, 4), INK, false), lp(MATCH, u(choice(25, 18))));
        }
        clickable(cell, date + (isToday ? "，今天" : "") + "，" + day.size() + " 项日程", () -> select(Mode.DAY, date));
        return cell;
    }

    private View day() {
        LinearLayout body = wide ? row() : column();
        pad(body, 0, choice(22, 10), 0, choice(28, 5));
        List<Data.Entry> day = entries(focus);
        LinearLayout card = dayCard(day);
        body.addView(card, wide ? lp(contentWidth * 286 / 1000, MATCH) : lp(MATCH, u(100)));
        body.addView(space(), wide ? lp(u(35), 1) : lp(1, u(8)));
        LinearLayout list = column();
        body.addView(list, wide ? growX() : growY());
        LinearLayout heading = row();
        list.addView(heading, lp(MATCH, u(choice(57, 42))));
        heading.addView(text("当天日程", choice(26, 20), INK, true), growX());
        int all = 0;
        for (Data.Entry e : day) if (e.allDay) all++;
        heading.addView(text(all + " 项全天 · " + (day.size() - all) + " 项定时", choice(18, 13), MUTED, false));
        rule(list, LINE, hair());
        final ScrollView scroll = new ScrollView(activity);
        agendaScroll = scroll;
        list.addView(scroll, growY());
        LinearLayout rows = column();
        scroll.addView(rows);
        if (day.isEmpty()) {
            TextView empty = text("当天暂无日程", choice(23, 17), MUTED, false);
            rows.addView(empty, lp(MATCH, u(choice(108, 68))));
        }
        for (Data.Entry e : day) {
            LinearLayout entry = row();
            rows.addView(entry, lp(MATCH, u(choice(124, 78))));
            LinearLayout times = column();
            times.setGravity(Gravity.CENTER_VERTICAL);
            times.addView(text(e.allDay ? "全天" : startOn(e, focus), choice(27, 19), INK, true));
            if (!e.allDay) times.addView(text(endOn(e, focus), choice(19, 14), MUTED, false));
            entry.addView(times, lp(u(choice(162, 79)), MATCH));
            LinearLayout details = column();
            details.setGravity(Gravity.CENTER_VERTICAL);
            details.addView(text(title(e), choice(32, 20), INK, true), lp(MATCH, WRAP));
            details.addView(space(), lp(1, u(choice(5, 3))));
            Data.Source src = source(e);
            details.addView(tag(src.name, src.color, choice(20, 14), choice(10, 7), eventInk(src.color), false), lp(MATCH, u(choice(29, 21))));
            entry.addView(details, growX());
            clickable(entry, title(e) + "，" + (e.allDay ? "全天" : startOn(e, focus) + " 至 " + endOn(e, focus)) + "，" + src.name, () -> eventDetails(e));
            rule(rows, LINE, hair());
        }
        int savedY = agendaY;
        scroll.post(() -> scroll.scrollTo(0, savedY));
        return body;
    }

    private LinearLayout dayCard(List<Data.Entry> day) {
        boolean today = focus.equals(now.toLocalDate());
        LinearLayout card = wide ? column() : row();
        card.setBackground(shape(SAGE, 10, 0));
        pad(card, choice(28, 14), choice(26, 8), choice(28, 12), choice(20, 8));
        LinearLayout clock = column();
        clock.setGravity(wide ? Gravity.TOP : Gravity.CENTER_VERTICAL);
        card.addView(clock, wide ? new LinearLayout.LayoutParams(MATCH, 0, 1f) : new LinearLayout.LayoutParams(0, MATCH, 1f));
        clock.addView(text(today ? "现在" : "所选日期", choice(22, 13), GREEN, true));
        clock.addView(text(today ? now.format(CLOCK) : md(focus), today ? choice(98, 32) : choice(50, 24), INK, false));
        clock.addView(text("星期" + weekday(focus), choice(32, 15), INK, true));
        if (wide) rule(card, 0xffd7e1d2, hair());
        else {
            View separator = space();
            separator.setBackgroundColor(0xffd7e1d2);
            card.addView(separator, lp(hair(), MATCH));
        }
        LinearLayout next = column();
        next.setGravity(wide ? Gravity.TOP : Gravity.CENTER_VERTICAL);
        pad(next, choice(0, 14), choice(24, 0), 0, 0);
        card.addView(next, wide ? new LinearLayout.LayoutParams(MATCH, 0, .96f) : new LinearLayout.LayoutParams(0, MATCH, 1.1f));
        long instant = now.toInstant().toEpochMilli();
        Data.Entry item = null;
        for (Data.Entry e : day) {
            if (!e.allDay && (!today || e.end > instant)) { item = e; break; }
        }
        boolean ongoing = today && item != null && item.start <= instant;
        next.addView(text(today ? (ongoing ? "进行中" : "下一项") : "首项定时日程", choice(22, 13), MUTED, true));
        next.addView(space(), lp(1, u(choice(10, 3))));
        next.addView(text(item == null ? "暂无定时日程" : title(item), item == null ? choice(26, 17) : choice(42, 19), INK, true));
        if (item != null) next.addView(text((ongoing ? endOn(item, focus) + " 结束" : startOn(item, focus) + " 开始"), choice(25, 14), GREEN, false));
        return card;
    }

    final class ScreenRoot extends FrameLayout {
        ScreenRoot() { super(activity); }
        @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
            super.onSizeChanged(w, h, oldW, oldH);
            scheduleRender();
        }
        @Override protected Parcelable onSaveInstanceState() {
            rememberScroll();
            Bundle b = new Bundle();
            b.putParcelable("view", super.onSaveInstanceState());
            b.putString("mode", mode.name());
            b.putLong("focus", focus.toEpochDay());
            b.putBoolean("follow", followToday);
            b.putBoolean("followWeekTime", followWeekTime);
            b.putDouble("hour", topHour);
            b.putDouble("dayOffset", leftDay);
            b.putInt("agenda", agendaY);
            b.putBoolean("drawer", drawer != null && drawer.isShowing());
            return b;
        }
        @Override protected void onRestoreInstanceState(Parcelable state) {
            if (!(state instanceof Bundle)) { super.onRestoreInstanceState(state); return; }
            Bundle b = (Bundle) state;
            super.onRestoreInstanceState(b.getParcelable("view"));
            mode = readMode(b.getString("mode"));
            followToday = b.getBoolean("follow");
            followWeekTime = b.getBoolean("followWeekTime", true);
            LocalDate savedFocus = LocalDate.ofEpochDay(b.getLong("focus"));
            focus = followToday ? now.toLocalDate() : savedFocus;
            board = null;
            agendaScroll = null;
            if (mode == Mode.WEEK && followToday && followWeekTime && !savedFocus.equals(focus)) {
                topHour = leftDay = Double.NaN;
            } else {
                topHour = b.getDouble("hour", Double.NaN);
                leftDay = b.getDouble("dayOffset", Double.NaN);
            }
            agendaY = b.getInt("agenda");
            reopenDrawer = b.getBoolean("drawer");
            requestRange();
        }
    }
}
