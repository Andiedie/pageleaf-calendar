package io.github.pageleaf.calendar;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Local civil dates and half-open intervals used by all three views. */
final class CalendarGeometry {
    enum Mode { MONTH, WEEK, DAY }

    static LocalDate monday(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    static LocalDate first(Mode mode, LocalDate date) {
        if (mode == Mode.DAY) return date;
        return monday(mode == Mode.MONTH ? date.withDayOfMonth(1) : date);
    }

    static LocalDate after(Mode mode, LocalDate date) {
        if (mode == Mode.DAY) return date.plusDays(1);
        if (mode == Mode.WEEK) return monday(date).plusWeeks(1);
        return monday(date.withDayOfMonth(date.lengthOfMonth())).plusWeeks(1);
    }

    static long midnight(LocalDate date, ZoneId zone) {
        return date.atStartOfDay(zone).toInstant().toEpochMilli();
    }

    static long segmentEnd(Data.Entry event, LocalDate day, ZoneId zone) {
        return Math.min(event.end, midnight(day.plusDays(1), zone));
    }

    static final Comparator<Data.Entry> AGENDA_ORDER = Comparator
            .comparing((Data.Entry e) -> !e.allDay)
            .thenComparingLong(e -> e.start)
            .thenComparing((Data.Entry e) -> e.end, Comparator.reverseOrder())
            .thenComparing(e -> e.title, Comparator.nullsLast(Comparator.naturalOrder()));

    static final class Slot {
        final Data.Entry event;
        final double start;
        final double end;
        int lane;
        int lanes;

        Slot(Data.Entry event, double start, double end) {
            this.event = event;
            this.start = start;
            this.end = end;
        }
    }

    static double minute(long instant, ZoneId zone) {
        LocalTime t = Instant.ofEpochMilli(instant).atZone(zone).toLocalTime();
        return t.toNanoOfDay() / 60_000_000_000.0;
    }

    static List<Slot> timedSlots(List<Data.Entry> events, LocalDate date, ZoneId zone) {
        long midnight = midnight(date, zone);
        long tomorrow = midnight(date.plusDays(1), zone);
        List<Slot> slots = new ArrayList<>();
        for (Data.Entry e : events) {
            if (e.allDay || e.start >= tomorrow || e.end <= midnight || e.end <= e.start) continue;
            double start = e.start <= midnight ? 0 : minute(e.start, zone);
            double end = e.end >= tomorrow ? 1440 : minute(e.end, zone);
            // A fall-back transition can put the end earlier on the wall clock.
            if (end <= start) end = Math.min(1440, start + (e.end - Math.max(e.start, midnight)) / 60_000.0);
            slots.add(new Slot(e, start, end));
        }
        slots.sort(Comparator.comparingDouble((Slot s) -> s.start)
                .thenComparing(Comparator.comparingDouble((Slot s) -> s.end).reversed()));
        assignLanes(slots);
        return slots;
    }

    /** Intersecting events share a cluster; touching events can reuse a lane. */
    private static void assignLanes(List<Slot> slots) {
        List<Double> ends = new ArrayList<>();
        int cluster = 0;
        double clusterEnd = -1;
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            if (slot.start >= clusterEnd) {
                for (int j = cluster; j < i; j++) slots.get(j).lanes = ends.size();
                cluster = i;
                ends.clear();
            }
            int lane = 0;
            while (lane < ends.size() && ends.get(lane) > slot.start) lane++;
            if (lane == ends.size()) ends.add(slot.end);
            else ends.set(lane, slot.end);
            slot.lane = lane;
            clusterEnd = Math.max(clusterEnd, slot.end);
        }
        for (int j = cluster; j < slots.size(); j++) slots.get(j).lanes = ends.size();
    }

    private CalendarGeometry() {}
}
