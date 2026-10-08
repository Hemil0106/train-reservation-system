package com.rail.model;

import java.time.LocalDate;
import java.util.List;

/**
 * A booking that has been committed to the database, exactly as the traveller
 * is shown it.
 *
 * <p>The existence of this type is a promise: an instance can only be built
 * after the transaction committed, because {@code BookingService} creates it
 * from the rows it has just read back. Nothing renders a PNR from this class
 * before the database has agreed to it, which is what stops the application
 * showing a ticket number that does not exist.
 *
 * <p>It is a snapshot, not a live view. Reading it back after a cancellation
 * would produce a different object; this one keeps saying what was true at the
 * moment of booking, which is what a confirmation screen should show.
 *
 * @param pnr the ticket number, unique across the system
 * @param trainNo the service, e.g. {@code 12951}
 * @param trainName its name
 * @param journeyDate the date travelled, always the departure date
 * @param fromStationCode boarding station code
 * @param fromStationName boarding station name
 * @param toStationCode destination station code
 * @param toStationName destination station name
 * @param classCode travel class code, e.g. {@code 2A}
 * @param className travel class name
 * @param status the committed status
 * @param fare the breakdown and total that were charged
 * @param passengers one entry per passenger, each with the berth granted
 */
public record ConfirmedBooking(long pnr,
                               String trainNo,
                               String trainName,
                               LocalDate journeyDate,
                               String fromStationCode,
                               String fromStationName,
                               String toStationCode,
                               String toStationName,
                               String classCode,
                               String className,
                               BookingStatus status,
                               FareEstimate fare,
                               List<BerthAllocation> passengers) {

    public ConfirmedBooking {
        // Defensive copy: this is what the confirmation screen renders, and a
        // caller mutating the list afterwards would make the screen disagree
        // with what was committed.
        passengers = List.copyOf(passengers);
    }

    /** The PNR as a passenger would quote it. */
    public String pnrDisplay() {
        return String.format("%010d", pnr);
    }

    public int passengerCount() {
        return passengers.size();
    }

    /** How many passengers asked for a berth they did not get. */
    public int preferencesNotHonoured() {
        return (int) passengers.stream().filter(passenger -> !passenger.preferenceHonoured()).count();
    }
}