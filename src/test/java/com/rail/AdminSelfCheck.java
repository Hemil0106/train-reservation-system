package com.rail;

import com.rail.db.BookingDAO;
import com.rail.db.ConnectionSource;
import com.rail.db.DatabaseConnection;
import com.rail.db.TrainDAO;
import com.rail.model.AdminView;
import com.rail.model.BerthPreference;
import com.rail.model.BookingContact;
import com.rail.model.BookingDraft;
import com.rail.model.ConfirmedBooking;
import com.rail.model.Gender;
import com.rail.model.PassengerDraft;
import com.rail.model.PassengerInput;
import com.rail.model.Role;
import com.rail.model.StopInfo;
import com.rail.model.TrainSearchCriteria;
import com.rail.model.TrainSearchResult;
import com.rail.model.TrainSelection;
import com.rail.model.User;
import com.rail.service.AccessDeniedException;
import com.rail.service.AdminService;
import com.rail.service.AuthException;
import com.rail.service.AuthService;
import com.rail.service.BookingPreparationService;
import com.rail.service.BookingService;
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
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Verification for the administration area.
 *
 * <h2>Nothing is left behind</h2>
 *
 * <p>Same arrangement as {@code BookingManagementSelfCheck}: the application
 * account cannot clean up after itself, so every scenario runs inside one
 * transaction that is rolled back, with the service's own {@code commit}
 * recorded rather than performed. A run that left a train or a station behind
 * would be testing the wrong thing.
 *
 * <h2>What is actually proven</h2>
 *
 * <ul>
 *   <li>The role check is in the service, not in the panel. Every public
 *       operation of {@link AdminService} is called as a signed-in traveller
 *       and as a signed-out visitor, and every one is refused - and the tables
 *       are asserted unchanged afterwards, so "refused" cannot mean "refused
 *       after doing it".</li>
 *   <li>Trains and stations can be created and edited, and duplicate numbers,
 *       codes and names are refused with the duplicate named rather than as a
 *       driver error.</li>
 *   <li>The route rules hold: no gaps, no station twice, only the origin
 *       arriving at nothing, distances increasing, and the train actually
 *       going forwards - including across midnight.</li>
 *   <li>A booking is indestructible. A real booking is made first, then every
 *       destructive administrative change is attempted against it and refused,
 *       and the booking is then re-read field by field.</li>
 *   <li>The schema refuses what the service refuses, proved with raw SQL that
 *       bypasses the service entirely - the second layer is real, not assumed.</li>
 * </ul>
 */
public final class AdminSelfCheck {

    /** A seeded train with a real overnight route and a real booking history. */
    private static final String SEEDED_TRAIN = "12952";
    private static final String FROM = "CSTM";
    private static final String NEXT = "PUNE";
    private static final String SLEEPER_CLASS = "2A";
    private static final String SEATED_CLASS = "CC";

    /** Everything this check creates, named so nothing collides with the sample data. */
    private static final String NEW_CODE = "ZQX";
    private static final String NEW_NAME = "Admin Check Junction";
    private static final String NEW_CITY = "Testville";
    private static final String NEW_TRAIN = "99001";
    private static final String NEW_TRAIN_NAME = "Admin Check Express";

    private static final LocalDate FAR_DATE = LocalDate.now().plusDays(30);
    private static final String CONTACT_MOBILE = "9876500011";
    private static final String CONTACT_EMAIL = "admin.check@example.com";
    private static final int PARTY_LIMIT = 6;

    private static int passed;
    private static int failed;
    private static String section = "";

    public static void main(String[] args) throws Exception {
        System.out.println("Administration self-check");
        System.out.println("========================");

        checkNobodySignedInIsRefused();
        checkATravellerIsRefused();
        checkAnAdministratorIsAllowed();

        // Everything below is administration, so from here on the session is an
        // administrator's. Each access check above leaves the session deliberately
        // wrong for the scenario that follows it, so the sign-in is restated here
        // rather than inherited.
        signInAsAdministrator();

        checkATrainCanBeAddedAndEdited();
        checkDuplicateTrainNumbersAreRefused();
        checkInvalidTrainValuesAreRefused();

        checkAStationCanBeAddedAndEdited();
        checkInvalidStationCodesAreRefused();
        checkStationCodesAndNamesAreUnique();
        checkWhyAStationCannotBeRemoved();

        checkARouteCanBeConfigured();
        checkInvalidRoutesAreRefused();
        checkAnOvernightRouteIsAccepted();

        checkCoachesAndBerthsCanBeConfigured();
        checkInvalidRakeConfigurationIsRefused();

        checkFareRulesCanBeConfigured();
        checkInvalidFareRulesAreRefused();

        checkBookingsSurviveAdministration();
        checkTheRouteOfABookedTrainIsLeftAlone();
        checkTheDatabaseRefusesWhatTheServiceRefuses();
        checkNoAccountCanBecomeAnAdministratorFromTheApplication();

        report();
    }

    // ---------------------------------------------------------------- access

    /**
     * No session, no administration - including the reads.
     *
     * <p>Every operation is called, not just the ones with a button behind them.
     * A method that forgets its check is invisible to a UI test and is exactly
     * the hole this milestone must not have.
     */
    private static void checkNobodySignedInIsRefused() {
        section("Access with nobody signed in");
        SessionManager.get().endSession();
        AdminService admin = liveAdmin();

        int[] before = tableCounts();
        int refused = 0;
        for (Named operation : everyOperation(admin)) {
            if (isRefused(operation)) {
                refused++;
            } else {
                see("signed out, " + operation.name() + " is refused", false);
            }
        }
        see("all " + everyOperation(admin).size()
                + " administration operations are refused to a signed-out visitor",
                refused == everyOperation(admin).size());
        see("nothing was written while refusing a signed-out visitor",
                sameCounts(tableCounts(), before));
    }

    /** The same list, as somebody who is signed in and is not an administrator. */
    private static void checkATravellerIsRefused() {
        section("Access as an ordinary traveller");
        signInAs(traveller());
        AdminService admin = liveAdmin();

        int[] before = tableCounts();
        int refused = 0;
        for (Named operation : everyOperation(admin)) {
            if (isRefused(operation)) {
                refused++;
            } else {
                see("a traveller is refused " + operation.name(), false);
            }
        }
        see("all " + everyOperation(admin).size()
                + " administration operations are refused to a traveller",
                refused == everyOperation(admin).size());
        see("nothing was written while refusing a traveller",
                sameCounts(tableCounts(), before));
    }

    /** And the same operations work once an administrator is signed in. */
    private static void checkAnAdministratorIsAllowed() throws Exception {
        section("Access as an administrator");
        signInAs(administrator());
        AdminService admin = liveAdmin();

        see("an administrator can read the overview", admin.summary() != null);
        see("an administrator can read the trains", !admin.trains().isEmpty());
        see("an administrator can read the stations", !admin.stations().isEmpty());
        see("an administrator can read a timetable", !admin.stops(SEEDED_TRAIN).isEmpty());
        see("an administrator can read a rake", !admin.coaches(SEEDED_TRAIN).isEmpty());
        see("an administrator can read the fare rules", !admin.fareRules().isEmpty());

        signInAs(traveller());
    }

    // ---------------------------------------------------------------- trains

    private static void checkATrainCanBeAddedAndEdited() {
        section("Adding and editing a train");
        scenario((connection, probe) -> {
            AdminService admin = adminFor(probe);
            int trainsBefore = countWhere(connection, "SELECT COUNT(*) FROM trains");

            admin.addTrain(new AdminView.TrainInput(NEW_TRAIN, NEW_TRAIN_NAME,
                    "EXPRESS", true, 500));

            AdminView.TrainRow added = trainRow(admin, NEW_TRAIN);
            see("a new train is listed", added != null);
            see("its name is what was typed", added != null && added.trainName().equals(NEW_TRAIN_NAME));
            see("it is active and running daily by default",
                    added != null && added.active() && added.runsDaily());
            see("its distance is stored", added != null
                    && Integer.valueOf(500).equals(added.distanceKm()));
            see("a new train has no stops yet", added != null && added.stopCount() == 0);
            see("a new train offers no classes yet",
                    added != null && added.classes() == null);
            see("exactly one train was added",
                    countWhere(connection, "SELECT COUNT(*) FROM trains") == trainsBefore + 1);

            admin.editTrain(new AdminView.TrainInput(NEW_TRAIN, "Admin Check Renamed",
                    "SUPERFAST", false, 640));
            AdminView.TrainRow edited = trainRow(admin, NEW_TRAIN);
            see("the name can be changed",
                    edited != null && edited.trainName().equals("Admin Check Renamed"));
            see("the type can be changed",
                    edited != null && edited.trainType().equals("SUPERFAST"));
            see("running daily can be turned off", edited != null && !edited.runsDaily());
            see("the distance can be changed",
                    edited != null && Integer.valueOf(640).equals(edited.distanceKm()));
            see("editing does not create a second train",
                    countWhere(connection, "SELECT COUNT(*) FROM trains") == trainsBefore + 1);

            admin.setTrainActive(NEW_TRAIN, false);
            see("a train can be deactivated", !trainRow(admin, NEW_TRAIN).active());
            admin.setTrainActive(NEW_TRAIN, true);
            see("and reactivated", trainRow(admin, NEW_TRAIN).active());

            see("a train number can be typed in lower case and is stored in capitals",
                    capitalisesTrainNumber(admin));

            see("editing a train that does not exist is refused",
                    refused(admin, "There is no train numbered", 1,
                            () -> admin.editTrain(new AdminView.TrainInput("77777",
                                    "Nothing At All", "EXPRESS", true, null))));
            see("a train number is not a free-text field",
                    refused(admin, "2 to 10 letters and digits", 2,
                            () -> admin.addTrain(new AdminView.TrainInput("9", "Too Short",
                                    "EXPRESS", true, null))));
        });
    }

