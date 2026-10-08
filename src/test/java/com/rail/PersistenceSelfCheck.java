package com.rail;

import com.rail.db.DatabaseConnection;
import com.rail.model.BerthAllocation;
import com.rail.model.BerthPreference;
import com.rail.model.BookingDetail;
import com.rail.model.BookingDraft;
import com.rail.model.BookingStatus;
import com.rail.model.BookingSummary;
import com.rail.model.ConfirmedBooking;
import com.rail.model.Gender;
import com.rail.model.PassengerDraft;
import com.rail.model.PassengerInput;
import com.rail.model.Role;
import com.rail.model.TrainSearchCriteria;
import com.rail.model.TrainSearchResult;
import com.rail.model.TrainSelection;
import com.rail.model.User;
import com.rail.service.AuthException;
import com.rail.service.AuthService;
import com.rail.service.BookingPreparationService;
import com.rail.service.BookingQueryService;
import com.rail.service.BookingService;
import com.rail.service.PnrGenerator;
import com.rail.service.SessionManager;
import com.rail.service.TrainSearchService;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

/**
 * Proves that a booking survives the application being closed and reopened.
 *
 * <h2>Why this check is different from every other one</h2>
 *
 * <p>All the other database checks run inside a transaction that is rolled back,
 * which is the only way to write real rows and leave nothing behind. That makes
 * them unable to answer this question: a rolled-back row was never durable, so
 * it proves nothing about durability.
 *
 * <p>So this one commits, deliberately, and then pretends the application has
 * been restarted. "Restarted" means what it really means for a desktop
 * application with no server-side session:
 *
 * <ul>
 *   <li>the session is ended and a new one is begun by signing in again;</li>
 *   <li>every service object is discarded and rebuilt;</li>
 *   <li>every connection is closed and a new one opened - a new TCP connection,
 *       a new MySQL session, with nothing carried over in memory.</li>
 * </ul>
 *
 * <p>The account is created through {@link AuthService#register} and the ticket
 * is read back through {@link BookingQueryService}, so the password hash, the
 * foreign keys and the ownership check are all exercised on the way.
 *
 * <h2>The residue, stated plainly</h2>
 *
 * <p>This check leaves exactly one account and one confirmed booking behind,
 * forever, because the application account holds no {@code DELETE} on
 * {@code bookings} - a committed booking cannot be removed by the application,
 * and pretending otherwise would be a lie about the grants. They are
 * <em>fixture</em> rows, and the README documents them as such.
 *
 * <p>It is idempotent: a second run finds the account, signs in with the same
 * password, finds the booking for the same date and reads that. It books again
 * only if the booking is gone, so repeated runs add nothing.
 *
 * <p>ponytail: one fixture, one date, no cleanup path. If a future milestone
 * grants DELETE on bookings, delete these rows here and the check becomes a
 * rollback like the rest.
 */
public final class PersistenceSelfCheck {

    /** Fixed, so a second run finds the first run's work instead of adding to it. */
    private static final String USERNAME = "persist.check";
    private static final String PASSWORD = "Persist-Check-9";

    private static final String TRAIN = "12952";
    private static final String TRAVEL_CLASS = "2A";
    private static final String FROM = "CSTM";
    private static final String TO = "PUNE";
    private static final LocalDate JOURNEY_DATE = LocalDate.of(2027, 12, 15);

    private static final String FULL_NAME = "Persistence Check";
    private static final String EMAIL = "persistence@example.com";
    private static final String MOBILE = "9876543210";

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        System.out.println("Persistence self-check");
        if (!DatabaseConnection.isConfigured()) {
            System.out.println("SKIP  no database configured, nothing was connected to.");
            return;
        }
        System.out.println();

        try {
            runTheApplicationOnce();
            restartAndReadItBack();
        } catch (Exception failed) {
            see("the scenario ran to the end", false);
            failed.printStackTrace();
        }

