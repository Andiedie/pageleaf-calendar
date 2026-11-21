package io.github.pageleaf.calendar;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

public final class CalendarStore extends SQLiteOpenHelper {
    // All-day rows use UTC midnight to encode civil dates independent of device zone.
    private static final String OVERLAP = "((all_day=0 AND starts<? AND ends>?) OR "
            + "(all_day=1 AND starts<? AND ends>?))";

    private static final int DATABASE_VERSION = 3;

    public CalendarStore(Context context) {
        this(context, "calendar.db");
    }

    CalendarStore(Context context, String databaseName) {
        super(context.getApplicationContext(), databaseName, null, DATABASE_VERSION);
    }

    @Override public void onConfigure(SQLiteDatabase db) {
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE sources (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, "
                + "color INTEGER NOT NULL, enabled INTEGER NOT NULL DEFAULT 0, reason TEXT NOT NULL)");
        db.execSQL("CREATE TABLE events (calendar_id TEXT NOT NULL REFERENCES sources(id) ON DELETE CASCADE, "
                + "id TEXT NOT NULL, title TEXT NOT NULL, starts INTEGER NOT NULL, ends INTEGER NOT NULL, "
                + "all_day INTEGER NOT NULL, PRIMARY KEY(calendar_id,id))");
        db.execSQL("CREATE INDEX event_range ON events(calendar_id,all_day,starts)");
        db.execSQL("CREATE TABLE sync_state (id INTEGER PRIMARY KEY, completed INTEGER NOT NULL)");
        createOwner(db);
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if ((oldVersion != 1 && oldVersion != 2) || newVersion != 3) {
            throw new IllegalStateException("Unsupported calendar cache version");
        }
        // Pre-release v2 could adopt mixed v1 rows. Reset both caches in the helper's upgrade transaction.
        eraseRows(db);
        if (oldVersion == 1) createOwner(db);
        else db.execSQL("UPDATE cache_owner SET session='' WHERE id=1");
    }

    private static void createOwner(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE cache_owner (id INTEGER PRIMARY KEY CHECK(id=1), session TEXT NOT NULL)");
        db.execSQL("INSERT INTO cache_owner(id,session) VALUES(1,'')");
    }

    static final class Snapshot {
        static final Snapshot EMPTY = new Snapshot(Collections.emptyList(), Collections.emptyList(), 0);
        final List<Data.Source> sources;
        final List<Data.Entry> events;
        final long lastSync;
        Snapshot(List<Data.Source> sources, List<Data.Entry> events, long lastSync) {
            this.sources = sources;
            this.events = events;
            this.lastSync = lastSync;
        }
    }

    final class Session {
        final FeishuAuth auth;
        final String id;

        Session(FeishuAuth auth, String id) {
            this.auth = auth;
            this.id = id;
        }

        private <T> T access(boolean bind, Supplier<T> work) throws FeishuAuth.SignInRequired {
            return auth.withSession(id, () -> {
                SQLiteDatabase db = getWritableDatabase();
                db.beginTransaction();
                try {
                    String owner = DatabaseUtils.stringForQuery(db,
                            "SELECT session FROM cache_owner WHERE id=1", null);
                    if (!id.equals(owner)) {
                        if (!bind) throw new FeishuAuth.SignInRequired();
                        eraseRows(db);
                        db.execSQL("UPDATE cache_owner SET session=? WHERE id=1", new Object[]{id});
                    }
                    T result = work.get();
                    // Wall-clock expiry can occur while SQLite is busy. Roll back that operation.
                    if (!id.equals(auth.sessionId())) throw new FeishuAuth.SignInRequired();
                    db.setTransactionSuccessful();
                    return result;
                } finally { db.endTransaction(); }
            });
        }

        void bind() throws FeishuAuth.SignInRequired { access(true, () -> Boolean.TRUE); }

        Snapshot snapshot(LocalDate from, LocalDate until) throws FeishuAuth.SignInRequired {
            if (id.isEmpty()) return Snapshot.EMPTY;
            return access(true, () -> new Snapshot(sources(), events(from, until), lastSync()));
        }

        List<Data.Source> catalog(Map<String, Data.Source> catalog) throws FeishuAuth.SignInRequired {
            return access(false, () -> { updateSources(catalog); return sources(); });
        }

        void replace(CalendarWindow window, Map<String, Map<String, Data.Entry>> rows) throws FeishuAuth.SignInRequired {
            access(false, () -> { replaceWindow(window, rows); return Boolean.TRUE; });
        }

        void toggle(String source, boolean enabled) throws FeishuAuth.SignInRequired {
            access(false, () -> { CalendarStore.this.toggle(source, enabled); return Boolean.TRUE; });
        }
    }

    Session session(FeishuAuth auth, String id) { return new Session(auth, id); }