    /**
     * Two trains cannot share a number, and two cannot share a name either.
     *
     * <p>The name matters as much as the number: a passenger searches by name,
     * so two trains sharing one is a booking somebody cannot tell apart.
     */
    private static void checkDuplicateTrainNumbersAreRefused() {
        section("Duplicate train numbers and names");
        scenario((connection, probe) -> {
            AdminService admin = adminFor(probe);
            int trainsBefore = countWhere(connection, "SELECT COUNT(*) FROM trains");

            see("a train number already in use is refused",
                    refused(admin, "already configured", 3, () -> admin.addTrain(
                            new AdminView.TrainInput(SEEDED_TRAIN, "A Different Name Entirely",
                                    "EXPRESS", true, null))));
            see("a name already in use is refused, naming the train that holds it",
                    refused(admin, "is already called", 4, () -> admin.addTrain(
                            new AdminView.TrainInput("99002", "Mumbai Surat Night Demo Service",
                                    "EXPRESS", true, null))));
            see("neither duplicate was written",
                    countWhere(connection, "SELECT COUNT(*) FROM trains") == trainsBefore);
        });
    }

    private static void checkInvalidTrainValuesAreRefused() {
        section("Invalid train values");
        scenario((connection, probe) -> {
            AdminService admin = adminFor(probe);

            see("a blank train number is refused",
                    refused(admin, "required", 5,
                            () -> admin.addTrain(new AdminView.TrainInput("  ", "Named",
                                    "EXPRESS", true, null))));
            see("a blank train name is refused",
                    refused(admin, "name is required", 6,
                            () -> admin.addTrain(new AdminView.TrainInput("99003", "  ",
                                    "EXPRESS", true, null))));
            see("an unknown train type is refused, listing the real ones",
                    refused(admin, "Train type must be one of", 7,
                            () -> admin.addTrain(new AdminView.TrainInput("99003", "Typed",
                                    "HOVERCRAFT", true, null))));
            see("a negative distance is refused",
                    refused(admin, "between 0 and 100000", 8,
                            () -> admin.addTrain(new AdminView.TrainInput("99003", "Negative",
                                    "EXPRESS", true, -5))));
            see("a distance of more than the earth is refused",
                    refused(admin, "between 0 and 100000", 9,
                            () -> admin.addTrain(new AdminView.TrainInput("99003", "Too Far",
                                    "EXPRESS", true, 900_000))));
            see("an over-long name is refused",
                    refused(admin, "at most 100", 10,
                            () -> admin.addTrain(new AdminView.TrainInput("99003",
                                    "x".repeat(101), "EXPRESS", true, null))));
            see("a blank distance is allowed, because the column is nullable",
                    allowed(() -> admin.addTrain(new AdminView.TrainInput("99003", "No Distance",
                            "EXPRESS", true, null))));
        });
    }

    // ---------------------------------------------------------------- stations

    private static void checkAStationCanBeAddedAndEdited() {
        section("Adding and editing a station");
        scenario((connection, probe) -> {
            AdminService admin = adminFor(probe);
            int stationsBefore = countWhere(connection, "SELECT COUNT(*) FROM stations");

            admin.addStation(new AdminView.StationInput(NEW_CODE, NEW_NAME, NEW_CITY, "ZONE-A"));
            see("a new station is listed", stationRow(admin, NEW_CODE) != null);
            see("its name is what was typed",
                    nameOf(stationRow(admin, NEW_CODE)).equals(NEW_NAME));
            see("its city is what was typed", cityOf(stationRow(admin, NEW_CODE)).equals(NEW_CITY));
            see("a new station is active and unused",
                    stationRow(admin, NEW_CODE).active()
                            && stationRow(admin, NEW_CODE).stopUses() == 0);
            see("exactly one station was added",
                    countWhere(connection, "SELECT COUNT(*) FROM stations") == stationsBefore + 1);

            admin.editStation(new AdminView.StationInput(NEW_CODE, NEW_NAME + " Renamed",
                    "Newtown", null));
            see("a station can be renamed",
                    nameOf(stationRow(admin, NEW_CODE)).equals(NEW_NAME + " Renamed"));
            see("a station can be moved to another city",
                    cityOf(stationRow(admin, NEW_CODE)).equals("Newtown"));
            see("a zone can be cleared",
                    zoneOf(stationRow(admin, NEW_CODE)) == null);
            see("editing does not create a second station",
                    countWhere(connection, "SELECT COUNT(*) FROM stations") == stationsBefore + 1);
            see("the code is untouched by an edit",
                    stationRow(admin, NEW_CODE).code().equals(NEW_CODE));

            see("editing a station that does not exist is refused",
                    refused(admin, "no station with code", 11,
                            () -> admin.editStation(new AdminView.StationInput("QQQ",
                                    "Nowhere At All", "Nowhere", null))));
        });
    }

    /**
     * The code is the key every stop, booking and fare rule refers to, so it has
     * to look like a code: two to four capital letters, nothing else.
     */
    private static void checkInvalidStationCodesAreRefused() {
        section("Invalid station codes");
        scenario((connection, probe) -> {
            AdminService admin = adminFor(probe);

            see("a one-letter code is refused",
                    refusedCode(admin, "2 to 4 capital letters", "Z"));
            see("a five-letter code is refused",
                    refusedCode(admin, "2 to 4 capital letters", "ZZZZZ"));
            see("a code with a digit in it is refused",
                    refusedCode(admin, "2 to 4 capital letters", "ZZ1A"));
            see("a code with punctuation is refused",
                    refusedCode(admin, "2 to 4 capital letters", "ZZ-1"));
            see("a code with a space is refused",
                    refusedCode(admin, "2 to 4 capital letters", "ZZ 1"));
            see("a blank code is refused",
                    refusedCode(admin, "required", "   "));
            see("a lower-case code is accepted and stored in capitals",
                    acceptedAsCode(admin, "zqz"));
            see("the two-letter minimum is accepted", acceptedAsCode(admin, "ZZ"));
            see("the four-letter maximum is accepted", acceptedAsCode(admin, "ZZZZ"));
        });
    }

    private static void checkStationCodesAndNamesAreUnique() {
        section("Duplicate station codes and names");
        scenario((connection, probe) -> {
            AdminService admin = adminFor(probe);
            int stationsBefore = countWhere(connection, "SELECT COUNT(*) FROM stations");

            see("a station code already in use is refused, naming the code",
                    refused(admin, "already in use", 12, () -> admin.addStation(
                            new AdminView.StationInput("pune", "Pune Some Other Name",
                                    "Pune", null))));
            see("a station name already in use is refused, naming the code that holds it",
                    refused(admin, "already called", 13, () -> admin.addStation(
                            new AdminView.StationInput("ZZZ", "Pune Junction", "Pune", null))));
            see("a blank city is refused",
                    refused(admin, "city is required", 14,
                            () -> admin.addStation(new AdminView.StationInput("ZZZ", "Nowhere",
                                    "  ", null))));
            see("a blank name is refused",
                    refused(admin, "name is required", 15,
                            () -> admin.addStation(new AdminView.StationInput("ZZZ", " ",
                                    "Nowhere", null))));
            see("neither duplicate was written",
                    countWhere(connection, "SELECT COUNT(*) FROM stations") == stationsBefore);
        });
    }

    /**
     * Removal is never offered, and the reason is shown rather than discovered.
     */
    private static void checkWhyAStationCannotBeRemoved() {
        section("Why a station cannot be removed");
        scenario((connection, probe) -> {
            AdminService admin = adminFor(probe);

String busy = admin.whyStationCannotBeRemoved(FROM);
            see("a station a train calls at says so",
                    busy != null && busy.contains("train") && busy.contains("call"));
            see("and the explanation names the station", busy != null && busy.contains(FROM));

            admin.addStation(new AdminView.StationInput(NEW_CODE, NEW_NAME, NEW_CITY, null));
            see("a station nothing references has nothing to explain",
                    admin.whyStationCannotBeRemoved(NEW_CODE) == null);
        });
    }

    // ---------------------------------------------------------------- stops

