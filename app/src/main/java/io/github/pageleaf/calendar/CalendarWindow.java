package io.github.pageleaf.calendar;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Half-open display window for local timed events and zone-independent all-day dates. */
final class CalendarWindow {
    final long localStart, localEnd, utcStart, utcEnd;
    final long firstSecond, lastSecond;

    CalendarWindow(LocalDate from, LocalDate until, ZoneId zone) {
        localStart = midnight(from, zone);
        localEnd = midnight(until, zone);
        utcStart = midnight(from, ZoneOffset.UTC);
        utcEnd = midnight(until, ZoneOffset.UTC);
        // Include the exact boundaries for APIs with an open interval, then filter locally.
        firstSecond = Math.min(localStart, utcStart) / 1000 - 1;
        lastSecond = Math.max(localEnd, utcEnd) / 1000 + 1;
    }

    static long midnight(LocalDate date, ZoneId zone) {
        return date.atStartOfDay(zone).toInstant().toEpochMilli();
    }

    boolean overlaps(Data.Entry entry) {
        return entry.allDay ? entry.start < utcEnd && entry.end > utcStart
                : entry.start < localEnd && entry.end > localStart;
    }

    String[] queryBounds() {
        return new String[]{String.valueOf(localEnd), String.valueOf(localStart),
                String.valueOf(utcEnd), String.valueOf(utcStart)};
    }
}
