package io.github.pageleaf.calendar;

import android.content.Context;
import android.net.Uri;
import android.test.InstrumentationTestCase;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;

public final class CalendarSyncTest extends InstrumentationTestCase {
    private static final LocalDate DAY = LocalDate.of(2025, 3, 9);
    private CalendarStore store;
    private FeishuAuth auth;
    private CalendarSync sync;
    private final List<Object> replies = new ArrayList<>();
    private final List<Uri> requests = new ArrayList<>();

    @Override protected void setUp() throws Exception {
        super.setUp();
        store = new CalendarStore(getInstrumentation().getTargetContext(), null);
        auth = new FeishuAuth(new FeishuAuth.Store() {
            public JSONObject read() { return null; }
            public void save(JSONObject state) { }
            public void clear() { }
        }, null);
        auth.install(auth.newLogin(), "id", "secret", new JSONObject()
                .put("access_token", "fixture").put("refresh_token", "refresh"));
        sync = new CalendarSync(store.session(auth, auth.sessionId()), (url, bearer) -> {
            assertEquals("fixture", bearer);
            Uri uri = Uri.parse(url);
            assertEquals("https", uri.getScheme());
            assertEquals("open.feishu.cn", uri.getHost());
            requests.add(uri);
            assertFalse("Unexpected request", replies.isEmpty());
            Object next = replies.remove(0);
            if (next instanceof IOException) throw (IOException) next;
            return (JSONObject) next;
        });
    }

    @Override protected void tearDown() throws Exception {
        try {
            store.close();
        } finally {
            super.tearDown();
        }
    }

    private static JSONObject calendar(String id, String type, String role) throws JSONException {
        return new JSONObject().put("calendar_id", id).put("summary", id)
                .put("type", type).put("role", role);
    }

    private static JSONObject page(String key, JSONObject... items) throws JSONException {
        return new JSONObject().put("code", 0).put("data", new JSONObject()
                .put(key, new JSONArray(Arrays.asList(items))).put("has_more", false));
    }

    private static JSONObject catalog(JSONObject... items) throws JSONException {
        return page("calendar_list", items);
    }

    private static JSONObject timed(String id, LocalDate date) throws JSONException {
        long start = date.atTime(12, 0).atZone(ZoneId.systemDefault()).toEpochSecond();
        return new JSONObject().put("event_id", id).put("summary", id)
                .put("start_time", new JSONObject().put("timestamp", String.valueOf(start)))
                .put("end_time", new JSONObject().put("timestamp", String.valueOf(start + 600)));
    }

    private void runSync(LocalDate start, LocalDate end, Object... responses) throws Exception {
        replies.addAll(Arrays.asList(responses));
        sync.sync("fixture", start, end);
        assertTrue("Unused responses", replies.isEmpty());
    }

    public void testSourcePaginationAndFirstLogin() throws Exception {
        JSONObject shared = calendar("shared/@", "shared", "reader").put("summary_alias", "Team");
        JSONObject first = catalog(shared);
        first.getJSONObject("data").put("has_more", true).put("page_token", "a+&/=");
        runSync(DAY, DAY.plusDays(1), first, catalog(calendar("room", "resource", "writer"),
                calendar("external", "exchange", "owner"), calendar("busy", "primary", "free_busy_reader")));
        assertEquals(2, requests.size());
        assertEquals("a+&/=", requests.get(1).getQueryParameter("page_token"));
        assertEquals(4, store.sources().size());
        for (Data.Source source : store.sources()) {
            assertFalse(source.enabled);
            if (source.id.equals("shared/@")) assertEquals("Team", source.name);
            assertEquals(source.id.equals("shared/@") || source.id.equals("room"), source.readable);
            if (!source.readable) assertFalse(source.reason.isEmpty());
        }
        store.toggle("shared/@", true);
        runSync(DAY, DAY.plusDays(1), catalog(shared), page("items", timed("event_0", DAY)));
        Uri eventRequest = requests.get(3);
        assertEquals(Arrays.asList("open-apis", "calendar", "v4", "calendars", "shared/@", "events", "instance_view"),
                eventRequest.getPathSegments());
        assertNull(eventRequest.getQueryParameter("page_size"));
        assertEquals(1, store.sources().size());
        assertTrue(store.sources().get(0).enabled);
        assertEquals("event_0", store.events(DAY, DAY.plusDays(1)).get(0).id);
        assertTrue(store.lastSync() > 0);
    }