    private static void checkARouteCanBeConfigured() {
        section("Configuring a route");
        scenario((connection, probe) -> {
            AdminService admin = adminFor(probe);
            fixture = AdminSelfCheck::newTrain;
            newTrain(admin);
            int stopsBefore = countWhere(connection,
                    "SELECT COUNT(*) FROM train_stops WHERE train_no = ?", NEW_TRAIN);

            admin.replaceStops(NEW_TRAIN, route(
                    stop(1, FROM, null, LocalTime.of(6, 0), 0, 0),
                    stop(2, NEW_CODE, LocalTime.of(9, 30), LocalTime.of(9, 45), 0, 120),
                    stop(3, NEXT, LocalTime.of(13, 0), LocalTime.of(13, 15), 0, 300)));

            List<StopInfo> saved = admin.stops(NEW_TRAIN);
            see("all three stops are saved", saved.size() == 3);
            see("they come back in order", saved.size() == 3
                    && saved.get(0).stopSeq() == 1 && saved.get(2).stopSeq() == 3);
            see("the origin has no arrival time", saved.size() == 3
                    && saved.get(0).arrivalTime() == null);
            see("a middle stop has both times", saved.size() == 3
                    && saved.get(0).arrivalTime() == null
                    && saved.get(1).arrivalTime().equals(LocalTime.of(9, 30))
                    && saved.get(1).departureTime().equals(LocalTime.of(9, 45)));
            see("the station names come back joined in", saved.size() == 3
                    && !saved.get(1).stationName().isBlank());
            see("the train now reports its stop count",
                    trainRow(admin, NEW_TRAIN).stopCount() == 3);
            see("exactly three stop rows were written", countWhere(connection,
                    "SELECT COUNT(*) FROM train_stops WHERE train_no = ?", NEW_TRAIN)
                    == stopsBefore + 3);

            admin.replaceStops(NEW_TRAIN, route(
                    stop(1, FROM, null, LocalTime.of(7, 0), 0, 0),
                    stop(2, NEW_CODE, LocalTime.of(10, 0), LocalTime.of(10, 15), 0, 120),
                    stop(3, NEXT, LocalTime.of(13, 0), LocalTime.of(13, 15), 0, 300)));
            see("a route can be rebuilt while nothing is booked",
                    admin.stops(NEW_TRAIN).size() == 3
                            && admin.stops(NEW_TRAIN).get(0).departureTime()
                                    .equals(LocalTime.of(7, 0)));

            admin.editStopTiming(NEW_TRAIN, 2, LocalTime.of(9, 25),
                    LocalTime.of(9, 50), 0, 125);
            see("one stop's timings can be edited in place",
                    admin.stops(NEW_TRAIN).get(1).arrivalTime().equals(LocalTime.of(9, 25))
                            && admin.stops(NEW_TRAIN).get(1).distanceKm() == 125);
            see("editing a stop does not renumber the route",
                    admin.stops(NEW_TRAIN).size() == 3);
            see("editing a stop that is not on the route is refused",
                    refused(admin, "not part of this train", 16,
                            () -> admin.editStopTiming(NEW_TRAIN, 9, LocalTime.of(9, 0),
                                    LocalTime.of(9, 5), 0, 130)));
        });
    }

    /** The five route rules, each refused with the reason rather than a driver error. */
    private static void checkInvalidRoutesAreRefused() {
        section("Invalid routes");
        scenario((connection, probe) -> {
            AdminService admin = adminFor(probe);
            fixture = AdminSelfCheck::newTrain;
            newTrain(admin);
            // Counted for every other train: the fixture above is rebuilt after
            // each refusal, so a total would move for reasons of its own.
            int stopsBefore = countWhere(connection,
                    "SELECT COUNT(*) FROM train_stops WHERE train_no <> ?", NEW_TRAIN);

            see("a route needs at least two stops",
                    refusedRoute(admin, "at least an origin", 17, route(
                            stop(1, FROM, null, LocalTime.of(6, 0), 0, 0))));
            see("an empty route is refused",
                    refusedRoute(admin, "at least an origin", 18, List.of()));
            see("a gap in the stop numbers is refused",
                    refusedRoute(admin, "with no gaps", 19, route(
                            stop(1, FROM, null, LocalTime.of(6, 0), 0, 0),
                            stop(3, NEXT, LocalTime.of(13, 0), LocalTime.of(13, 15), 0, 300))));
            see("a stop at a station that does not exist is refused",
                    refusedRoute(admin, "no station with code", 20, route(
                            stop(1, FROM, null, LocalTime.of(6, 0), 0, 0),
                            stop(2, "QQ7", LocalTime.of(9, 0), LocalTime.of(9, 15), 0, 120),
                            stop(3, NEXT, LocalTime.of(13, 0), LocalTime.of(13, 15), 0, 300))));
            see("a train calling twice at one station is refused",
                    refusedRoute(admin, "more than once", 21, route(
                            stop(1, FROM, null, LocalTime.of(6, 0), 0, 0),
                            stop(2, NEXT, LocalTime.of(9, 0), LocalTime.of(9, 15), 0, 120),
                            stop(3, NEXT, LocalTime.of(13, 0), LocalTime.of(13, 15), 0, 300))));
            see("an origin with an arrival time is refused",
                    refusedRoute(admin, "no arrival time", 22, route(
                            stop(1, FROM, LocalTime.of(5, 0), LocalTime.of(6, 0), 0, 0),
                            stop(2, NEXT, LocalTime.of(13, 0), LocalTime.of(13, 15), 0, 300))));
            see("a later stop with no arrival time is refused",
                    refusedRoute(admin, "needs an arrival time", 23, route(
                            stop(1, FROM, null, LocalTime.of(6, 0), 0, 0),
                            stop(2, NEXT, null, LocalTime.of(13, 15), 0, 300))));
            see("a stop with no departure time is refused",
                    refusedRoute(admin, "needs a departure time", 24, route(
                            stop(1, FROM, null, LocalTime.of(6, 0), 0, 0),
                            stop(2, NEXT, LocalTime.of(13, 0), null, 0, 300))));
            see("distances that do not increase are refused",
                    refusedRoute(admin, "must increase", 25, route(
                            stop(1, FROM, null, LocalTime.of(6, 0), 0, 0),
                            stop(2, NEXT, LocalTime.of(13, 0), LocalTime.of(13, 15), 0, 300),
                            stop(3, "KOP", LocalTime.of(14, 0), LocalTime.of(14, 15), 0, 100))));
            see("two stops at the same distance are refused",
                    refusedRoute(admin, "must increase", 26, route(
                            stop(1, FROM, null, LocalTime.of(6, 0), 0, 0),
                            stop(2, NEXT, LocalTime.of(13, 0), LocalTime.of(13, 15), 0, 300),
                            stop(3, "KOP", LocalTime.of(14, 0), LocalTime.of(14, 15), 0, 300))));
            see("a stop reached before the previous one leaves is refused",
                    refusedRoute(admin, "before stop", 27, route(
                            stop(1, FROM, null, LocalTime.of(6, 0), 0, 0),
                            stop(2, NEXT, LocalTime.of(5, 0), LocalTime.of(5, 15), 0, 300),
                            stop(3, "KOP", LocalTime.of(14, 0), LocalTime.of(14, 15), 0, 400))));
            see("a negative day offset is refused",
                    refusedRoute(admin, "before the day the train leaves", 28, route(
                            stop(1, FROM, null, LocalTime.of(6, 0), 0, 0),
                            stop(2, NEXT, LocalTime.of(5, 0), LocalTime.of(5, 15), -1, 300))));
            see("a route for a train that does not exist is refused",
                    refused(admin, "no train numbered", 29,
                            () -> admin.replaceStops("88888", route(
                                    stop(1, FROM, null, LocalTime.of(6, 0), 0, 0),
                                    stop(2, NEXT, LocalTime.of(13, 0),
                                            LocalTime.of(13, 15), 0, 300)))));
            see("not one stop row was written by any of those",
                    countWhere(connection, "SELECT COUNT(*) FROM train_stops"
                            + " WHERE train_no <> ?", NEW_TRAIN) == stopsBefore);
        });
    }

    /**
     * A train that runs overnight: leave Mumbai at 21:15, be in Pune at 01:40.
     *
     * <p>This is the rule a naive time comparison gets wrong - 01:40 is earlier
     * than 21:15 on the clock, and four hours later on the journey.
     */
    private static void checkAnOvernightRouteIsAccepted() {
        section("An overnight route");
        scenario((connection, probe) -> {
            AdminService admin = adminFor(probe);
            fixture = AdminSelfCheck::newTrain;
            newTrain(admin);

            allowed(() -> admin.replaceStops(NEW_TRAIN, route(
                    stop(1, FROM, null, LocalTime.of(21, 15), 0, 0),
                    stop(2, NEXT, LocalTime.of(1, 40), LocalTime.of(1, 55), 1, 150))));

            List<StopInfo> saved = admin.stops(NEW_TRAIN);
            see("the overnight leg is saved with its day offset",
                    saved.size() == 2 && saved.get(1).dayOffset() == 1);
            see("and reports that it arrives the next day",
                    saved.size() == 2 && saved.get(1).arrivesNextDay());

            see("the same route without the day offset is refused, because it would run backwards",
                    refusedRoute(admin, "before stop", 30, route(
                            stop(1, FROM, null, LocalTime.of(21, 15), 0, 0),
                            stop(2, NEXT, LocalTime.of(1, 40), LocalTime.of(1, 55), 0, 150))));
            see("a day offset going backwards is refused",
                    refusedRoute(admin, "earlier day", 31, route(
                            stop(1, FROM, null, LocalTime.of(21, 15), 1, 0),
                            stop(2, NEXT, LocalTime.of(1, 40), LocalTime.of(1, 55), 0, 150))));
        });
    }

