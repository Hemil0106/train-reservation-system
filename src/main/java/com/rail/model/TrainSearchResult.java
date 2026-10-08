package com.rail.model;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * One train that can carry the party over the requested segment.
 *
 * <p>The stop sequence numbers travel with the result on purpose: availability
 * is segment-scoped, so anything that later seats this party needs to know
 * exactly which slice of the route was searched.
 *
 * <p>All times are as printed in the timetable. {@code arrivalDayOffset} is
 * what distinguishes "arrives the same day" from "arrives the next day".
 */
public record TrainSearchResult(
        String trainNo,
        String trainName,
        Station fromStation,
        Station toStation,
        int fromSeq,
        int toSeq,
        LocalTime departureTime,
        LocalTime arrivalTime,
        int arrivalDayOffset,
        int durationMinutes,
        int distanceKm,
        List<ClassAvailability> classes) {

    public TrainSearchResult {
        if (trainNo == null || trainNo.isBlank()) {
            throw new IllegalArgumentException("trainNo must not be blank");
        }
        if (fromStation == null || toStation == null) {
            throw new IllegalArgumentException("stations must not be null");
        }
        if (departureTime == null || arrivalTime == null) {
            throw new IllegalArgumentException("times must not be null");
        }
        if (fromSeq < 1 || toSeq <= fromSeq) {
            throw new IllegalArgumentException("the destination must follow the origin");
        }
        if (durationMinutes < 0) {
            throw new IllegalArgumentException("durationMinutes must not be negative");
        }
        if (classes == null) {
            throw new IllegalArgumentException("classes must not be null");
        }
        classes = List.copyOf(classes);
    }

    /** True when this train runs through the night into the following day. */
    public boolean crossesMidnight() {
        return arrivalDayOffset > 0;
    }

    public Optional<ClassAvailability> availabilityFor(String classCode) {
        return classes.stream().filter(c -> c.classCode().equals(classCode)).findFirst();
    }

    /** True when every requested passenger can travel in the given class. */
    public boolean seats(String classCode, int passengers) {
        return availabilityFor(classCode).map(a -> a.seats(passengers)).orElse(false);
    }
}