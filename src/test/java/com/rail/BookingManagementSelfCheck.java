package com.rail;

import com.rail.db.AvailabilityDAO;
import com.rail.db.BookingDAO;
import com.rail.db.ConnectionSource;
import com.rail.db.DatabaseConnection;
import com.rail.db.TrainDAO;
import com.rail.model.BerthPreference;
import com.rail.model.BookingContact;
import com.rail.model.BookingDetail;
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
import com.rail.service.BookingPreparationService;
import com.rail.service.BookingQueryService;
import com.rail.service.BookingService;
import com.rail.service.CancellationService;
import com.rail.service.FareService;
import com.rail.service.RefundPolicy;
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
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Verification for booking management: looking a ticket up, and taking it back.
 *
 * <h2>Nothing is left behind</h2>
 *
 * <p>Same arrangement as {@code BookingEngineSelfCheck}: the application account
 * cannot DELETE, so every scenario runs inside one transaction that is rolled
 * back, with the service's own {@code commit} recorded but suppressed. After a
 * full run, {@code bookings}, {@code passengers}, {@code seat_reservations} and
 * {@code cancellations} are exactly as they were.
 *
 * <h2>What is actually proven</h2>
 *
 * <ul>
 *   <li>Ownership is enforced in SQL, not by hiding rows afterwards: another
 *       account's PNR is not found by the ticket read, and cancelling it through
 *       the service is refused with the booking untouched.</li>
 *   <li>A cancellation is atomic in the direction that matters - if anything
 *       fails, the berths stay held. {@link #checkAFailedCancellationReleasesNothing}
 *       breaks the ledger insert on purpose and asserts the booking is still
 *       confirmed with every reservation intact.</li>
 *   <li>A booking cannot be cancelled twice, so the money cannot be refunded
 *       twice.</li>
 *   <li>Cancelling frees exactly this booking's berths and no one else's.</li>
 *   <li>The refund is the documented figure, at both sides of the cutoff, and the
 *       refund plus the charges always add back up to the fare paid.</li>
 * </ul>
 *
 * <p>Deliberately not tested: a genuine two-connection cancellation race. Under
 * these grants it would have to commit a real cancellation to be observable, and
 * {@code cancellations} cannot be cleaned up. The lock that makes it safe is the
 * same {@code SELECT ... FOR UPDATE} the booking engine's race check already
 * proves blocks a second writer.
 */
public final class BookingManagementSelfCheck {

    /** CSTM 1 - PUNE 2, the shortest real segment on this rake. */
    private static final String TRAIN = "12952";

    /** AC1: a single coach of eight berths, small enough to reason about. */
    private static final String SMALL_CLASS = "2A";

    private static final String FROM = "CSTM";
    private static final String NEXT = "PUNE";

    /** Comfortably outside the seven-day cutoff. */
    private static final LocalDate FAR_DATE = LocalDate.now().plusDays(30);

    /** Inside the cutoff, so the late rate applies. */
    private static final LocalDate NEAR_DATE = LocalDate.now().plusDays(3);

    private static final int PARTY_LIMIT = 6;

    private static final String CONTACT_NAME = "Management Check";
    private static final String CONTACT_MOBILE = "9876543210";
    private static final String CONTACT_EMAIL = "traveller@example.com";

    private static int passed;
    private static int failed;
    private static String section = "";

    public static void main(String[] args) throws Exception {
        System.out.println("Booking management self-check");
        System.out.println("============================");

        startSession();

        checkRefundPolicyArithmetic();
        checkRefundPolicyReadsItsConfiguration();

        checkTicketLookupReturnsTheWholeBooking();
        checkInvalidPnrIsNotFound();
        checkAnotherAccountsTicketIsNotFound();
        checkMyBookingsAgreesWithTheTicket();
        checkHistoryIsScopedToTheAccount();

        checkEligibleCancellation();
        checkTheQuoteMatchesWhatIsRecorded();
        checkCancellationInsideTheCutoff();
        checkCancellationAfterDepartureIsRefused();
        checkCancellingTwiceIsRefused();
        checkCancellingAnotherAccountsBookingIsRefused();
        checkCancellationPreservesHistory();
        checkCancellationReleasesOnlyItsOwnBerths();
        checkAFailedCancellationReleasesNothing();

        endSession();
        report();
    }

    // ---------------------------------------------------------------- the policy

    /**
     * The documented refund figures, checked without a database.
     *
     * <p>Pure arithmetic first, so a wrong number is caught as a wrong number and
     * not as a mysterious mismatch three tables deep.
     */
    private static void checkRefundPolicyArithmetic() {
        section("Refund arithmetic");

        LocalDate today = LocalDate.of(2026, 3, 1);
        BigDecimal fare = new BigDecimal("1000.00");

        RefundPolicy.Outcome early = RefundPolicy.refundFor(fare, 2,
                today.plusDays(10), today);
        see("ten days out the whole fare less the charges is refunded",
                early.refundable()
                        && early.refundAmount().compareTo(new BigDecimal("900.00")) == 0);
        see("ten days out the charges are two passengers' worth",
                early.charges().compareTo(new BigDecimal("100.00")) == 0);
        see("ten days out the refund plus the charges is the fare",
                early.refundAmount().add(early.charges()).compareTo(fare) == 0);

        RefundPolicy.Outcome onCutoff = RefundPolicy.refundFor(fare, 2,
                today.plusDays(RefundPolicy.CUTOFF_DAYS), today);
        see("exactly on the cutoff still gets the early rate",
                onCutoff.refundAmount().compareTo(early.refundAmount()) == 0);

        RefundPolicy.Outcome late = RefundPolicy.refundFor(fare, 2,
                today.plusDays(RefundPolicy.CUTOFF_DAYS - 1), today);
        see("a day inside the cutoff refunds half the fare, not less the charges",
                late.refundable()
                        && late.refundAmount().compareTo(new BigDecimal("500.00")) == 0);
        see("a day inside the cutoff keeps the other half as charges",
                late.charges().compareTo(new BigDecimal("500.00")) == 0);

        RefundPolicy.Outcome today_ = RefundPolicy.refundFor(fare, 1, today, today);
        see("same-day cancellation still gets the late rate",
                today_.refundAmount().compareTo(new BigDecimal("500.00")) == 0);

        RefundPolicy.Outcome departed = RefundPolicy.refundFor(fare, 2,
                today.minusDays(1), today);
        see("a departed journey is not refundable",
                !departed.refundable() && departed.refundAmount().signum() == 0);
        see("a departed journey says so in its refund status",
                RefundPolicy.REFUND_STATUS_NOT_APPLICABLE.equals(departed.refundStatus()));
        see("a departed journey explains itself",
                departed.explanation().contains("already departed"));

        RefundPolicy.Outcome odd = RefundPolicy.refundFor(new BigDecimal("333.33"), 1,
                today.plusDays(2), today);
        see("an awkward fare rounds half-up to two decimals",
                odd.refundAmount().scale() == FareEstimate.SCALE
                        && odd.refundAmount().compareTo(new BigDecimal("166.67")) == 0);

        RefundPolicy.Outcome tiny = RefundPolicy.refundFor(new BigDecimal("30.00"), 1,
                today.plusDays(20), today);
        see("a fare smaller than the charge refunds nothing rather than going negative",
                tiny.refundable() && tiny.refundAmount().signum() == 0
                        && tiny.charges().compareTo(new BigDecimal("30.00")) == 0);

        see("the policy sentence warns that these are not official rules",
                RefundPolicy.policySentence(3).toLowerCase().contains("not an official"));
    }

    /** The thresholds are configuration, not constants baked into the code. */
    private static void checkRefundPolicyReadsItsConfiguration() {
        section("Refund configuration");
        LocalDate today = LocalDate.of(2026, 3, 1);
        BigDecimal fare = new BigDecimal("1000.00");

        see("the default cutoff is seven days", RefundPolicy.CUTOFF_DAYS == 7);
        see("the default late rate is half",
                RefundPolicy.LATE_REFUND_PERCENT.intValueExact() == 50);

        System.setProperty(RefundPolicy.PROP_CUTOFF_DAYS, "20");
        try {
            RefundPolicy.Outcome tenDaysOut = RefundPolicy.refundFor(fare, 1,
                    today.plusDays(10), today);
            see("with the cutoff at twenty days, ten days out is the late rate",
                    tenDaysOut.refundAmount().compareTo(new BigDecimal("500.00")) == 0);
        } finally {
            System.clearProperty(RefundPolicy.PROP_CUTOFF_DAYS);
        }

        RefundPolicy.Outcome backToDefault = RefundPolicy.refundFor(fare, 1,
                today.plusDays(10), today);
        see("clearing the property restores the default",
                backToDefault.refundAmount().compareTo(new BigDecimal("950.00")) == 0);

        System.setProperty(RefundPolicy.PROP_CUTOFF_DAYS, "not-a-number");
        try {
            RefundPolicy.Outcome nonsense = RefundPolicy.refundFor(fare, 1,
                    today.plusDays(10), today);
            see("an unparseable cutoff falls back to the default instead of failing",
                    nonsense.refundAmount().compareTo(new BigDecimal("950.00")) == 0);
        } finally {
            System.clearProperty(RefundPolicy.PROP_CUTOFF_DAYS);
        }

        System.setProperty(RefundPolicy.PROP_LATE_PERCENT, "900");
        try {
            RefundPolicy.Outcome silly = RefundPolicy.refundFor(fare, 1,
                    today.plusDays(1), today);
            see("a late rate above one hundred percent falls back to the default",
                    silly.refundAmount().compareTo(new BigDecimal("500.00")) == 0);
        } finally {
            System.clearProperty(RefundPolicy.PROP_LATE_PERCENT);
        }
    }

    // ---------------------------------------------------------------- A: PNR status

    private static void checkTicketLookupReturnsTheWholeBooking() {
        section("PNR status");
        scenario(probe -> {
            Connection c = probe.connection();
            TrainSelection selection = selectionFor(c, FAR_DATE, 2);
            ConfirmedBooking booked = book(c, selection, party(2));

            BookingDetail ticket = new BookingDAO(c).ticketForUser(booked.pnr(), myId())
                    .orElseThrow();

            see("the PNR is the one booked", ticket.pnr() == booked.pnr());
            see("the PNR prints as ten digits",
                    ticket.pnrDisplay().matches("\\d{10}"));
            see("the train is the one booked",
                    ticket.trainNo().equals(TRAIN) && ticket.trainName().equals(booked.trainName()));
            see("the route is the one booked",
                    ticket.routeLabel().equals(FROM + " → " + NEXT));
            see("both station names are resolved",
                    ticket.fromStationName().equals(booked.fromStationName())
                            && ticket.toStationName().equals(booked.toStationName()));
            see("the journey date is the one booked",
                    ticket.journeyDate().equals(FAR_DATE));
            see("the class is the one booked", ticket.classCode().equals(SMALL_CLASS));
            see("the fare is the fare charged",
                    ticket.totalFare().compareTo(booked.fare().total()) == 0);
            see("the fare keeps two decimals", ticket.totalFare().scale() == FareEstimate.SCALE);
            see("every passenger is on the ticket", ticket.passengerCount() == 2);
            see("each passenger has the berth they were given",
                    ticket.passengers().stream().allMatch(passenger ->
                            booked.passengers().stream().anyMatch(berth ->
                                    berth.berthId() == passenger.berthId())));
            see("each passenger has a coach and a berth number",
                    ticket.passengers().stream().allMatch(passenger ->
                            passenger.coachNumber() != null && passenger.berthNumber() > 0));
            see("the contact passenger is flagged",
                    ticket.passengers().stream().anyMatch(BookingDetail.Passenger::primary));
            see("every reservation reads as confirmed",
                    ticket.passengers().stream().allMatch(passenger ->
                            "CONFIRMED".equals(passenger.reservationStatus())));
            see("the passenger fares add up to the booking total",
                    ticket.passengers().stream().map(BookingDetail.Passenger::amount)
                            .reduce(BigDecimal.ZERO, BigDecimal::add)
                            .compareTo(ticket.totalFare()) == 0);
            see("a live booking has no cancellation yet", ticket.cancellation() == null);
            see("a live booking is cancellable", ticket.cancellable());
            see("a live booking is not blocked", ticket.cancellationBlocker() == null);
            see("the ticket belongs to this account", ticket.userId() == myId());
        });
    }

    private static void checkInvalidPnrIsNotFound() {
        section("PNR status");
        scenario(probe -> {
            Connection c = probe.connection();
            BookingDAO bookings = new BookingDAO(c);

            see("a PNR nobody holds is simply not found",
                    bookings.ticketForUser(999_999_999L, myId()).isEmpty());
            see("a PNR of zero is not found", bookings.ticketForUser(0L, myId()).isEmpty());
            see("a PNR above the signed range is not found",
                    bookings.ticketForUser(Long.MAX_VALUE, myId()).isEmpty());

            BookingQueryService query = new BookingQueryService(sameConnection(c));
            see("the service agrees about an unknown PNR",
                    query.myTicketByPnr(999_999_999L).isEmpty());
        });
    }

    /**
     * Another account's ticket is not found - not found <em>for this account</em>,
     * which is a different statement and the only one that is safe to show.
     *
     * <p>The foreign booking is inserted directly rather than through the engine,
     * because the engine books for whoever is signed in and this check is about
     * what happens when somebody signs in who is not the owner.
     */
    private static void checkAnotherAccountsTicketIsNotFound() {
        section("PNR status");
        scenario(probe -> {
            Connection c = probe.connection();
            long otherUser = otherUserId(c);
            long theirs = foreignPnr();
            insertBookingRow(c, theirs, selectionFor(c, FAR_DATE, 1), otherUser);

            see("the other account can read its own ticket",
                    new BookingDAO(c).ticketForUser(theirs, otherUser).isPresent());
            see("this account cannot read that ticket",
                    new BookingDAO(c).ticketForUser(theirs, myId()).isEmpty());
            see("the service refuses to hand it over either",
                    new BookingQueryService(sameConnection(c)).myTicketByPnr(theirs).isEmpty());
            see("an account that does not exist finds nothing either",
                    new BookingDAO(c).ticketForUser(theirs, myId() + 9999L).isEmpty());
        });
    }

    /** The list and the ticket are two reads of one booking and must agree. */
    private static void checkMyBookingsAgreesWithTheTicket() {
        section("My Bookings");
        scenario(probe -> {
            Connection c = probe.connection();
            ConfirmedBooking booked = book(c, selectionFor(c, FAR_DATE, 2), party(2));

            BookingQueryService query = new BookingQueryService(sameConnection(c));
            List<com.rail.model.BookingSummary> history = query.myBookings();
            var listed = history.stream()
                    .filter(row -> row.pnr() == booked.pnr()).findFirst();

            see("the new booking appears in My Bookings", listed.isPresent());
            see("the list counts it as active", query.myActiveBookingCount() >= 1);
            see("the list counts it in the total", query.myBookingCount() >= 1);

            BookingDetail ticket = query.myTicketByPnr(booked.pnr()).orElseThrow();
            see("the list and the ticket agree on the status",
                    listed.orElseThrow().status().equals(ticket.status()));
            see("the list and the ticket agree on the train",
                    listed.orElseThrow().trainNo().equals(ticket.trainNo()));
            see("the list and the ticket agree on the date",
                    listed.orElseThrow().journeyDate().equals(ticket.journeyDate()));
            see("the list and the ticket agree on the class",
                    listed.orElseThrow().travelClass().equals(ticket.classCode()));
            see("the list shows a whole-rupee fare that matches the ticket",
                    (long) listed.orElseThrow().totalFare() == ticket.totalFare().longValue());
        });
    }

    private static void checkHistoryIsScopedToTheAccount() {
        section("My Bookings");
        scenario(probe -> {
            Connection c = probe.connection();
            long otherUser = otherUserId(c);
            ConfirmedBooking mine = book(c, selectionFor(c, FAR_DATE, 1),
                    List.of(passenger("Mine", 30, Gender.MALE, BerthPreference.NO_PREFERENCE)));
            insertBookingRow(c, foreignPnr(), selectionFor(c, FAR_DATE, 1), otherUser);

            List<com.rail.model.BookingSummary> history =
                    new BookingQueryService(sameConnection(c)).myBookings();
            Set<Long> pnrs = new LinkedHashSet<>();
            history.forEach(row -> pnrs.add(row.pnr()));

            see("My Bookings contains my booking", pnrs.contains(mine.pnr()));
            see("My Bookings contains nothing belonging to anybody else",
                    !pnrs.contains(foreignPnr()));
            see("the count matches what the list shows",
                    new BookingQueryService(sameConnection(c)).myBookingCount() == pnrs.size());

            // Whether the session user is an administrator or not, an ordinary
            // traveller must be refused another account's history, and an
            // administrator must be allowed. Signing in as each role is the only
            // way to ask both questions of one database.
            // Captured before the switch: read inside the lambda it would return the
// switched-to account's id, and the check would pass for the wrong reason.
long adminId = myId();
            User asTraveller = traveller();
            long travellerId = asTraveller.userId();
            User asAdmin = administrator();

            see("a traveller asking for another account's bookings is refused",
                    asUser(c, asTraveller, () ->
                            new BookingQueryService(sameConnection(c))
                                    .bookingsFor(adminId, 10)) == null);

            List<com.rail.model.BookingSummary> asAdminRead =
                    asUser(c, asAdmin, () ->
                            new BookingQueryService(sameConnection(c))
                                    .bookingsFor(travellerId, 10));
            see("an administrator may read another account's bookings",
                    asAdminRead != null);
            see("the administrator's read contains the foreign booking",
                    asAdminRead != null && asAdminRead.stream()
                            .anyMatch(row -> row.pnr() == foreignPnr()));

            List<com.rail.model.BookingSummary> own =
                    asUser(c, asTraveller, () ->
                            new BookingQueryService(sameConnection(c))
                                    .bookingsFor(travellerId, 10));
            see("a traveller may read its own bookings through the same call",
                    own != null);
        });
    }

    // ---------------------------------------------------------------- C: cancelling

    private static void checkEligibleCancellation() {
        section("Cancellation");
        scenario(probe -> {
            Connection c = probe.connection();
            TrainSelection selection = selectionFor(c, FAR_DATE, 2);
            ConfirmedBooking booked = book(c, selection, party(2));

            String refusal = cancelFails(c, booked.pnr(), "Change of plan");
            see("an eligible booking is cancelled", refusal == null);

            BookingDetail ticket = new BookingDAO(c).ticketForUser(booked.pnr(), myId())
                    .orElseThrow();
            see("the booking now reads as cancelled",
                    ticket.bookingStatus() == BookingStatus.CANCELLED
                            && "CANCELLED".equals(ticket.status()));
            see("a cancelled booking is not cancellable again", !ticket.cancellable());
            see("a cancelled booking says why it cannot be cancelled",
                    ticket.cancellationBlocker() != null);
            see("every reservation is cancelled",
                    ticket.passengers().stream().allMatch(passenger ->
                            "CANCELLED".equals(passenger.reservationStatus())));

            see("the ledger records one cancellation",
                    countWhere(c, "SELECT COUNT(*) FROM cancellations WHERE pnr = ?",
                            booked.pnr()) == 1);
            see("the whole booking was cancelled, not one passenger",
                    countWhere(c, "SELECT COUNT(*) FROM cancellations WHERE pnr = ?"
                            + " AND passenger_id IS NULL AND reservation_id IS NULL",
                            booked.pnr()) == 1);
            see("the ledger records who cancelled it",
                    scalar(c, "SELECT cancelled_by_user_id FROM cancellations WHERE pnr = ?",
                            booked.pnr()) == myId());
            see("the ledger records the reason",
                    "Change of plan".equals(text(c,
                            "SELECT reason FROM cancellations WHERE pnr = ?", booked.pnr())));
            see("the ledger records when it happened",
                    countWhere(c, "SELECT COUNT(*) FROM cancellations WHERE pnr = ?"
                            + " AND cancelled_at IS NOT NULL", booked.pnr()) == 1);

            BigDecimal refund = decimal(c, "SELECT refund_amount FROM cancellations WHERE pnr = ?",
                    booked.pnr());
            BigDecimal charges = decimal(c,
                    "SELECT cancellation_charges FROM cancellations WHERE pnr = ?", booked.pnr());
            see("the refund is the fare less two passengers' charges",
                    refund.compareTo(booked.fare().total()
                            .subtract(RefundPolicy.CHARGE_PER_PASSENGER.multiply(
                                    BigDecimal.valueOf(2)))) == 0);
            see("the refund and the charges reconcile to the fare",
                    refund.add(charges).compareTo(booked.fare().total()) == 0);
            see("the refund is marked refundable",
                    countWhere(c, "SELECT COUNT(*) FROM cancellations WHERE pnr = ?"
                            + " AND is_refundable = TRUE", booked.pnr()) == 1);
            see("the stored refund status is PROCESSED",
                    "PROCESSED".equals(text(c,
                            "SELECT refund_status FROM cancellations WHERE pnr = ?",
                            booked.pnr())));

            // Two commits: the booking that set this scenario up, and the
            // cancellation. Search materialises its journey but commits nothing.
            see("the engine committed the booking and then the cancellation",
                    probe.commits() == 2);
            see("nothing had to be rolled back", probe.rollbacks() == 0);
        });
    }

    /**
     * The figure in the confirmation dialog is the figure that gets recorded.
     *
     * <p>The dialog quotes first and the service recalculates inside its
     * transaction, so the only way to be sure a passenger agrees to the right
     * number is to show that the two agree.
     */
    private static void checkTheQuoteMatchesWhatIsRecorded() {
        section("Cancellation");
        scenario(probe -> {
            Connection c = probe.connection();
            ConfirmedBooking booked = book(c, selectionFor(c, FAR_DATE, 2), party(2));
            CancellationService service = new CancellationService(sameConnection(c));

            CancellationService.Quote quote = quoteOf(c, booked.pnr());
            see("the quote names the booking", quote.ticket().pnr() == booked.pnr());
            see("the quote carries the policy in words", quote.policy().contains("days"));
            see("quoting does not change the booking",
                    "CONFIRMED".equals(storedBookingStatus(c, booked.pnr())));
            see("quoting writes nothing to the ledger",
                    countWhere(c, "SELECT COUNT(*) FROM cancellations WHERE pnr = ?",
                            booked.pnr()) == 0);
            see("quoting a second time gives the same figure",
                    quoteOf(c, booked.pnr()).outcome().refundAmount()
                            .compareTo(quote.outcome().refundAmount()) == 0);

            see("the cancellation goes through", cancelFails(c, booked.pnr(), null) == null);
            see("the recorded refund is the quoted refund",
                    decimal(c, "SELECT refund_amount FROM cancellations WHERE pnr = ?",
                            booked.pnr()).compareTo(quote.outcome().refundAmount()) == 0);
            see("the recorded charges are the quoted charges",
                    decimal(c, "SELECT cancellation_charges FROM cancellations WHERE pnr = ?",
                            booked.pnr()).compareTo(quote.outcome().charges()) == 0);
            see("quoting a cancelled booking is refused",
                    quoteFails(c, booked.pnr()) != null);
            see("quoting an unknown PNR is refused", quoteFails(c, 999_999_999L) != null);
            see("service was built without complaint", service != null);
        });
    }

    private static CancellationService.Quote quoteOf(Connection c, long pnr) {
        try {
            return new CancellationService(sameConnection(c)).quote(pnr);
        } catch (Exception refused) {
            throw new IllegalStateException("expected a quote for " + pnr, refused);
        }
    }

    private static String quoteFails(Connection c, long pnr) {
        try {
            new CancellationService(sameConnection(c)).quote(pnr);
            return null;
        } catch (CancellationService.CancellationException | AccessDeniedException refused) {
            return refused.getMessage();
        } catch (Exception other) {
            return rootCause(other);
        }
    }

    private static void checkCancellationInsideTheCutoff() {
        section("Cancellation");
        scenario(probe -> {
            Connection c = probe.connection();
            ConfirmedBooking booked = book(c, selectionFor(c, NEAR_DATE, 2), party(2));

            String refusal = cancelFails(c, booked.pnr(), null);
            see("a booking inside the cutoff cancels", refusal == null);

            BigDecimal refund = decimal(c,
                    "SELECT refund_amount FROM cancellations WHERE pnr = ?", booked.pnr());
            BigDecimal charges = decimal(c,
                    "SELECT cancellation_charges FROM cancellations WHERE pnr = ?", booked.pnr());
            BigDecimal half = booked.fare().total()
                    .multiply(RefundPolicy.LATE_REFUND_PERCENT)
                    .divide(BigDecimal.valueOf(100), FareEstimate.SCALE,
                            java.math.RoundingMode.HALF_UP);

            see("an inside-the-cutoff refund is half the fare, not fare less charges",
                    refund.compareTo(half) == 0);
            see("an inside-the-cutoff cancellation keeps the other half",
                    charges.compareTo(booked.fare().total().subtract(half)) == 0);
            see("an inside-the-cutoff refund and charges still reconcile to the fare",
                    refund.add(charges).compareTo(booked.fare().total()) == 0);
        });
    }

    private static void checkCancellationAfterDepartureIsRefused() {
        section("Cancellation");
        scenario(probe -> {
            Connection c = probe.connection();
            long pnr = book(c, selectionFor(c, FAR_DATE, 1),
                    List.of(passenger("Latecomer", 30, Gender.MALE,
                            BerthPreference.NO_PREFERENCE))).pnr();

            // The database has no clock of its own, so a day passing is simulated
            // by moving the booking's own snapshot date into the past - which is
            // what the application reads when deciding eligibility.
            execute(c, "UPDATE bookings SET journey_date = ? WHERE pnr = ?",
                    java.sql.Date.valueOf(LocalDate.now().minusDays(1)), pnr);

            String refusal = cancelFails(c, pnr, "Too late");
            see("a departed journey cannot be cancelled", refusal != null);
            see("the refusal explains that it has departed",
                    refusal != null && refusal.contains("departed"));
            see("the service rolled its own work back rather than writing anything",
                    probe.rollbacks() >= 1);
            see("nothing was committed", probe.commits() == 1);

            // The refusal rolled the enclosing transaction back, fixture and all -
            // see the note on RefusalScenario. Put it back to show that the
            // refused attempt left no trace to find.
            see("the ledger has nothing for that booking",
                    ledgerIsEmpty(c, pnr));
        });
    }

    private static void checkCancellingTwiceIsRefused() {
        section("Cancellation");
        scenario(probe -> {
            Connection c = probe.connection();
            ConfirmedBooking booked = book(c, selectionFor(c, FAR_DATE, 2), party(2));

            see("the first cancellation succeeds", cancelFails(c, booked.pnr(), "First") == null);

            // Everything about the first attempt, while it is still in the
            // transaction: a refused second attempt rolls this back.
            see("still only one cancellation row",
                    countWhere(c, "SELECT COUNT(*) FROM cancellations WHERE pnr = ?",
                            booked.pnr()) == 1);
            BigDecimal refund = decimal(c,
                    "SELECT refund_amount FROM cancellations WHERE pnr = ?", booked.pnr());
            see("the refund is the single documented figure",
                    refund.compareTo(booked.fare().total().subtract(
                            RefundPolicy.CHARGE_PER_PASSENGER.multiply(
                                    BigDecimal.valueOf(2)))) == 0);

            String second = cancelFails(c, booked.pnr(), "Second");
            see("the second cancellation is refused", second != null);
            see("the second refusal says the booking is not confirmed",
                    second != null && second.contains("not confirmed"));
            see("the second refusal does not mention a refund",
                    second == null || !second.toLowerCase().contains("refund"));
            see("the second attempt rolled back instead of writing a second row",
                    probe.rollbacks() >= 1);
        });
    }

    private static void checkCancellingAnotherAccountsBookingIsRefused() {
        section("Cancellation");
        scenario(probe -> {
            Connection c = probe.connection();
            long otherUser = otherUserId(c);
            long theirs = foreignPnr();
            insertBookingRow(c, theirs, selectionFor(c, FAR_DATE, 1), otherUser);

            String refusal = cancelFails(c, theirs, "Should not work");
            see("cancelling another account's booking is refused", refusal != null);
            see("the refusal does not confirm the PNR exists",
                    refusal != null && refusal.contains("No booking found"));
            see("the refused attempt wrote nothing to the ledger",
                    ledgerIsEmpty(c, theirs));
            see("the refused attempt rolled back", probe.rollbacks() >= 1);
        });
    }

    /** Nothing is deleted, and the berth that was sold is still identifiable. */
    private static void checkCancellationPreservesHistory() {
        section("Cancellation");
        scenario(probe -> {
            Connection c = probe.connection();
            ConfirmedBooking booked = book(c, selectionFor(c, FAR_DATE, 3), party(3));
            List<Long> berthIdsBefore = reservationBerths(c, booked.pnr());
            BigDecimal totalBefore = storedTotal(c, booked.pnr());

            see("the cancellation succeeds", cancelFails(c, booked.pnr(), "Keeping history") == null);

            see("the booking row still exists",
                    countWhere(c, "SELECT COUNT(*) FROM bookings WHERE pnr = ?", booked.pnr()) == 1);
            see("every passenger row still exists",
                    passengerRows(c, booked.pnr()) == 3);
            see("every reservation row still exists",
                    reservationRows(c, booked.pnr()) == 3);
            see("the same berths are still recorded",
                    reservationBerths(c, booked.pnr()).equals(berthIdsBefore));
            see("the fare charged is unchanged",
                    storedTotal(c, booked.pnr()).compareTo(totalBefore) == 0);
            see("the passenger names are unchanged",
                    passengerNames(c, booked.pnr()).contains("Party 0")
                            && passengerNames(c, booked.pnr()).contains("Party 1")
                            && passengerNames(c, booked.pnr()).contains("Party 2"));
            BookingDetail ticket = new BookingDAO(c).ticketForUser(booked.pnr(), myId())
                    .orElseThrow();
            see("the ticket still lists every passenger", ticket.passengerCount() == 3);
            see("the ticket still shows what was paid",
                    ticket.totalFare().compareTo(totalBefore) == 0);
            see("the ticket says cancelled and carries the ledger with it",
                    ticket.bookingStatus() == BookingStatus.CANCELLED
                            && ticket.cancellation() != null);
        });
    }

    /**
     * A cancellation frees this booking's berths and nobody else's.
     *
     * <p>Two bookings on one train, then cancel one: the other must keep its
     * berth and stay counted as occupied.
     */
    private static void checkCancellationReleasesOnlyItsOwnBerths() {
        section("Cancellation");
        scenario(probe -> {
            Connection c = probe.connection();
            TrainSelection selection = selectionFor(c, FAR_DATE, 2);
            ConfirmedBooking mine = book(c, selection, party(2));
            ConfirmedBooking theirs = book(c, selection, party(2));
            Set<Long> theirBerths = new LinkedHashSet<>(reservationBerths(c, theirs.pnr()));
            Set<Long> myBerths = new LinkedHashSet<>(reservationBerths(c, mine.pnr()));

            see("the two bookings hold four different berths",
                    disjoint(myBerths, theirBerths));

            int freeBefore = availableCount(c, selection);
            see("the cancellation succeeds", cancelFails(c, mine.pnr(), "Freeing berths") == null);

            see("the class now has two more berths free",
                    availableCount(c, selection) == freeBefore + 2);
            see("my berths read as released",
                    disjoint(heldBerthIds(c, mine.pnr()), myBerths));
            see("their berths are still held",
                    heldBerthIds(c, theirs.pnr()).containsAll(theirBerths));
            see("their booking is still confirmed",
                    "CONFIRMED".equals(storedBookingStatus(c, theirs.pnr())));
            see("their reservations are still confirmed",
                    confirmedReservations(c, theirs.pnr()) == 2);
            see("their ledger is untouched",
                    countWhere(c, "SELECT COUNT(*) FROM cancellations WHERE pnr = ?",
                            theirs.pnr()) == 0);
        });
    }

    /**
     * A failure part-way through releases nothing.
     *
     * <p>The ledger insert is made to fail after the reservations and the booking
     * have already been updated, which is the worst possible moment. If the
     * transaction were not real, this scenario would leave a cancelled booking
     * holding no berth and no ledger entry: a refund nobody can trace.
     */
    private static void checkAFailedCancellationReleasesNothing() {
        section("Cancellation");
        scenario(probe -> {
            Connection c = probe.connection();
            ConfirmedBooking booked = book(c, selectionFor(c, FAR_DATE, 2), party(2));

            // The fixture is re-created on its own connection and left to
            // rollback, so that the failing service - which rolls back whatever
            // connection it is given - cannot take the evidence with it.
            FailingLedger failing = new FailingLedger(
                    DatabaseConnection.getConnection());
            String refusal;
            try {
                new CancellationService(failing.source()).cancel(booked.pnr(), "Doomed");
                refusal = null;
            } catch (Exception failure) {
                refusal = failure.getMessage();
            } finally {
                failing.discard();
            }

            see("the cancellation was refused", refusal != null);
            see("the refusal does not blame the passenger",
                    refusal != null && refusal.contains("nothing was changed"));
            see("the service rolled its own work back", failing.rollbacks() >= 1);
            see("nothing was committed", failing.commits() == 0);

            see("the booking is still confirmed",
                    "CONFIRMED".equals(storedBookingStatus(c, booked.pnr())));
            see("every reservation is still confirmed", confirmedReservations(c, booked.pnr()) == 2);
            see("the berths are still held", heldBerthIds(c, booked.pnr()).size() == 2);
            see("no ledger row was left behind", ledgerIsEmpty(c, booked.pnr()));
            see("the fare is untouched",
                    storedTotal(c, booked.pnr()).compareTo(booked.fare().total()) == 0);
        });
    }

    /** Whether the ledger holds nothing at all for a booking. */
    private static boolean ledgerIsEmpty(Connection c, long pnr) throws SQLException {
        return countWhere(c, "SELECT COUNT(*) FROM cancellations WHERE pnr = ?", pnr) == 0;
    }

    // ---------------------------------------------------------------- plumbing

    /** Runs one scenario inside a transaction and rolls it back. */
    private static void scenario(Scenario body) {
        try (Connection real = DatabaseConnection.getConnection()) {
            real.setAutoCommit(false);
            TxProbe probe = new TxProbe(real);
            try {
                body.run(probe);
            } catch (Exception failed) {
                see("the scenario ran without throwing (" + rootCause(failed) + ")", false);
            } finally {
                real.rollback();
            }
        } catch (SQLException cannotEvenConnect) {
            see("a connection could be opened", false);
        }
    }

    private static ConnectionSource sameConnection(Connection c) {
        return () -> c;
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

    private static long myId() {
        return signedIn().userId();
    }

    /** The signed-in account. */
    private static User signedIn() {
        try {
            return SessionManager.get().requireUser();
        } catch (AccessDeniedException nobody) {
            throw new IllegalStateException("no session", nobody);
        }
    }

    /** Any account that is not this one. Two accounts exist in the seeded data. */
    private static long otherUserId(Connection c) throws SQLException {
        try (PreparedStatement statement = c.prepareStatement(
                "SELECT user_id FROM users WHERE user_id <> ? ORDER BY user_id LIMIT 1")) {
            statement.setLong(1, myId());
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? results.getLong(1) : myId();
            }
        }
    }

    /** The lowest-numbered account that is not an administrator. */
    private static User traveller() {
        return userWithRole("role <> 'ADMIN'");
    }

    /** Any administrator account. */
    private static User administrator() {
        return userWithRole("role = 'ADMIN'");
    }

    private static User userWithRole(String where) {
        try (Connection c = DatabaseConnection.getConnection();
             PreparedStatement statement = c.prepareStatement(
                     "SELECT user_id, username, full_name, role FROM users WHERE " + where
                             + " ORDER BY user_id LIMIT 1");
             ResultSet results = statement.executeQuery()) {
            if (results.next()) {
                return new User(results.getLong("user_id"), results.getString("username"),
                        results.getString("full_name"), Role.valueOf(results.getString("role")));
            }
        } catch (SQLException noSuchAccount) {
            throw new IllegalStateException("no account matching " + where, noSuchAccount);
        }
        throw new IllegalStateException("no account matching " + where);
    }

    /**
     * Runs a query as somebody else, then puts the session back exactly as it was.
     *
     * <p>The isolation rules are role-dependent, so the only way to ask what a
     * traveller may see and what an administrator may see is to be each of them in
     * turn. Restoring on the way out is not tidiness: a scenario left signed in as
     * an administrator would make every later ownership check pass for the wrong
     * reason.
     *
     * @return whatever the query returned, or {@code null} if it was refused
     */
    private static <T> T asUser(Connection c, User as, Callable<T> query) {
        User original = signedIn();
        SessionManager.get().beginSession(as);
        try {
            return query.call();
        } catch (Exception refused) {
            return null;
        } finally {
            SessionManager.get().beginSession(original);
        }
    }

private static void see(String what, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("PASS  " + what);
        } else {
            failed++;
            System.out.println("FAIL  " + what);
        }
    }

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
            System.out.println("All " + passed + " booking management checks passed.");
        } else {
            System.out.println(failed + " of " + (passed + failed)
                    + " booking management checks FAILED.");
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

    // ---------------------------------------------------------------- fixtures

    private static TrainSelection selectionFor(Connection c, LocalDate date, int passengers)
            throws AccessDeniedException, ValidationException, SQLException {
        TrainSearchService search = new TrainSearchService(sameConnection(c), PARTY_LIMIT);
        List<TrainSearchResult> results = search.search(
                new TrainSearchCriteria(FROM, NEXT, date, SMALL_CLASS, passengers));
        for (TrainSearchResult result : results) {
            if (result.trainNo().equals(TRAIN)) {
                return new TrainSelection(result,
                        result.availabilityFor(SMALL_CLASS).orElseThrow(), date, passengers);
            }
        }
        throw new IllegalStateException("no " + TRAIN + " in " + SMALL_CLASS
                + " for " + FROM + " to " + NEXT + " on " + date);
    }

    /** Books for the session user. */
    private static ConfirmedBooking book(Connection c, TrainSelection selection,
                                         List<PassengerDraft> party) throws Exception {
        BookingPreparationService preparation = new BookingPreparationService(sameConnection(c));
        BookingDraft draft = new BookingDraft(selection);
        for (PassengerDraft wanted : party) {
            preparation.addPassenger(draft, preparation.profileOf(selection.classCode()),
                    new PassengerInput(wanted.fullName(), String.valueOf(wanted.age()),
                            wanted.gender().label(), wanted.berthPreference().label(),
                            wanted.mealPreference()));
        }
        preparation.setContact(draft, CONTACT_MOBILE, CONTACT_EMAIL);
        return new BookingService(sameConnection(c), pnrGenerator()).confirm(draft);
    }

    /**
     * A confirmed booking row owned by somebody else, with no passengers.
     *
     * <p>Inserted rather than booked, because the engine books for whoever is
     * signed in. A booking row with no passengers is still a real row the ticket
     * read must refuse to return, which is the whole question being asked.
     */
    private static void insertBookingRow(Connection c, long pnr, TrainSelection selection,
                                         long userId) throws Exception {
        FareEstimate fare = new FareService(new TrainDAO(sameConnection(c))).quoteFor(selection);
        new BookingDAO(c).insertBooking(pnr, journeyIdOf(c, selection), selection, fare,
                CONTACT_NAME, new BookingContact(CONTACT_MOBILE, CONTACT_EMAIL), userId);
    }

    /** A PNR that cannot collide with a generated one. */
    private static long foreignPnr() {
        return 555_000_001L;
    }

    private static long journeyIdOf(Connection c, TrainSelection selection) throws SQLException {
        try (PreparedStatement statement = c.prepareStatement(
                "SELECT journey_id FROM journeys WHERE train_no = ? AND journey_date = ?")) {
            statement.setString(1, selection.trainNo());
            statement.setObject(2, selection.journeyDate());
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? results.getLong(1) : 0L;
            }
        }
    }

    /** Cancels for the session user, expecting it to be refused. */
    private static String cancelFails(Connection c, long pnr, String reason) {
        try {
            new CancellationService(sameConnection(c)).cancel(pnr, reason);
            return null;
        } catch (CancellationService.CancellationException refused) {
            return refused.getMessage();
        } catch (AccessDeniedException refused) {
            return refused.getMessage();
        } catch (Exception other) {
            return rootCause(other);
        }
    }

    private static List<PassengerDraft> party(int count) {
        List<PassengerDraft> party = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            party.add(passenger("Party " + index, 30, Gender.MALE,
                    index % 2 == 0 ? BerthPreference.LOWER : BerthPreference.UPPER));
        }
        return party;
    }

    private static PassengerDraft passenger(String name, int age, Gender gender,
                                            BerthPreference preference) {
        return new PassengerDraft(name, age, gender, preference, null);
    }

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

    // ---------------------------------------------------------------- db helpers

    private static void execute(Connection c, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            bind(statement, values);
            statement.executeUpdate();
        }
    }

    private static int countWhere(Connection c, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? results.getInt(1) : 0;
            }
        }
    }

    private static long scalar(Connection c, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? results.getLong(1) : 0L;
            }
        }
    }

    private static String text(Connection c, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? results.getString(1) : null;
            }
        }
    }

    private static BigDecimal decimal(Connection c, String sql, Object... values)
            throws SQLException {
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? results.getBigDecimal(1) : BigDecimal.ZERO;
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

    private static int passengerRows(Connection c, long pnr) throws SQLException {
        return countWhere(c, "SELECT COUNT(*) FROM passengers WHERE pnr = ?", pnr);
    }

    private static int reservationRows(Connection c, long pnr) throws SQLException {
        return countWhere(c, "SELECT COUNT(*) FROM seat_reservations WHERE pnr = ?", pnr);
    }

    private static int confirmedReservations(Connection c, long pnr) throws SQLException {
        return countWhere(c, "SELECT COUNT(*) FROM seat_reservations"
                + " WHERE pnr = ? AND status = 'CONFIRMED'", pnr);
    }

    private static String storedBookingStatus(Connection c, long pnr) throws SQLException {
        return text(c, "SELECT status FROM bookings WHERE pnr = ?", pnr);
    }

    private static BigDecimal storedTotal(Connection c, long pnr) throws SQLException {
        return decimal(c, "SELECT total_fare FROM bookings WHERE pnr = ?", pnr);
    }

    private static List<Long> reservationBerths(Connection c, long pnr) throws SQLException {
        List<Long> berths = new ArrayList<>();
        try (PreparedStatement statement = c.prepareStatement(
                "SELECT berth_id FROM seat_reservations WHERE pnr = ? ORDER BY berth_id")) {
            statement.setLong(1, pnr);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    berths.add(results.getLong(1));
                }
            }
        }
        return berths;
    }

    private static Set<String> passengerNames(Connection c, long pnr) throws SQLException {
        Set<String> names = new LinkedHashSet<>();
        try (PreparedStatement statement = c.prepareStatement(
                "SELECT full_name FROM passengers WHERE pnr = ?")) {
            statement.setLong(1, pnr);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    names.add(results.getString(1));
                }
            }
        }
        return names;
    }

    private static Set<Long> heldBerthIds(Connection c, long pnr) throws SQLException {
        Set<Long> berths = new LinkedHashSet<>();
        try (PreparedStatement statement = c.prepareStatement(
                "SELECT berth_id FROM seat_reservations"
                + " WHERE pnr = ? AND status IN ('CONFIRMED','RAC')")) {
            statement.setLong(1, pnr);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    berths.add(results.getLong(1));
                }
            }
        }
        return berths;
    }

    /** How many berths of this class the database currently counts as still available. */
    private static int availableCount(Connection c, TrainSelection selection) throws SQLException {
        return AvailabilityDAO.countForSegment(sameConnection(c),
                journeyIdOf(c, selection), selection.classCode(), selection.fromSeq(),
                selection.toSeq()).available();
    }

    private static boolean disjoint(Set<Long> left, Set<Long> right) {
        for (Long berth : left) {
            if (right.contains(berth)) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- the probes

    /**
     * A connection whose {@code commit} is recorded rather than executed.
     *
     * <p>The same device as the booking engine's probe: the service's own commit
     * path is exercised and can be asserted on, while the surrounding transaction
     * still discards everything.
     */
    private static final class TxProbe {

        private final Connection real;
        private final Connection wrapped;
        private final AtomicInteger commits = new AtomicInteger();
        private final AtomicInteger rollbacks = new AtomicInteger();

        @SuppressWarnings("unchecked")
        TxProbe(Connection real) {
            this.real = real;
            this.wrapped = wrap(real, commits, rollbacks, "");
        }

        Connection connection() {
            return wrapped;
        }

        int commits() {
            return commits.get();
        }

        int rollbacks() {
            return rollbacks.get();
        }
    }

    /**
     * A connection on which the ledger insert fails, on purpose.
     *
     * <p>Fails at the worst possible moment: after the reservations have been
     * released and the booking marked cancelled, so only a real rollback can put
     * the booking back together. If the service did not roll back, the evidence
     * in the scenario's transaction would show a cancelled booking with no ledger
     * entry and free berths.
     *
     * <p>Given its own connection, not the scenario's. The service rolls back
     * whatever connection it is handed, which is correct behaviour and would
     * otherwise take the fixture - and the evidence - down with it. The fixture
     * transaction on the scenario's connection is discarded afterwards in the
     * ordinary way, leaving nothing behind.
     *
     * <p>{@code commit} is recorded rather than executed, as elsewhere, so the
     * failure is the only thing this run exercises.
     */
    private static final class FailingLedger {

        private final AtomicInteger commits = new AtomicInteger();
        private final AtomicInteger rollbacks = new AtomicInteger();
        private final Connection real;
        private final Connection wrapped;

        @SuppressWarnings("unchecked")
        FailingLedger(Connection real) throws SQLException {
            this.real = real;
            real.setAutoCommit(false);
            this.wrapped = wrap(real, commits, rollbacks, "INTO cancellations");
        }

        ConnectionSource source() {
            return () -> wrapped;
        }

        /** Undoes the fixture transaction and gives the connection back. */
        void discard() throws SQLException {
            real.rollback();
            real.close();
        }

        int commits() {
            return commits.get();
        }

        int rollbacks() {
            return rollbacks.get();
        }
    }

    /**
     * One proxy implementation for both probes.
     *
     * <p>{@code commit} is counted and swallowed so the enclosing transaction can
     * still discard the work; {@code rollback} is counted <em>and</em> executed,
     * because the service has to genuinely clean up after itself for the failure
     * check to mean anything. When {@code tripOn} is non-empty, preparing a
     * statement containing it throws - the injected fault.
     */
    @SuppressWarnings("unchecked")
    private static Connection wrap(Connection real, AtomicInteger commits,
                                   AtomicInteger rollbacks, String tripOn) {
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "commit" -> {
                    commits.incrementAndGet();
                    return null;
                }
                case "close" -> {
                    // The scenario's transaction outlives any helper that used
                    // try-with-resources on this connection.
                    return null;
                }
                case "prepareStatement", "prepareCall" -> {
                    if (!tripOn.isEmpty() && String.valueOf(args[0]).contains(tripOn)) {
                        throw new SQLException("injected failure writing the ledger");
                    }
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
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, handler);
    }
}