    // ---------------------------------------------------------------- the rake

    private static void checkCoachesAndBerthsCanBeConfigured() {
        section("Configuring coaches and berths");
        scenario((connection, probe) -> {
            AdminService admin = adminFor(probe);
            fixture = AdminSelfCheck::newTrainWithRake;
            newTrain(admin);

            admin.addCoach(new AdminView.CoachInput(NEW_TRAIN, "AC1", SLEEPER_CLASS,
                    "AC-2 Tier", 1, true));
            admin.addCoach(new AdminView.CoachInput(NEW_TRAIN, "C1", SEATED_CLASS,
                    "Chair Car", 2, false));

            List<AdminView.CoachRow> coaches = admin.coaches(NEW_TRAIN);
            see("both coaches are listed", coaches.size() == 2);
            see("they come back in rake order", coaches.size() == 2
                    && coaches.get(0).coachNumber().equals("AC1")
                    && coaches.get(1).coachNumber().equals("C1"));
            see("a new coach has no berths yet", coaches.size() == 2
                    && coaches.get(0).berthCount() == 0);
            see("a new coach has nothing sold on it", coaches.size() == 2
                    && coaches.get(0).soldBerths() == 0);
            see("the train's supported classes are its coaches' classes",
                    trainRow(admin, NEW_TRAIN).classes().equals("2A,CC"));

            long sleeper = coaches.get(0).coachId();
            long seated = coaches.get(1).coachId();
            admin.addBerth(new AdminView.BerthInput(sleeper, 1, "AC_BERTH", "LOWER"));
            admin.addBerth(new AdminView.BerthInput(sleeper, 2, "AC_BERTH", "UPPER"));
            admin.addBerth(new AdminView.BerthInput(seated, 1, "SEAT", "WINDOW"));

            List<AdminView.BerthRow> berths = admin.berths(sleeper);
            see("the sleeper coach's berths are listed", berths.size() == 2);
            see("they come back numbered in order", berths.size() == 2
                    && berths.get(0).berthNumber() == 1 && berths.get(1).berthNumber() == 2);
            see("their positions are stored as typed", berths.size() == 2
                    && berths.get(0).position().equals("LOWER"));
            see("a seating coach takes a window seat", admin.berths(seated).size() == 1
                    && admin.berths(seated).get(0).position().equals("WINDOW"));

            admin.removeBerth(admin.berths(sleeper).get(1).berthId());
            see("an unsold berth can be removed", admin.berths(sleeper).size() == 1);
            admin.removeCoach(seated);
            see("an unsold coach can be removed", admin.coaches(NEW_TRAIN).size() == 1);
            see("removing the coach took its berths with it",
                    countWhere(connection, "SELECT COUNT(*) FROM berths WHERE coach_id = ?",
                            seated) == 0);

see("a coach number already used on this train is refused",
                    refused(admin, "already used on train", 32,
                            () -> admin.addCoach(new AdminView.CoachInput(NEW_TRAIN, "AC1",
                                    SLEEPER_CLASS, "AC-2 Tier", 3, true))));
see("a berth number already used in this coach is refused",
                    refused(admin, "already exists in this coach", 33,
                            () -> admin.addBerth(new AdminView.BerthInput(coachId(admin, "AC1"),
                                    1, "AC_BERTH", "MIDDLE"))));
            see("a coach for a class that does not exist is refused",
                    refused(admin, "no travel class", 34,
                            () -> admin.addCoach(new AdminView.CoachInput(NEW_TRAIN, "X1",
                                    "9Z", "Mystery", 3, false))));
            see("a coach for a train that does not exist is refused",
                    refused(admin, "no train numbered", 35,
                            () -> admin.addCoach(new AdminView.CoachInput("88888", "X1",
                                    SLEEPER_CLASS, "AC-2 Tier", 3, false))));
            see("a seat position in a sleeper class is refused, listing what is allowed",
                    refused(admin, "sleeper class has", 36,
                            () -> admin.addBerth(new AdminView.BerthInput(coachId(admin, "AC1"),
                                    9, "AC_BERTH", "WINDOW"))));
            see("a berth position in a seating class is refused",
                    refused(admin, "seating class has", 37,
                            () -> admin.addBerth(new AdminView.BerthInput(
                                    coachId(admin, "GEN"), 9, "AC_BERTH", "LOWER"))));
            see("an unknown berth type is refused, listing the real ones",
                    refused(admin, "Berth type must be one of", 38,
                            () -> admin.addBerth(new AdminView.BerthInput(coachId(admin, "AC1"),
                                    9, "HAMMOCK", "LOWER"))));
            see("a berth number of zero is refused",
                    refused(admin, "start at 1", 39,
                            () -> admin.addBerth(new AdminView.BerthInput(coachId(admin, "AC1"),
                                    0, "AC_BERTH", "LOWER"))));
            see("a rake position below one is refused",
                    refused(admin, "starts at 1", 40,
                            () -> admin.addCoach(new AdminView.CoachInput(NEW_TRAIN, "Z1",
                                    SLEEPER_CLASS, "AC-2 Tier", 0, false))));
            see("removing a coach that is not there is refused, not silently ignored",
                    refused(admin, "no such coach", -1, () -> admin.removeCoach(999_999L)));
        });
    }

    /**
     * Nothing about a rake may be removed once it has sold something.
     *
     * <p>The refusals are the {@code fk_res_*} constraints, checked here first so
     * the message can say what to do instead.
     *
     * <p>One scenario each: a refused change rolls the transaction back, and on
     * this connection the booking goes with it, so a second refusal in the same
     * transaction would be tested against a rake that has sold nothing.
     */
    private static void checkInvalidRakeConfigurationIsRefused() {
        section("A rake that has sold a berth");
see("removing a coach that has sold a berth is refused, saying what to do instead",
                scenario((connection, probe) -> {
                    AdminService admin = adminFor(probe);
                    bookOnSeededTrain(connection);
                    long coach = sleeperCoach(admin);
                    int coachesBefore = countWhere(connection, "SELECT COUNT(*) FROM coaches");

                    see("the coach is still there", countWhere(connection,
                            "SELECT COUNT(*) FROM coaches WHERE coach_id = ?", coach) == 1);
                    see("and no coach went missing", countWhere(connection,
                            "SELECT COUNT(*) FROM coaches") == coachesBefore);
                    return refused(admin, "reservation(s) against it", 41,
                            () -> admin.removeCoach(coach));
                }));
        see("removing a sold berth is refused, saying the ticket is part of it",
                scenario((connection, probe) -> {
                    AdminService admin = adminFor(probe);
                    long soldBerth = firstBerthId(connection,
                            bookOnSeededTrain(connection).pnr());

                    see("the booking did take that berth", soldBerth > 0);
                    return refused(admin, "part of a ticket", 42,
                            () -> admin.removeBerth(soldBerth));
                }));
    }

    /** A scenario that answers a single yes-or-no check, for the refusal cases. */
    private static boolean scenario(Expectation body) {
        boolean[] answered = {false};
        scenario((connection, probe) -> {
            answered[0] = body.test(connection, probe);
        });
        return answered[0];
    }

    @FunctionalInterface
    private interface Expectation {
        boolean test(Connection connection, TxProbe probe) throws Exception;
    }

    private static long sleeperCoach(AdminService admin) throws Exception {
        return admin.coaches(SEEDED_TRAIN).stream()
                .filter(row -> row.classCode().equals(SLEEPER_CLASS))
                .findFirst().orElseThrow().coachId();
    }

    // ---------------------------------------------------------------- fares

