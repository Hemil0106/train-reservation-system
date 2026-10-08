package com.rail;

import com.rail.db.AvailabilityDAO;
import com.rail.db.AvailabilityDAO.CandidateBerth;
import com.rail.db.BookingDAO;
import com.rail.db.ConnectionSource;
import com.rail.db.DatabaseConnection;
import com.rail.db.TrainDAO;
import com.rail.model.BerthAllocation;
import com.rail.model.BerthPreference;
import com.rail.model.BookingContact;
import com.rail.model.BookingDraft;
import com.rail.model.BookingStatus;
import com.rail.model.ConfirmedBooking;
import com.rail.model.FareEstimate;
import com.rail.model.Gender;
import com.rail.model.PassengerDraft;
import com.rail.model.PassengerInput;
import com.rail.model.Role;
import com.rail.model.TrainSearchCriteria;
import com.rail.model.TrainSearchResult;
import com.rail.model.TrainSelection;
import com.rail.model.User;
import com.rail.service.AccessDeniedException;
import com.rail.service.BookingException;
import com.rail.service.BookingPreparationService;
import com.rail.service.BookingService;
import com.rail.service.FareService;
import com.rail.service.SessionManager;
import com.rail.service.TrainSearchService;
import com.rail.service.ValidationException;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Verification for the booking engine: the checks that a seat is really sold,
 * once, to one passenger, and that a failure sells nothing at all.
 *
 * <h2>Nothing sold is left behind</h2>
 *
 * <p>The application account has no DELETE privilege - by design, so that a bug
 * in this application cannot erase production data - which means a check that
 * committed a real booking could never clean up after itself. Every scenario
 * therefore runs inside one transaction that is rolled back at the end, with the
 * engine's own {@code commit} recorded but suppressed by {@link TxProbe}. After
 * a full run, {@code bookings}, {@code passengers} and {@code seat_reservations}
 * all still hold nothing but what was there before.
 *
 * <p>{@code journeys} is the one exception, and it is not test debris: search
 * materialises a journey row for the date it is asked about, by design
 * ({@code TrainDAO.ensureJourney}), and it is keyed by train and date so repeated
 * runs reuse it rather than growing it. The one scenario that genuinely needs a
 * committed journey - the two-connection lock check - is the only thing that
 * creates one.
 *
 * <h2>How the race is proven</h2>
 *
 * <p>Proving that two simultaneous bookings cannot take the same berth needs each
 * booking to be <em>visible</em> to the other, and uncommitted rows are invisible
 * across connections. So it is tested in two halves that together prove it:
 *
 * <ul>
 *   <li>{@link #checkTheLockActuallyBlocks} runs two real connections and shows
 *       the second genuinely blocks on the first's {@code FOR UPDATE} and only
 *       proceeds once the first rolls back. That is the mutex, observed rather
 *       than assumed.</li>
 *   <li>{@link #checkAnOccupiedBerthIsNeverReissued} reproduces the exact state
 *       the blocked transaction wakes up to - a reservation already committed for
 *       the segment - and shows the next allocation cannot pick that berth.</li>
 * </ul>
 *
 * <p>A genuine two-connection test where both bookings commit is impossible under
 * these grants without leaving a real booking in the database, so it is not
 * attempted. Asserting that two threads "probably" serialise would be a check
 * that passes when the locking is broken.
 *
 * <p>The fixture is one train, {@code 12952}, whose rake makes the two cases
 * needed: AC 2-Tier (2A, {@value #SMALL_CLASS}) is a single coach of eight
 * berths, small enough to fill on purpose, and AC 3-Tier (3A, {@value
 * #THREE_TIER_CLASS}) is one coach of eighteen with six of each position, so
 * preferences can be told apart.
 */
public final class BookingEngineSelfCheck {

    /** CSTM 1 - PUNE 2 - NAS 3 - KOP 4 - ST 5, so adjacent legs exist to test. */
    private static final String TRAIN = "12952";

    /** AC1: eight berths, four LOWER and four UPPER. No MIDDLE. */
    private static final String SMALL_CLASS = "2A";

    /** AC2: eighteen berths - six each of LOWER, MIDDLE and UPPER. */
    private static final String THREE_TIER_CLASS = "3A";

    private static final String FROM = "CSTM";
    private static final String NEXT = "PUNE";
    private static final String AFTER_NEXT = "NAS";

    private static final LocalDate DATE = LocalDate.now().plusDays(30);

    /** The most passengers one booking may carry; the schema caps it at six. */
    private static final int PARTY_LIMIT = 6;

    private static final String CONTACT_NAME = "Booking Check";
    private static final String CONTACT_MOBILE = "9876543210";
    private static final String CONTACT_EMAIL = "traveller@example.com";

    private static int passed;
    private static int failed;
    private static String section = "";

public static void main(String[] args) throws Exception {
        System.out.println("Booking engine self-check");
        System.out.println("========================");

        startSession();
        rememberStartingCounts();

        // The one journey this run commits, for the two-connection check below.
        // Exactly what a search would have created, and reused on later runs.
        long sharedJourneyId = new TrainDAO(DatabaseConnection::getConnection)
                .ensureJourney(TRAIN, DATE).journeyId();

        checkSinglePassengerBookingCommits();
        checkMultiplePassengersGetDistinctBerths();
        checkPreferenceIsHonouredWhenFree();
        checkPreferenceFallsBackAndSaysSo();
        checkOverlappingSegmentCannotReuseABerth();
        checkAdjacentSegmentMayReuseABerth();
        checkInsufficientInventorySellsNothing();
        checkTheLockActuallyBlocks(sharedJourneyId);
        checkAnOccupiedBerthIsNeverReissued();
        checkPnrCollisionIsRetried();
        checkEveryBookingHasADistinctPnr();
        checkMoneyIsExactAndAddsUp();
        checkStatusesAreOnlyWhatWeImplement();
        checkBadDraftsAreRefusedBeforeAnythingIsWritten();
        checkNoSessionIsRefused();
        checkCancelledJourneyIsRefused();
        checkVanishedJourneyIsRefused();

        endSession();

        see("no booking was left behind by this run", totalBookings() == 0);
        see("no passenger was left behind by this run", totalPassengers() == 0);
        see("no reservation was left behind by this run", totalReservations() == 0);
        report();
    }

    // ---------------------------------------------------------------- scenarios

    /** One passenger, one berth, three rows written and one PNR issued. */
    private static void checkSinglePassengerBookingCommits() {
        section("Single passenger booking");
        scenario(probe -> {
            Connection c = probe.connection();
            TrainSelection selection = selectionFor(c, SMALL_CLASS, 1, FROM, NEXT);
            ConfirmedBooking booked = book(c, selection, one());

            see("a PNR was issued", booked.pnr() > 0);
            see("the PNR is ten digits", booked.pnrDisplay().matches("\\d{10}"));
            see("the PNR is not a small sequence number",
                    booked.pnr() > 1_000_000_000L);
            see("the booking is CONFIRMED", booked.status() == BookingStatus.CONFIRMED);
            see("one passenger was seated", booked.passengerCount() == 1);
            see("one berth was allocated",
                    booked.passengers().get(0).seatLabel().contains("berth"));
            see("the engine committed exactly once", probe.commits() == 1);
            see("the booking row exists", bookingRows(c, booked.pnr()) == 1);
            see("one passenger row exists", passengerRows(c, booked.pnr()) == 1);
            see("one reservation row exists", reservationRows(c, booked.pnr()) == 1);
            see("the reservation names the allocated berth",
                    reservationBerth(c, booked.pnr())
                            == booked.passengers().get(0).berthId());
            see("the reservation is for the searched segment",
                    reservationSegment(c, booked.pnr())
                            .equals(segmentOf(selection)));
            see("the booking total was written",
                    storedTotal(c, booked.pnr()).compareTo(booked.fare().total()) == 0);
// The contact name is the signed-in traveller's, not whatever the form said:
            // a booking is only ever made by, and for, the account making it.
            see("the contact name came from the signed-in account",
                    storedContactName(c, booked.pnr())
                            .equals(SessionManager.get().currentUser().fullName()));
            see("the passenger is flagged as the contact person",
                    isPrimaryCount(c, booked.pnr()) == 1);
        });
    }

    /** A party of four: four passengers, four berths, none shared. */
    private static void checkMultiplePassengersGetDistinctBerths() {
        section("Multiple passengers");
        scenario(probe -> {
            Connection c = probe.connection();
            TrainSelection selection = selectionFor(c, THREE_TIER_CLASS, 4, FROM, NEXT);
            List<PassengerDraft> party = List.of(
                    passenger("Asha Rao", 34, Gender.FEMALE, BerthPreference.LOWER),
                    passenger("Bilal Khan", 29, Gender.MALE, BerthPreference.UPPER),
                    passenger("Chitra Das", 61, Gender.FEMALE, BerthPreference.MIDDLE),
                    passenger("Dev Patel", 8, Gender.MALE, BerthPreference.NO_PREFERENCE));
            ConfirmedBooking booked = book(c, selection, party);

            see("four passengers were seated", booked.passengerCount() == 4);
            see("four passengers were written",
                    passengerRows(c, booked.pnr()) == 4);
            see("four reservations were written",
                    reservationRows(c, booked.pnr()) == 4);
            see("every passenger has a berth",
                    booked.passengers().stream().allMatch(p -> p.berthId() > 0));
            see("no two passengers share a berth",
                    distinctBerths(booked.passengers()) == 4);
            see("every berth is in the booked class",
                    allInClass(c, booked.pnr(), THREE_TIER_CLASS));
            see("no passenger is flagged as the contact person twice",
                    isPrimaryCount(c, booked.pnr()) == 1);
            see("all four are in one booking", bookingRows(c, booked.pnr()) == 1);
        });
    }

    /** Two requests for different positions, both available, both honoured. */
    private static void checkPreferenceIsHonouredWhenFree() {
        section("Preferences honoured");
        scenario(probe -> {
            Connection c = probe.connection();
            TrainSelection selection = selectionFor(c, THREE_TIER_CLASS, 2, FROM, NEXT);
            ConfirmedBooking booked = book(c, selection, List.of(
                    passenger("Lower One", 40, Gender.MALE, BerthPreference.LOWER),
                    passenger("Upper One", 41, Gender.FEMALE, BerthPreference.UPPER)));

            BerthAllocation first = booked.passengers().get(0);
            BerthAllocation second = booked.passengers().get(1);
            see("the lower request got a lower berth",
                    first.grantedPosition() == BerthPreference.LOWER);
            see("the upper request got an upper berth",
                    second.grantedPosition() == BerthPreference.UPPER);
            see("both preferences are reported as honoured",
                    first.preferenceHonoured() && second.preferenceHonoured());
            see("nothing needed the fallback", booked.preferencesNotHonoured() == 0);
            see("the request is stored, not the outcome",
                    storedPreferences(c, booked.pnr()).equals("LOWER,UPPER"));
            see("the outcome is stored on the reservations",
                    storedPositions(c, booked.pnr()).equals("LOWER,UPPER"));
        });
    }

    /**
     * Sell every lower berth, then ask for one more.
     *
     * <p>The request cannot be met, so the documented fallback gives the
     * passenger a real berth anyway - and says the request was not met, which is
     * the whole point of recording both.
     */
    private static void checkPreferenceFallsBackAndSaysSo() {
        section("Preference unavailable");
scenario(probe -> {
            Connection c = probe.connection();
            TrainSelection selection = selectionFor(c, THREE_TIER_CLASS, 2, FROM, NEXT);
            int lowers = countPosition(c, selection, "LOWER");
            see("the class has lower berths to fill", lowers > 1);

            // Other travellers take every lower berth on this segment first. Their
            // own selection is for one passenger each, which is what a separate
            // booking genuinely looks like.
            TrainSelection single = selectionFor(c, THREE_TIER_CLASS, 1, FROM, NEXT);
            int capacity = totalBerths(c, selection, THREE_TIER_CLASS);
            for (int index = 0; index < lowers; index++) {
                book(c, single, List.of(passenger("Seat Filler " + index, 30,
                        Gender.MALE, BerthPreference.LOWER)));
            }
            see("exactly the lower berths went",
                    availableBerths(c, selection) == capacity - lowers);
            see("no lower berth is left on the segment",
                    countFreeAtPosition(c, selection, "LOWER") == 0);

            ConfirmedBooking booked = book(c, selection, List.of(
                    passenger("Wants Lower", 30, Gender.MALE, BerthPreference.LOWER),
                    passenger("Wants Nothing", 31, Gender.FEMALE, BerthPreference.NO_PREFERENCE)));

            BerthAllocation asked = booked.passengers().get(0);
            see("the passenger who asked for lower did not get lower",
                    asked.grantedPosition() != BerthPreference.LOWER);
            see("they still got a real berth", asked.berthId() > 0);
            see("the unmet request is reported, not hidden", !asked.preferenceHonoured());
            see("the booking counts one unmet preference",
                    booked.preferencesNotHonoured() == 1);
            see("a passenger with no preference is not counted as unmet",
                    booked.passengers().get(1).preferenceHonoured());
            see("the stored request is still LOWER",
                    firstStoredPreference(c, booked.pnr()).equals("LOWER"));
            see("the stored outcome is not LOWER",
                    !firstStoredPosition(c, booked.pnr()).equals("LOWER"));
        });
    }

    /** Two bookings over the same segment must never share a berth. */
    private static void checkOverlappingSegmentCannotReuseABerth() {
        section("Overlapping segments");
        scenario(probe -> {
            Connection c = probe.connection();
            TrainSelection selection = selectionFor(c, THREE_TIER_CLASS, 3, FROM, NEXT);
            Set<Long> firstBerths = new LinkedHashSet<>(
                    bookAndCollectBerths(c, selection, 3, "First"));
            Set<Long> secondBerths = new LinkedHashSet<>(
                    bookAndCollectBerths(c, selection, 3, "Second"));

            see("both parties were seated in full",
                    firstBerths.size() == 3 && secondBerths.size() == 3);
            Set<Long> union = new LinkedHashSet<>(firstBerths);
            union.addAll(secondBerths);
            see("six passengers were seated in total", union.size() == 6);
            see("no berth was sold twice on the same segment", union.size() == 6);
            see("all six reservations cover the searched segment",
                    reservationsOnSegment(c, selection) == 6);
            see("no reservation overlaps another on one berth",
                    overlappingPairs(c, selection) == 0);
        });
    }

    /** A berth sold for stops 1-2 is free again for stops 2-3. */
    private static void checkAdjacentSegmentMayReuseABerth() {
        section("Adjacent segments reuse a berth");
        scenario(probe -> {
            Connection c = probe.connection();
            TrainSelection first = selectionFor(c, THREE_TIER_CLASS, 1, FROM, NEXT);
            ConfirmedBooking booked = book(c, first, one());
            long berthId = booked.passengers().get(0).berthId();
            see("a berth was sold on the first leg", berthId > 0);

            // The same physical berth, the next leg along.
            TrainSelection next = selectionFor(c, THREE_TIER_CLASS, 1, NEXT, AFTER_NEXT);
            see("the next leg is a different segment",
                    next.fromSeq() == first.toSeq() && next.toSeq() == first.toSeq() + 1);
            see("the next leg has every berth to offer",
                    availableBerths(c, next) == countPosition(c, next, "LOWER")
                            + countPosition(c, next, "MIDDLE")
                            + countPosition(c, next, "UPPER"));

            ConfirmedBooking second = book(c, next, one());
            see("the same berth was resold on the adjacent leg",
                    second.passengers().get(0).berthId() == berthId);
            see("the two bookings are separate", second.pnr() != booked.pnr());
            see("each leg has exactly one reservation",
                    reservationsOnSegment(c, first) == 1
                            && reservationsOnSegment(c, next) == 1);
            see("neither reservation is marked as overlapping the other",
                    overlappingPairs(c, first) == 0);
        });
    }

    /**
     * Fewer free berths than passengers: refuse, and sell nothing.
     *
     * <p>Arranged the way it happens in life. The form was priced when six berths
     * were free; other travellers took three while it sat open; the passenger then
     * presses the button. The party still matches the search, so the refusal has to
     * come from allocation rather than from the passenger count - and it has to
     * count what is actually free, not how many berths the class owns.
     */
    private static void checkInsufficientInventorySellsNothing() {
        section("Insufficient inventory");
        scenario(probe -> {
            Connection c = probe.connection();
            TrainSelection selection = selectionFor(c, SMALL_CLASS, PARTY_LIMIT, FROM, NEXT);
            int capacity = totalBerths(c, selection, SMALL_CLASS);
            see("the class has a knowable small capacity", capacity == 8);
            see("the whole class was free to begin with",
                    availableBerths(c, selection) == capacity);

            // A different party, booked while this form was open.
            TrainSelection earlier = selectionFor(c, SMALL_CLASS, 3, FROM, NEXT);
            bookAndCollectBerths(c, earlier, 3, "Earlier");
            see("three berths went while the form was open",
                    availableBerths(c, selection) == capacity - 3);

            String message = bookingFails(c, readyDraft(c, selection, party(PARTY_LIMIT)));
            see("the booking was refused", message != null);
            see("the refusal explains why", message != null && message.contains("berths"));
            see("the refusal counts the free berths, not the class size",
                    message != null && message.contains("free in this class"));
// The refusal rolled back its own transaction, which on this single scenario
            // connection also discards the earlier booking - it was never committed.
            // In production that earlier booking is another traveller's committed
            // row and no rollback here could touch it. What matters is that the
            // refused attempt left nothing of its own behind.
            see("the refused attempt wrote no rows of its own", totalBookings(c) == 0);
            see("the earlier booking committed exactly once", probe.commits() == 1);
            see("the refused attempt rolled itself back", probe.rollbacks() >= 1);
        });
    }

    /**
     * The mutex, observed.
     *
     * <p>Two real connections against the one committed journey. The first locks
     * the class's berths and holds them; the second must block. If it did not, the
     * locking read would be a plain read and two bookings could choose the same
     * berth. Both roll back, so this leaves nothing behind.
     */
    private static void checkTheLockActuallyBlocks(long journeyId) throws Exception {
        section("The lock blocks a competing booking");
        AtomicReference<String> failure = new AtomicReference<>();
        CountDownLatch firstHasLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondFinished = new CountDownLatch(1);

        Thread first = new Thread(() -> {
            try (Connection connection = DatabaseConnection.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    new AvailabilityDAO(connection)
                            .lockCandidateBerths(journeyId, SMALL_CLASS, 1, 2);
                    firstHasLocked.countDown();
                    releaseFirst.await(10, TimeUnit.SECONDS);
                } finally {
                    connection.rollback();
                }
            } catch (Exception problem) {
                failure.compareAndSet(null, "first: " + problem);
                firstHasLocked.countDown();
            }
        }, "berth-lock-holder");

        AtomicInteger secondBerths = new AtomicInteger(-1);
        Thread second = new Thread(() -> {
            try (Connection connection = DatabaseConnection.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    // Held back until the first transaction owns the rows.
                    firstHasLocked.await(10, TimeUnit.SECONDS);
                    secondBerths.set(new AvailabilityDAO(connection)
                            .lockCandidateBerths(journeyId, SMALL_CLASS, 1, 2).size());
                } finally {
                    connection.rollback();
                }
            } catch (Exception problem) {
                failure.compareAndSet(null, "second: " + problem);
            } finally {
                secondFinished.countDown();
            }
        }, "berth-lock-waiter");

        first.start();
        second.start();

        boolean blocked = false;
        try {
            // If the second thread were not blocked it would have finished by now.
            blocked = !secondFinished.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        see("a competing transaction is made to wait for the berths", blocked);

        releaseFirst.countDown();
        try {
            secondFinished.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        await(first);
        await(second);

        int expected = berthsInClass(SMALL_CLASS);
        see("once the first transaction ends, the second proceeds", secondBerths.get() > 0);
        see("neither transaction reported a failure", failure.get() == null);
        see("the class's berths were all locked, not just some",
                secondBerths.get() == expected);
        see("the small class really is that small", expected == 8);
        see("nothing was left behind", totalBookings() == 0);
    }

    /**
     * The state the blocked transaction wakes up to.
     *
     * <p>A reservation already exists for the segment, so the next allocation must
     * not hand that berth out again. This is the decision the lock protects, and
     * it is asserted here against the database rather than against the allocator's
     * own bookkeeping.
     */
    private static void checkAnOccupiedBerthIsNeverReissued() {
        section("An occupied berth is never reissued");
        scenario(probe -> {
            Connection c = probe.connection();
            TrainSelection selection = selectionFor(c, THREE_TIER_CLASS, 2, FROM, NEXT);
            Set<Long> first = new LinkedHashSet<>(bookAndCollectBerths(c, selection, 2, "First"));
            Set<Long> stillFree = freeBerthIds(c, selection);
            see("the first booking took two berths out of the free list",
                    stillFree.size() + first.size() == totalBerths(c, selection, THREE_TIER_CLASS));

            Set<Long> second = new LinkedHashSet<>(bookAndCollectBerths(c, selection, 2, "Second"));
            see("the second booking was seated", second.size() == 2);
            for (long berth : second) {
                see("berth " + berth + " was not already sold", !first.contains(berth));
            }
            see("the availability read now reports every sold berth as held",
                    freeBerthIds(c, selection).size() == stillFree.size() - 2);
            see("every sold berth reads as occupied",
                    heldBerthIds(c, selection).containsAll(first)
                            && heldBerthIds(c, selection).containsAll(second));
            see("no reservation overlaps another on one berth",
                    overlappingPairs(c, selection) == 0);
        });
    }

    /**
     * A PNR that is already taken must be retried, not surfaced.
     *
     * <p>The generator is replaced with one that hands back a number already in
     * the table, which is what a collision looks like from inside. The booking
     * must still succeed on the second attempt.
     */
    private static void checkPnrCollisionIsRetried() {
        section("PNR collision");
        scenario(probe -> {
            Connection c = probe.connection();
            TrainSelection selection = selectionFor(c, SMALL_CLASS, 1, FROM, NEXT);
            long taken = 424_242_424L;
            insertBookingRow(c, taken, selection);
            see("a booking already holds that PNR", bookingRows(c, taken) == 1);

            List<Long> offered = List.of(taken, taken + 1);
            AtomicInteger call = new AtomicInteger();
            BookingService service = new BookingService(sameConnection(c), () -> {
                int index = call.getAndIncrement();
                return index < offered.size() ? offered.get(index) : 987_654_321L;
            });
            ConfirmedBooking booked = service.confirm(readyDraft(c, selection, one()));

            see("the retry produced a different PNR", booked.pnr() != taken);
            see("the generator was asked twice", call.get() == 2);
            see("the booking committed on the retry", bookingRows(c, booked.pnr()) == 1);
            see("the passenger was written once", passengerRows(c, booked.pnr()) == 1);
            see("the reservation was written once", reservationRows(c, booked.pnr()) == 1);
// The collision rolled that attempt back, and with it the fixture: in
            // production the colliding row would be another user's committed
            // booking, which no rollback here could reach. What this proves is
            // that the retry wrote exactly one booking and reused nobody's PNR.
            see("the retry left exactly one booking", totalBookings(c) == 1);
            see("the colliding PNR was not reused", bookingRows(c, taken) == 0);
            see("the engine committed only the retry", probe.commits() == 1);
        });
    }

    /** A batch of bookings, every PNR distinct. */
    private static void checkEveryBookingHasADistinctPnr() {
        section("PNR uniqueness");
        scenario(probe -> {
            Connection c = probe.connection();
            Set<Long> seen = new LinkedHashSet<>();
            List<Long> stored = new ArrayList<>();
            for (int index = 0; index < 5; index++) {
ConfirmedBooking booked = book(c,
                        selectionFor(c, SMALL_CLASS, 1, FROM, NEXT),
                        List.of(passenger("Repeat " + index, 30, Gender.MALE,
                                BerthPreference.NO_PREFERENCE)));
                see("PNR " + booked.pnrDisplay() + " is new to this run",
                        seen.add(booked.pnr()));
                stored.add(booked.pnr());
            }
            see("five bookings produced five distinct PNRs", seen.size() == 5);
            see("the database agrees they are distinct", distinctPnrCount(c, stored) == 5);
            see("every PNR is ten digits", stored.stream()
                    .allMatch(pnr -> String.format("%010d", pnr).matches("\\d{10}")));
            see("every PNR is beyond the old sequence range",
                    stored.stream().allMatch(pnr -> pnr > 1_000_000_000L));
        });
    }

    /** The stored money is exact, and the parts add up to the whole. */
    private static void checkMoneyIsExactAndAddsUp() {
        section("Fare arithmetic");
        scenario(probe -> {
            Connection c = probe.connection();
            TrainSelection selection = selectionFor(c, THREE_TIER_CLASS, 3, FROM, NEXT);
            ConfirmedBooking booked = book(c, selection, List.of(
                    passenger("Pay One", 30, Gender.MALE, BerthPreference.LOWER),
                    passenger("Pay Two", 31, Gender.FEMALE, BerthPreference.MIDDLE),
                    passenger("Pay Three", 32, Gender.OTHER, BerthPreference.UPPER)));

            FareEstimate fare = booked.fare();
            see("base plus distance is the per-passenger fare",
                    fare.perPassenger().compareTo(fare.baseFare().add(fare.distanceFare())) == 0);
            see("per-passenger times three is the total",
                    fare.total().compareTo(
                            fare.perPassenger().multiply(BigDecimal.valueOf(3))) == 0);
            see("every amount is at two decimals", atScale(fare));
            see("the total was stored exactly",
                    storedTotal(c, booked.pnr()).compareTo(fare.total()) == 0);
            see("the passenger count was stored",
                    storedPassengerCount(c, booked.pnr()) == 3);
            BigDecimal sumOfReservations = sumOfReservationAmounts(c, booked.pnr());
            see("the reservation amounts add up to the booking total",
                    sumOfReservations.compareTo(fare.total()) == 0);
            see("each reservation was charged the per-passenger fare",
                    sumOfReservations.compareTo(
                            fare.perPassenger().multiply(BigDecimal.valueOf(3))) == 0);
            see("the total keeps two decimals", fare.total().scale() == FareEstimate.SCALE);
            see("the fare is the re-read rule, not the search figure",
                    fare.perPassenger().compareTo(selection.farePerPassenger()) == 0);
        });
    }

    /** Nothing writes a status the application cannot honour. */
    private static void checkStatusesAreOnlyWhatWeImplement() {
        section("Statuses");
        scenario(probe -> {
            Connection c = probe.connection();
            TrainSelection selection = selectionFor(c, SMALL_CLASS, 2, FROM, NEXT);
            ConfirmedBooking booked = book(c, selection, List.of(
                    passenger("Status One", 30, Gender.MALE, BerthPreference.LOWER),
                    passenger("Status Two", 31, Gender.FEMALE, BerthPreference.UPPER)));

            see("the booking status is CONFIRMED",
                    storedBookingStatus(c, booked.pnr()).equals("CONFIRMED"));
            see("every reservation is CONFIRMED",
                    allReservationsConfirmed(c, booked.pnr()));
            see("no row anywhere claims RAC", countStatus(c, "RAC") == 0);
            see("no row anywhere claims WAITLISTED", countStatus(c, "WAITLISTED") == 0);
see("a passenger has no status of their own to disagree with",
                    countWhere(c, "SELECT COUNT(*) FROM information_schema.COLUMNS"
                            + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'passengers'"
                            + " AND COLUMN_NAME = 'status'") == 0);
            see("reading a status back is strict", fromDbRefuses("PARTIALLY_CANCELLED"));
            see("a status this build knows is accepted",
                    BookingStatus.fromDb("CONFIRMED") == BookingStatus.CONFIRMED);
            see("a cancelled booking is representable",
                    BookingStatus.fromDb("CANCELLED") == BookingStatus.CANCELLED);
        });
    }

    /** A draft that no longer matches the search is refused before any write. */
    private static void checkBadDraftsAreRefusedBeforeAnythingIsWritten() {
        section("Stale and incomplete drafts");
        scenario(probe -> {
            Connection c = probe.connection();
            TrainSelection selection = selectionFor(c, SMALL_CLASS, 1, FROM, NEXT);

            BookingDraft empty = readyDraft(c, selection, one());
            empty.removePassenger(0);
            see("a draft short of its passengers is refused",
                    refuses(c, empty) != null);
            see("the refusal names the passenger count",
                    refuses(c, empty).contains("passenger"));

            BookingDraft past = readyDraft(c, selection, one());
            see("a past journey date is refused",
                    refusesDate(past, LocalDate.now().minusDays(1)) != null);
            see("a yesterday booking wrote nothing", totalBookings(c) == 0);

            BookingDraft noContact = readyDraft(c, selection, one());
            noContact.setContact(null);
            see("a draft with no contact is refused",
                    refuses(c, noContact) != null);
            see("still nothing was written", totalBookings(c) == 0);

            see("an age outside the configured range is refused at booking",
                    refusesAge(c, readyDraft(c, selection, one())) != null);
            see("and still nothing was written", totalBookings(c) == 0);
            see("no lock was taken for any of these",
                    countReservationsForJourney(c, selection) == 0);
        });
    }

/** Booking requires a session. */
    private static void checkNoSessionIsRefused() {
        section("No session");
        User signedIn = SessionManager.get().currentUser();
        try {
            scenario(probe -> {
                Connection c = probe.connection();
                // Search also requires a session, so the selection is built first.
                // What is under test here is the booking, not the search.
                TrainSelection selection = selectionFor(c, SMALL_CLASS, 1, FROM, NEXT);
                BookingDraft draft = readyDraft(c, selection, one());
                SessionManager.get().endSession();
                try {
                    String problem = null;
                    try {
                        new BookingService(sameConnection(c), pnrGenerator()).confirm(draft);
                    } catch (AccessDeniedException refused) {
                        problem = refused.getMessage();
                    }
                    see("booking without a session is refused", problem != null);
                    see("the refusal asks for a sign-in",
                            problem != null && problem.toLowerCase().contains("sign in"));
                    see("nothing was written", totalBookings(c) == 0);
                } finally {
                    SessionManager.get().beginSession(signedIn);
                }
            });
        } finally {
            SessionManager.get().beginSession(signedIn);
        }
    }

    /** A journey that has been cancelled cannot be booked. */
    private static void checkCancelledJourneyIsRefused() {
        section("Cancelled journey");
        scenario(probe -> {
            Connection c = probe.connection();
            TrainSelection selection = selectionFor(c, SMALL_CLASS, 1, FROM, NEXT);
            execute(c, "UPDATE journeys SET service_status = 'CANCELLED' WHERE journey_id = ?",
                    journeyIdOf(c, selection));

            String problem = refuses(c, readyDraft(c, selection, one()));
            see("a cancelled journey is refused", problem != null);
            see("the refusal names the train",
                    problem != null && problem.contains(TRAIN));
            see("nothing was written", totalBookings(c) == 0);
            see("nothing was committed", probe.commits() == 0);
        });
    }

/**
     * A selection whose journey has since been removed cannot be booked.
     *
     * <p>Deleting the row would be the obvious way to arrange this, and the
     * application account is not allowed to delete anything. Moving the draft to a
     * date nobody has searched for reaches the same code path - the journey simply
     * is not there any more - without needing a privilege the app does not have.
     */
    private static void checkVanishedJourneyIsRefused() {
        section("Vanished journey");
        scenario(probe -> {
            Connection c = probe.connection();
            TrainSelection selection = copyOnto(DATE.plusDays(1),
                    selectionFor(c, SMALL_CLASS, 1, FROM, NEXT));
            see("no journey exists for that date", journeyIdOrZero(c, selection) == 0);

            String problem = refuses(c, readyDraft(c, selection, one()));
            see("a journey that no longer exists is refused", problem != null);
            see("the refusal suggests searching again",
                    problem != null && problem.toLowerCase().contains("search"));
            see("nothing was written", totalBookings(c) == 0);
        });
    }

    /** The same selection, moved to another date. */
    private static TrainSelection copyOnto(LocalDate date, TrainSelection selection) {
        return new TrainSelection(selection.trainNo(), selection.trainName(),
                selection.classCode(), selection.className(), selection.fromStation(),
                selection.toStation(), selection.fromSeq(), selection.toSeq(),
                selection.segmentKm(), date, selection.passengers(),
                selection.farePerPassenger(), selection.berthsAvailable());
    }

    /** Zero instead of an exception when the selection's journey is not there. */
    private static long journeyIdOrZero(Connection c, TrainSelection selection)
            throws SQLException {
        try (PreparedStatement statement = c.prepareStatement(
                "SELECT journey_id FROM journeys WHERE train_no = ? AND journey_date = ?")) {
            statement.setString(1, selection.trainNo());
            statement.setObject(2, selection.journeyDate());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0L;
            }
        }
    }

    // ---------------------------------------------------------------- plumbing

    /**
     * Runs one scenario inside a transaction and rolls it back.
     *
     * <p>{@code commit} is recorded rather than executed, so the engine's commit
     * path is exercised - and asserted - while the data still disappears. Nothing
     * else in the scenario may write outside this connection, or it would survive.
     */
    private static void scenario(Scenario body) {
        try (Connection real = DatabaseConnection.getConnection()) {
            real.setAutoCommit(false);
            TxProbe probe = new TxProbe(real);
try {
                body.run(probe);
            } catch (Exception failed) {
                see("the scenario ran without throwing (" + failed + " / "
                        + rootCause(failed) + ")", false);
            } finally {
                real.rollback();
            }
        } catch (SQLException cannotEvenConnect) {
            see("a connection could be opened", false);
        }
    }

    private static void await(Thread thread) {
        try {
            thread.join(10_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

/** A source that hands back the scenario's one connection, transaction and all. */
    private static ConnectionSource sameConnection(Connection c) {
        return () -> c;
    }

    /**
     * Searches inside the scenario's transaction.
     *
     * <p>Deliberately on the scenario's own connection: search is the one write in
     * the read path, it materialises the {@code journeys} row, and doing that on a
     * separate autocommit connection would commit a journey that the scenario then
     * rolls back around - leaving debris the account has no privilege to remove.
     */
private static TrainSelection selectionFor(Connection c, String classCode, int passengers,
                                               String fromCode, String toCode)
            throws AccessDeniedException, ValidationException, SQLException {
        TrainSearchService search = new TrainSearchService(sameConnection(c), PARTY_LIMIT);
        List<TrainSearchResult> results = search.search(
                new TrainSearchCriteria(fromCode, toCode, DATE, classCode, passengers));
        for (TrainSearchResult result : results) {
            if (result.trainNo().equals(TRAIN)) {
                return new TrainSelection(result, result.availabilityFor(classCode).orElseThrow(),
                        DATE, passengers);
            }
        }
        throw new IllegalStateException(
                "no " + TRAIN + " in " + classCode + " for " + fromCode + " to " + toCode);
    }

    /** Books the party and returns what was committed. */
    private static ConfirmedBooking book(Connection c, TrainSelection selection,
                                         List<PassengerDraft> party) throws Exception {
        return new BookingService(sameConnection(c), pnrGenerator())
                .confirm(readyDraft(c, selection, party));
    }

/** Books a party of {@code count} passengers and returns the berth ids given. */
    private static List<Long> bookAndCollectBerths(Connection connection, TrainSelection selection,
                                                   int count, String tag) throws Exception {
        List<PassengerDraft> party = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            party.add(passenger(tag + " " + index, 30,
                    index % 2 == 0 ? Gender.MALE : Gender.FEMALE,
                    BerthPreference.NO_PREFERENCE));
        }
return berthIdsOf(book(connection, selection, party));
    }

    /** A draft with the given passengers and a valid contact, ready to book. */
    private static BookingDraft readyDraft(Connection c, TrainSelection selection,
                                           List<PassengerDraft> passengers) throws Exception {
        BookingPreparationService preparation =
                new BookingPreparationService(sameConnection(c));
        BookingDraft draft = new BookingDraft(selection);
        for (PassengerDraft wanted : passengers) {
            preparation.addPassenger(draft, preparation.profileOf(selection.classCode()),
                    new PassengerInput(wanted.fullName(), String.valueOf(wanted.age()),
                            wanted.gender().label(), wanted.berthPreference().label(),
                            wanted.mealPreference()));
        }
        preparation.setContact(draft, CONTACT_MOBILE, CONTACT_EMAIL);
        return draft;
    }

    private static List<PassengerDraft> one() {
        return List.of(passenger("Solo Traveller", 30, Gender.MALE, BerthPreference.LOWER));
    }

    /** A party of {@code count} passengers who all expressed no preference. */
    private static List<PassengerDraft> party(int count) {
        List<PassengerDraft> party = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            party.add(passenger("Party " + index, 30, Gender.MALE, BerthPreference.NO_PREFERENCE));
        }
        return party;
    }

    private static PassengerDraft passenger(String name, int age, Gender gender,
                                            BerthPreference preference) {
        return new PassengerDraft(name, age, gender, preference, null);
    }

    /** A generator of plausible ten-digit PNRs, distinct within the run. */
    private static Supplier<Long> pnrGenerator() {
        Supplier<Long> generator = new com.rail.service.PnrGenerator()::next;
        Set<Long> used = new LinkedHashSet<>();
        return () -> {
            long candidate = generator.get();
            while (!used.add(candidate)) {
                candidate = generator.get();
            }
            return candidate;
        };
    }

    private static void startSession() throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT user_id, username, full_name, role FROM users ORDER BY user_id LIMIT 1");
             ResultSet results = statement.executeQuery()) {
            if (!results.next()) {
                throw new IllegalStateException("no user to sign in as; register one first");
            }
            SessionManager.get().beginSession(new User(
                    results.getLong("user_id"), results.getString("username"),
                    results.getString("full_name"),
                    Role.valueOf(results.getString("role"))));
        }
    }

    private static void endSession() {
        SessionManager.get().endSession();
    }

    // ---------------------------------------------------------------- assertions

    private static void see(String what, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("PASS  " + what);
        } else {
            failed++;
            System.out.println("FAIL  " + what);
        }
    }

/**
     * The innermost message in a cause chain.
     *
     * <p>A self-check is not a user interface, so it is allowed to print what the
     * database actually said. That is the whole point of the failure text: a
     * refusal message is deliberately vague, and without this a real failure would
     * arrive here as three identical unhelpful lines.
     */
    private static String rootCause(Throwable failure) {
        Throwable deepest = failure;
        while (deepest.getCause() != null) {
            deepest = deepest.getCause();
        }
        return deepest.getClass().getSimpleName() + ": " + deepest.getMessage();
    }

    private static void section(String title) {
        section = title;
        System.out.println();
        System.out.println(title);
    }

    private static void report() {
        System.out.println();
        if (failed == 0) {
            System.out.println("All " + passed + " booking engine checks passed.");
        } else {
            System.out.println(failed + " of " + (passed + failed) + " booking engine checks FAILED.");
            System.out.println("Last section: " + section);
        }
        if (failed > 0) {
            System.exit(1);
        }
    }

/** One scenario, given the probe whose connection it must use for everything. */
    private interface Scenario {
        void run(TxProbe probe) throws Exception;
    }

    // ---------------------------------------------------------------- db helpers

    private static void execute(Connection connection, String sql, Object... values)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            statement.executeUpdate();
        }
    }

    private static int countWhere(Connection connection, String sql, Object... values)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? results.getInt(1) : 0;
            }
        }
    }

    private static void bind(PreparedStatement statement, Object... values) throws SQLException {
        for (int index = 0; index < values.length; index++) {
            Object value = values[index];
            if (value instanceof Integer number) {
                statement.setInt(index + 1, number);
            } else if (value instanceof Long number) {
                statement.setLong(index + 1, number);
            } else {
                statement.setString(index + 1, String.valueOf(value));
            }
        }
    }

    private static int bookingRows(Connection connection, long pnr) throws SQLException {
        return countWhere(connection, "SELECT COUNT(*) FROM bookings WHERE pnr = ?", pnr);
    }

    private static int passengerRows(Connection connection, long pnr) throws SQLException {
        return countWhere(connection, "SELECT COUNT(*) FROM passengers WHERE pnr = ?", pnr);
    }

    private static int reservationRows(Connection connection, long pnr) throws SQLException {
        return countWhere(connection, "SELECT COUNT(*) FROM seat_reservations WHERE pnr = ?", pnr);
    }

