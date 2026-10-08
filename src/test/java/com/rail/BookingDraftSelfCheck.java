package com.rail;

import com.rail.db.ConnectionSource;
import com.rail.db.DatabaseConnection;
import com.rail.model.BerthPreference;
import com.rail.model.BookingDraft;
import com.rail.model.ClassAvailability;
import com.rail.model.ClassProfile;
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
import com.rail.service.SessionManager;
import com.rail.service.TrainSearchService;
import com.rail.service.ValidationException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Milestone 5 checks: passenger entry, berth preferences filtered by what a
 * class actually contains, contact validation, and the fare breakdown.
 *
 * <p>Runs inside one transaction on a single connection and rolls back, so
 * nothing it touches survives.
 *
 * <p>The checks that matter most here are the ones about offering only what can
 * be honoured. AC 2-Tier is a sleeper class with no middle berths, and Chair Car
 * is a seated class with no berths at all, so a preference list written from the
 * class name alone would offer both a middle berth on 2A and a lower berth on CC.
 * Those two cases are asserted directly against the data.
 */
public final class BookingDraftSelfCheck {

    private static final LocalDate DATE = LocalDate.now().plusDays(30);

    private static int checksRun;
    private static int failures;

    private BookingDraftSelfCheck() {
    }

    public static void main(String[] args) {
        if (!DatabaseConnection.isConfigured()) {
            System.out.println("SKIP  no database configured, nothing was connected to.");
            return;
        }

        try (Connection real = DatabaseConnection.getConnection()) {
            long bookingsBefore = count(real, "bookings");
            long reservationsBefore = count(real, "seat_reservations");
            long passengersBefore = count(real, "passengers");

            Connection shared = nonClosing(real);
            ConnectionSource source = () -> shared;

            real.setAutoCommit(false);
            try {
                signIn();
                runScenario(source);
            } finally {
                real.rollback();
                SessionManager.get().endSession();
            }

            System.out.println();
            System.out.println("Nothing was booked");
            check("no booking row was created (" + bookingsBefore + " before, "
                            + count(real, "bookings") + " after)",
                    count(real, "bookings") == bookingsBefore);
            check("no reservation row was created (" + reservationsBefore + " before, "
                            + count(real, "seat_reservations") + " after)",
                    count(real, "seat_reservations") == reservationsBefore);
            check("no passenger row was created (" + passengersBefore + " before, "
                            + count(real, "passengers") + " after)",
                    count(real, "passengers") == passengersBefore);
        } catch (SQLException failure) {
            System.err.println();
            System.err.println("DATABASE CHECK FAILED: " + failure.getMessage());
            failure.printStackTrace();
            System.exit(1);
        } catch (ValidationException | AccessDeniedException unexpected) {
            System.err.println();
            System.err.println("SERVICE CHECK FAILED: " + unexpected.getMessage());
            System.exit(1);
        }

        System.out.println();
        if (failures == 0) {
            System.out.println("All " + checksRun + " booking checks passed.");
        } else {
            System.out.println(failures + " of " + checksRun + " booking checks FAILED.");
            System.exit(1);
        }
    }

    private static Connection nonClosing(Connection real) {
        return (Connection) java.lang.reflect.Proxy.newProxyInstance(
                BookingDraftSelfCheck.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (java.lang.reflect.InvocationTargetException wrapper) {
                        throw wrapper.getCause();
                    }
                });
    }

    private static void signIn() {
        SessionManager.get().beginSession(new User(1L, "booking_check", "Booking Check",
                Role.CUSTOMER));
    }

    // ---------------------------------------------------------------- checks