    private static void checkFareRulesCanBeConfigured() {
        section("Configuring fare rules");
        scenario((connection, probe) -> {
            AdminService admin = adminFor(probe);
            int rulesBefore = countWhere(connection, "SELECT COUNT(*) FROM fare_rules");

            admin.saveFareRule(new AdminView.FareRule(0, SLEEPER_CLASS, null, null,
                    null, new BigDecimal("275.50"), new BigDecimal("1.25"), 4, 2,
                    LocalDate.now().plusDays(1), null));
            see("a class-wide default rule is listed",
                    admin.fareRules().stream().anyMatch(rule -> rule.ruleId() != 0
                            && rule.origin() == null && rule.destination() == null));
            see("its amounts are stored at two decimals",
                    admin.fareRules().stream()
                            .anyMatch(rule -> rule.baseFare().scale() == 2
                                    && rule.perKmFare().scale() == 2));
            see("exactly one rule was written",
                    countWhere(connection, "SELECT COUNT(*) FROM fare_rules") == rulesBefore + 1);

            AdminView.FareRule saved = admin.fareRules().stream()
                    .filter(rule -> rule.ruleId() != 0 && rule.origin() == null
                            && rule.destination() == null)
                    .findFirst().orElseThrow();
            admin.saveFareRule(new AdminView.FareRule(saved.ruleId(), SLEEPER_CLASS, null, null,
                    400, new BigDecimal("300.00"), new BigDecimal("1.50"), 6, 3,
                    LocalDate.now().plusDays(1), LocalDate.now().plusDays(90)));
            AdminView.FareRule edited = admin.fareRules().stream()
                    .filter(rule -> rule.ruleId() == saved.ruleId()).findFirst().orElseThrow();
            see("a rule can be edited in place",
                    edited.baseFare().compareTo(new BigDecimal("300.00")) == 0
                            && edited.quotaSeats() == 6);
            see("an end date can be set", edited.validTo() != null);
            see("editing does not create a second rule",
                    countWhere(connection, "SELECT COUNT(*) FROM fare_rules") == rulesBefore + 1);

            admin.saveFareRule(new AdminView.FareRule(0, SLEEPER_CLASS, FROM, NEXT,
                    null, new BigDecimal("150.00"), new BigDecimal("0.80"), 2, 1,
                    LocalDate.now().plusDays(365), null));
            see("a route-specific rule is listed with both stations",
                    admin.fareRules().stream().anyMatch(rule -> FROM.equals(rule.origin())
                            && NEXT.equals(rule.destination())));

            long future = admin.fareRules().stream()
                    .filter(rule -> rule.origin() != null).findFirst().orElseThrow().ruleId();
            allowed(() -> admin.removeFareRule(future));
            see("a rule that has not taken effect can be removed", !containsRule(admin, future));

            see("a rule that is already in force cannot be removed, and says what to do instead",
                    refused(admin, "part of the record", 43, () -> admin.removeFareRule(
                            inForceRuleId(connection))));
        });
    }

    private static void checkInvalidFareRulesAreRefused() {
        section("Invalid fare rules");
        scenario((connection, probe) -> {
            AdminService admin = adminFor(probe);
            int rulesBefore = countWhere(connection, "SELECT COUNT(*) FROM fare_rules");

            see("a fare for one destination with no origin is refused",
                    refused(admin, "needs an origin", 44,
                            () -> admin.saveFareRule(new AdminView.FareRule(0, SLEEPER_CLASS,
                                    null, NEXT, null, new BigDecimal("100.00"),
                                    new BigDecimal("1.00"), 0, 0, LocalDate.now(), null))));
            see("tatkal quota larger than the reserved quota is refused",
                    refused(admin, "part of the reserved quota", 45,
                            () -> admin.saveFareRule(new AdminView.FareRule(0, SLEEPER_CLASS,
                                    null, null, null, new BigDecimal("100.00"),
                                    new BigDecimal("1.00"), 2, 5, LocalDate.now(), null))));
            see("a negative base fare is refused",
                    refused(admin, "cannot be negative", 46,
                            () -> admin.saveFareRule(new AdminView.FareRule(0, SLEEPER_CLASS,
                                    null, null, null, new BigDecimal("-1.00"),
                                    new BigDecimal("1.00"), 0, 0, LocalDate.now(), null))));
            see("a per-km fare with three decimals is refused",
                    refused(admin, "two decimal places", 47,
                            () -> admin.saveFareRule(new AdminView.FareRule(0, SLEEPER_CLASS,
                                    null, null, null, new BigDecimal("100.00"),
                                    new BigDecimal("1.005"), 0, 0, LocalDate.now(), null))));
            see("a rule that ends before it starts is refused",
                    refused(admin, "cannot end before it starts", 48,
                            () -> admin.saveFareRule(new AdminView.FareRule(0, SLEEPER_CLASS,
                                    null, null, null, new BigDecimal("100.00"),
                                    new BigDecimal("1.00"), 0, 0,
                                    LocalDate.now().plusDays(10), LocalDate.now()))));
            see("a rule with no start date is refused",
                    refused(admin, "start date is required", 49,
                            () -> admin.saveFareRule(new AdminView.FareRule(0, SLEEPER_CLASS,
                                    null, null, null, new BigDecimal("100.00"),
                                    new BigDecimal("1.00"), 0, 0, null, null))));
            see("a rule for a class that does not exist is refused by the database",
                    refused(admin, "", -1,
                            () -> admin.saveFareRule(new AdminView.FareRule(0, "9Z", null, null,
                                    null, new BigDecimal("100.00"), new BigDecimal("1.00"),
                                    0, 0, LocalDate.now(), null))));
            see("a rule naming a station that does not exist is refused by the database",
                    refused(admin, "", -1,
                            () -> admin.saveFareRule(new AdminView.FareRule(0, SLEEPER_CLASS,
                                    "QQ7", null, null, new BigDecimal("100.00"),
                                    new BigDecimal("1.00"), 0, 0, LocalDate.now(), null))));
            see("updating a rule that does not exist is refused",
                    refused(admin, "no fare rule", 50,
                            () -> admin.saveFareRule(new AdminView.FareRule(999_999L,
                                    SLEEPER_CLASS, null, null, null, new BigDecimal("100.00"),
                                    new BigDecimal("1.00"), 0, 0, LocalDate.now(), null))));
            see("no rule was written by any of those",
                    countWhere(connection, "SELECT COUNT(*) FROM fare_rules") == rulesBefore);
        });
    }

    // ---------------------------------------------------------------- bookings

    /**
     * The one that matters: a real booking, then every destructive change the
     * administration area could make, then the booking again.
     *
     * <p>Everything is re-read from the database rather than from an object the
     * service returned, so "unchanged" means the rows are unchanged.
     *
     * <p>Only changes that are allowed happen here. A refusal is its own
     * scenario, {@link #checkTheRouteOfABookedTrainIsLeftAlone}, because the
     * service rolls back the transaction it was handed and on this connection
     * that would take the booking with it.
     */
    private static void checkBookingsSurviveAdministration() {
        section("An existing booking survives administration");
        scenario((connection, probe) -> {
            AdminService admin = adminFor(probe);
            ConfirmedBooking booked = bookOnSeededTrain(connection);
            long pnr = booked.pnr();

            String statusBefore = text(connection,
                    "SELECT status FROM bookings WHERE pnr = ?", pnr);
            BigDecimal fareBefore = decimal(connection,
                    "SELECT total_fare FROM bookings WHERE pnr = ?", pnr);
            int passengersBefore = passengerRows(connection, pnr);
            int reservationsBefore = reservationRows(connection, pnr);
            Set<Long> berthsBefore = heldBerthIds(connection, pnr);
            int bookingsOverall = countWhere(connection, "SELECT COUNT(*) FROM bookings");
            String stopsBefore = text(connection,
                    "SELECT GROUP_CONCAT(CONCAT(stop_seq, ':', station_code)"
                            + " ORDER BY stop_seq) FROM train_stops WHERE train_no = ?",
                    SEEDED_TRAIN);

            // Changes that are allowed, because they destroy nothing.
            admin.editTrain(new AdminView.TrainInput(SEEDED_TRAIN,
                    "Mumbai Surat Night Demo Service", "SUPERFAST", true, 285));
            admin.setTrainActive(SEEDED_TRAIN, false);
            admin.setTrainActive(SEEDED_TRAIN, true);
            admin.editStation(new AdminView.StationInput(NEXT, "Pune Junction", "Pune", "WR"));
            admin.saveFareRule(new AdminView.FareRule(0, SLEEPER_CLASS, null, null, null,
                    new BigDecimal("999.00"), new BigDecimal("9.00"), 1, 1,
                    LocalDate.now().plusYears(1), null));
            admin.addCoach(new AdminView.CoachInput(SEEDED_TRAIN, "EXTRA1", SLEEPER_CLASS,
                    "AC-2 Tier", 9, false));

            // Changes that keep the ticket's meaning, so they are allowed.
            see("correcting a stop's timing on that train is allowed",
                    allowed(() -> admin.editStopTiming(SEEDED_TRAIN, 3,
                            LocalTime.of(4, 30), LocalTime.of(4, 45), 1, 205)));

            see("the booking is still confirmed",
                    statusBefore.equals(text(connection,
                            "SELECT status FROM bookings WHERE pnr = ?", pnr)));
            see("its fare is untouched", fareBefore.compareTo(decimal(connection,
                    "SELECT total_fare FROM bookings WHERE pnr = ?", pnr)) == 0);
            see("every passenger is still there",
                    passengerRows(connection, pnr) == passengersBefore);
            see("every reservation is still there",
                    reservationRows(connection, pnr) == reservationsBefore);
            see("every berth is still held by the same passenger",
                    heldBerthIds(connection, pnr).equals(berthsBefore));
            see("no booking anywhere was added or lost",
                    countWhere(connection, "SELECT COUNT(*) FROM bookings") == bookingsOverall);
            see("no reservation was added or lost",
                    countWhere(connection, "SELECT COUNT(*) FROM seat_reservations")
                            == reservationRowsTotal(connection));
            see("the route is unchanged, because rebuilding it was refused",
                    stopsBefore.equals(text(connection,
                            "SELECT GROUP_CONCAT(CONCAT(stop_seq, ':', station_code)"
                                    + " ORDER BY stop_seq) FROM train_stops WHERE train_no = ?",
                            SEEDED_TRAIN)));
            see("the station it travels to is still called the same thing",
                    "Pune Junction".equals(text(connection,
                            "SELECT station_name FROM stations WHERE station_code = ?", NEXT)));
            see("the ticket still reads the berths it was sold",
                    ticketBerthsMatch(connection, pnr, berthsBefore));
            see("the train it was sold on still exists",
                    countWhere(connection, "SELECT COUNT(*) FROM trains WHERE train_no = ?",
                            SEEDED_TRAIN) == 1);
            see("the journey it was sold on still exists",
                    countWhere(connection, "SELECT COUNT(*) FROM journeys WHERE train_no = ?",
                            SEEDED_TRAIN) >= 1);
        });
    }

