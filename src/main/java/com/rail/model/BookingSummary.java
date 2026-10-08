package com.rail.model;

import java.time.LocalDate;

/**
 * One row of a user's own booking history, as shown on the dashboard.
 *
 * <p>Every field is read from the database. When a user has never booked
 * anything the dashboard renders an empty state instead of placeholder rows.
 */
public record BookingSummary(
        long pnr,
        String trainNo,
        String trainName,
        LocalDate journeyDate,
        String fromStation,
        String toStation,
        String travelClass,
        String status,
        double totalFare) {

    /** The PNR in the 10-digit form a passenger would quote. See README 9.4. */
    public String pnrDisplay() {
        return String.format("%010d", pnr);
    }
}