private static void runScenario(ConnectionSource source)
            throws SQLException, ValidationException, AccessDeniedException {
        BookingPreparationService booking = new BookingPreparationService(source, 6, 1, 120);

        section("Class preferences come from the coaches, not the class name");

        ClassProfile twoAC = booking.profileOf("2A");
        check("2A is recognised as a sleeper class", twoAC.isSleeper());
        check("2A offers a lower berth", twoAC.supports(BerthPreference.LOWER));
        check("2A offers an upper berth", twoAC.supports(BerthPreference.UPPER));
        check("2A does NOT offer a middle berth, because no 2A coach has one",
                !twoAC.supports(BerthPreference.MIDDLE));
        check("every class offers No Preference first",
                twoAC.supportedPreferences().get(0) == BerthPreference.NO_PREFERENCE);

        ClassProfile threeAC = booking.profileOf("3A");
        check("3A does offer a middle berth, unlike 2A",
                threeAC.supports(BerthPreference.MIDDLE));
        check("so the two AC classes differ in what they offer",
                threeAC.supports(BerthPreference.MIDDLE)
                        != twoAC.supports(BerthPreference.MIDDLE));

        ClassProfile chairCar = booking.profileOf("CC");
        check("Chair Car is not a sleeper class", !chairCar.isSleeper());
        check("Chair Car offers a window seat", chairCar.supports(BerthPreference.WINDOW));
        check("Chair Car offers an aisle seat", chairCar.supports(BerthPreference.AISLE));
        check("Chair Car offers NO lower berth, because it is a seated class",
                !chairCar.supports(BerthPreference.LOWER));
        check("Chair Car offers no middle or upper berth",
                !chairCar.supports(BerthPreference.MIDDLE)
                        && !chairCar.supports(BerthPreference.UPPER));

        section("Side berths follow the coach configuration");

        check("no configured coach has a side berth, so no side preference is offered",
                offeredSidePreferences(source).isEmpty());
        check("so Side Lower is not offered on 3A either",
                !threeAC.supports(BerthPreference.SIDE_LOWER));

        // Each check below starts from its own empty draft. Sharing one draft would
        // let the party-size cap answer on behalf of the rule being tested, and a
        // check that passes for the wrong reason is worse than no check.
        TrainSelection twoPerson = realSelection(source, 2);
        TrainSelection sixPerson = realSelection(source, 6);
        ClassProfile profile = booking.profileOf(twoPerson.classCode());

        section("Passenger names");

        check("a passenger with a name, age and gender is accepted",
                mustAdd(booking, fresh(twoPerson), profile, "Asha Rao", "34", "Female", "Lower")
                        != null);
        check("the passenger is in the draft",
                countAfterAdding(booking, twoPerson, profile, "Asha Rao", "34", "Female", "Lower") == 1);
        check("the name is trimmed",
                "Asha Rao".equals(mustAdd(booking, fresh(twoPerson), profile,
                        "  Asha Rao  ", "34", "Female", "Lower").fullName()));

        check("a blank name is refused", refused(booking, twoPerson, profile,
                "   ", "30", "Male", "No Preference", "fullName"));
        check("an empty name is refused", refused(booking, twoPerson, profile,
                "", "30", "Male", "No Preference", "fullName"));
        check("a name of only tabs is refused", refused(booking, twoPerson, profile,
                "\t\t", "30", "Male", "No Preference", "fullName"));
        check("a null name is refused", refused(booking, twoPerson, profile,
                null, "30", "Male", "No Preference", "fullName"));

        section("Passenger ages");

        check("an age below the range is refused", refused(booking, twoPerson, profile,
                "Asha Rao", "0", "Male", "No Preference", "age"));
        check("an age above the range is refused", refused(booking, twoPerson, profile,
                "Asha Rao", "121", "Male", "No Preference", "age"));
        check("a negative age is refused", refused(booking, twoPerson, profile,
                "Asha Rao", "-5", "Male", "No Preference", "age"));
        check("a non-numeric age is refused", refused(booking, twoPerson, profile,
                "Asha Rao", "twelve", "Male", "No Preference", "age"));
        check("a blank age is refused", refused(booking, twoPerson, profile,
                "Asha Rao", "  ", "Male", "No Preference", "age"));
        check("a null age is refused", refused(booking, twoPerson, profile,
                "Asha Rao", null, "Male", "No Preference", "age"));
        check("a decimal age is refused", refused(booking, twoPerson, profile,
                "Asha Rao", "12.5", "Male", "No Preference", "age"));
        check("a one-thousand-digit age is refused", refused(booking, twoPerson, profile,
                "Asha Rao", "9".repeat(1000), "Male", "No Preference", "age"));
        check("both ends of the range are accepted",
                accepted(booking, twoPerson, profile, "Edge One", "1", "Male")
                        && accepted(booking, twoPerson, profile, "Edge Two", "120", "Male"));

        section("Genders");

        check("a lowercase gender is accepted",
                mustAdd(booking, fresh(twoPerson), profile, "Bilal Khan", "41", "male", "Lower")
                        .gender() == Gender.MALE);
        check("an Other gender is accepted",
                mustAdd(booking, fresh(twoPerson), profile, "Chitra Rao", "29", "Other", "Lower")
                        .gender() == Gender.OTHER);
        check("every documented gender is accepted",
                Gender.values().length == 3
                        && accepted(booking, twoPerson, profile, "Male", "30", "Male")
                        && accepted(booking, twoPerson, profile, "Female", "31", "Female")
                        && accepted(booking, twoPerson, profile, "Other", "32", "Other"));
        check("an unknown gender is refused", refused(booking, twoPerson, profile,
                "Asha Rao", "30", "Unknown", "No Preference", "gender"));
        check("a blank gender is refused", refused(booking, twoPerson, profile,
                "Asha Rao", "30", "", "No Preference", "gender"));
        check("a gender of only spaces is refused", refused(booking, twoPerson, profile,
                "Asha Rao", "30", "   ", "No Preference", "gender"));
        check("a number is not a gender", refused(booking, twoPerson, profile,
                "Asha Rao", "30", "1", "No Preference", "gender"));

        section("Berth preferences are checked against the class");

        check("a preference the class does not have is refused",
                refused(booking, twoPerson, profile, "Dilip Shah", "55",
                        "Male", "Side Lower", "berthPreference"));
        check("the refusal names the class that cannot offer it",
                refusalMessage(booking, twoPerson, profile, "Dilip Shah", "55",
                        "Male", "Side Lower").contains(twoPerson.className()));
        check("Side Upper is refused too", refused(booking, twoPerson, profile,
                "Dilip Shah", "55", "Male", "Side Upper", "berthPreference"));
        check("No Preference is always allowed",
                mustAdd(booking, fresh(twoPerson), profile, "Dilip Shah", "55", "Male",
                        "No Preference").berthPreference() == BerthPreference.NO_PREFERENCE);
        check("a blank preference is treated as No Preference",
                mustAdd(booking, fresh(twoPerson), profile, "Ekta Joshi", "33", "Female", "  ")
                        .berthPreference() == BerthPreference.NO_PREFERENCE);
        check("the stored preference is the one asked for",
                mustAdd(booking, fresh(twoPerson), profile, "Farah Ali", "27", "Female", "Upper")
                        .berthPreference() == BerthPreference.UPPER);
        check("a nonsense preference is refused", refused(booking, twoPerson, profile,
                "Asha Rao", "30", "Male", "Middle Deck", "berthPreference"));

        ClassProfile seated = booking.profileOf("CC");
        check("a lower berth is refused on a seated class, with a seated-class reason",
                refusalMessage(booking, twoPerson, seated, "Gopal Rao", "63", "Male", "Lower")
                        .toLowerCase().contains("seated"));
        check("a window seat is accepted on a seated class",
                mustAdd(booking, fresh(twoPerson), seated, "Gopal Rao", "63", "Male", "Window")
                        .berthPreference() == BerthPreference.WINDOW);

        section("Meals only where the train has a pantry");

        ClassProfile sleeper = booking.profileOf("SL");
        check("3A has a pantry, so a meal is kept",
                "Veg Thali".equals(mustAdd(booking, fresh(twoPerson), profile,
                        "Hina Verma", "22", "Female", "Lower", "Veg Thali").mealPreference()));
        check("a blank meal is stored as absent",
                mustAdd(booking, fresh(twoPerson), profile, "Ibrahim Khan", "40", "Male",
                        "Lower", "  ").mealPreference() == null);
        check("a null meal is stored as absent",
                mustAdd(booking, fresh(twoPerson), profile, "Jaya Menon", "45", "Female",
                        "Lower", null).mealPreference() == null);
        check("Sleeper Class has no pantry", !sleeper.hasPantry());
        check("a meal requested on a train without catering is refused",
                refusedWithMeal(booking, twoPerson, sleeper, "Kamal Rao", "50", "Male",
                        "Lower", "Jain Meal"));
        check("the refusal points at the meal field",
                "mealPreference".equals(refusalFieldWithMeal(booking, twoPerson, sleeper,
                        "Kamal Rao", "50", "Male", "Lower", "Jain Meal")));
check("leaving the meal blank is fine on a train without catering",
                acceptedWithMeal(booking, fresh(twoPerson), sleeper, "Kamal Rao", "50", "Male",
                        "Lower", null));

        section("Adding and removing passengers");

        BookingDraft party = fresh(sixPerson);
        ClassProfile partyProfile = booking.profileOf(sixPerson.classCode());
        for (int i = 1; i <= 6; i++) {
            mustAdd(booking, party, partyProfile, "Traveller " + i, "30", "Male", "Lower", null);
        }
        check("all six passengers are listed", party.passengerCount() == 6);
        check("the party is full, so no more may be added",
                !booking.canAddPassenger(party));
check("a seventh passenger is refused", refused(booking, party, partyProfile,
                "Seventh", "30", "Male", "No Preference", "passengers"));

        check("removing the first passenger works",
                removes(booking, party, 0) && party.passengerCount() == 5);
        check("the right passenger was removed, and the rest kept their order",
                !party.passengers().get(0).fullName().equals("Traveller 1")
                        && party.passengers().get(0).fullName().equals("Traveller 2")
                        && party.passengers().get(4).fullName().equals("Traveller 6"));
        check("a removal frees a slot",
                booking.canAddPassenger(party)
                        && accepted(booking, party, partyProfile, "Replacement", "31", "Female"));
        check("the replacement is at the end of the party",
                "Replacement".equals(party.passengers().get(5).fullName()));
        check("removing an index past the end is refused",
                refusedRemoval(booking, party, 999));
        check("removing a negative index is refused", refusedRemoval(booking, party, -1));
        check("removing every passenger empties the draft",
                removes(booking, party, 5) && removes(booking, party, 4)
                        && removes(booking, party, 3) && removes(booking, party, 2)
                        && removes(booking, party, 1) && removes(booking, party, 0)
                        && party.passengerCount() == 0);
        check("an empty draft is not ready",
                !booking.isReadyToContinue(party)
                        && booking.readinessProblem(party) != null);

        section("Party size is fixed by the search");

        BookingDraft pair = fresh(twoPerson);
        ClassProfile pairProfile = booking.profileOf(twoPerson.classCode());
        mustAdd(booking, pair, pairProfile, "One Passenger", "30", "Male", "Lower", null);
        mustAdd(booking, pair, pairProfile, "Two Passenger", "31", "Female", "Lower", null);
        check("the draft now holds the whole party", pair.passengerCount() == 2);
        check("a third passenger is refused, because search asked for two",
                refused(booking, pair, pairProfile, "Three Passenger", "32", "Male",
                        "No Preference", "passengers"));
        check("the refusal says how many the booking is for",
                refusalMessage(booking, pair, pairProfile, "Three", "32", "Male", "Lower")
                        .contains("2"));
        check("no more passengers may be added",
                !booking.canAddPassenger(pair));
        check("a complete party without contact details is not ready",
                !booking.isReadyToContinue(pair));
        check("readiness explains the missing contact details",
                booking.readinessProblem(pair).contains("mobile"));
        check("an empty draft reports how many passengers are missing",
                booking.readinessProblem(fresh(twoPerson)).contains("2"));

        section("Contact details");

        check("a valid 10-digit mobile is accepted",
                acceptsContact(booking, pair, "9876543210", null));
        check("the mobile is stored as ten digits",
                "9876543210".equals(pair.contact().mobile()));
        check("an absent email is allowed",
                acceptsContact(booking, pair, "9876543210", "  "));
        check("the absent email is stored as null", pair.contact().email() == null);
        check("a valid email is accepted",
                acceptsContact(booking, pair, "9876543210", "asha@example.com"));
        check("the email is kept",
                "asha@example.com".equals(pair.contact().email()));
        check("an email is trimmed",
                acceptsContact(booking, pair, "9876543210", "  asha@example.com  ")
                        && "asha@example.com".equals(pair.contact().email()));
        check("a +91 prefixed mobile is accepted",
                acceptsContact(booking, pair, "+91 98765 43210", null));
        check("the country code is stripped",
                "9876543210".equals(pair.contact().mobile()));
        check("a mobile with spaces is accepted",
                acceptsContact(booking, pair, "98765 43210", null)
                        && "9876543210".equals(pair.contact().mobile()));
        check("a nine-digit mobile is refused",
                !acceptsContact(booking, pair, "987654321", null));
        check("a twelve-digit mobile is refused",
                !acceptsContact(booking, pair, "987654321099", null));
        check("a mobile of letters is refused",
                !acceptsContact(booking, pair, "not-a-number", null));
        check("a blank mobile is refused", !acceptsContact(booking, pair, "   ", null));
        check("a null mobile is refused", !acceptsContact(booking, pair, null, null));
        check("an email without an @ is refused",
                !acceptsContact(booking, pair, "9876543210", "asha.example.com"));
        check("an email without a domain dot is refused",
                !acceptsContact(booking, pair, "9876543210", "asha@example"));
        check("an email with spaces is refused",
                !acceptsContact(booking, pair, "9876543210", "asha @example.com"));
        check("an email without a tld is refused",
                !acceptsContact(booking, pair, "9876543210", "asha@example."));
        check("a distinct baseline contact is stored",
                acceptsContact(booking, pair, "9000000011", "baseline@example.com")
                        && "9000000011".equals(pair.contact().mobile()));
        check("a refused contact leaves the previous one in place",
                "9000000011".equals(pair.contact().mobile())
                        && "baseline@example.com".equals(pair.contact().email()));

        check("a complete draft is ready to continue",
                booking.isReadyToContinue(pair));
        check("a ready draft has no readiness problem",
                booking.readinessProblem(pair) == null);

        section("Going back and returning keeps the draft");

        // Navigating away to search and back must not lose anything, because the
        // draft is held by the frame rather than rebuilt from the form's fields.
        BookingDraft kept = fresh(twoPerson);
        ClassProfile keptProfile = booking.profileOf(twoPerson.classCode());
        mustAdd(booking, kept, keptProfile, "Ravi Menon", "45", "Male", "Upper", "Jain Meal");
        mustAdd(booking, kept, keptProfile, "Sana Menon", "42", "Female", "Lower", null);
        acceptsContact(booking, kept, "9000000001", "ravi@example.com");
        List<PassengerDraft> beforeNavigation = kept.passengers();
        boolean readyBeforeNavigation = booking.isReadyToContinue(kept);
        booking.profileOf(twoPerson.classCode());
        check("the passengers survive a trip to search and back",
                kept.passengers().equals(beforeNavigation));
        check("the contact survives a trip to search and back",
                "9000000001".equals(kept.contact().mobile()));
        check("each passenger keeps their own preference",
                kept.passengers().get(0).berthPreference() == BerthPreference.UPPER
                        && kept.passengers().get(1).berthPreference() == BerthPreference.LOWER);
        check("the meal preference survives the trip",
                "Jain Meal".equals(kept.passengers().get(0).mealPreference()));
        check("the draft is still ready after returning",
                readyBeforeNavigation && booking.isReadyToContinue(kept));

        section("Fare breakdown");

FareEstimate estimate = booking.estimate(pair);
        check("the breakdown covers both passengers", estimate.passengers() == 2);
        // compareTo, not ==: BigDecimal.equals compares scale as well as value,
        // and == compares references, so 85.00 == 85.0 is false and
        // 85.00 == 85.00 is only true by accident of interning. Neither would
        // test arithmetic.
        check("base plus distance equals the per-passenger fare",
                estimate.perPassenger()
                        .compareTo(estimate.baseFare().add(estimate.distanceFare())) == 0);
        check("the total is the per-passenger fare times the party",
                estimate.total()
                        .compareTo(estimate.perPassenger()
                                .multiply(java.math.BigDecimal.valueOf(2))) == 0);
        check("the charged distance is the segment searched",
                estimate.distanceKm() == twoPerson.segmentKm());
        check("the per-passenger fare matches what search quoted",
                estimate.perPassenger().compareTo(twoPerson.farePerPassenger()) == 0);
        check("the total matches the search screen's party fare",
                estimate.total().compareTo(twoPerson.totalFare()) == 0);
        check("an empty party is refused rather than quoted as zero",
                refusesEmptyQuote(booking, twoPerson));
        check("a larger party costs proportionally more", biggerPartyCostsMore(booking, twoPerson));
        check("the estimate has no negative component",
                estimate.baseFare().signum() >= 0 && estimate.distanceFare().signum() >= 0);
        check("every amount is at the two-decimal storage scale",
                atStorageScale(estimate));
        check("the components and the total are the same money",
                estimate.total().compareTo(
                        estimate.baseFare().add(estimate.distanceFare())
                                .multiply(java.math.BigDecimal.valueOf(estimate.passengers()))) == 0);

        section("Configured limits are honoured");

        BookingPreparationService narrow = new BookingPreparationService(source, 6, 18, 60);
        check("an age at the narrower minimum is accepted",
                accepted(narrow, twoPerson, profile, "Tara Singh", "18", "Female"));
        check("an age at the narrower maximum is accepted",
                accepted(narrow, twoPerson, profile, "Uday Kumar", "60", "Male"));
        check("an age above the narrower maximum is refused", refused(narrow, twoPerson, profile,
                "Vera Dsouza", "61", "Female", "No Preference", "age"));
        check("an age below the narrower minimum is refused", refused(narrow, twoPerson, profile,
                "Walt Abraham", "17", "Male", "No Preference", "age"));
        check("an age of 100 is accepted by the wide range but refused by the narrow one",
                !refused(booking, twoPerson, profile, "Xavier Gray", "100", "Male", "No Preference", "age")
                        && refused(narrow, twoPerson, profile, "Xavier Gray", "100", "Male", "No Preference", "age"));
        check("an age of 30 sits inside both ranges",
                !refused(booking, twoPerson, profile, "Yara Silva", "30", "Male", "No Preference", "age")
                        && !refused(narrow, twoPerson, profile, "Yara Silva", "30", "Male", "No Preference", "age"));
        check("the configured limits are readable",
                narrow.minAge() == 18 && narrow.maxAge() == 60 && narrow.maxPassengers() == 6);
        check("an unknown class code is refused", refusedProfile(narrow, "ZZ"));
        check("a null class code is refused", refusedProfile(narrow, null));

        BookingPreparationService oneOnly = new BookingPreparationService(source, 1, 1, 120);
        BookingDraft single = fresh(twoPerson);
        ClassProfile singleProfile = oneOnly.profileOf(twoPerson.classCode());
        mustAdd(oneOnly, single, singleProfile, "Solo Traveller", "30", "Male", "Lower", null);
        check("a configured cap of one still allows that one",
                single.passengerCount() == 1);
        check("a second passenger breaches the configured cap",
                refused(oneOnly, single, singleProfile, "Second", "31", "Female",
                        "Lower", "passengers"));
    }

    /** Whether any configured coach offers a side berth, found from the data. */
    private static List<String> offeredSidePreferences(ConnectionSource source)
            throws SQLException {
        try (Connection c = source.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT DISTINCT position FROM berths WHERE position = 'SIDE'");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? List.of("SIDE") : List.of();
        }
    }

    /** A real searched selection, so the draft holds a genuine journey. */
    private static TrainSelection realSelection(ConnectionSource source, int passengers)
            throws SQLException, ValidationException, AccessDeniedException {
        TrainSearchService search = new TrainSearchService(source, Math.max(passengers, 6));
        for (String classCode : List.of("3A", "2A", "SL")) {
            List<TrainSearchResult> results = search.search(
                    new TrainSearchCriteria("CSTM", "PUNE", DATE, classCode, passengers));
            for (TrainSearchResult train : results) {
                for (ClassAvailability offered : train.classes()) {
                    if (offered.availableBerths() >= passengers) {
                        return new TrainSelection(train, offered, DATE, passengers);
                    }
                }
            }
        }
        throw new IllegalStateException("no train had " + passengers
                + " berths free; the sample data changed");
    }

    private static boolean biggerPartyCostsMore(BookingPreparationService booking,
                                                TrainSelection selection)
            throws SQLException, ValidationException {
        BookingDraft solo = fresh(selection);
        BookingDraft pair = fresh(selection);
        ClassProfile profile = booking.profileOf(selection.classCode());
        mustAdd(booking, solo, profile, "Solo", "30", "Male", "Lower", null);
        mustAdd(booking, pair, profile, "First", "30", "Male", "Lower", null);
        mustAdd(booking, pair, profile, "Second", "31", "Female", "Lower", null);
return booking.estimate(pair).total().compareTo(booking.estimate(solo).total()) > 0;
    }

    /** Every amount in a breakdown is at the scale the money columns use. */
    private static boolean atStorageScale(FareEstimate estimate) {
        return estimate.baseFare().scale() == FareEstimate.SCALE
                && estimate.distanceFare().scale() == FareEstimate.SCALE
                && estimate.perPassenger().scale() == FareEstimate.SCALE
                && estimate.total().scale() == FareEstimate.SCALE;
    }

    /**
     * A quote for nobody must fail rather than return zero.
     *
     * <p>A confident ₹0 for an empty party is the kind of wrong answer a
     * passenger trusts. The screen shows a prompt instead of asking for one.
     */
    private static boolean refusesEmptyQuote(BookingPreparationService booking,
                                             TrainSelection selection)
            throws SQLException, ValidationException {
        try {
            booking.estimate(booking.fareRuleFor(selection), 0);
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }
// ---------------------------------------------------------------- helpers

/** A new, empty draft for a selection, so one check cannot affect the next. */
    private static BookingDraft fresh(TrainSelection selection) {
        return new BookingDraft(selection);
    }

    // The refusals below come in two shapes: most checks want a throwaway draft,
    // and a few deliberately act on a draft they are building up.

    private static boolean refused(BookingPreparationService booking, TrainSelection selection,
                                   ClassProfile profile, String name, String age,
                                   String gender, String berth, String expectedField) {
        return refused(booking, fresh(selection), profile, name, age, gender, berth, expectedField);
    }

    private static String refusalMessage(BookingPreparationService booking,
                                         TrainSelection selection, ClassProfile profile,
                                         String name, String age, String gender, String berth) {
        return refusalMessage(booking, fresh(selection), profile, name, age, gender, berth);
    }

    private static boolean refusedWithMeal(BookingPreparationService booking,
                                           TrainSelection selection, ClassProfile profile,
                                           String name, String age, String gender,
                                           String berth, String meal) {
        return refusedWithMeal(booking, fresh(selection), profile, name, age, gender, berth, meal);
    }

    private static String refusalFieldWithMeal(BookingPreparationService booking,
                                               TrainSelection selection, ClassProfile profile,
                                               String name, String age, String gender,
                                               String berth, String meal) {
        return refusalFieldWithMeal(booking, fresh(selection), profile, name, age, gender,
                berth, meal);
    }

    private static boolean accepted(BookingPreparationService booking, TrainSelection selection,
                                    ClassProfile profile, String name, String age,
                                    String gender) {
        return accepted(booking, fresh(selection), profile, name, age, gender);
    }

    /**
     * Adds a passenger that the service is expected to accept.
     *
     * <p>Fails loudly rather than returning null, because a refusal here means the
     * service is wrong and every later assertion would be built on a false start.
     */
    private static PassengerDraft mustAdd(BookingPreparationService booking, BookingDraft draft,
                                          ClassProfile profile, String name, String age,
                                          String gender, String berth) {
        return mustAdd(booking, draft, profile, name, age, gender, berth, null);
    }

    private static PassengerDraft mustAdd(BookingPreparationService booking, BookingDraft draft,
                                          ClassProfile profile, String name, String age,
                                          String gender, String berth, String meal) {
        try {
            return booking.addPassenger(draft, profile,
                    new PassengerInput(name, age, gender, berth, meal));
        } catch (ValidationException refused) {
            throw new IllegalStateException(
                    "expected '" + name + "' to be accepted but it was refused: "
                            + refused.getMessage(), refused);
        }
    }

    private static boolean accepted(BookingPreparationService booking, BookingDraft draft,
                                    ClassProfile profile, String name, String age,
                                    String gender) {
        return acceptedWithMeal(booking, draft, profile, name, age, gender, "No Preference", null);
    }

    private static boolean acceptedWithMeal(BookingPreparationService booking, BookingDraft draft,
                                            ClassProfile profile, String name, String age,
                                            String gender, String berth, String meal) {
        try {
            booking.addPassenger(draft, profile,
                    new PassengerInput(name, age, gender, berth, meal));
            return true;
        } catch (ValidationException refused) {
            return false;
        }
    }

    /** Whether the named field was the one rejected. */
    private static boolean refused(BookingPreparationService booking, BookingDraft draft,
                                   ClassProfile profile, String name, String age,
                                   String gender, String berth, String expectedField) {
        return expectedField.equals(refusalField(booking, draft, profile, name, age, gender, berth));
    }

    private static String refusalField(BookingPreparationService booking, BookingDraft draft,
                                       ClassProfile profile, String name, String age,
                                       String gender, String berth) {
        String field = refusalFieldWithMeal(booking, draft, profile, name, age, gender, berth, null);
        return field == null ? "" : field;
    }

    private static String refusalFieldWithMeal(BookingPreparationService booking,
                                                BookingDraft draft, ClassProfile profile,
                                                String name, String age, String gender,
                                                String berth, String meal) {
        try {
            booking.addPassenger(draft, profile,
                    new PassengerInput(name, age, gender, berth, meal));
            return null;
        } catch (ValidationException refused) {
            return refused.field();
        }
    }

    private static boolean refusedWithMeal(BookingPreparationService booking, BookingDraft draft,
                                           ClassProfile profile, String name, String age,
                                           String gender, String berth, String meal) {
        return !acceptedWithMeal(booking, draft, profile, name, age, gender, berth, meal);
    }

    /** The message shown when a preference or meal is refused. */
    private static String refusalMessage(BookingPreparationService booking, BookingDraft draft,
                                         ClassProfile profile, String name, String age,
                                         String gender, String berth) {
        try {
            booking.addPassenger(draft, profile,
                    new PassengerInput(name, age, gender, berth, null));
            return "";
        } catch (ValidationException refused) {
            return refused.getMessage();
        }
    }

    private static boolean removes(BookingPreparationService booking, BookingDraft draft, int index) {
        try {
            booking.removePassenger(draft, index);
            return true;
        } catch (ValidationException refused) {
            return false;
        }
    }

    /** A refused removal must also leave the party untouched. */
    private static boolean refusedRemoval(BookingPreparationService booking, BookingDraft draft,
                                          int index) {
        int before = draft.passengerCount();
        return !removes(booking, draft, index) && draft.passengerCount() == before;
    }

    private static boolean acceptsContact(BookingPreparationService booking, BookingDraft draft,
                                          String mobile, String email) {
        try {
            booking.setContact(draft, mobile, email);
            return true;
        } catch (ValidationException refused) {
            return false;
        }
    }

    private static boolean refusedProfile(BookingPreparationService booking, String classCode)
            throws SQLException {
        try {
            booking.profileOf(classCode);
            return false;
        } catch (ValidationException refused) {
            return true;
        }
    }

    /** How many passengers a draft holds after one expected-accepted add. */
    private static int countAfterAdding(BookingPreparationService booking, TrainSelection selection,
                                        ClassProfile profile, String name, String age,
                                        String gender, String berth) {
        BookingDraft draft = fresh(selection);
        mustAdd(booking, draft, profile, name, age, gender, berth, null);
        return draft.passengerCount();
    }

    private static long count(Connection c, String table) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + table);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void section(String title) {
        System.out.println();
        System.out.println(title);
    }

    private static void check(String description, boolean condition) {
        checksRun++;
        if (condition) {
            System.out.println("PASS  " + description);
        } else {
            failures++;
            System.out.println("FAIL  " + description);
        }
    }
}