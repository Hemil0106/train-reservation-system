package com.rail.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A booking as a traveller sees it on a ticket: who, what, when, which berths,
 * what it cost, and what has happened to it since.
 *
 * <p>This is the read model behind both the PNR lookup and My Bookings, so the
 * two screens cannot disagree about the same booking. Every field comes from the
 * database. There is no "expected status" here: a cancelled booking is
 * reported as cancelled because that is what the row says.
 *
 * <p>Assembled from three reads - the booking, its passengers, and their
 * reservations - and it is only ever built for the account that owns it. The
 * {@code user_id} is part of the SQL, not a check afterwards, so another
 * traveller's PNR is not found rather than found and hidden.
 *
 * @param pnr the ticket number
 * @param userId the owning account
 * @param trainNo the service
 * @param trainName its name
 * @param journeyDate the date travelled
 * @param fromStationCode boarding station
 * @param fromStationName boarding station name
 * @param toStationCode destination station
 * @param toStationName destination station name
 * @param classCode travel class
 * @param className travel class name
 * @param status the status as stored, never inferred
 * @param bookingStatus that status, parsed; a stored value this build does not
 *     know is a bug rather than something to quietly render as confirmed
 * @param totalFare what the booking cost in total
 * @param passengers one entry per passenger, each with their berth
 * @param cancellation what was cancelled and refunded, or {@code null} while
 *     the booking stands
 */
public record BookingDetail(long pnr,
                            long userId,
                            String trainNo,
                            String trainName,
                            LocalDate journeyDate,
                            String fromStationCode,
                            String fromStationName,
                            String toStationCode,
                            String toStationName,
                            String classCode,
                            String className,
                            String status,
                            BookingStatus bookingStatus,
                            BigDecimal totalFare,
                            List<Passenger> passengers,
                            Cancellation cancellation) {

    public BookingDetail {
        passengers = List.copyOf(passengers);
    }

    /** The PNR in the 10-digit form a passenger would quote. */
    public String pnrDisplay() {
        return String.format("%010d", pnr);
    }

    /** {@code CSTM → PUNE}, the way a ticket prints the route. */
    public String routeLabel() {
        return fromStationCode + " → " + toStationCode;
    }

    public int passengerCount() {
        return passengers.size();
    }

    /**
     * Whether this booking can still be called off.
     *
     * <p>A presentation convenience, and deliberately the same answer
     * {@link com.rail.service.CancellationService} gives: only a confirmed
     * booking whose journey has not departed is eligible. The service checks
     * again inside its transaction, because this cannot know what another
     * traveller is doing to the same booking right now.
     */
    public boolean cancellable() {
        return bookingStatus == BookingStatus.CONFIRMED
                && cancellation == null
                && !journeyDate.isBefore(LocalDate.now());
    }

    /**
     * Why it cannot be cancelled, in words a traveller can act on.
     *
     * <p>{@code null} when it can. Shown on the screen so a greyed-out button
     * always comes with a reason.
     */
    public String cancellationBlocker() {
        if (bookingStatus == BookingStatus.CANCELLED) {
            return "This booking was already cancelled.";
        }
        if (cancellation != null) {
            return "This booking was already cancelled.";
        }
        if (journeyDate.isBefore(LocalDate.now())) {
            return "This journey has already departed, so it can no longer be cancelled "
                    + "online. Please see station staff.";
        }
        return null;
    }

    /** What a cancelled booking cost to cancel, or {@code null} while it stands. */
    public record Cancellation(long cancellationId,
                               LocalDateTime cancelledAt,
                               String reason,
                               boolean refundable,
                               BigDecimal refundAmount,
                               BigDecimal cancellationCharges,
                               String refundStatus) {
    }

    /**
     * One passenger and the berth they hold.
     *
     * @param reservationStatus this passenger's own status, read from their
     *     reservation rather than copied from the booking: it is the row that
     *     holds the berth, so it is the row that has to be right
     */
    public record Passenger(long passengerId,
                            String fullName,
                            int age,
                            String gender,
                            String requestedPreference,
                            boolean primary,
                            long berthId,
                            String coachNumber,
                            int berthNumber,
                            String berthType,
                            String position,
                            BigDecimal amount,
                            String reservationStatus) {

        /** {@code AC2 / berth 14}, the way the berth is quoted on a ticket. */
        public String seatLabel() {
            return coachNumber + " / berth " + berthNumber;
        }
    }
}