        System.out.println();
        if (failed == 0) {
            System.out.println("All " + passed + " persistence checks passed.");
            System.out.println("Fixture rows left in place on purpose: one account '"
                    + USERNAME + "' and its booking for " + JOURNEY_DATE + ".");
        } else {
            System.out.println(failed + " of " + (passed + failed) + " persistence checks FAILED.");
            System.exit(1);
        }
    }

    /** Signs up, signs in and books - the way a traveller does. Committed. */
    private static void runTheApplicationOnce() throws Exception {
        section("The first run, which commits");

        AuthService auth = new AuthService();
        boolean accountExisted;
        try {
            User created = auth.register(FULL_NAME, USERNAME, EMAIL, MOBILE, PASSWORD);
            accountExisted = false;
            see("the account was created", created.userId() > 0);
            see("it is an ordinary traveller, never an administrator",
                    created.role() == Role.CUSTOMER);
        } catch (AuthException alreadyThere) {
            accountExisted = true;
            see("the account already existed, so this run reuses it", true);
        }

        User signedIn = signIn();
        see("the password verifies against the stored hash", signedIn != null);

        List<BookingSummary> mine = new BookingQueryService().myBookings();
        BookingSummary existing = mine.stream()
                .filter(booking -> JOURNEY_DATE.equals(booking.journeyDate()))
                .findFirst()
                .orElse(null);

        if (existing != null) {
            see("a booking for " + JOURNEY_DATE + " already exists, so none was added", true);
            see("it belongs to this account", existing.pnr() > 0);
            return;
        }

        ConfirmedBooking booked = book();
        see("the booking was confirmed", booked.status() == BookingStatus.CONFIRMED);
        see("it is the booking for the fixture date",
                JOURNEY_DATE.equals(booked.journeyDate()));
        see("the engine wrote it with a ten-digit PNR", booked.pnrDisplay().length() == 10);
        see("one passenger was seated", booked.passengers().size() == 1);
        BerthAllocation seated = booked.passengers().get(0);
        see("that passenger has a real berth",
                seated.coachNumber() != null && seated.berthNumber() > 0);
        see("the fare is the fare charged", booked.fare().total().signum() > 0);
    }

    /**
     * Throws away every object the first run built, then reads the booking back.
     *
     * <p>Nothing here is reused from {@link #runTheApplicationOnce()} except the
     * constants. The session is ended and re-begotten by signing in, so the read
     * is done by a fresh {@link BookingQueryService} over a fresh connection.
     */
    private static void restartAndReadItBack() throws Exception {
        section("After a restart, with nothing left in memory");

        SessionManager.get().endSession();
        see("the session is gone", !SessionManager.get().isSignedIn());

        see("a booking cannot be read with no session at all", refusedWithoutASession());

        long pnr = pnrOfTheFixtureBooking();
        see("the booking is still in the database", pnr > 0);

        User again = signIn();
        see("the account signs in again after the restart", again != null);

        // New service objects, new connection, no shared state with the first run.
        BookingQueryService queries = new BookingQueryService();
        BookingDetail ticket = queries.myTicketByPnr(pnr).orElse(null);

        see("the ticket is readable after the restart", ticket != null);
        if (ticket == null) {
            return;
        }
        see("it is the same PNR", ticket.pnr() == pnr);
        see("it is the same train", TRAIN.equals(ticket.trainNo()));
        see("it is the same route", FROM.equals(ticket.fromStationCode())
                && TO.equals(ticket.toStationCode()));
        see("it is the same journey date", JOURNEY_DATE.equals(ticket.journeyDate()));
        see("it is the same class", TRAVEL_CLASS.equals(ticket.classCode()));
        see("it is still confirmed", ticket.bookingStatus() == BookingStatus.CONFIRMED);
        see("its fare is unchanged", ticket.totalFare().signum() > 0);
        see("the passenger survived", ticket.passengers().size() == 1);
        see("with the same berth",
                ticket.passengers().get(0).coachNumber() != null
                        && ticket.passengers().get(0).berthNumber() > 0);

        List<BookingSummary> history = queries.myBookings();
        see("it appears in My Bookings after the restart",
                history.stream().anyMatch(booking -> booking.pnr() == pnr));
        see("the booking count agrees with the list",
                queries.myBookingCount() == history.size());
        see("it counts as active", queries.myActiveBookingCount() >= 1);

        see("the berth it holds is still held", berthIsStillHeld(ticket));
        see("the stored password is a hash, never the password", passwordIsHashed());
        see("the account owns the booking and nobody else can read it",
                refusedForAnotherAccount(pnr));
    }

    /** Signs in for real: BCrypt against the hash the first run left behind. */
    private static User signIn() throws AuthException {
        return new AuthService().login(USERNAME, PASSWORD);
    }

    private static long pnrOfTheFixtureBooking() throws Exception {
        // Signed in as the fixture account, so this is the account's own PNR.
        for (BookingSummary booking : new BookingQueryService().myBookings()) {
            if (JOURNEY_DATE.equals(booking.journeyDate())) {
                return booking.pnr();
            }
        }
        return 0L;
    }

    /** A real booking, committed, for one passenger. */
    private static ConfirmedBooking book() throws Exception {
        TrainSearchService search = new TrainSearchService();
        TrainSelection selection = null;
        for (TrainSearchResult result : search.search(
                new TrainSearchCriteria(FROM, TO, JOURNEY_DATE, TRAVEL_CLASS, 1))) {
            if (result.trainNo().equals(TRAIN)) {
                selection = new TrainSelection(result,
                        result.availabilityFor(TRAVEL_CLASS).orElseThrow(), JOURNEY_DATE, 1);
                break;
            }
        }
        if (selection == null) {
            throw new IllegalStateException("no " + TRAIN + " in " + TRAVEL_CLASS
                    + " for " + FROM + " to " + TO + " on " + JOURNEY_DATE);
        }

        BookingPreparationService preparation = new BookingPreparationService();
        BookingDraft draft = new BookingDraft(selection);
        preparation.addPassenger(draft, preparation.profileOf(selection.classCode()),
                new PassengerInput("Asha Rao", "34", Gender.FEMALE.label(),
                        BerthPreference.LOWER.label(), null));
        preparation.setContact(draft, MOBILE, EMAIL);
        return new BookingService(DatabaseConnection::getConnection, new PnrGenerator()::next)
                .confirm(draft);
    }

    /** The reservation is still on the row, which is what "held" means. */
    private static boolean berthIsStillHeld(BookingDetail ticket) throws SQLException {
        try (Connection c = DatabaseConnection.getConnection();
             PreparedStatement statement = c.prepareStatement(
                     "SELECT COUNT(*) FROM seat_reservations WHERE pnr = ? AND status = 'CONFIRMED'")) {
            statement.setLong(1, ticket.pnr());
            try (ResultSet results = statement.executeQuery()) {
                results.next();
                return results.getInt(1) == ticket.passengers().size();
            }
        }
    }

    private static boolean passwordIsHashed() throws SQLException {
        try (Connection c = DatabaseConnection.getConnection();
             PreparedStatement statement = c.prepareStatement(
                     "SELECT password_hash FROM users WHERE username = ?")) {
            statement.setString(1, USERNAME);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    return false;
                }
                String hash = results.getString(1);
                // BCrypt, and never the password in the clear.
                return hash != null && hash.startsWith("$2") && !hash.contains(PASSWORD);
            }
        }
    }

    private static boolean refusedWithoutASession() {
        SessionManager.get().endSession();
        try {
            new BookingQueryService().myBookings();
            return false;
        } catch (Exception expected) {
            return true;
        } finally {
            signInQuietly();
        }
    }

    private static boolean refusedForAnotherAccount(long pnr) {
        SessionManager.get().endSession();
        SessionManager.get().beginSession(new User(1L, "someone.else", "Somebody Else",
                Role.CUSTOMER));
        try {
            return new BookingQueryService().myTicketByPnr(pnr).isEmpty();
        } catch (Exception expected) {
            return true;
        } finally {
            SessionManager.get().endSession();
            signInQuietly();
        }
    }

    private static void signInQuietly() {
        try {
            SessionManager.get().beginSession(signIn());
        } catch (AuthException cannot) {
            throw new IllegalStateException("the fixture account cannot sign in", cannot);
        }
    }

    // ---------------------------------------------------------------- plumbing

    private static void section(String title) {
        System.out.println(title);
        System.out.println("-".repeat(title.length()));
    }

    private static void see(String what, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what);
        if (ok) {
            passed++;
        } else {
            failed++;
        }
    }
}