    public void testOverfullInstanceViewSplitsAndDeduplicates() throws Exception {
        JSONObject list = catalog(calendar("shared", "shared", "reader"));
        runSync(DAY, DAY.plusDays(42), list);
        store.toggle("shared", true);
        JSONObject event = timed("series_123456", DAY.plusDays(1));
        runSync(DAY, DAY.plusDays(42), list, new Net.HttpError(400, 193104, ""),
                page("items", event), page("items", event), page("items", timed("series_234567", DAY.plusDays(40))));
        for (Uri uri : requests.subList(2, requests.size())) {
            long start = Long.parseLong(uri.getQueryParameter("start_time"));
            long end = Long.parseLong(uri.getQueryParameter("end_time"));
            assertTrue(end > start && end - start < 40 * 86_400);
        }
        assertEquals(2, store.events(DAY, DAY.plusDays(42)).size());
    }

    public void testCacheSurvivesPartialDownloadAndRedactsFailure() throws Exception {
        JSONObject a = calendar("a", "primary", "owner"), b = calendar("b", "shared", "reader");
        JSONObject list = catalog(a, b);
        runSync(DAY, DAY.plusDays(1), list);
        store.toggle("a", true);
        store.toggle("b", true);
        runSync(DAY, DAY.plusDays(1), list, page("items", timed("old_a", DAY)), page("items", timed("old_b", DAY)));
        long completed = store.lastSync();
        try {
            runSync(DAY, DAY.plusDays(1), list, page("items"), new IOException("private secret in URL"));
            fail();
        } catch (IOException failure) {
            assertFalse(failure.getMessage().contains("private secret"));
            assertNull(failure.getCause());
        }
        assertEquals(completed, store.lastSync());
        assertEquals(2, store.events(DAY, DAY.plusDays(1)).size());
        JSONObject partial = catalog(a);
        partial.getJSONObject("data").put("has_more", true).put("page_token", "same");
        try {
            runSync(DAY, DAY.plusDays(1), partial, partial);
            fail();
        } catch (IOException expected) {
            assertEquals(2, store.sources().size());
            assertTrue(store.sources().get(1).enabled);
        }
    }

    public void testPerCalendarAccessLossAndGlobalPermissionError() throws Exception {
        JSONObject list = catalog(calendar("a", "primary", "owner"), calendar("b", "shared", "reader"));
        runSync(DAY, DAY.plusDays(1), list);
        store.toggle("a", true);
        store.toggle("b", true);
        runSync(DAY, DAY.plusDays(1), list, page("items", timed("private", DAY)), page("items", timed("keep", DAY)));
        runSync(DAY, DAY.plusDays(1), list, new Net.HttpError(403, 191002, ""), page("items", timed("keep", DAY)));
        assertFalse(store.sources().get(0).readable);
        assertEquals(1, store.events(DAY, DAY.plusDays(1)).size());
        try {
            runSync(DAY, DAY.plusDays(1), list, new Net.HttpError(403, 99991672, ""));
            fail();
        } catch (Net.HttpError expected) {
            assertEquals(403, expected.status);
            assertEquals("keep", store.events(DAY, DAY.plusDays(1)).get(0).id);
        }
        runSync(DAY, DAY.plusDays(1), list, new Net.HttpError(404, 191000, ""), page("items"));
        assertEquals(1, store.sources().size());
        assertEquals("b", store.sources().get(0).id);
    }