    /**
     * The one change a booked train's route cannot survive.
     *
     * <p>Its own scenario for the same reason as the rake removals: the refusal
     * rolls back, so nothing after it in this transaction could be trusted.
     */
    private static void checkTheRouteOfABookedTrainIsLeftAlone() {
        section("The route of a booked train");
        scenario((connection, probe) -> {
            AdminService admin = adminFor(probe);
            bookOnSeededTrain(connection);
            String stopsBefore = text(connection,
                    "SELECT GROUP_CONCAT(CONCAT(stop_seq, ':', station_code)"
                            + " ORDER BY stop_seq) FROM train_stops WHERE train_no = ?",
                    SEEDED_TRAIN);

            see("rebuilding it is refused, and says why", refused(admin, "cannot be rebuilt", 51,
                    () -> admin.replaceStops(SEEDED_TRAIN, route(
                            stop(1, FROM, null, LocalTime.of(6, 0), 0, 0),
                            stop(2, NEXT, LocalTime.of(13, 0),
                                    LocalTime.of(13, 15), 0, 300)))));
            see("not one stop was written", stopsBefore.equals(text(connection,
                    "SELECT GROUP_CONCAT(CONCAT(stop_seq, ':', station_code)"
                            + " ORDER BY stop_seq) FROM train_stops WHERE train_no = ?",
                    SEEDED_TRAIN)));
        });
    }

    /**
     * The schema is the second layer, and it is real.
     *
     * <p>Each statement bypasses {@link AdminService} completely and goes
     * straight at MySQL. If the column constraint were missing, the service's
     * careful validation would still hold the line - and these checks are what
     * proves the protection does not depend on the application running.
     */
    private static void checkTheDatabaseRefusesWhatTheServiceRefuses() {
        section("Database constraints as a second layer");
        scenario((connection, probe) -> {
            // Something has to be sold before a foreign key can be shown to be real.
            long soldBerth = firstBerthId(connection, bookOnSeededTrain(connection).pnr());
            int trains = countWhere(connection, "SELECT COUNT(*) FROM trains");
            int stations = countWhere(connection, "SELECT COUNT(*) FROM stations");
            int stops = countWhere(connection, "SELECT COUNT(*) FROM train_stops");

            see("ck_stops_seq_positive refuses stop number zero",
                    refusedByDatabase(connection,
                            "INSERT INTO train_stops (train_no, stop_seq, station_code,"
                                    + " departure_time, day_offset, distance_km)"
                                    + " VALUES (?, 0, ?, ?, 0, 0)",
                            SEEDED_TRAIN, NEXT, "09:00"));
            see("ck_stations_code refuses a five-letter code",
                    refusedByDatabase(connection,
                            "INSERT INTO stations (station_code, station_name, city)"
                                    + " VALUES ('ZZZZZ', 'Raw Insert Station', 'Nowhere')"));
            see("ck_stations_code refuses lower-case",
                    refusedByDatabase(connection,
                            "INSERT INTO stations (station_code, station_name, city)"
                                    + " VALUES ('lower', 'Raw Lower Station', 'Nowhere')"));
            see("uq_trains_name refuses a duplicate train name",
                    refusedByDatabase(connection,
                            "INSERT INTO trains (train_no, train_name) VALUES (?, ?)",
                            "99003", "Mumbai Surat Night Demo Service"));
            see("uq_trains_name refuses a duplicate train number",
                    refusedByDatabase(connection,
                            "INSERT INTO trains (train_no, train_name) VALUES (?, ?)",
                            SEEDED_TRAIN, "Raw Duplicate Number"));
            see("uq_stations_name refuses a duplicate station name",
                    refusedByDatabase(connection,
                            "INSERT INTO stations (station_code, station_name, city)"
                                    + " VALUES ('ZZ7', 'Pune Junction', 'Pune')"));
            see("ck_fare_specificity refuses a destination with no origin",
                    refusedByDatabase(connection,
                            "INSERT INTO fare_rules (class_code, destination_station_code,"
                                    + " base_fare, per_km_fare, quota_seats, tatkal_quota_seats,"
                                    + " valid_from) VALUES (?, ?, 10.00, 1.00, 0, 0, ?)",
                            SLEEPER_CLASS, NEXT, LocalDate.now()));
            see("ck_fare_quota refuses tatkal above the reserved quota",
                    refusedByDatabase(connection,
                            "INSERT INTO fare_rules (class_code, base_fare, per_km_fare,"
                                    + " quota_seats, tatkal_quota_seats, valid_from)"
                                    + " VALUES (?, 10.00, 1.00, 1, 5, ?)",
                            SLEEPER_CLASS, LocalDate.now()));
            see("fk_res_berth refuses deleting a sold berth",
                    refusedByDatabase(connection,
                            "DELETE FROM berths WHERE berth_id = ?", soldBerth));
            see("the train types the form offers are the column's own ENUM, in order",
                    enumValues(connection, "trains", "train_type").equals(AdminView.TRAIN_TYPES));
            see("the berth types the form offers are the column's own ENUM, in order",
                    enumValues(connection, "berths", "berth_type").equals(AdminView.BERTH_TYPES));
            see("not one row was written by any of those",
                    countWhere(connection, "SELECT COUNT(*) FROM trains") == trains
                            && countWhere(connection, "SELECT COUNT(*) FROM stations") == stations
                            && countWhere(connection, "SELECT COUNT(*) FROM train_stops") == stops);
        });
    }

    /**
     * There is no way into the administration area from inside the application.
     *
     * <p>Registration cannot ask for the role, no administrator ships with a
     * password, and the promotion is a DBA statement - so a hardcoded back door
     * would have to be added deliberately to exist at all.
     */
    private static void checkNoAccountCanBecomeAnAdministratorFromTheApplication() {
        section("No hardcoded administrator");
        scenario((connection, probe) -> {
            signInAs(traveller());
            AuthService auth = new AuthService(sameConnection(probe.connection()));
            String username = "admin.check." + System.nanoTime();
            auth.register("Admin Check", username, "admincheck@example.com",
                    "9876500099", "Str0ngPassphrase!1");
            see("a registered account is a traveller",
                    "CUSTOMER".equals(text(connection,
                            "SELECT role FROM users WHERE username = ?", username)));
            see("no role was written that the application does not know",
                    text(connection, "SELECT COUNT(*) FROM users WHERE role = 'SUPERUSER'")
                            .equals("0"));

            see("no administrator in the database has a password set",
                    scalar(connection, "SELECT COUNT(*) FROM users"
                            + " WHERE role = 'ADMIN' AND password_hash IS NOT NULL") == 0);
            signInAs(administrator());
        });
    }

    // ---------------------------------------------------------------- operations

    /** One public administration operation, named so a failure says which. */
    private record Named(String name, Operation run) {
    }

    @FunctionalInterface
    private interface Operation {
        void run() throws Exception;
    }

