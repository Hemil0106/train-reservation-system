package com.rail.service;

import com.rail.db.AvailabilityDAO;
import com.rail.db.AvailabilityDAO.CandidateBerth;
import com.rail.db.BookingDAO;
import com.rail.db.ConnectionSource;
import com.rail.db.DatabaseConnection;
import com.rail.db.TrainDAO;
import com.rail.model.BerthAllocation;
import com.rail.model.BookingDraft;
import com.rail.model.BookingStatus;
import com.rail.model.ClassProfile;
import com.rail.model.ConfirmedBooking;
import com.rail.model.FareEstimate;
import com.rail.model.PassengerDraft;
import com.rail.model.TrainSelection;
import com.rail.model.User;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;

/**
 * Turns a validated draft into a committed booking, or into nothing at all.
 *
 * <p>This is the only place in the application where a seat becomes sold. It
 * either commits every row or leaves the database exactly as it found it, and
 * the {@link ConfirmedBooking} it returns is built from the draft that was
 * written, so a passenger is never shown a PNR the database has not accepted.
 *
 * <h2>What happens, in order</h2>
 *
 * <ol>
 *   <li>The session is checked, so a booking cannot be made by nobody.</li>
 *   <li>The draft is checked over again: right number of passengers, contact
 *       details present, journey date still in the future, and every passenger
 *       still valid against the class <em>as the database describes it now</em>.</li>
 *   <li>The journey is re-read and must still exist and still be running. The
 *       passenger form priced the party minutes ago; a fare rule or a timetable
 *       could have changed since, so the fare is read again too and that is the
 *       figure charged.</li>
 *   <li>A transaction starts. Every candidate berth for the class is locked
 *       {@code FOR UPDATE}, so a second traveller attempting the same booking
 *       waits here instead of being told the same berth is free.</li>
 *   <li>Berths are allocated - preferences first, then the documented fallback.</li>
 *   <li>PNR, booking, passenger rows and reservation rows are inserted.</li>
 *   <li>Commit. Only now does a PNR exist.</li>
 * </ol>
 *
 * <h2>Why the reference-data reads sit outside the transaction</h2>
 *
 * <p>Fare rules, the journey row and the class layout are re-read immediately
 * before the transaction opens, on their own connections. They are reference
 * data: nothing this method writes can invalidate them, and a fare rule edited
 * microseconds later cannot un-sell a seat. Only the inventory - the berths, and
 * the rows written against them - needs atomicity, and those are all inside.
 * Reading them through the same transaction would mean reworking every read
 * method in {@link TrainDAO} to accept a caller's connection, for no gain in
 * correctness.
 *
 * <h2>Failure is never partial</h2>
 *
 * <p>Any failure at all - no berths, a duplicate PNR, a dropped connection, a
 * constraint - rolls the transaction back and throws {@link BookingException}
 * carrying a message fit for a passenger. No {@code SQLException} reaches the
 * user: a duplicate-key error would otherwise reveal the table and constraint
 * name to anyone who triggered one.
 */
public final class BookingService {

    /** How many PNRs to try before giving up. One collision is already unlikely. */
    private static final int PNR_ATTEMPTS = 3;

    /** MySQL's error code for a duplicate unique key. */
    private static final int DUPLICATE_KEY = 1062;

    private final ConnectionSource connections;
    private final Supplier<Long> pnrGenerator;
    private final BerthAllocationService allocator = new BerthAllocationService();
    private final FareService fares;
    private final BookingPreparationService preparation;

    public BookingService() {
        this(DatabaseConnection::getConnection, new PnrGenerator()::next);
    }

    /** See {@link AuthService#AuthService(ConnectionSource)}. */
    public BookingService(ConnectionSource connections, Supplier<Long> pnrGenerator) {
        this.connections = connections;
        this.pnrGenerator = pnrGenerator;
        this.fares = new FareService(new TrainDAO(connections));
        this.preparation = new BookingPreparationService(connections);
    }