    List<Data.Source> sources() {
        List<Data.Source> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,name,color,enabled,reason FROM sources "
                + "ORDER BY name COLLATE NOCASE,id", null)) {
            while (c.moveToNext()) {
                String reason = c.getString(4);
                boolean readable = reason.isEmpty();
                result.add(new Data.Source(c.getString(0), c.getString(1), c.getInt(2),
                        readable && c.getInt(3) != 0, readable, reason));
            }
        }
        return result;
    }

    void toggle(String id, boolean enabled) {
        ContentValues values = new ContentValues();
        values.put("enabled", enabled);
        getWritableDatabase().update("sources", values, "id=? AND reason=''", new String[]{id});
    }

    /** Apply only a completely enumerated catalog, preserving local switches for retained IDs. */
    private void updateSources(Map<String, Data.Source> catalog) {
        SQLiteDatabase db = getWritableDatabase();
        Set<String> obsolete = new HashSet<>();
        try (Cursor c = db.rawQuery("SELECT id FROM sources", null)) {
            while (c.moveToNext()) {
                String id = c.getString(0);
                if (!catalog.containsKey(id)) obsolete.add(id);
            }
        }
        for (String id : obsolete) db.delete("sources", "id=?", new String[]{id});
        for (Data.Source source : catalog.values()) {
            ContentValues values = new ContentValues();
            values.put("name", source.name);
            values.put("color", source.color);
            values.put("reason", source.reason);
            if (db.update("sources", values, "id=?", new String[]{source.id}) == 0) {
                values.put("id", source.id);
                db.insertOrThrow("sources", null, values);
            }
            if (!source.readable) db.delete("events", "calendar_id=?", new String[]{source.id});
        }
    }

    List<Data.Entry> events(LocalDate from, LocalDate until) {
        List<Data.Entry> result = new ArrayList<>();
        if (!from.isBefore(until)) return result;
        ZoneId zone = ZoneId.systemDefault();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT e.id,calendar_id,title,starts,ends,all_day "
                + "FROM events e JOIN sources s ON s.id=e.calendar_id "
                + "WHERE enabled=1 AND reason='' AND " + OVERLAP, new CalendarWindow(from, until, zone).queryBounds())) {
            while (c.moveToNext()) {
                boolean allDay = c.getInt(5) != 0;
                long start = c.getLong(3), end = c.getLong(4);
                if (allDay) {
                    start = CalendarWindow.midnight(Instant.ofEpochMilli(start).atZone(ZoneOffset.UTC).toLocalDate(), zone);
                    end = CalendarWindow.midnight(Instant.ofEpochMilli(end).atZone(ZoneOffset.UTC).toLocalDate(), zone);
                }
                result.add(new Data.Entry(c.getString(0), c.getString(1), c.getString(2), start, end, allDay));
            }
        }
        result.sort(java.util.Comparator.comparingLong((Data.Entry e) -> e.start)
                .thenComparingLong(e -> e.end).thenComparing(e -> e.calendarId).thenComparing(e -> e.id));
        return result;
    }

    private void replaceWindow(CalendarWindow window, Map<String, Map<String, Data.Entry>> snapshots) {
        SQLiteDatabase db = getWritableDatabase();
        String[] range = window.queryBounds();
        for (Map.Entry<String, Map<String, Data.Entry>> snapshot : snapshots.entrySet()) {
            db.delete("events", "calendar_id=? AND " + OVERLAP,
                    new String[]{snapshot.getKey(), range[0], range[1], range[2], range[3]});
            for (Map.Entry<String, Data.Entry> row : snapshot.getValue().entrySet()) {
                Data.Entry event = row.getValue();
                if (event == null) {
                    db.delete("events", "calendar_id=? AND id=?", new String[]{snapshot.getKey(), row.getKey()});
                    continue;
                }
                ContentValues values = new ContentValues();
                values.put("calendar_id", event.calendarId);
                values.put("id", event.id);
                values.put("title", event.title);
                values.put("starts", event.start);
                values.put("ends", event.end);
                values.put("all_day", event.allDay);
                if (window.overlaps(event)) {
                    db.replaceOrThrow("events", null, values);
                } else {
                    db.update("events", values, "calendar_id=? AND id=?", new String[]{event.calendarId, event.id});
                }
            }
        }
        db.execSQL("INSERT OR REPLACE INTO sync_state(id,completed) VALUES(1,?)",
                new Object[]{System.currentTimeMillis()});
    }

    long lastSync() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT completed FROM sync_state WHERE id=1", null)) {
            return c.moveToFirst() ? c.getLong(0) : 0;
        }
    }

    private static void eraseRows(SQLiteDatabase db) {
        db.delete("sources", null, null);
        db.delete("sync_state", null, null);
    }

    void clear() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            eraseRows(db);
            db.execSQL("UPDATE cache_owner SET session='' WHERE id=1");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }
}