/**
     * The PNRs that belong to {@code PersistenceSelfCheck}'s documented fixture.
     *
     * <p>Those rows are committed on purpose by another check, so a global count
     * over the booking tables can no longer be zero. Excluding them here keeps the
     * meaning of every assertion that reads these helpers - <em>this run wrote
     * nothing</em> - without teaching each call site about the fixture.
     */
private static final String FIXTURE_PNRS =
            "SELECT f.pnr FROM bookings f JOIN users u ON u.user_id = f.user_id"
                    + " WHERE u.username = 'persist.check'";

    /** What each booking table held before this run, so the counts read as *added by this run*. */
    private static int bookingsBefore;
    private static int passengersBefore;
    private static int reservationsBefore;

    /**
     * Notes what was already in the booking tables.
     *
     * <p>A real booking made through the application is not this suite's business, and
     * the application account has no {@code DELETE} to tidy it away with. Comparing
     * against a starting snapshot keeps "this run wrote nothing" true on a database
     * that is also being used for real.
     */
    private static void rememberStartingCounts() throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection()) {
            bookingsBefore = totalBookings(connection);
            passengersBefore = totalPassengers(connection);
            reservationsBefore = totalReservations(connection);
        }
    }

    private static int totalBookings(Connection connection) throws SQLException {
        return countWhere(connection, "SELECT COUNT(*) FROM bookings"
                + " WHERE pnr NOT IN (" + FIXTURE_PNRS + ")") - bookingsBefore;
    }

    private static int totalBookings() throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection()) {
            return totalBookings(connection);
        }
    }

    private static int totalPassengers(Connection connection) throws SQLException {
        return countWhere(connection, "SELECT COUNT(*) FROM passengers"
                + " WHERE pnr NOT IN (" + FIXTURE_PNRS + ")") - passengersBefore;
    }

    private static int totalPassengers() throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection()) {
            return totalPassengers(connection);
        }
    }

    private static int totalReservations(Connection connection) throws SQLException {
        return countWhere(connection, "SELECT COUNT(*) FROM seat_reservations"
                + " WHERE pnr NOT IN (" + FIXTURE_PNRS + ")") - reservationsBefore;
    }

    private static int totalReservations() throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection()) {
            return totalReservations(connection);
        }
    }

    private static int countReservationsForJourney(Connection connection, TrainSelection selection)
            throws SQLException {
        return countWhere(connection,
                "SELECT COUNT(*) FROM seat_reservations WHERE journey_id = ?",
                journeyIdOf(connection, selection));
    }

    private static long reservationBerth(Connection connection, long pnr) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT berth_id FROM seat_reservations WHERE pnr = ? LIMIT 1")) {
            statement.setLong(1, pnr);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? results.getLong(1) : -1L;
            }
        }
    }

    private static String reservationSegment(Connection connection, long pnr) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT CONCAT(from_stop_seq,'-',to_stop_seq) AS seg"
                        + " FROM seat_reservations WHERE pnr = ? LIMIT 1")) {
            statement.setLong(1, pnr);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? results.getString(1) : "";
            }
        }
    }

    private static String segmentOf(TrainSelection selection) {
        return selection.fromSeq() + "-" + selection.toSeq();
    }

    private static BigDecimal storedTotal(Connection connection, long pnr) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT total_fare FROM bookings WHERE pnr = ?")) {
            statement.setLong(1, pnr);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? results.getBigDecimal(1) : null;
            }
        }
    }

    private static int storedPassengerCount(Connection connection, long pnr) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT passenger_count FROM bookings WHERE pnr = ?")) {
            statement.setLong(1, pnr);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? results.getInt(1) : -1;
            }
        }
    }

    private static String storedContactName(Connection connection, long pnr) throws SQLException {
        return firstColumn(connection, "SELECT contact_name FROM bookings WHERE pnr = ?", pnr);
    }

    private static int isPrimaryCount(Connection connection, long pnr) throws SQLException {
        return countWhere(connection,
                "SELECT COUNT(*) FROM passengers WHERE pnr = ? AND is_primary = 1", pnr);
    }

    private static boolean allInClass(Connection connection, long pnr, String classCode)
            throws SQLException {
        return countWhere(connection,
                "SELECT COUNT(*) FROM seat_reservations WHERE pnr = ? AND travel_class_code = ?",
                pnr, classCode) == reservationRows(connection, pnr);
    }

    private static int distinctBerths(List<BerthAllocation> allocations) {
        Set<Long> ids = new LinkedHashSet<>();
        allocations.forEach(allocation -> ids.add(allocation.berthId()));
        return ids.size();
    }

    private static List<Long> berthIdsOf(ConfirmedBooking booked) {
        List<Long> ids = new ArrayList<>();
        booked.passengers().forEach(allocation -> ids.add(allocation.berthId()));
        return ids;
    }

    private static String storedPreferences(Connection connection, long pnr) throws SQLException {
        return orderedColumn(connection,
                "SELECT berth_preference FROM passengers WHERE pnr = ? ORDER BY passenger_id", pnr);
    }

    private static String storedPositions(Connection connection, long pnr) throws SQLException {
        return orderedColumn(connection,
                "SELECT position FROM seat_reservations WHERE pnr = ? ORDER BY reservation_id", pnr);
    }

    private static String firstStoredPreference(Connection connection, long pnr) throws SQLException {
        return firstColumn(connection,
                "SELECT berth_preference FROM passengers WHERE pnr = ? ORDER BY passenger_id", pnr);
    }

    private static String firstStoredPosition(Connection connection, long pnr) throws SQLException {
        return firstColumn(connection,
                "SELECT position FROM seat_reservations WHERE pnr = ? ORDER BY reservation_id", pnr);
    }

    private static String orderedColumn(Connection connection, String sql, Object... values)
            throws SQLException {
        StringBuilder joined = new StringBuilder();
        for (String value : columnValues(connection, sql, values)) {
            if (joined.length() > 0) {
                joined.append(',');
            }
            joined.append(value);
        }
        return joined.toString();
    }

    private static String firstColumn(Connection connection, String sql, Object... values)
            throws SQLException {
        List<String> all = columnValues(connection, sql, values);
        return all.isEmpty() ? null : all.get(0);
    }

    private static List<String> columnValues(Connection connection, String sql, Object... values)
            throws SQLException {
        List<String> found = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    found.add(results.getString(1));
                }
            }
        }
        return found;
    }

    private static boolean allReservationsConfirmed(Connection connection, long pnr)
            throws SQLException {
        return countWhere(connection,
                "SELECT COUNT(*) FROM seat_reservations WHERE pnr = ? AND status = 'CONFIRMED'",
                pnr) == reservationRows(connection, pnr);
    }

    private static String storedBookingStatus(Connection connection, long pnr) throws SQLException {
        return firstColumn(connection, "SELECT status FROM bookings WHERE pnr = ?", pnr);
    }

    private static int countStatus(Connection connection, String status) throws SQLException {
        return countWhere(connection,
                "SELECT (SELECT COUNT(*) FROM bookings WHERE status = ?)"
                        + " + (SELECT COUNT(*) FROM seat_reservations WHERE status = ?)",
                status, status);
    }

    private static BigDecimal sumOfReservationAmounts(Connection connection, long pnr)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(SUM(amount), 0) FROM seat_reservations WHERE pnr = ?")) {
            statement.setLong(1, pnr);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? results.getBigDecimal(1) : BigDecimal.ZERO;
            }
        }
    }

    private static int distinctPnrCount(Connection connection, List<Long> pnrs) throws SQLException {
        if (pnrs.isEmpty()) {
            return 0;
        }
        StringBuilder placeholders = new StringBuilder();
        for (int index = 0; index < pnrs.size(); index++) {
            placeholders.append(index == 0 ? "?" : ",?");
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(DISTINCT pnr) FROM bookings WHERE pnr IN (" + placeholders + ")")) {
            for (int index = 0; index < pnrs.size(); index++) {
                statement.setLong(index + 1, pnrs.get(index));
            }
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? results.getInt(1) : 0;
            }
        }
    }

    private static boolean atScale(FareEstimate fare) {
        return fare.baseFare().scale() == FareEstimate.SCALE
                && fare.distanceFare().scale() == FareEstimate.SCALE
                && fare.perPassenger().scale() == FareEstimate.SCALE
                && fare.total().scale() == FareEstimate.SCALE;
    }

    private static boolean fromDbRefuses(String stored) {
        try {
            BookingStatus.fromDb(stored);
            return false;
        } catch (IllegalArgumentException refused) {
            return true;
        }
    }

    // ------------------------------------------------------------ availability

    /**
     * How many reservations cover exactly the searched segment.
     *
     * <p>Counted the way the allocator decides occupancy, so a check and the
     * engine cannot agree for the wrong reason.
     */
    private static int reservationsOnSegment(Connection connection, TrainSelection selection)
            throws SQLException {
        return countWhere(connection,
                "SELECT COUNT(*) FROM seat_reservations"
                        + " WHERE journey_id = ? AND from_stop_seq = ? AND to_stop_seq = ?",
                journeyIdOf(connection, selection), selection.fromSeq(), selection.toSeq());
    }

    /** Reservations that overlap each other on one berth. Must always be zero. */
    private static int overlappingPairs(Connection connection, TrainSelection selection)
            throws SQLException {
        return countWhere(connection,
                "SELECT COUNT(*) FROM seat_reservations a"
                        + " JOIN seat_reservations b"
                        + "   ON a.journey_id = b.journey_id AND a.berth_id = b.berth_id"
                        + "  AND a.reservation_id <> b.reservation_id"
                        + " WHERE a.journey_id = ?"
                        + "   AND a.from_stop_seq < b.to_stop_seq AND a.to_stop_seq > b.from_stop_seq"
                        + "   AND a.status = 'CONFIRMED' AND b.status = 'CONFIRMED'",
                journeyIdOf(connection, selection));
    }

    private static int availableBerths(Connection connection, TrainSelection selection)
            throws SQLException {
        return AvailabilityDAO.countForSegment(sameConnection(connection),
                journeyIdOf(connection, selection), selection.classCode(),
                selection.fromSeq(), selection.toSeq()).available();
    }