    /**
     * Books the draft, or fails leaving nothing behind.
     *
     * @return the committed booking, safe to render
     * @throws BookingException  with a message fit to show the passenger; the
     *     transaction has already been rolled back by the time it is thrown
     * @throws AccessDeniedException when nobody is signed in
     * @throws ValidationException when the draft is no longer valid
     */
    public ConfirmedBooking confirm(BookingDraft draft)
            throws BookingException, AccessDeniedException, ValidationException {
        User user = SessionManager.get().requireUser();

        // Outside the transaction: anything that can be refused before taking a
        // lock should be, so a bad draft never blocks another traveller.
        requireBookable(draft);
        TrainSelection selection = draft.selection();

        FareEstimate fare;
        try {
            ClassProfile profile = preparation.profileOf(selection.classCode());
            preparation.revalidatePassengers(draft, profile);
            fare = fares.quoteFor(selection);
            requireRunningJourney(selection);
        } catch (SQLException unreachable) {
            // The only way out of here is a database that will not answer. No
            // lock has been taken and nothing has been written, so this is a
            // plain "we could not reach the booking system" and never a SQL
            // error on screen.
            throw new BookingException(
                    "Could not reach the booking database. Nothing was reserved; please try again.",
                    unreachable);
        }

        Exception lastCollision = null;
        for (int attempt = 1; attempt <= PNR_ATTEMPTS; attempt++) {
            long pnr = pnrGenerator.get();
            try {
                return commit(draft, user, pnr, fare);
            } catch (DuplicatePnrException collision) {
                // The generator's dice were unlucky. Try again with a new
                // number rather than failing a booking that has not started.
                lastCollision = collision;
            }
        }
        throw new BookingException(
                "Could not issue a unique booking number. Please try again.", lastCollision);
    }

    /**
     * Everything cheap to check, checked before a single row is locked.
     *
     * <p>Separate from {@link #commit} on purpose: these are refusals the
     * passenger can act on, and acting on one should not have held up anybody
     * booking the same train.
     */
    private void requireBookable(BookingDraft draft) throws ValidationException {
        if (draft == null || draft.selection() == null) {
            throw new ValidationException("selection", "Choose a train before booking.");
        }
        TrainSelection selection = draft.selection();

        if (selection.journeyDate().isBefore(LocalDate.now())) {
            throw new ValidationException("journeyDate",
                    "That journey date has passed. Search again for a current date.");
        }
        if (draft.passengerCount() != selection.passengers()) {
            throw new ValidationException("passengers",
                    "This booking is for " + selection.passengers() + " passenger"
                            + (selection.passengers() == 1 ? "" : "s")
                            + ", but " + draft.passengerCount() + " have been entered.");
        }
        if (draft.contact() == null) {
            throw new ValidationException("contact",
                    "Enter a contact mobile number before booking.");
        }
    }

