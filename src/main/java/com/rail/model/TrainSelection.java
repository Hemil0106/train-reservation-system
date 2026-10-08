package com.rail.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The train and class the traveller committed to on the search screen.
 *
 * <p>This is the hand-off point between Milestone 4 and the booking stage: it
 * pins down the train, the class, the exact station segment (by stop sequence,
 * not just station name) and the party size, so whoever seats the party next
 * cannot accidentally re-interpret the search.
 *
 * <p>The fare carried here is the per-passenger rate for the chosen class and
 * segment, so the passenger form can price a party as its rows are added and a
 * per-head figure never drifts from what search showed.
 */
public record TrainSelection(
        String trainNo,
        String trainName,
        String classCode,
        String className,
        Station fromStation,
        Station toStation,
        int fromSeq,
        int toSeq,
        int segmentKm,
        LocalDate journeyDate,
        int passengers,
        BigDecimal farePerPassenger,
        int berthsAvailable) {

    public TrainSelection {
        if (trainNo == null || trainNo.isBlank()) {
            throw new IllegalArgumentException("trainNo must not be blank");
        }
        if (classCode == null || classCode.isBlank()) {
            throw new IllegalArgumentException("classCode must not be blank");
        }
        if (fromStation == null || toStation == null) {
            throw new IllegalArgumentException("stations must not be null");
        }
        if (journeyDate == null) {
            throw new IllegalArgumentException("journeyDate is required");
        }
        if (fromSeq < 1 || toSeq <= fromSeq) {
            throw new IllegalArgumentException("the destination must follow the origin");
        }
        if (segmentKm < 1) {
            throw new IllegalArgumentException("a searched segment must be at least 1 km");
        }
        if (passengers < 1) {
            throw new IllegalArgumentException("passengers must be at least one");
        }
        if (berthsAvailable < passengers) {
            throw new IllegalArgumentException(
                    "cannot select " + passengers + " passengers when only "
                            + berthsAvailable + " berths are free");
        }
    }

    public TrainSelection(TrainSearchResult train, ClassAvailability chosen,
                          LocalDate journeyDate, int passengers) {
        this(train.trainNo(), train.trainName(), chosen.classCode(), chosen.className(),
             train.fromStation(), train.toStation(), train.fromSeq(), train.toSeq(),
             train.distanceKm(), journeyDate, passengers, chosen.fare(),
             chosen.availableBerths());
    }

    /** The whole party's fare, which is what the traveller will be charged. */
    public BigDecimal totalFare() {
        return farePerPassenger.multiply(BigDecimal.valueOf(passengers));
    }

    /** One line describing the choice, for confirmation messages. */
    public String summary() {
        return trainNo + " " + trainName + ", " + className + ", "
                + fromStation.code() + " to " + toStation.code()
                + " on " + journeyDate + " for " + passengers
                + (passengers == 1 ? " passenger" : " passengers");
    }
}