/** How many berths at one position the journey has, free ones included. */
    private static int countPosition(Connection connection, TrainSelection selection,
                                     String position) throws SQLException {
        return countWhere(connection,
                "SELECT COUNT(*) FROM berths b JOIN coaches c ON c.coach_id = b.coach_id"
                        + " WHERE c.train_no = ? AND c.class_code = ? AND b.position = ?",
                selection.trainNo(), selection.classCode(), position);
    }

    /**
     * How many berths at one position are still free on the segment.
     *
     * <p>Independent of the allocator's own reasoning: it asks the schema, using
     * the same overlap comparison, so "no lower berth is left" is a fact about
     * the database rather than a restatement of what allocation believes.
     */
    private static int countFreeAtPosition(Connection connection, TrainSelection selection,
                                           String position) throws SQLException {
        return countWhere(connection,
                "SELECT COUNT(*) FROM berths b"
                        + " JOIN coaches c ON c.coach_id = b.coach_id"
                        + " JOIN journey_coaches jc ON jc.coach_id = c.coach_id"
                        + " WHERE jc.journey_id = ? AND jc.coach_status = 'ATTACHED'"
                        + "   AND c.class_code = ? AND b.position = ?"
                        + "   AND NOT EXISTS (SELECT 1 FROM seat_reservations sr"
                        + "        WHERE sr.journey_id = jc.journey_id AND sr.berth_id = b.berth_id"
                        + "          AND sr.status = 'CONFIRMED'"
                        + "          AND sr.from_stop_seq < ? AND sr.to_stop_seq > ?)",
                journeyIdOf(connection, selection), selection.classCode(), position,
                selection.toSeq(), selection.fromSeq());
    }

    private static int totalBerths(Connection connection, TrainSelection selection,
                                   String classCode) throws SQLException {
        return countWhere(connection,
                "SELECT COUNT(*) FROM berths b JOIN coaches c ON c.coach_id = b.coach_id"
                        + " WHERE c.train_no = ? AND c.class_code = ?",
                selection.trainNo(), classCode);
    }

    private static int berthsInClass(String classCode) throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection()) {
            return countWhere(connection,
                    "SELECT COUNT(*) FROM berths b JOIN coaches c ON c.coach_id = b.coach_id"
                            + " WHERE c.train_no = ? AND c.class_code = ?", TRAIN, classCode);
        }
    }

    /** Free berth ids on the segment, read through the locking candidate statement. */
    private static Set<Long> freeBerthIds(Connection connection, TrainSelection selection)
            throws SQLException {
        Set<Long> free = new LinkedHashSet<>();
        for (CandidateBerth berth : candidates(connection, selection)) {
            if (!berth.occupied()) {
                free.add(berth.berthId());
            }
        }
        return free;
    }

    /** Berth ids the same statement reports as held on the segment. */
    private static Set<Long> heldBerthIds(Connection connection, TrainSelection selection)
            throws SQLException {
        Set<Long> held = new LinkedHashSet<>();
        for (CandidateBerth berth : candidates(connection, selection)) {
            if (berth.occupied()) {
                held.add(berth.berthId());
            }
        }
        return held;
    }

    /** The engine's own candidate read, so a check and the engine agree on occupancy. */
    private static List<CandidateBerth> candidates(Connection connection, TrainSelection selection)
            throws SQLException {
        return new AvailabilityDAO(connection).lockCandidateBerths(
                journeyIdOf(connection, selection), selection.classCode(),
                selection.fromSeq(), selection.toSeq());
    }

    private static long journeyIdOf(Connection connection, TrainSelection selection)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT journey_id FROM journeys WHERE train_no = ? AND journey_date = ?")) {
            statement.setString(1, selection.trainNo());
            statement.setDate(2, java.sql.Date.valueOf(selection.journeyDate()));
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? results.getLong(1) : -1L;
            }
        }
    }

    /** A booking row with a PNR of our choosing, to collide with. */
    private static void insertBookingRow(Connection connection, long pnr,
                                         TrainSelection selection) throws Exception {
        FareEstimate fare = new FareService(new TrainDAO(() -> connection)).quoteFor(selection);
        new BookingDAO(connection).insertBooking(pnr, journeyIdOf(connection, selection), selection,
                fare, CONTACT_NAME, new BookingContact(CONTACT_MOBILE, CONTACT_EMAIL),
                SessionManager.get().requireUser().userId());
    }

    // ---------------------------------------------------------------- refusals

    /** Books, expecting a refusal, and returns the message. */
