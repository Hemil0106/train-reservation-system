package com.rail.model;

import java.math.BigDecimal;

/**
 * Fare and seat availability for one travel class on one requested segment.
 *
 * <p><strong>These figures are a simulation.</strong> Availability is computed
 * from this project's own {@code berths} and {@code seat_reservations} tables
 * for the selected train, journey date, class and station segment. It is not
 * live IRCTC data and must never be presented as such.
 *
 * @param fare per passenger, for the requested segment only
 * @param availableBerths berths free for the whole requested segment
 * @param totalBerths berths attached to the class for this journey
 */
public record ClassAvailability(
        String classCode,
        String className,
        BigDecimal fare,
        int availableBerths,
        int totalBerths) {

    public ClassAvailability {
        if (classCode == null || classCode.isBlank()) {
            throw new IllegalArgumentException("classCode must not be blank");
        }
        if (fare == null) {
            throw new IllegalArgumentException("fare must not be null");
        }
        if (availableBerths < 0 || totalBerths < 0) {
            throw new IllegalArgumentException("berth counts must not be negative");
        }
        if (availableBerths > totalBerths) {
            throw new IllegalArgumentException(
                    "availableBerths " + availableBerths + " exceeds totalBerths " + totalBerths);
        }
    }

    /** The whole party can be seated in this class on this segment. */
    public boolean seats(int passengers) {
        return availableBerths >= passengers;
    }

    /**
     * Availability phrased for a specific party size, so the UI never has to
     * imply that a class is "available" when it cannot seat everyone.
     */
    public String statusFor(int passengers) {
        if (availableBerths == 0) {
            return "No seats on this segment";
        }
        if (!seats(passengers)) {
            return "Only " + availableBerths + " left for " + passengers + " passengers";
        }
        return availableBerths + " of " + totalBerths + " berths free";
    }
}