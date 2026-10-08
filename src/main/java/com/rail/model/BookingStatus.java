package com.rail.model;

import java.util.Locale;

/**
 * The statuses this application actually writes.
 *
 * <p>{@code bookings.status} is an enum column that also allows
 * {@code PARTIALLY_CANCELLED} and {@code WAITLISTED}, and
 * {@code seat_reservations.status} additionally allows {@code RAC}. None of
 * those three is used here, and the distinction is deliberate:
 *
 * <ul>
 *   <li><b>CANCELLED</b> - a whole booking called off. Written by the
 *       cancellation milestone; the value exists now so the state is
 *       representable, and no screen offers it yet.</li>
 *   <li><b>PARTIALLY_CANCELLED</b> - some passengers called off, others
 *       travelling. This needs per-passenger cancellation, which is the same
 *       milestone as {@code CANCELLED}. Inventing it now would mean rendering a
 *       state the application cannot reach.</li>
 *   <li><b>WAITLISTED</b> - reserved against no seat, with a queue position.
 *       There is no waitlist table and no queue ordering, so a
 *       {@code WAITLISTED} row could only ever be a fiction. This project
 *       <b>rejects</b> a booking it cannot fill instead.</li>
 *   <li><b>RAC</b> - a confirmed seat plus a running allowance. There is no
 *       RAC quota accounting and no cancellation-with-rerouting logic, so this
 *       application never claims RAC.</li>
 * </ul>
 *
 * <p>A passenger has no status column of their own. Their status is their
 * reservation's status: a {@link #CONFIRMED} booking gives every passenger in
 * it a confirmed berth, and cancelling the booking cancels them all. Storing a
 * second copy of a status that is always derived from the reservation would
 * create a second thing to keep in step with the first.
 */
public enum BookingStatus {

    /** Committed, with a specific coach and berth for every passenger. */
    CONFIRMED,

    /** Called off. No berth is held. */
    CANCELLED;

    /** The exact text stored in the database. */
    public String dbValue() {
        return name();
    }

    /**
     * Reads a status written by this application.
     *
     * @throws IllegalArgumentException on anything else, so an unrecognised
     *     value surfaces as a bug instead of being quietly rendered as
     *     {@code CONFIRMED}. Silently defaulting is how a waitlisted or
     *     part-cancelled booking ends up shown to a passenger as confirmed.
     */
    public static BookingStatus fromDb(String stored) {
        if (stored == null) {
            throw new IllegalArgumentException("booking status was null");
        }
        String wanted = stored.trim().toUpperCase(Locale.ROOT);
        for (BookingStatus candidate : values()) {
            if (candidate.name().equals(wanted)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("unsupported booking status: " + stored);
    }
}