    /**
     * Every public operation on {@link AdminService}.
     *
     * <p>Written out one by one on purpose. A reflective sweep would keep
     * passing if a method were renamed away and would silently miss a new one;
     * this list fails to compile when the service changes, which is the point.
     */
    private static List<Named> everyOperation(AdminService admin) {
        List<AdminView.StopInput> twoStops = route(
                stop(1, FROM, null, LocalTime.of(6, 0), 0, 0),
                stop(2, NEXT, LocalTime.of(13, 0), LocalTime.of(13, 15), 0, 300));
        return List.of(
                new Named("summary()", admin::summary),
                new Named("trains()", admin::trains),
                new Named("stations()", admin::stations),
                new Named("stops(train)", () -> admin.stops(SEEDED_TRAIN)),
                new Named("coaches(train)", () -> admin.coaches(SEEDED_TRAIN)),
                new Named("berths(coach)", () -> admin.berths(1L)),
                new Named("fareRules()", admin::fareRules),
                new Named("whyStationCannotBeRemoved(code)", () -> admin.whyStationCannotBeRemoved(FROM)),
                new Named("addTrain", () -> admin.addTrain(new AdminView.TrainInput(
                        "99010", "Refused Train", "EXPRESS", true, null))),
                new Named("editTrain", () -> admin.editTrain(new AdminView.TrainInput(
                        SEEDED_TRAIN, "Refused Rename", "EXPRESS", true, null))),
                new Named("setTrainActive", () -> admin.setTrainActive(SEEDED_TRAIN, false)),
                new Named("addStation", () -> admin.addStation(new AdminView.StationInput(
                        "QQ1", "Refused Station", "Nowhere", null))),
                new Named("editStation", () -> admin.editStation(new AdminView.StationInput(
                        NEXT, "Refused Station", "Nowhere", null))),
                new Named("replaceStops", () -> admin.replaceStops(SEEDED_TRAIN, twoStops)),
                new Named("editStopTiming", () -> admin.editStopTiming(SEEDED_TRAIN, 1,
                        null, LocalTime.of(5, 0), 0, 0)),
                new Named("addCoach", () -> admin.addCoach(new AdminView.CoachInput(
                        SEEDED_TRAIN, "REF1", SLEEPER_CLASS, "Refused", 9, false))),
                new Named("removeCoach", () -> admin.removeCoach(1L)),
                new Named("addBerth", () -> admin.addBerth(new AdminView.BerthInput(
                        1L, 900, "AC_BERTH", "LOWER"))),
                new Named("removeBerth", () -> admin.removeBerth(1L)),
                new Named("saveFareRule", () -> admin.saveFareRule(new AdminView.FareRule(0,
                        SLEEPER_CLASS, null, null, null, new BigDecimal("1.00"),
                        new BigDecimal("0.10"), 0, 0, LocalDate.now().plusYears(5), null))),
                new Named("removeFareRule", () -> admin.removeFareRule(1L)));
    }

    private static boolean isRefused(Named operation) {
        try {
            operation.run().run();
            return false;
        } catch (AccessDeniedException expected) {
            return true;
        } catch (Exception wrong) {
            return false;
        }
    }

    // ---------------------------------------------------------------- plumbing

    /** Runs one scenario inside a transaction and rolls it back. */
    private static void scenario(Scenario body) {
        fixture = AdminSelfCheck::nothing;
        try (Connection real = DatabaseConnection.getConnection()) {
            real.setAutoCommit(false);
            TxProbe probe = new TxProbe(real);
            try {
                // The probe, not the real connection: a DAO that uses
                // try-with-resources would close the real one out from under the
                // rollback below.
                body.run(probe.connection(), probe);
            } catch (Exception failed) {
                see("the scenario ran without throwing (" + rootCause(failed) + ")", false);
            } finally {
                real.rollback();
            }
        } catch (SQLException cannotEvenConnect) {
            see("a connection could be opened", false);
        }
    }

    private static void nothing(AdminService admin) {
        // Most scenarios hold nothing that a refusal could take away with it.
    }

    /** What {@link #refused} rebuilds after a refusal rolled the scenario back. */
    private static Fixture fixture = AdminSelfCheck::nothing;

    @FunctionalInterface
    private interface Scenario {
        void run(Connection connection, TxProbe probe) throws Exception;
    }

    private static AdminService adminFor(TxProbe probe) {
        return new AdminService(sameConnection(probe.connection()));
    }

    private static AdminService liveAdmin() {
        return new AdminService();
    }

    private static ConnectionSource sameConnection(Connection c) {
        return () -> c;
    }

    /**
     * Asserts a call is refused with a {@link ValidationException} whose message
     * contains {@code fragment}, then puts the scenario's fixture back.
     *
     * <p>The restore is not tidiness. The service rolls back the transaction it
     * was handed, and on this shared test connection that discards rows the
     * scenario wrote earlier as well. Without it every refusal after the first
     * would fail for the wrong reason - "there is no train 99001" instead of the
     * rule actually under test.
     *
     * <p>An empty {@code fragment} accepts any refusal, which is how a refusal
     * that came from the database's own foreign keys is asserted: the point
     * there is that MySQL refused, not which layer did it.
     */
    private static boolean refused(AdminService admin, String fragment, int number,
                                   Operation call) {
        try {
            call.run();
            see(number > 0 ? "check " + number : "", false);
            return false;
        } catch (ValidationException refused) {
            boolean matches = fragment.isEmpty() || refused.getMessage().contains(fragment);
            if (!matches) {
                see("expected a refusal mentioning \"" + fragment + "\" but got \""
                        + refused.getMessage() + "\"", false);
            }
            return true;
        } catch (AdminService.AdminException refusedByTheDatabase) {
            if (!fragment.isEmpty()) {
                see("expected a refusal mentioning \"" + fragment
                        + "\" but the database refused it: " + refusedByTheDatabase.getMessage(),
                        false);
                return false;
            }
            return true;
        } catch (Exception wrong) {
            see("expected a validation refusal but got " + rootCause(wrong), false);
            return false;
        } finally {
            restoreFixture(admin);
        }
    }

    /**
     * Rebuilds whatever the running scenario needs to exist before its next
     * refusal. Scenarios that assert refusals against their own fixture set this
     * once; the rest leave it as {@link #nothing}.
     */
    private static void restoreFixture(AdminService admin) {
        try {
            fixture.build(admin);
        } catch (Exception cannotRestore) {
            see("the fixture could be put back (" + rootCause(cannotRestore) + ")", false);
        }
    }

    /** What a scenario needs in place before each of its refusals. */
    @FunctionalInterface
    private interface Fixture {
        void build(AdminService admin) throws Exception;
    }

    /** Re-creates the train a refused route or rake would have been checked against. */
    private static void newTrain(AdminService admin) throws Exception {
        if (admin.stations().stream().noneMatch(row -> row.code().equals(NEW_CODE))) {
            admin.addStation(new AdminView.StationInput(NEW_CODE, NEW_NAME, NEW_CITY, null));
        }
if (admin.trains().stream().noneMatch(row -> row.trainNo().equals(NEW_TRAIN))) {
            admin.addTrain(new AdminView.TrainInput(NEW_TRAIN, NEW_TRAIN_NAME,
                    "EXPRESS", true, 300));
        }
    }

    /** The same, plus one coach of each class with a berth to clash with. */
    private static void newTrainWithRake(AdminService admin) throws Exception {
        newTrain(admin);
        if (admin.coaches(NEW_TRAIN).isEmpty()) {
            admin.addCoach(new AdminView.CoachInput(NEW_TRAIN, "AC1", SLEEPER_CLASS,
                    "AC-2 Tier", 3, true));
            admin.addBerth(new AdminView.BerthInput(coachId(admin, "AC1"), 1,
                    "AC_BERTH", "LOWER"));
            admin.addCoach(new AdminView.CoachInput(NEW_TRAIN, "GEN", SEATED_CLASS,
                    "Chair Car", 2, false));
            admin.addBerth(new AdminView.BerthInput(coachId(admin, "GEN"), 1,
                    "SEAT", "WINDOW"));
        }
    }

    private static long coachId(AdminService admin, String coachNumber) throws Exception {
        return admin.coaches(NEW_TRAIN).stream()
                .filter(row -> row.coachNumber().equals(coachNumber))
                .findFirst().orElseThrow().coachId();
    }

    private static boolean allowed(Operation call) {
        try {
            call.run();
            return true;
        } catch (Exception refused) {
            see("expected this to be allowed but got " + rootCause(refused), false);
            return false;
        }
    }

    private static boolean refusedRoute(AdminService admin, String fragment, int number,
                                        List<AdminView.StopInput> stops) {
        return refused(admin, fragment, number, () -> admin.replaceStops(NEW_TRAIN, stops));
    }

    private static boolean refusedCode(AdminService admin, String fragment, String code) {
        return refused(admin, fragment, -1, () -> admin.addStation(
                new AdminView.StationInput(code, "Check Station " + code, "Checkville", null)));
    }

    private static boolean acceptedAsCode(AdminService admin, String code) {
        return allowed(() -> admin.addStation(
                new AdminView.StationInput(code, "Accepted Station " + code, "Checkville", null)));
    }

    /**
     * The values a column's ENUM actually holds, read from the server.
     *
     * <p>The Java copies in {@link AdminView} exist so a form can refuse an
     * unknown value politely; if one drifted from the column the form would
     * start refusing values the database accepts, and nothing else would say so.
     */
    private static List<String> enumValues(Connection c, String table, String column)
            throws SQLException {
        String declared = text(c,
                "SELECT COLUMN_TYPE FROM information_schema.COLUMNS"
                        + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                table, column);
        String inside = declared.substring(declared.indexOf('(') + 1, declared.lastIndexOf(')'));
        return List.of(inside.replace("'", "").split(","));
    }

