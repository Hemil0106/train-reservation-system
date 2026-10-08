package com.rail.service;

import java.security.SecureRandom;

/**
 * Generates the PNR: the ten-digit number a passenger quotes.
 *
 * <p>The value is drawn from {@link SecureRandom}, not from a counter and not
 * from {@code Random}. Two separate reasons:
 *
 * <ul>
 *   <li><b>Unguessable.</b> A sequential PNR means passenger 41 can work out
 *       that 42 booked yesterday afternoon, and any future screen that trusts a
 *       typed PNR is then probing an enumerable range.</li>
 *   <li><b>Collision-resistant.</b> Ten digits is ten billion values, so two
 *       unrelated bookings landing on the same number is a birthday-bound
 *       event: negligible at a few hundred bookings, around half a percent at
 *       a hundred thousand. That is not good enough to *assume*, so the booking
 *       engine treats the database's primary key as the real guarantee and
 *       retries on the duplicate-key error. Randomness makes a collision
 *       unlikely; the unique index makes it impossible to keep.</li>
 * </ul>
 *
 * <p>Sequential primary keys also insert badly - every new row lands at the end
 * of the index rather than where it belongs - so the price of this choice is
 * paid knowingly, and is irrelevant at this project's scale.
 *
 * <p>Deliberately one class with one method. It is not a strategy: the booking
 * engine takes a {@code Supplier<Long>} instead, so a check can hand it a
 * generator that always collides and prove the retry path works.
 */
public final class PnrGenerator {

    /** PNRs are always exactly this many digits, which is what the UI promises. */
    public static final int DIGITS = 10;

    /** Smallest value with ten digits. Zero is never issued. */
    private static final long FIRST = 1_000_000_000L;

    /** How many ten-digit values exist: 9 x 10^9. */
    private static final long SPAN = 9_000_000_000L;

    private final SecureRandom random = new SecureRandom();

    /**
     * The next candidate PNR.
     *
     * <p>A <em>candidate</em>, not a PNR: it means nothing until the database
     * has accepted it inside a committed transaction. Nothing may display a
     * value from here before that point.
     */
    public long next() {
        // nextLong(bound) is uniform over the range, which Random.nextDouble
        // only approximates, and source level 17 already guarantees it.
        return FIRST + random.nextLong(SPAN);
    }
}