    /**
     * The journey must exist and still be running.
     *
     * <p>Search creates the journey row, so a missing one means the search result
     * is stale or the journey has been removed since - either way the traveller
     * should search again rather than have a booking invented for them.
     *
     * <p>Deliberately does not create the journey. Only search may do that.
     */
    private void requireRunningJourney(TrainSelection selection)
            throws BookingException, SQLException {
        String sql = "SELECT service_status FROM journeys WHERE train_no = ? AND journey_date = ?";
        try (Connection connection = connections.open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, selection.trainNo());
            statement.setDate(2, java.sql.Date.valueOf(selection.journeyDate()));
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    throw new BookingException(
                            "That journey is no longer available. Please search again.");
                }
                String status = results.getString(1);
                if (!"SCHEDULED".equals(status)) {
                    throw new BookingException(
                            "Train " + selection.trainNo() + " is not running on "
                                    + selection.journeyDate() + ".");
                }
            }
        }
    }

    /**
     * The transaction: lock, allocate, insert, commit.
     *
     * <p>Also re-reads the journey id inside the transaction, because the booking
     * row has to point at a journey and reading it again is how the value used is
     * known to be the one that was validated. The read is a primary-key lookup on
     * an existing row, so it neither locks anything nor changes.
     */
    private ConfirmedBooking commit(BookingDraft draft, User user, long pnr, FareEstimate fare)
            throws BookingException, DuplicatePnrException {
        TrainSelection selection = draft.selection();

        try (Connection connection = connections.open()) {
            connection.setAutoCommit(false);
            try {
                long journeyId = journeyIdIn(connection, selection);

                // Everything below this line either takes a lock or writes.
                List<CandidateBerth> candidates = new AvailabilityDAO(connection)
                        .lockCandidateBerths(journeyId, selection.classCode(),
                                selection.fromSeq(), selection.toSeq());

                List<BerthAllocation> berths = allocator.allocate(draft.passengers(), candidates);

                BookingDAO bookings = new BookingDAO(connection);
                // Inserts the booking row first, and on its own, so that the one
                // duplicate key this statement can hit - the PNR primary key -
                // is identified by where it happened rather than by matching the
                // server's message text. See isDuplicateKey.
                try {
                    bookings.insertBooking(pnr, journeyId, selection, fare,
                            user.fullName(), draft.contact(), user.userId());
                } catch (SQLException failure) {
                    safeRollback(connection);
                    if (isDuplicateKey(failure)) {
                        throw new DuplicatePnrException(failure);
                    }
                    throw translate(failure);
                }

                List<PassengerDraft> passengers = draft.passengers();
                for (int index = 0; index < passengers.size(); index++) {
                    long passengerId = bookings.insertPassenger(pnr, passengers.get(index), index == 0);
                    bookings.insertSeatReservation(pnr, passengerId, journeyId, berths.get(index),
                            selection.classCode(), selection.fromSeq(), selection.toSeq(),
                            selection.fromStation().code(), selection.toStation().code(),
                            fares.perPassengerAmount(fare));
                }

                connection.commit();
                return describe(pnr, selection, fare, berths);
            } catch (SQLException failure) {
                safeRollback(connection);
                throw translate(failure);
            } catch (BookingException | RuntimeException failure) {
                safeRollback(connection);
                throw failure;
            }
        } catch (SQLException failure) {
            // Opening the connection or committing failed. Nothing is known to
            // have been written, so say so rather than guessing.
            throw new BookingException(
                    "Could not reach the booking database. Nothing was reserved; please try again.",
                    failure);
        }
    }

    /** The validated journey's id, read back inside the transaction. */
    private long journeyIdIn(Connection connection, TrainSelection selection) throws SQLException {
        String sql = "SELECT journey_id FROM journeys WHERE train_no = ? AND journey_date = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, selection.trainNo());
            statement.setDate(2, java.sql.Date.valueOf(selection.journeyDate()));
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    throw new SQLException("journey disappeared between validation and booking");
                }
                return results.getLong("journey_id");
            }
        }
    }

    /**
     * Builds what the traveller is shown, from the values just written.
     *
     * <p>Called only after the commit succeeded. Reading the rows back from the
     * database instead would prove less: it would compare what this method
     * intended against what this method wrote. The self-check queries the tables
     * independently for that.
     */
    private ConfirmedBooking describe(long pnr, TrainSelection selection,
                                     FareEstimate fare, List<BerthAllocation> berths) {
        return new ConfirmedBooking(
                pnr,
                selection.trainNo(),
                selection.trainName(),
                selection.journeyDate(),
                selection.fromStation().code(),
                selection.fromStation().name(),
                selection.toStation().code(),
                selection.toStation().name(),
                selection.classCode(),
                selection.className(),
                BookingStatus.CONFIRMED,
                fare,
                berths);
    }

    /**
     * Rolls back, and shrugs if that fails.
     *
     * <p>A failure here is worth knowing about but must not replace the real
     * error: the caller needs the reason the booking failed, not the reason the
     * cleanup did. Closing the connection discards the transaction anyway, so the
     * worst case is a connection returned to the pool still holding work that will
     * be rolled back on return.
     */
    private static void safeRollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // Reported through the original failure, not this one.
        }
    }

    /**
     * Turns a driver failure into something a passenger can read.
     *
     * <p>Reached only for the inserts <em>after</em> the booking row, so there is
     * nothing left to retry: a failure here is reported, not papered over.
     */
    private static BookingException translate(SQLException failure) {
        return new BookingException(
                "The booking could not be completed, so nothing was reserved. Please try again.",
                failure);
    }

    /**
     * Whether a failure was a unique-key collision.
     *
     * <p>Identified by the vendor error code alone, and only ever asked about the
     * booking-row insert - whose primary key is {@code pnr} and whose only other
     * constraints are foreign keys. Matching the server's message text for
     * {@code "pnr"} instead would not work: MySQL reports
     * {@code Duplicate entry '…' for key 'bookings.PRIMARY'}, which never names the
     * column, so a text match would silently fail to recognise a real collision
     * and turn a retryable event into a refusal.
     *
     * <p>The cause chain is walked because the JDBC driver wraps server errors,
     * and it is the innermost cause that reliably carries the vendor code.
     */
    private static boolean isDuplicateKey(SQLException failure) {
        for (SQLException current = failure; current != null; ) {
            if (current.getErrorCode() == DUPLICATE_KEY) {
                return true;
            }
            Throwable cause = current.getCause();
            current = cause instanceof SQLException ? (SQLException) cause : null;
        }
        for (SQLException chained = failure.getNextException(); chained != null;
             chained = chained.getNextException()) {
            if (chained.getErrorCode() == DUPLICATE_KEY) {
                return true;
            }
        }
        return false;
    }

    /**
     * Raised only to unwind to the PNR retry loop. Never escapes this class.
     *
     * <p>Its own type rather than a flag on {@code SQLException}, so that
     * "that insert failed" and "that insert failed because the PNR was taken" are
     * not the same thing to catch. A method that declares it cannot forget it is
     * possible.
     */
    private static final class DuplicatePnrException extends Exception {
        private static final long serialVersionUID = 1L;

        DuplicatePnrException(SQLException cause) {
            super("PNR already in use", cause);
        }
    }
}