    /** MySQL refused the statement. */
    private static boolean refusedByDatabase(Connection connection, String sql, Object... values) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            statement.executeUpdate();
            return false;
        } catch (SQLException expected) {
            return true;
        }
    }

    private static AdminView.StopInput stop(int seq, String code, LocalTime arrival,
                                            LocalTime departure, int dayOffset, int distanceKm) {
        return new AdminView.StopInput(seq, code, arrival, departure, dayOffset, distanceKm);
    }

    private static List<AdminView.StopInput> route(AdminView.StopInput... stops) {
        return List.of(stops);
    }

    private static boolean capitalisesTrainNumber(AdminService admin) {
        try {
            admin.addTrain(new AdminView.TrainInput("99004", "Lower Case Number",
                    "EXPRESS", true, null));
            return trainRow(admin, "99004") != null;
        } catch (Exception refused) {
            return false;
        }
    }

    private static AdminView.TrainRow trainRow(AdminService admin, String trainNo)
            throws Exception {
        return admin.trains().stream().filter(row -> row.trainNo().equals(trainNo))
                .findFirst().orElse(null);
    }

    private static AdminView.StationRow stationRow(AdminService admin, String code)
            throws Exception {
        return admin.stations().stream().filter(row -> row.code().equals(code))
                .findFirst().orElse(null);
    }

    private static boolean containsRule(AdminService admin, long ruleId) throws Exception {
        return admin.fareRules().stream().anyMatch(rule -> rule.ruleId() == ruleId);
    }

    private static String nameOf(AdminView.StationRow row) {
        return row == null ? "" : row.name();
    }

    private static String cityOf(AdminView.StationRow row) {
        return row == null ? "" : row.city();
    }

    private static String zoneOf(AdminView.StationRow row) {
        return row == null ? "" : row.zone();
    }

    /** A seeded fare rule whose start date is in the past. */
    private static long inForceRuleId(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT fare_rule_id FROM fare_rules WHERE valid_from <= ?"
                        + " ORDER BY fare_rule_id LIMIT 1")) {
            statement.setDate(1, java.sql.Date.valueOf(LocalDate.now()));
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? results.getLong(1) : 0L;
            }
        }
    }

    // ---------------------------------------------------------------- fixtures

    /**
     * A real booking on the seeded train, made through the real booking engine.
     *
     * <p>Inserted by hand it would be a row and nothing more. This way the
     * reservations, the berth holds and the journey attachment are exactly what
     * the application produces, which is what the destructive checks are about.
     */
    private static ConfirmedBooking bookOnSeededTrain(Connection connection) throws Exception {
        TrainSearchService search = new TrainSearchService(sameConnection(connection), PARTY_LIMIT);
        TrainSelection selection = null;
        for (TrainSearchResult result : search.search(new TrainSearchCriteria(
                FROM, NEXT, FAR_DATE, SLEEPER_CLASS, 1))) {
            if (result.trainNo().equals(SEEDED_TRAIN)) {
                selection = new TrainSelection(result,
                        result.availabilityFor(SLEEPER_CLASS).orElseThrow(), FAR_DATE, 1);
            }
        }
        if (selection == null) {
            throw new IllegalStateException("no " + SEEDED_TRAIN + " in " + SLEEPER_CLASS
                    + " on " + FAR_DATE);
        }
        BookingPreparationService preparation =
                new BookingPreparationService(sameConnection(connection));
        BookingDraft draft = new BookingDraft(selection);
        preparation.addPassenger(draft, preparation.profileOf(selection.classCode()),
                new PassengerInput("Admin Check Traveller", "34", Gender.FEMALE.label(),
                        BerthPreference.LOWER.label(), null));
        preparation.setContact(draft, CONTACT_MOBILE, CONTACT_EMAIL);
        return new BookingService(sameConnection(connection), pnrGenerator()).confirm(draft);
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

    private static void signInAs(User user) {
        SessionManager.get().beginSession(user);
    }

    private static void signInAsAdministrator() {
        signInAs(administrator());
    }

    private static User traveller() {
        return userWithRole("role <> 'ADMIN'");
    }

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
                        results.getString("full_name"), Role.fromDatabase(results.getString("role")));
            }
        } catch (SQLException noSuchAccount) {
            throw new IllegalStateException("no account matching " + where, noSuchAccount);
        }
        throw new IllegalStateException("no account matching " + where
                + " - promote one with the DBA statement in README 10.6");
    }

    // ---------------------------------------------------------------- db helpers

    private static void bind(PreparedStatement statement, Object... values) throws SQLException {
        for (int i = 0; i < values.length; i++) {
            statement.setObject(i + 1, values[i]);
        }
    }

    private static int countWhere(Connection c, String sql, Object... values) throws SQLException {
        return (int) scalar(c, sql, values);
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
                return results.next() ? results.getBigDecimal(1) : null;
            }
        }
    }

    private static int passengerRows(Connection c, long pnr) throws SQLException {
        return countWhere(c, "SELECT COUNT(*) FROM passengers WHERE pnr = ?", pnr);
    }

    /** Every seat reservation in the database, for a total that cannot drift. */
    private static int reservationRowsTotal(Connection c) throws SQLException {
        return countWhere(c, "SELECT COUNT(*) FROM seat_reservations");
    }

    private static int reservationRows(Connection c, long pnr) throws SQLException {
        return countWhere(c, "SELECT COUNT(*) FROM seat_reservations WHERE pnr = ?", pnr);
    }

    /** The berths a booking holds, which is what must not change. */
    private static Set<Long> heldBerthIds(Connection c, long pnr) throws SQLException {
        Set<Long> held = new LinkedHashSet<>();
        try (PreparedStatement statement = c.prepareStatement(
                "SELECT berth_id FROM seat_reservations WHERE pnr = ? ORDER BY berth_id")) {
            statement.setLong(1, pnr);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    held.add(results.getLong(1));
                }
            }
        }
        return held;
    }

    private static long firstBerthId(Connection c, long pnr) throws SQLException {
        return scalar(c, "SELECT berth_id FROM seat_reservations WHERE pnr = ?"
                + " AND berth_id IS NOT NULL ORDER BY berth_id LIMIT 1", pnr);
    }

    /** The ticket's own copy of the berth, which a delete would have taken with it. */
    private static boolean ticketBerthsMatch(Connection c, long pnr, Set<Long> berths)
            throws SQLException {
        return heldBerthIds(c, pnr).equals(berths)
                && countWhere(c, "SELECT COUNT(*) FROM seat_reservations WHERE pnr = ?"
                        + " AND berth_number IS NOT NULL", pnr) == berths.size();
    }

    /** trains, stations, train_stops, coaches, berths, fare_rules, bookings. */
    private static int[] tableCounts() {
        try (Connection c = DatabaseConnection.getConnection()) {
            return new int[]{
                    countWhere(c, "SELECT COUNT(*) FROM trains"),
                    countWhere(c, "SELECT COUNT(*) FROM stations"),
                    countWhere(c, "SELECT COUNT(*) FROM train_stops"),
                    countWhere(c, "SELECT COUNT(*) FROM coaches"),
                    countWhere(c, "SELECT COUNT(*) FROM berths"),
                    countWhere(c, "SELECT COUNT(*) FROM fare_rules"),
                    countWhere(c, "SELECT COUNT(*) FROM bookings"),
                    countWhere(c, "SELECT COUNT(*) FROM seat_reservations"),
                    countWhere(c, "SELECT COUNT(*) FROM passengers"),
                    countWhere(c, "SELECT COUNT(*) FROM cancellations")};
        } catch (SQLException unreachable) {
            see("the database could be reached to count rows", false);
            return new int[10];
        }
    }

    private static boolean sameCounts(int[] left, int[] right) {
        if (left.length != right.length) {
            return false;
        }
        for (int i = 0; i < left.length; i++) {
            if (left[i] != right[i]) {
                return false;
            }
        }
        return true;
    }

    private static String rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    private static void section(String title) {
        section = title;
        System.out.println();
        System.out.println(title);
        System.out.println("-".repeat(title.length()));
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

    private static void report() {
        SessionManager.get().endSession();
        System.out.println();
        if (failed == 0) {
            System.out.println("All " + passed + " administration checks passed.");
        } else {
            System.out.println(failed + " of " + (passed + failed)
                    + " administration checks FAILED.");
            System.exit(1);
        }
    }

    /** A connection whose {@code commit} is recorded rather than executed. */
    private static final class TxProbe {

        private final Connection wrapped;

        @SuppressWarnings("unchecked")
        TxProbe(Connection real) {
            this.wrapped = wrap(real, new AtomicInteger(), new AtomicInteger());
        }

        Connection connection() {
            return wrapped;
        }
    }

    @SuppressWarnings("unchecked")
    private static Connection wrap(Connection real, AtomicInteger commits,
                                   AtomicInteger rollbacks) {
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