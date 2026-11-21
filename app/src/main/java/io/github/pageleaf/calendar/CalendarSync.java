package io.github.pageleaf.calendar;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads the authenticated user's Feishu calendars. Call from the serial network executor. */
public final class CalendarSync {
    private static final Uri CALENDARS = Uri.parse("https://open.feishu.cn/open-apis/calendar/v4/calendars");
    private static final long DAY_SECONDS = 86_400L;
    private final CalendarStore.Session session;
    private final Get http;

    interface Get {
        JSONObject get(String url, String bearer) throws IOException;
    }

    CalendarSync(CalendarStore.Session session) {
        this(session, Net::get);
    }

    CalendarSync(CalendarStore.Session session, Get http) {
        this.session = session;
        this.http = http;
    }

    public void sync(String bearer, LocalDate from, LocalDate until) throws IOException {
        if (!from.isBefore(until)) throw new IllegalArgumentException("Empty calendar range");
        try {
            session.bind();
            fetch(bearer, new CalendarWindow(from, until, ZoneId.systemDefault()));
        } catch (JSONException | DateTimeException | ArithmeticException | NumberFormatException malformed) {
            // JSON and timestamp exceptions can quote raw titles, URLs or whole responses.
            throw new IOException("Invalid Feishu calendar response");
        }
    }

    private void fetch(String bearer, CalendarWindow window) throws IOException, JSONException {
        Map<String, Data.Source> catalog = new LinkedHashMap<>();
        Set<String> rooms = new HashSet<>();
        Uri list = CALENDARS.buildUpon().appendQueryParameter("page_size", "1000").build();
        pages(list, bearer, "calendar_list", row -> {
            String id = id(row, "calendar_id");
            if (row.optBoolean("is_deleted")) {
                catalog.remove(id);
                rooms.remove(id);
                return;
            }
            String type = text(row, "type");
            if ("resource".equals(type)) rooms.add(id); else rooms.remove(id);
            String reason = accessReason(type, text(row, "role"));
            String name = text(row, "summary_alias");
            if (name.isEmpty()) name = text(row, "summary");
            int rgb = row.optInt("color", -1);
            int color = rgb == -1 || rgb == 0 ? 0xff507764 : rgb | 0xff000000;
            catalog.put(id, new Data.Source(id, name.isEmpty() ? "未命名日历" : name, color,
                    false, reason.isEmpty(), reason));
        });
        // A complete catalog is authoritative for unsubscribes and permission loss even
        // when a subsequent event request fails. Partial lists never reach the cache.
        List<Data.Source> sources = session.catalog(catalog);
        Map<String, Map<String, Data.Entry>> snapshots = new LinkedHashMap<>();
        for (Data.Source source : sources) {
            if (!source.enabled || !source.readable) continue;
            Uri events = CALENDARS.buildUpon().appendPath(source.id).appendPath("events").build();
            Map<String, Data.Entry> rows = new LinkedHashMap<>();
            try {
                loadCalendar(events, source.id, rooms.contains(source.id), bearer, window, rows);
                snapshots.put(source.id, rows);
            } catch (Net.HttpError error) {
                // Only these Feishu business codes establish calendar-specific loss of access.
                // An app scope error, rate limit or auth failure preserves offline events.
                if (error.code == 191000 || error.code == 191003) {
                    catalog.remove(source.id);
                } else if (error.code == 191002 || error.code == 191004) {
                    String reason = error.code == 191002 ? "无日程读取权限" : "暂不支持此日历类型";
                    catalog.put(source.id, new Data.Source(source.id, source.name, source.color, false, false, reason));
                } else throw error;
                session.catalog(catalog);
            }
        }
        session.replace(window, snapshots);
    }

    private void loadCalendar(Uri events, String calendar, boolean room, String bearer, CalendarWindow window,
                              Map<String, Data.Entry> rows) throws IOException, JSONException {
        if (!room) {
            Uri view = events.buildUpon().appendPath("instance_view").build();
            // instance_view has no pagination and requires a span strictly under 40 days.
            try {
                for (long start = window.firstSecond; start < window.lastSecond; ) {
                    long end = Math.min(start + 39 * DAY_SECONDS, window.lastSecond);
                    instanceView(view, calendar, bearer, window, start, end, rows);
                    start = end;
                }
                return;
            } catch (Net.HttpError error) {
                if (error.code != 193104) throw error;
                // Even short intervals may contain too many simultaneous events to split.
                rows.clear();
            }
        }
        pagedEvents(events, calendar, bearer, window, rows);
    }

    private static String accessReason(String type, String role) {
        if ("google".equals(type)) return "飞书暂不支持读取 Google 日历日程";
        if ("exchange".equals(type)) return "飞书暂不支持读取 Exchange 日历日程";
        if (!"primary".equals(type) && !"shared".equals(type) && !"resource".equals(type))
            return "暂不支持此日历类型";
        if ("free_busy_reader".equals(role)) return "仅可查看忙闲";
        if (!"reader".equals(role) && !"writer".equals(role) && !"owner".equals(role))
            return "无日程读取权限";
        return "";
    }

