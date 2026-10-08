package com.rail.model;

import java.time.LocalTime;

/**
 * One row of a train's timetable, read from {@code train_stops}.
 *
 * <p>{@code dayOffset} is the number of days after the departure date, which is
 * what makes an overnight journey expressible: a train leaving at 21:30 and
 * reaching its terminus at 08:00 the next morning has {@code dayOffset} 0 at the
 * origin and 1 at the terminus. Durations are computed from the two offsets
 * rather than from the clock times alone, so a journey that crosses midnight
 * does not come out negative.
 *
 * @param arrivalTime null only at the originating stop, per the schema CHECK
 * @param departureTime always present, per the schema
 */
public record StopInfo(
        int stopSeq,
        String stationCode,
        String stationName,
        LocalTime arrivalTime,
        LocalTime departureTime,
        int dayOffset,
        int distanceKm) {

    public StopInfo {
        if (stopSeq < 1) {
            throw new IllegalArgumentException("stopSeq must be 1-based");
        }
        if (stationCode == null || stationCode.isBlank()) {
            throw new IllegalArgumentException("stationCode must not be blank");
        }
        if (stationName == null || stationName.isBlank()) {
            throw new IllegalArgumentException("stationName must not be blank");
        }
        if (departureTime == null) {
            throw new IllegalArgumentException("departureTime is required");
        }
        if (dayOffset < 0) {
            throw new IllegalArgumentException("dayOffset must not be negative");
        }
        if (stopSeq > 1 && arrivalTime == null) {
            throw new IllegalArgumentException("only the origin may omit an arrival time");
        }
        if (distanceKm < 0) {
            throw new IllegalArgumentException("distanceKm must not be negative");
        }
    }

    public boolean isOrigin() {
        return stopSeq == 1;
    }

    /** True when this stop is reached on a later calendar day than departure. */
    public boolean arrivesNextDay() {
        return dayOffset > 0;
    }
}