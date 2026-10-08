package com.rail.service;

import com.rail.db.BookingDAO;
import com.rail.db.CancellationDAO;
import com.rail.db.ConnectionSource;
import com.rail.db.DatabaseConnection;
import com.rail.model.BookingDetail;
import com.rail.model.BookingStatus;
import com.rail.model.User;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;

/**
 * Cancels a booking, or leaves it exactly as it was.
 *
 * <p>One transaction does all of it: read the booking under a lock, decide
 * whether it may be cancelled, release the berths, mark the booking cancelled,
 * append the cancellation to the ledger, commit. Any failure at any point rolls
 * the whole thing back, so there is no state in which the money has been refunded
 * but the berth is still sold, or the berth is free but the booking still says
 * confirmed.
 *
 * <h2>Why the lock is the whole design</h2>
 *
 * <p>{@link BookingDAO#lockStatusForUser} locks the booking's own row before the
 * status is read. Two cancellations of the same booking therefore run one after
 * the other, not side by side: the second one waits, then reads the
 * {@code CANCELLED} the first one wrote, and is refused. That is what makes a
 * duplicate refund impossible, and it is why there is no check-then-act window
 * anywhere in this class - the check is the locked read.
 *
 * <p>Only one booking is locked. Cancelling a booking never touches another
 * booking's reservations, so two travellers cancelling different berths of the
 * same train cannot block each other.
 *
 * <h2>What a cancellation does and does not do</h2>
 *
 * <ul>
 *   <li>Updates, never deletes. The booking, its passengers and its reservations
 *       stay exactly where they are so the ticket remains printable.</li>
 *   <li>The reservation rows move to {@code CANCELLED}, which is what frees the
 *       berth: availability counts {@code CONFIRMED} and {@code RAC} only. A
 *       reservation outside this booking is untouched.</li>
 *   <li>Refunds are this project's simulated amounts from {@link RefundPolicy},
 *       recorded as money on the cancellation row. No payment gateway is
 *       involved and no real money moves.</li>
 *   <li>No waitlist is promoted, because this build has no waitlist. If one is
 *       added, promotion belongs here, inside this transaction, or the freed berth
 *       will sit empty while somebody queued for it.</li>
 * </ul>
 */
public final class CancellationService {

    /** Recorded when the passenger did not say why. */
    public static final String DEFAULT_REASON = "Cancelled by passenger";

    private final ConnectionSource connections;

    public CancellationService() {
        this(DatabaseConnection::getConnection);
    }

    /** See {@link AuthService#AuthService(ConnectionSource)}. */
    public CancellationService(ConnectionSource connections) {
        this.connections = connections;
    }

    /**
     * Cancels the whole booking and returns it as it now stands.
     *
     * @param pnr the ticket to cancel
     * @param reason why, in the passenger's own words; blank means
     *     {@link #DEFAULT_REASON}
     * @return the ticket, re-read after the commit, so what the screen shows is
     *     what the database now says rather than what this method intended
     * @throws CancellationException with a message fit to show the passenger;
     *     the transaction has already been rolled back by the time it is thrown
     * @throws AccessDeniedException when nobody is signed in
     */
    public BookingDetail cancel(long pnr, String reason)
            throws CancellationException, AccessDeniedException {
        User user = SessionManager.get().requireUser();
        String why = reason == null || reason.isBlank() ? DEFAULT_REASON : reason.trim();
        LocalDate today = LocalDate.now();

        try (Connection connection = connections.open()) {
            connection.setAutoCommit(false);
            try {
                BookingDAO bookings = new BookingDAO(connection);

                // The lock. Everything below is decided from the row it leaves
                // locked, so no other cancellation of this booking can interleave.
                String lockedStatus = bookings.lockStatusForUser(pnr, user.userId());
                if (lockedStatus == null) {
                    // Deliberately the same refusal as a PNR that does not exist.
                    // Saying "not yours" would confirm the PNR is real to anyone
                    // who guessed it.
                    throw new CancellationException(
                            "No booking found with that PNR for your account.");
                }

                // Safe to read now: the booking cannot change under the lock, and
                // nothing has been written yet.
                BookingDetail ticket = bookings.ticketForUser(pnr, user.userId())
                        .orElseThrow(() -> new CancellationException(
                                "No booking found with that PNR for your account."));

                requireEligible(ticket, lockedStatus);

                RefundPolicy.Outcome outcome = RefundPolicy.refundFor(ticket.totalFare(),
                        ticket.passengerCount(), ticket.journeyDate(), today);

                // Berths first: if this fails the berths are still held, which is
                // the safe direction to fail in - never free a berth that is
                // still sold.
                bookings.cancelReservations(pnr);
                bookings.markCancelled(pnr);
                new CancellationDAO(connection).insert(pnr, user.userId(), why, outcome);

                connection.commit();

                // Re-read after the commit so the cancellation row the caller sees
                // is the one that was actually written.
                return bookings.ticketForUser(pnr, user.userId()).orElse(ticket);
            } catch (SQLException failure) {
                safeRollback(connection);
                throw new CancellationException(
                        "The booking could not be cancelled, so nothing was changed. "
                                + "Please try again.", failure);
            } catch (CancellationException | RuntimeException failure) {
                safeRollback(connection);
                throw failure;
            }
        } catch (SQLException failure) {
            throw new CancellationException(
                    "Could not reach the booking database. Nothing was changed; please try again.",
                    failure);
        }
    }

