package io.github.pageleaf.calendar;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class CalendarCacheSessionTest extends InstrumentationTestCase {
    private static final LocalDate DAY = LocalDate.of(2025, 3, 9);
    private static final class Credentials implements FeishuAuth.Store {
        JSONObject value;
        volatile boolean unavailable;
        boolean refreshOnly;
        public JSONObject read() throws IOException {
            if (unavailable) throw new IOException("Keystore temporarily unavailable");
            return value;
        }
        public void save(JSONObject state) throws Exception {
            if (refreshOnly) state.put("accessUntil", 0);
            value = state;
        }
        public void clear() { value = null; }
    }

    private final Credentials credentials = new Credentials();
    private FeishuAuth auth;
    private CalendarStore store;

    @Override protected void setUp() throws Exception {
        super.setUp();
        auth = new FeishuAuth(credentials, null);
        login("A");
        store = new CalendarStore(getInstrumentation().getTargetContext(), null);
    }

    @Override protected void tearDown() throws Exception {
        try { store.close(); } finally { super.tearDown(); }
    }

    private void login(String token) throws Exception {
        FeishuAuth.Login login = auth.newLogin();
        auth.install(login, "test-client", "fixture", new JSONObject()
                .put("access_token", token).put("refresh_token", "refresh-" + token));
        assertTrue(auth.acceptLogin(login));
    }

    private static JSONObject catalog(String name) throws Exception {
        JSONObject source = new JSONObject().put("calendar_id", "common").put("summary", name)
                .put("type", "primary").put("role", "owner");
        return new JSONObject().put("code", 0).put("data", new JSONObject()
                .put("calendar_list", new JSONArray().put(source)).put("has_more", false));
    }

    private CalendarStore.Session session() { return store.session(auth, auth.sessionId()); }

    private CalendarStore.Snapshot snapshot() throws Exception { return session().snapshot(DAY, DAY.plusDays(1)); }

    private static JSONObject event(String name) throws Exception {
        JSONObject row = new JSONObject().put("event_id", "same-event").put("summary", name)
                .put("start_time", new JSONObject().put("date", DAY.toString()))
                .put("end_time", new JSONObject().put("date", DAY.toString()));
        return new JSONObject().put("code", 0).put("data", new JSONObject().put("items", new JSONArray().put(row)));
    }

    private void seed(String owner) throws Exception {
        JSONObject calendars = catalog(owner + " calendar"), events = event(owner + " event");
        CalendarSync sync = new CalendarSync(session(), (url, bearer) ->
                "calendars".equals(android.net.Uri.parse(url).getLastPathSegment()) ? calendars : events);
        sync.sync(owner, DAY, DAY.plusDays(1));
    }

    public void testColdCredentialReadFailureHidesThenRestoresCacheAndChoices() throws Exception {
        seed("A");
        long completed = snapshot().lastSync;
        credentials.unavailable = true;
        auth = new FeishuAuth(credentials, null); // Cold process: credentials must be read again.
        CalendarStore.Snapshot hidden = snapshot();
        assertTrue(hidden.sources.isEmpty());
        assertTrue(hidden.events.isEmpty());
        assertEquals(0L, hidden.lastSync);
        credentials.unavailable = false;
        CalendarStore.Snapshot restored = snapshot();
        assertEquals("A calendar", restored.sources.get(0).name);
        assertTrue(restored.sources.get(0).enabled);
        assertEquals("A event", restored.events.get(0).title);
        assertEquals(completed, restored.lastSync);
    }

    private static Field activityField(String name) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    public void testActivityHidesColdCacheAndDiscardsQueuedSnapshotAfterGrantChange() throws Exception {
        seed("A");
        // Every token acquisition needs a refresh, supplied by this offline transport.
        credentials.refreshOnly = true;
        credentials.value.put("accessUntil", 0);
        FeishuProtocol offline = new FeishuProtocol((url, headers, type, body) -> { throw new IOException("Offline"); });
        credentials.unavailable = true;
        auth = new FeishuAuth(credentials, offline);
        Instrumentation ins = getInstrumentation();
        Context context = ins.getTargetContext();
        Field appAuth = activityField("appAuth"), appStore = activityField("appStore");
        Object previousAuth = appAuth.get(null), previousStore = appStore.get(null);
        ExecutorService cache = (ExecutorService) activityField("CACHE").get(null);
        MainActivity host = null;
        try {
            appAuth.set(null, auth);
            appStore.set(null, store);
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            host = (MainActivity) ins.startActivitySync(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            MainActivity activity = host;
            CalendarScreen screen = (CalendarScreen) activityField("screen").get(activity);
            cache.submit(() -> { }).get(5, TimeUnit.SECONDS);
            ins.runOnMainSync(() -> {
                assertFalse(screen.connected);
                assertTrue(screen.sources.isEmpty());
            });
            credentials.unavailable = false;
            ins.runOnMainSync(() -> activity.rangeChanged(DAY, DAY.plusDays(1)));
            // A network callback can supersede the first queued snapshot and request another.
            AtomicReference<Data.Source> restored = new AtomicReference<>();
            long deadline = SystemClock.elapsedRealtime() + 5000;
            while (SystemClock.elapsedRealtime() < deadline) {
                ins.runOnMainSync(() -> {
                    if (!screen.sources.isEmpty()) restored.set(screen.sources.get(0));
                });
                if (restored.get() != null) break;
                SystemClock.sleep(10);
            }
            assertNotNull("Cache was not displayed after credentials became available", restored.get());
            assertEquals("A calendar", restored.get().name);
            assertTrue(restored.get().enabled);
            // Hold the main queue until SQLite has posted A's rows, then install B before delivery.
            ins.runOnMainSync(() -> {
                try {
                    activity.rangeChanged(DAY, DAY.plusDays(1));
                    cache.submit(() -> { }).get(5, TimeUnit.SECONDS);
                    login("B");
                    activity.toggle("common", false); // Old visible switch races the credential notification.
                    assertTrue(screen.sources.isEmpty());
                } catch (Exception failure) { throw new AssertionError(failure); }
            });
            ins.waitForIdleSync();
            cache.submit(() -> { }).get(5, TimeUnit.SECONDS);
            ins.runOnMainSync(() -> {
                assertTrue(screen.connected);
                assertTrue(screen.sources.isEmpty());
            });
            assertTrue(snapshot().sources.isEmpty());
        } finally {
            if (host != null) ins.runOnMainSync(host::finish);
            ins.waitForIdleSync();
            appAuth.set(null, previousAuth);
            appStore.set(null, previousStore);
            cache.submit(() -> { }).get(5, TimeUnit.SECONDS);
        }
    }

    public void testVersionOneMigrationDiscardsRowsDespiteMatchingLegacyPreference() throws Exception {
        assertPreReleaseCacheInvalidated(1);
    }

    public void testVersionTwoMigrationDiscardsRowsDespiteMatchingAdoptedOwner() throws Exception {
        assertPreReleaseCacheInvalidated(2);
    }

    private void assertPreReleaseCacheInvalidated(int version) throws Exception {
        Context target = getInstrumentation().getTargetContext();
        String name = "cache-migration-" + UUID.randomUUID();
        SharedPreferences legacy = target.getSharedPreferences(name, Context.MODE_PRIVATE);
        Context isolated = new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String file, int mode) {
                assertEquals("cache_account", file);
                return legacy;
            }
        };
        store.close();
        try {
            store = new CalendarStore(isolated, name);
            seed("A");
            assertEquals("A event", snapshot().events.get(0).title);
            assertTrue(snapshot().sources.get(0).enabled);
            login("B");
            String grant = auth.sessionId();
            assertTrue(legacy.edit().putString("session", grant).commit());
            SQLiteDatabase db = store.getWritableDatabase();
            if (version == 1) {
                db.execSQL("DROP TABLE cache_owner");
            } else {
                // RC2 could adopt the preference for B while retaining A's rows.
                db.execSQL("UPDATE cache_owner SET session=? WHERE id=1", new Object[]{grant});
            }
            db.execSQL("DROP TABLE removed_source_preferences");
            db.setVersion(version);
            assertCacheRowCounts(db, 1);
            store.close();

            store = new CalendarStore(isolated, name);
            SQLiteDatabase migrated = store.getWritableDatabase();
            // Check physical rows before snapshot() can bind B or filter the event join.
            assertCacheRowCounts(migrated, 0);
            assertEquals(4, migrated.getVersion());
            assertEquals("", DatabaseUtils.stringForQuery(migrated, "SELECT session FROM cache_owner WHERE id=1", null));
            auth = new FeishuAuth(credentials, null);
            assertEquals(grant, auth.sessionId());
            assertEquals("B", auth.accessToken());
            CalendarStore.Snapshot offline = snapshot();
            assertTrue(offline.sources.isEmpty());
            assertTrue(offline.events.isEmpty());
            assertEquals(0L, offline.lastSync);

            seed("B");
            long completed = snapshot().lastSync;
            assertTrue(completed > 0);
            store.close();
            store = new CalendarStore(isolated, name);
            CalendarStore.Snapshot reopened = snapshot();
            assertCacheRowCounts(store.getReadableDatabase(), 1);
            assertEquals("B calendar", reopened.sources.get(0).name);
            assertTrue(reopened.sources.get(0).enabled);
            assertEquals("B event", reopened.events.get(0).title);
            assertEquals(completed, reopened.lastSync);
        } finally {
            store.close();
            target.deleteDatabase(name);
            target.deleteSharedPreferences(name);
        }
    }

    public void testVersionThreePreservesSwitchesAndCacheAcrossDiscovery() throws Exception {
        Context target = getInstrumentation().getTargetContext();
        String name = "cache-v3-" + UUID.randomUUID();
        store.close();
        try {
            store = new CalendarStore(target, name);
            seed("A");
            long completed = snapshot().lastSync;
            SQLiteDatabase db = store.getWritableDatabase();
            db.execSQL("DROP TABLE removed_source_preferences");
            // V3 used the same zero for an untouched switch and an explicit opt-out.
            db.execSQL("INSERT INTO sources(id,name,color,reason) VALUES('off','off',0,'')");
            db.setVersion(3);
            store.close();

            store = new CalendarStore(target, name);
            CalendarStore.Snapshot upgraded = snapshot();
            assertEquals(completed, upgraded.lastSync);
            assertEquals("A event", upgraded.events.get(0).title);
            assertTrue(upgraded.sources.get(0).enabled);
            assertFalse(upgraded.sources.get(1).enabled);
            JSONObject calendars = catalog("A calendar");
            JSONArray items = calendars.getJSONObject("data").getJSONArray("calendar_list");
            for (String id : new String[]{"off", "new"}) {
                items.put(new JSONObject().put("calendar_id", id).put("summary", id)
                        .put("type", "shared").put("role", "reader"));
            }
            JSONObject events = event("synced");
            CalendarSync sync = new CalendarSync(session(), (url, bearer) -> {
                android.net.Uri uri = android.net.Uri.parse(url);
                if ("calendars".equals(uri.getLastPathSegment())) return calendars;
                assertFalse(uri.getPath().contains("/off/"));
                return events;
            });
            sync.sync("A", DAY, DAY.plusDays(1));
            CalendarStore.Snapshot refreshed = snapshot();
            assertEquals(2, refreshed.events.size());
            assertEquals("common", refreshed.events.get(0).calendarId);
            assertEquals("new", refreshed.events.get(1).calendarId);
            assertFalse(refreshed.sources.get(2).enabled);
        } finally {
            store.close();
            target.deleteDatabase(name);
        }
    }

    public void testRemovedCalendarKeepsOnlySwitchUntilGrantEnds() throws Exception {
        Context target = getInstrumentation().getTargetContext();
        String name = "cache-removed-" + UUID.randomUUID();
        store.close();
        try {
            store = new CalendarStore(target, name);
            seed("A");
            session().toggle("common", false);
            session().catalog(Collections.emptyMap());
            assertEquals(0L, DatabaseUtils.queryNumEntries(store.getReadableDatabase(), "sources"));
            assertEquals(0L, DatabaseUtils.queryNumEntries(store.getReadableDatabase(), "events"));
            store.close();
            store = new CalendarStore(target, name);
            JSONObject calendars = catalog("Resubscribed");
            CalendarSync sync = new CalendarSync(session(), (url, bearer) -> {
                assertEquals("calendars", android.net.Uri.parse(url).getLastPathSegment());
                return calendars;
            });
            sync.sync("A", DAY, DAY.plusDays(1));
            assertFalse(snapshot().sources.get(0).enabled);
            session().catalog(Collections.emptyMap());
            auth.signOut(auth.sessionId(), store::clear);
            assertEquals(0L, DatabaseUtils.queryNumEntries(store.getReadableDatabase(), "removed_source_preferences"));
        } finally {
            store.close();
            target.deleteDatabase(name);
        }
    }

    private static void assertCacheRowCounts(SQLiteDatabase db, long expected) {
        for (String table : new String[]{"sources", "events", "sync_state"}) {
            assertEquals(table, expected, DatabaseUtils.queryNumEntries(db, table));
        }
    }

    public void testStaleSwitchAndSnapshotCannotRebindAfterLogin() throws Exception {
        seed("A");
        CalendarStore.Session old = session();
        old.toggle("common", false);
        old.catalog(Collections.emptyMap());
        assertTrue(snapshot().sources.isEmpty());
        // A cold reader holding A still considers its credential valid even after SQLite rebinds.
        FeishuAuth oldReader = new FeishuAuth(credentials, null);
        CalendarStore.Session oldOwner = store.session(oldReader, oldReader.sessionId());
        login("B");
        seed("B");
        try { old.snapshot(DAY, DAY.plusDays(1)); fail(); } catch (FeishuAuth.SignInRequired expected) { }
        try { old.toggle("common", false); fail(); } catch (FeishuAuth.SignInRequired expected) { }
        try { oldOwner.toggle("common", false); fail(); } catch (FeishuAuth.SignInRequired expected) { }
        try { auth.token(old.id, "A"); fail(); } catch (FeishuAuth.SignInRequired expected) { }
        try { auth.signOut(old.id, store::clear); fail(); } catch (FeishuAuth.SignInRequired expected) { }
        assertEquals("B event", snapshot().events.get(0).title);
        assertTrue(snapshot().sources.get(0).enabled);
        auth.signOut(auth.sessionId(), store::clear);
        assertFalse(auth.connected());
        assertTrue(store.sources().isEmpty());
        assertEquals(0L, store.lastSync());
    }

    public void testLateCatalogCannotPopulateNewOfflineGrant() throws Exception {
        delayedReply(false, false);
    }

    public void testLateEventCannotReplaceNewGrantsSameCalendarId() throws Exception {
        delayedReply(true, false);
    }

    public void testLateRevocationCannotHideNewGrantsSameCalendarId() throws Exception {
        delayedReply(true, true);
    }

    private void delayedReply(boolean delayEvents, boolean revoke) throws Exception {
        if (delayEvents) seed("A");
        JSONObject calendars = catalog("Private A"), events = event("Late A");
        CountDownLatch requested = new CountDownLatch(1), release = new CountDownLatch(1);
        CalendarSync sync = new CalendarSync(session(), (url, bearer) -> {
            assertEquals("A", bearer);
            boolean listing = android.net.Uri.parse(url).getLastPathSegment().equals("calendars");
            if (delayEvents && listing) return calendars;
            requested.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("HTTP timeout");
            } catch (InterruptedException e) { throw new AssertionError(e); }
            if (revoke) throw new Net.HttpError(403, 191002, "");
            return listing ? calendars : events;
        });
        FutureTask<Void> oldSync = new FutureTask<>(() -> {
            sync.sync("A", DAY, DAY.plusDays(1));
            return null;
        });
        new Thread(oldSync).start();
        long completed = 0;
        try {
            assertTrue(requested.await(5, TimeUnit.SECONDS));
            credentials.value.put("accessUntil", 0).put("refreshUntil", 0);
            assertFalse(auth.connected());
            login("B");
            assertTrue(snapshot().sources.isEmpty());
            if (delayEvents) {
                seed("B");
                completed = snapshot().lastSync;
            }
        } finally { release.countDown(); }
        try { oldSync.get(5, TimeUnit.SECONDS); fail("Stale sync reported success"); }
        catch (ExecutionException e) { assertTrue(e.getCause() instanceof FeishuAuth.SignInRequired); }
        CalendarStore.Snapshot result = snapshot();
        if (delayEvents) {
            assertEquals("B calendar", result.sources.get(0).name);
            assertTrue(result.sources.get(0).enabled);
            assertEquals("B event", result.events.get(0).title);
        } else {
            assertTrue("B received A's delayed calendar", result.sources.isEmpty());
            assertTrue(result.events.isEmpty());
        }
        assertEquals(completed, result.lastSync);
    }
}
