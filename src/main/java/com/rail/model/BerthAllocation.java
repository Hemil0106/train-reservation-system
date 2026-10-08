package com.rail.model;

/**
 * One passenger's berth, as actually allocated, or the reason it could not be.
 *
 * <p>This is the record that ends the ambiguity the whole booking engine exists
 * to resolve. Before a booking there is only a <em>request</em>
 * ({@link PassengerDraft#berthPreference()}); after it there is a specific
 * coach, a specific berth number, and a specific position. Both are kept, and
 * {@link #preferenceHonoured()} compares them, because the honest thing to show
 * a passenger who asked for a lower berth and got a middle one is both numbers
 * side by side - not a silent substitution, and not a promise that was quietly
 * broken.
 *
 * <p>The two preferences land in two different columns on purpose:
 * {@code passengers.berth_preference} keeps what was <em>asked for</em>, while
 * {@code seat_reservations.position} records what was <em>given</em>. Collapsing
 * them would destroy the evidence that the fallback policy was used.
 *
 * @param passengerName who this berth is for, as typed on the form
 * @param age the passenger's age, carried through for the confirmation screen
 * @param gender the passenger's gender
 * @param requestedPreference what the passenger asked for
 * @param berthId the allocated berth, for the {@code seat_reservations} row
 * @param coachId the berth's coach, for the {@code seat_reservations} row
 * @param coachNumber the coach as a passenger would name it, such as {@code AC1}
 * @param berthNumber the berth's number within that coach
 * @param berthType {@code SEAT}, {@code SLEEPER} or {@code AC_BERTH}
 * @param grantedPosition the position actually allocated
 */
public record BerthAllocation(String passengerName,
                              int age,
                              Gender gender,
                              BerthPreference requestedPreference,
                              long berthId,
                              long coachId,
                              String coachNumber,
                              int berthNumber,
                              String berthType,
                              BerthPreference grantedPosition) {

    /**
     * Whether the request was met exactly.
     *
     * <p>{@code NO_PREFERENCE} counts as honoured: there was nothing to miss.
     * The two side variants count as honoured when either was granted, because
     * the schema stores both as one {@code SIDE} value and cannot tell them
     * apart - see {@link BerthPreference#dbValue()}.
     */
    public boolean preferenceHonoured() {
        if (requestedPreference == BerthPreference.NO_PREFERENCE) {
            return true;
        }
        return sameBerthType(requestedPreference, grantedPosition);
    }

    /**
     * {@code SIDE_LOWER} and {@code SIDE_UPPER} both collapse to {@code SIDE}
     * in the database, so treat them as equivalent when comparing.
     */
    private static boolean sameBerthType(BerthPreference requested, BerthPreference granted) {
        return requested.dbValue().equals(granted.dbValue());
    }

    /** How the berth is written on a ticket, such as {@code AC1 / berth 12}. */
    public String seatLabel() {
        return coachNumber + " / berth " + berthNumber;
    }
}