    /**
     * What cancelling this booking would return, without cancelling it.
     *
     * <p>For the confirmation dialog. Separate from {@link #cancel} because the
     * quote and the cancellation must not share code: a dialog that worked out
     * the refund by calling the thing it is about to do would be showing a
     * passenger a number produced by a cancellation they have not agreed to yet.
     *
     * <p>The booking is read without a lock, so this is a quote, not a promise -
     * {@link #cancel} recalculates inside its transaction and the confirmed
     * figures are the ones on the cancellation row.
     *
     * @throws CancellationException when the booking is not there, or not
     *     eligible - the dialog shows the same message the button would
     */
    public Quote quote(long pnr) throws CancellationException, AccessDeniedException {
        User user = SessionManager.get().requireUser();
        try (Connection connection = connections.open()) {
            BookingDetail ticket = new BookingDAO(connection)
                    .ticketForUser(pnr, user.userId())
                    .orElseThrow(() -> new CancellationException(
                            "No booking found with that PNR for your account."));
            requireEligible(ticket, ticket.status());
            LocalDate today = LocalDate.now();
            RefundPolicy.Outcome outcome = RefundPolicy.refundFor(ticket.totalFare(),
                    ticket.passengerCount(), ticket.journeyDate(), today);
            return new Quote(ticket, outcome,
                    RefundPolicy.policySentence(daysUntil(ticket.journeyDate(), today)));
        } catch (SQLException unreachable) {
            throw new CancellationException(
                    "Could not reach the booking database. Please try again.", unreachable);
        }
    }

    /**
     * Whether this booking may be cancelled at all, and why not if it may not.
     *
     * <p>Two things make a booking ineligible: it is not confirmed, or its
     * journey has departed. A cancelled <em>service</em> is deliberately still
     * cancellable - a train that will not run is the clearest case of a passenger
     * wanting their money back.
     *
     * @param lockedStatus the status as read under the lock, which is the one
     *     that decides; the ticket's own copy may be older than the lock
     */
    private static void requireEligible(BookingDetail ticket, String lockedStatus)
            throws CancellationException {
        if (BookingStatus.fromDb(lockedStatus) != BookingStatus.CONFIRMED) {
            throw new CancellationException(
                    "This booking is not confirmed, so there is nothing to cancel.");
        }
        String blocker = ticket.cancellationBlocker();
        if (blocker != null) {
            throw new CancellationException(blocker);
        }
    }

    private static long daysUntil(LocalDate journeyDate, LocalDate today) {
        return java.time.temporal.ChronoUnit.DAYS.between(today, journeyDate);
    }

    /** Rolls back, and shrugs if that fails, for the reason given in {@link BookingService}. */
    private static void safeRollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // Reported through the original failure, not this one.
        }
    }

    /**
     * What cancelling would cost the passenger and return to them.
     *
     * @param ticket the booking as it stands
     * @param outcome the simulated money
     * @param policy the rule, in words, at the moment of quoting
     */
    public record Quote(BookingDetail ticket, RefundPolicy.Outcome outcome, String policy) {
    }

    /**
     * Raised for every refusal: not found, not eligible, or a database failure.
     *
     * <p>One type for all three because the caller shows one message either way,
     * and because a passenger needs to know the booking did not change, not which
     * of the three reasons applied. The {@code cause} keeps the real failure for
     * the log without putting a driver message on screen.
     */
    public static final class CancellationException extends Exception {
        private static final long serialVersionUID = 1L;

        public CancellationException(String message) {
            super(message);
        }

        public CancellationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}