package com.photospace.sessions;

import com.photospace.shared.web.InvalidFieldsException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns the wall-clock start and end a photographer enters, in the workspace time zone, into UTC instants.
 *
 * <ul>
 *   <li>A local time that does not exist because clocks jump forward (a daylight-saving gap) is rejected
 *       rather than silently shifted.
 *   <li>A local time that happens twice because clocks fall back (an overlap) resolves to the earlier one.
 *   <li>The end, if set, needs a start and must be after it.
 * </ul>
 */
record SessionSchedule(Instant startsAt, Instant endsAt) {

    static final int MIN_YEAR = 2000;
    static final int MAX_YEAR = 2100;

    static SessionSchedule resolve(LocalDateTime startLocal, LocalDateTime endLocal, ZoneId zone) {
        Map<String, String> errors = new LinkedHashMap<>();
        Instant start = toInstant(startLocal, zone, "startLocal", errors);
        Instant end = toInstant(endLocal, zone, "endLocal", errors);
        if (endLocal != null && startLocal == null) {
            errors.putIfAbsent("startLocal", "is required when an end is set");
        } else if (start != null && end != null && !end.isAfter(start)) {
            errors.putIfAbsent("endLocal", "must be after the start");
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        return new SessionSchedule(start, end);
    }

    private static Instant toInstant(LocalDateTime local, ZoneId zone, String field, Map<String, String> errors) {
        if (local == null) {
            return null;
        }
        if (local.getYear() < MIN_YEAR || local.getYear() > MAX_YEAR) {
            errors.put(field, "must be between the years " + MIN_YEAR + " and " + MAX_YEAR);
            return null;
        }
        if (zone.getRules().getValidOffsets(local).isEmpty()) {
            errors.put(field, "does not exist in " + zone.getId() + " because the clocks change at that time");
            return null;
        }
        return ZonedDateTime.ofLocal(local, zone, null).toInstant();
    }
}