    private void instanceView(Uri view, String calendar, String bearer, CalendarWindow window,
                              long start, long end, Map<String, Data.Entry> rows) throws IOException, JSONException {
        JSONArray items;
        try {
            JSONObject data = get(range(view, start, end), bearer);
            items = data.getJSONArray("items");
            if (items.length() >= 1000 || data.optBoolean("has_more")) throw new Net.HttpError(200, 193104, "");
        } catch (Net.HttpError error) {
            if (error.code != 193104) throw error;
            // Feishu signals an overfull view with 193104. Never publish a truncated slice.
            if (end - start <= 2 * 3600) throw error;
            long middle = start + (end - start) / 2;
            instanceView(view, calendar, bearer, window, start, middle, rows);
            instanceView(view, calendar, bearer, window, middle, end, rows);
            return;
        }
        for (int i = 0; i < items.length(); i++) {
            putInstance(rows, items.getJSONObject(i), calendar, window);
        }
    }

    /** Rooms are supported by events/list but not by instance_view. Anchor mode pages
     * singles, every recurring master and moved exceptions; expand masters on Feishu. */
    private void pagedEvents(Uri events, String calendar, String bearer, CalendarWindow window,
                             Map<String, Data.Entry> rows) throws IOException, JSONException {
        Set<String> masters = new LinkedHashSet<>();
        Map<String, Data.Entry> exceptions = new LinkedHashMap<>();
        Uri list = events.buildUpon().appendQueryParameter("anchor_time", String.valueOf(window.firstSecond))
                .appendQueryParameter("page_size", "1000").build();
        pages(list, bearer, "items", row -> {
            String id = id(row, "event_id");
            if (row.optBoolean("is_exception")) {
                putInstance(exceptions, row, calendar, window);
            } else if (!text(row, "recurrence").isEmpty()) {
                if (!"cancelled".equals(text(row, "status"))) masters.add(id);
            } else {
                putInstance(rows, row, calendar, window);
            }
        });
        for (String id : masters) {
            Uri instances = events.buildUpon().appendPath(id).appendPath("instances")
                    .appendQueryParameter("page_size", "500").build();
            for (long start = window.firstSecond; start < window.lastSecond; ) {
                long end = Math.min(start + 365 * DAY_SECONDS, window.lastSecond);
                pages(range(instances, start, end), bearer, "items",
                        row -> putInstance(rows, row, calendar, window));
                start = end;
            }
        }
        // A moved/cancelled exception supersedes the generated occurrence using the ID
        // of its original time, even if its new time lies outside the visible window.
        rows.putAll(exceptions);
    }

    private static void putInstance(Map<String, Data.Entry> rows, JSONObject row, String calendar,
                                    CalendarWindow window) throws JSONException {
        String id = id(row, "event_id");
        if ("cancelled".equals(text(row, "status"))) {
            rows.put(id, null);
            return;
        }
        JSONObject a = row.getJSONObject("start_time"), b = row.getJSONObject("end_time");
        boolean allDay = !text(a, "date").isEmpty();
        if (allDay != !text(b, "date").isEmpty()) throw new JSONException("Mixed event time types");
        long start, end;
        if (allDay) {
            // Feishu's inclusive end date is a civil date, including on DST boundaries.
            start = CalendarWindow.midnight(LocalDate.parse(text(a, "date")), ZoneOffset.UTC);
            end = CalendarWindow.midnight(LocalDate.parse(text(b, "date")).plusDays(1), ZoneOffset.UTC);
        } else {
            start = Math.multiplyExact(Long.parseLong(text(a, "timestamp")), 1000);
            end = Math.multiplyExact(Long.parseLong(text(b, "timestamp")), 1000);
        }
        if (end <= start) throw new JSONException("Invalid event interval");
        String title = text(row, "summary");
        Data.Entry event = new Data.Entry(id, calendar, title.isEmpty() ? "（无标题）" : title, start, end, allDay);
        // An exception outside the view can correct an already cached occurrence.
        if (window.overlaps(event) || row.optBoolean("is_exception")) rows.put(id, event);
    }

    private interface Item {
        void accept(JSONObject row) throws IOException, JSONException;
    }

    private void pages(Uri base, String bearer, String key, Item consume) throws IOException, JSONException {
        Uri page = base;
        Set<String> seen = new HashSet<>();
        while (true) {
            JSONObject data = get(page, bearer);
            JSONArray items = data.getJSONArray(key);
            for (int i = 0; i < items.length(); i++) consume.accept(items.getJSONObject(i));
            if (!data.getBoolean("has_more")) break;
            String token = id(data, "page_token");
            if (!seen.add(token)) throw new IOException("Invalid Feishu pagination");
            page = base.buildUpon().appendQueryParameter("page_token", token).build();
        }
    }

    private JSONObject get(Uri url, String bearer) throws IOException, JSONException {
        if (!session.id.equals(session.auth.sessionId())) throw new FeishuAuth.SignInRequired();
        JSONObject json;
        try {
            json = http.get(url.toString(), bearer);
        } catch (Net.HttpError status) {
            throw status;
        } catch (IOException failure) {
            throw new IOException("Feishu calendar request failed");
        }
        int code = json.getInt("code");
        if (code != 0) throw new Net.HttpError(200, code, "");
        return json.getJSONObject("data");
    }

    private static String text(JSONObject obj, String key) {
        return obj.isNull(key) ? "" : obj.optString(key);
    }

    private static String id(JSONObject obj, String key) throws JSONException {
        Object value = obj.get(key);
        if (!(value instanceof String) || ((String) value).isEmpty()) throw new JSONException("Missing identifier");
        return (String) value;
    }

    private static Uri range(Uri uri, long start, long end) {
        return uri.buildUpon().appendQueryParameter("start_time", String.valueOf(start))
                .appendQueryParameter("end_time", String.valueOf(end)).build();
    }

}