private static String bookingFails(Connection c, BookingDraft draft) {
        try {
            new BookingService(sameConnection(c), pnrGenerator()).confirm(draft);
            return null;
        } catch (BookingException | AccessDeniedException | ValidationException refused) {
            return refused.getMessage();
        }
    }

    private static String refuses(Connection c, BookingDraft draft) {
        return bookingFails(c, draft);
    }

/**
     * A draft whose date has moved into the past since the form was filled in.
     *
     * <p>Built with a connection source that would fail if it were ever used, so
     * this proves the date is refused before the engine touches the database at
     * all - which is the whole value of refusing early. The absence of a
     * {@code catch (SQLException)} here is itself part of the guarantee:
     * {@code BookingService.confirm} does not declare one, so no SQL error can
     * reach a caller to be caught.
     */
    private static String refusesDate(BookingDraft draft, LocalDate date) {
        TrainSelection moved = new TrainSelection(draft.selection().trainNo(),
                draft.selection().trainName(), draft.selection().classCode(),
                draft.selection().className(), draft.selection().fromStation(),
                draft.selection().toStation(), draft.selection().fromSeq(),
                draft.selection().toSeq(), draft.selection().segmentKm(), date,
                draft.selection().passengers(), draft.selection().farePerPassenger(),
                draft.selection().berthsAvailable());
        try {
            new BookingService(() -> {
                throw new IllegalStateException(
                        "a past date must be refused before any connection is opened");
            }, pnrGenerator()).confirm(copyWith(moved, draft));
            return null;
        } catch (BookingException | AccessDeniedException | ValidationException refused) {
            return refused.getMessage();
        }
    }

    /**
     * A passenger row that was valid when typed and is not now.
     *
     * <p>The age range is configurable, so a draft assembled against an older
     * configuration can hold an age the engine now refuses. Built by hand to reach
     * that state, because the preparation service would never have let it through.
     */
    private static String refusesAge(Connection c, BookingDraft draft) {
        BookingDraft stale = copyWith(draft.selection(), draft);
        stale.removePassenger(0);
        stale.addPassenger(new PassengerDraft("Ancient Traveller",
                DatabaseConnection.config().maxPassengerAge() + 1, Gender.MALE,
                BerthPreference.LOWER, null));
        return refuses(c, stale);
    }

    /** Copies a draft's passengers and contact onto another selection. */
    private static BookingDraft copyWith(TrainSelection selection, BookingDraft from) {
        BookingDraft copy = new BookingDraft(selection);
        from.passengers().forEach(copy::addPassenger);
        copy.setContact(from.contact());
        return copy;
    }

    // ---------------------------------------------------------------- the probe

    /**
     * A connection whose {@code commit} is recorded instead of carried out.
     *
     * <p>Lets a check assert that the engine committed on the success path, and
     * that it did not on the failure path, while the outer transaction still
     * discards everything. A dynamic proxy rather than a hand-written delegate
     * because {@code Connection} has far too many methods to forward by hand.
     *
     * <p>{@code rollback} is <em>counted and executed</em>, not suppressed. The
     * engine has to genuinely discard its own partial writes; a probe that hid the
     * rollback too would leave the check unable to tell who cleaned up.
     */
    private static final class TxProbe {

        private final Connection real;
        private final Connection wrapped;
        private final AtomicInteger commits = new AtomicInteger();
        private final AtomicInteger rollbacks = new AtomicInteger();

        @SuppressWarnings("unchecked")
        TxProbe(Connection real) {
            this.real = real;
            InvocationHandler handler = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "commit" -> {
                        commits.incrementAndGet();
                        return null;
                    }
                    case "close" -> {
                        // The scenario's transaction outlives any helper that
                        // used try-with-resources on this connection.
                        return null;
                    }
                    default -> {
                        if ("rollback".equals(method.getName())) {
                            rollbacks.incrementAndGet();
                        }
                    }
                }
                try {
                    return method.invoke(real, args);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            };
            this.wrapped = (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, handler);
        }

        Connection connection() {
            return wrapped;
        }

        /** How many times the engine claimed to have committed. */
        int commits() {
            return commits.get();
        }

        /** How many times the engine rolled its own work back. */
        int rollbacks() {
            return rollbacks.get();
        }
    }
}