    public void testAllDayDatesAndOfflineZoneChange() throws Exception {
        TimeZone previous = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
            JSONObject list = catalog(calendar("p", "primary", "owner"));
            runSync(DAY, DAY.plusDays(2), list);
            store.toggle("p", true);
            JSONObject allDay = new JSONObject().put("event_id", "holiday").put("start_time",
                    new JSONObject().put("date", DAY.toString())).put("end_time",
                    new JSONObject().put("date", DAY.plusDays(1).toString()));
            runSync(DAY, DAY.plusDays(2), list, page("items", allDay));
            for (String zone : Arrays.asList("America/New_York", "Asia/Shanghai")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                Data.Entry item = store.events(DAY.plusDays(1), DAY.plusDays(2)).get(0);
                assertTrue(item.allDay);
                assertEquals(DAY.atStartOfDay(ZoneId.of(zone)).toInstant().toEpochMilli(), item.start);
                assertEquals(DAY.plusDays(2).atStartOfDay(ZoneId.of(zone)).toInstant().toEpochMilli(), item.end);
                assertTrue(store.events(DAY.plusDays(2), DAY.plusDays(3)).isEmpty());
            }
        } finally {
            TimeZone.setDefault(previous);
        }
    }

    public void testMovedCancelledAndUnsubscribed() throws Exception {
        JSONObject list = catalog(calendar("p", "primary", "owner"));
        runSync(DAY, DAY.plusDays(1), list);
        store.toggle("p", true);
        runSync(DAY, DAY.plusDays(1), list, page("items", timed("move", DAY), timed("cancel", DAY)));
        runSync(DAY.plusDays(8), DAY.plusDays(9), list, page("items", timed("move", DAY.plusDays(8))));
        assertEquals(1, store.events(DAY, DAY.plusDays(1)).size());
        runSync(DAY, DAY.plusDays(1), list, page("items", new JSONObject().put("event_id", "cancel").put("status", "cancelled")));
        assertTrue(store.events(DAY, DAY.plusDays(1)).isEmpty());
        assertEquals(1, store.events(DAY.plusDays(8), DAY.plusDays(9)).size());
        store.toggle("p", false);
        assertTrue(store.events(DAY, DAY.plusDays(9)).isEmpty());
        store.toggle("p", true);
        assertEquals(1, store.events(DAY, DAY.plusDays(9)).size());
        runSync(DAY, DAY.plusDays(1), catalog());
        runSync(DAY, DAY.plusDays(1), list);
        assertFalse(store.sources().get(0).enabled);
        store.toggle("p", true);
        assertTrue(store.events(DAY, DAY.plusDays(9)).isEmpty());
        store.clear();
        assertEquals(0L, store.lastSync());
        assertTrue(store.sources().isEmpty());
    }

    public void testRoomExpansionIncludesOverridesAndPages() throws Exception {
        JSONObject list = catalog(calendar("room", "resource", "reader"));
        runSync(DAY, DAY.plusDays(3), list);
        store.toggle("room", true);
        JSONObject master = timed("master_0", DAY.minusYears(2)).put("recurrence", "FREQ=DAILY");
        JSONObject override = timed("master_100", DAY.plusDays(1)).put("is_exception", true).put("summary", "moved");
        JSONObject cancelled = new JSONObject().put("event_id", "master_200").put("is_exception", true).put("status", "cancelled");
        JSONObject first = page("items", timed("master_100", DAY), timed("master_200", DAY));
        first.getJSONObject("data").put("has_more", true).put("page_token", "more&");
        runSync(DAY, DAY.plusDays(3), list, page("items", master, override, cancelled), first,
                page("items", timed("master_300", DAY.plusDays(2))));
        assertNotNull(requests.get(2).getQueryParameter("anchor_time"));
        assertNull(requests.get(2).getQueryParameter("start_time"));
        assertTrue(requests.get(3).getPath().endsWith("/room/events/master_0/instances"));
        assertEquals("more&", requests.get(4).getQueryParameter("page_token"));
        List<Data.Entry> events = store.events(DAY, DAY.plusDays(3));
        assertEquals(2, events.size());
        assertEquals("moved", events.get(0).title);
        assertEquals("master_300", events.get(1).id);
    }

    public void testDenseViewFallsBackToPaginatedEvents() throws Exception {
        JSONObject list = catalog(calendar("primary", "primary", "owner"));
        runSync(DAY, DAY.plusDays(1), list);
        store.toggle("primary", true);
        JSONObject fallback = page("items", timed("crowded", DAY));
        int[] calls = {0};
        sync = new CalendarSync(store.session(auth, auth.sessionId()), (url, bearer) -> {
            Uri uri = Uri.parse(url);
            if (uri.getPath().endsWith("/calendars")) return list;
            if (uri.getPath().endsWith("/instance_view")) {
                calls[0]++;
                throw new Net.HttpError(400, 193104, "");
            }
            assertTrue(uri.getPath().endsWith("/primary/events"));
            assertNotNull(uri.getQueryParameter("anchor_time"));
            return fallback;
        });
        sync.sync("fixture", DAY, DAY.plusDays(1));
        assertTrue(calls[0] > 1 && calls[0] < 9);
        assertEquals("crowded", store.events(DAY, DAY.plusDays(1)).get(0).id);
    }

    public void testMalformedResponsesAndTransientStatusesPreserveCache() throws Exception {
        JSONObject list = catalog(calendar("p", "primary", "owner"));
        runSync(DAY, DAY.plusDays(1), list);
        store.toggle("p", true);
        runSync(DAY, DAY.plusDays(1), list, page("items", timed("cached", DAY)));
        long completed = store.lastSync();
        for (int status : new int[]{401, 403, 429, 503}) {
            try {
                runSync(DAY, DAY.plusDays(1), list, new Net.HttpError(status, 0, ""));
                fail();
            } catch (Net.HttpError expected) {
                assertEquals(status, expected.status);
            }
        }
        JSONObject invalid = timed("sensitive", DAY);
        invalid.getJSONObject("start_time").put("timestamp", "sensitive");
        try {
            runSync(DAY, DAY.plusDays(1), list, page("items", invalid));
            fail();
        } catch (IOException expected) {
            assertFalse(expected.getMessage().contains("sensitive"));
            assertNull(expected.getCause());
        }
        assertEquals(completed, store.lastSync());
        assertEquals("cached", store.events(DAY, DAY.plusDays(1)).get(0).id);
    }

    public void testRefreshingRoomTodayPreservesFutureExceptionOffline() throws Exception {
        JSONObject list = catalog(calendar("room", "resource", "reader"));
        JSONObject future = timed("overridden_123", DAY.plusDays(8)).put("is_exception", true);
        runSync(DAY, DAY.plusDays(1), list);
        store.toggle("room", true);
        runSync(DAY.plusDays(8), DAY.plusDays(9), list, page("items", future));
        runSync(DAY, DAY.plusDays(1), list, page("items", future));
        assertTrue(store.events(DAY, DAY.plusDays(1)).isEmpty());
        assertEquals(1, store.events(DAY.plusDays(8), DAY.plusDays(9)).size());
    }

    public void testReopenDatabaseAndClear() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        String database = "calendar-sync-test-" + java.util.UUID.randomUUID() + ".db";
        JSONObject list = catalog(calendar("c", "primary", "owner"));
        store.close();
        try {
            store = new CalendarStore(context, database);
            sync = new CalendarSync(store.session(auth, auth.sessionId()), (url, bearer) -> (JSONObject) replies.remove(0));
            runSync(DAY, DAY.plusDays(1), list);
            store.toggle("c", true);
            runSync(DAY, DAY.plusDays(1), list, page("items", timed("persist", DAY)));
            long firstSync = store.lastSync();
            store.close();
            store = new CalendarStore(context, database);
            assertTrue(store.sources().get(0).enabled);
            assertEquals(firstSync, store.lastSync());
            assertEquals("persist", store.events(DAY, DAY.plusDays(1)).get(0).id);
            store.clear();
            store.close();
            store = new CalendarStore(context, database);
            assertEquals(0L, store.lastSync());
            assertTrue(store.sources().isEmpty());
            assertTrue(store.events(DAY, DAY.plusDays(1)).isEmpty());
        } finally {
            store.close();
            context.deleteDatabase(database);
        }
    }
}
