package io.github.pageleaf.calendar;

/** Values shared between the SQLite cache and the three calendar views. */
public final class Data {
    private Data() { }

    public static final class Source {
        public final String id, name, reason;
        public final int color;
        public final boolean enabled, readable;

        public Source(String id, String name, int color, boolean enabled, boolean readable, String reason) {
            this.id = id;
            this.name = name;
            this.color = color;
            this.enabled = enabled;
            this.readable = readable;
            this.reason = reason;
        }
    }

    public static final class Entry {
        public final String id, calendarId, title;
        /** Epoch milliseconds; end is exclusive, including for all-day events. */
        public final long start, end;
        public final boolean allDay;

        public Entry(String id, String calendarId, String title, long start, long end, boolean allDay) {
            this.id = id;
            this.calendarId = calendarId;
            this.title = title;
            this.start = start;
            this.end = end;
            this.allDay = allDay;
        }
    }
}
