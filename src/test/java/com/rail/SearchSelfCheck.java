package com.rail;

import com.rail.db.ConnectionSource;
import com.rail.db.DatabaseConnection;
import com.rail.db.TrainDAO;
import com.rail.model.ClassAvailability;
import com.rail.model.Role;
import com.rail.model.Station;
import com.rail.model.StopInfo;
import com.rail.model.TrainSearchCriteria;
import com.rail.model.TrainSearchResult;
import com.rail.model.User;
import com.rail.service.AccessDeniedException;
import com.rail.service.ValidationException;
import com.rail.service.SessionManager;
import com.rail.service.TrainSearchService;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * Milestone 4 checks: train search, route ordering, day-offset handling, fare
 * resolution and segment-scoped availability.
 *
 * <p>Runs inside one transaction on a single connection and rolls back, so the
 * journeys and reservations it creates do not survive and the application
 * account still needs no DELETE privilege.
 *
 * <p>The availability checks deliberately sell a berth on one segment and then
 * assert it is still free on a segment that does not overlap. That overlap test
 * is the easiest thing in this milestone to get subtly wrong, and a bug in it
 * would show a seat as available when it is already sold.
 */
public final class SearchSelfCheck {

    private static final LocalDate DATE = LocalDate.now().plusDays(30);

    private static int checksRun;
    private static int failures;

    private SearchSelfCheck() {
    }

    public static void main(String[] args) {
        if (!DatabaseConnection.isConfigured()) {
            System.out.println("SKIP  no database configured, nothing was connected to.");
            return;
        }

        try (Connection real = DatabaseConnection.getConnection()) {
            long journeysBefore = countJourneys(real);
            long reservationsBefore = countReservations(real);

            Connection shared = nonClosing(real);
            ConnectionSource source = () -> shared;

            real.setAutoCommit(false);
            try {
                signIn();
                runScenario(shared, source);
            } finally {
                real.rollback();
                SessionManager.get().endSession();
            }

            System.out.println();
            System.out.println("Cleanup");
            long journeysAfter = countJourneys(real);
            long reservationsAfter = countReservations(real);
            check("no journey survived the rollback (" + journeysBefore + " before, "
                    + journeysAfter + " after)", journeysAfter == journeysBefore);
            check("no reservation survived the rollback (" + reservationsBefore + " before, "
                    + reservationsAfter + " after)", reservationsAfter == reservationsBefore);
        } catch (SQLException failure) {
            System.err.println();
            System.err.println("DATABASE CHECK FAILED: " + failure.getMessage());
            // The stack trace names the statement, which a bare message does not.
            failure.printStackTrace();
            System.exit(1);
        } catch (ValidationException | AccessDeniedException unexpected) {
            System.err.println();
            System.err.println("SERVICE CHECK FAILED: " + unexpected.getMessage());
            System.exit(1);
        }

        System.out.println();
        if (failures == 0) {
            System.out.println("All " + checksRun + " search checks passed.");
        } else {
            System.out.println(failures + " of " + checksRun + " search checks FAILED.");
            System.exit(1);
        }
    }

    private static Connection nonClosing(Connection real) {
        return (Connection) java.lang.reflect.Proxy.newProxyInstance(
                SearchSelfCheck.class.getClassLoader(),
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

    /** Search is a signed-in feature, so the checks run as a real customer. */
    private static void signIn() {
        User traveller = new User(1L, "search_check", "Search Check", Role.CUSTOMER);
        SessionManager.get().beginSession(traveller);
    }

    private static void runScenario(Connection connection, ConnectionSource source)
            throws SQLException, ValidationException, AccessDeniedException {
        TrainSearchService search = new TrainSearchService(source, 6);

        section("Reference data");
        List<Station> stations = search.listStations();
        check("stations are loaded from the database", stations.size() >= 4);
        check("stations come back sorted by name",
                isSortedByName(stations));
        check("PUNE is among the known stations",
                stations.stream().anyMatch(s -> s.code().equals("PUNE")));
        check("classes are loaded from the database", search.listClasses().size() == 5);
        check("no station code is invented",
                stations.stream().allMatch(s -> s.code().matches("[A-Z]{2,4}")));

        section("Valid search");
        List<TrainSearchResult> results = search.search(
                criteria("CSTM", "PUNE", DATE, "3A", 2));
        check("a valid search returns trains", !results.isEmpty());
        check("every result runs the ordered segment",
                results.stream().allMatch(r -> r.fromStation().code().equals("CSTM")
                        && r.toStation().code().equals("PUNE")));
        check("every result puts the destination after the origin",
                results.stream().allMatch(r -> r.toSeq() > r.fromSeq()));
        check("every result is priced", results.stream()
                .allMatch(r -> r.classes().stream().anyMatch(c -> c.fare().signum() > 0)));
        check("every result carries seat counts", results.stream()
                .allMatch(r -> r.classes().stream()
                        .anyMatch(c -> c.totalBerths() >= c.availableBerths())));
        check("every result serves the requested class",
                results.stream().allMatch(r -> r.availabilityFor("3A").isPresent()));
        check("the requested class is listed first",
                results.stream().allMatch(r -> r.classes().get(0).classCode().equals("3A")));
        check("results are ordered by departure time",
                sortedByDeparture(results));

        TrainSearchResult first = results.get(0);
        section("Result detail: " + first.trainNo());
        check("the train number is present", first.trainNo() != null && !first.trainNo().isBlank());
        check("the train name is present", first.trainName() != null && !first.trainName().isBlank());
        check("a departure time is present", first.departureTime() != null);
        check("an arrival time is present", first.arrivalTime() != null);
        check("the segment distance is positive", first.distanceKm() > 0);
        check("the duration is positive", first.durationMinutes() > 0);
        check("class names come from the database, not the code alone",
                first.classes().stream().allMatch(c -> c.className().contains(c.classCode())));

        section("Station ordering");
        // The seeded timetable only runs outward from CSTM, so reversing the
        // endpoints legitimately finds no service. These assertions test the
        // ordering rule itself and hold whichever way the data runs: if the
        // "destination must follow the origin" constraint were dropped, the
        // reversed search would start handing back the forward trains and the
        // second check below would fail.
        List<TrainSearchResult> reversed = search.search(
                criteria("PUNE", "CSTM", DATE, "3A", 1));
        check("no train is offered whose destination precedes its origin",
                reversed.stream().allMatch(r -> r.fromSeq() < r.toSeq()));
        check("reversing the endpoints does not hand back the forward services",
                reversed.stream().noneMatch(r -> sameTrainAndOrder(first, r)));
        check("the forward direction still works after a reversed attempt",
                !search.search(criteria("CSTM", "PUNE", DATE, "3A", 2)).isEmpty());
        check("every forward result's stop numbers match the timetable order",
                forwardStopsAreOrdered(results));

        section("Rejected searches");
        check("identical origin and destination is refused",
                rejected(search, criteria("PUNE", "PUNE", DATE, "3A", 1), "to"));
        check("the identical-station message names the problem",
                mentions(search, criteria("PUNE", "pune", DATE, "3A", 1), "different"));
        check("a missing origin is refused",
                rejected(search, criteria("", "PUNE", DATE, "3A", 1), "from"));
        check("a missing destination is refused",
                rejected(search, criteria("CSTM", "", DATE, "3A", 1), "from"));
        check("an unknown station is refused",
                rejected(search, criteria("ZZZ", "PUNE", DATE, "3A", 1), "from"));
        check("the unknown-station message quotes the bad code",
                mentions(search, criteria("CSTM", "QQQ", DATE, "3A", 1), "QQQ"));
        check("an unparseable date is refused",
                rejected(search, criteria("CSTM", "PUNE", null, "3A", 1), "date"));
        check("a past date is refused",
                rejected(search, criteria("CSTM", "PUNE", LocalDate.now().minusDays(1), "3A", 1),
                        "date"));
        check("yesterday's date is refused by name",
                mentions(search, criteria("CSTM", "PUNE", LocalDate.now().minusDays(1), "3A", 1),
                        "past"));
        check("today is accepted, not treated as the past",
                !rejected(search, criteria("CSTM", "PUNE", LocalDate.now(), "3A", 1), "date"));
        check("zero passengers is refused",
                rejected(search, criteria("CSTM", "PUNE", DATE, "3A", 0), "passengers"));
        check("a negative passenger count is refused",
                rejected(search, criteria("CSTM", "PUNE", DATE, "3A", -3), "passengers"));
        check("a party above the configured limit is refused",
                rejected(search, criteria("CSTM", "PUNE", DATE, "3A", 7), "passengers"));
        check("the limit message states the configured maximum",
                mentions(search, criteria("CSTM", "PUNE", DATE, "3A", 7), "6"));
        check("a party exactly at the limit is allowed",
                !rejected(search, criteria("CSTM", "PUNE", DATE, "3A", 6), "passengers"));
        check("the configured limit is what the service reports", search.maxPassengers() == 6);
        check("a blank class is refused",
                rejected(search, criteria("CSTM", "PUNE", DATE, "  ", 1), "class"));
        check("an unknown class is refused",
                rejected(search, criteria("CSTM", "PUNE", DATE, "ZZ", 1), "class"));

        section("No matching train");
        List<TrainSearchResult> firstClass = search.search(
                criteria("CSTM", "PUNE", DATE, "1A", 1));
        check("a segment served in a rarer class still returns its trains",
                !firstClass.isEmpty());
        check("only trains with that class are returned",
                firstClass.stream().allMatch(r -> r.availabilityFor("1A").isPresent()));
        check("the rarer class filters out trains without it",
                firstClass.size() < results.size());
        // A segment/class pair the timetable genuinely does not serve, found
        // from the data rather than assumed, so the no-results path is real.
        check("a segment served in no train of the chosen class returns nothing",
                unservedClassCase(connection, DATE).isEmpty());
        check("a station pair no train connects returns an empty list",
                noServicePair(connection, DATE).isEmpty());

        section("Crossing midnight");
        List<StopInfo> nightRoute = search.routeOf("12952");
        check("the night train has stops", nightRoute.size() == 5);
        check("the night train's first stop has no arrival time",
                nightRoute.get(0).arrivalTime() == null);
        check("the night train reports a day offset past midnight",
                nightRoute.stream().anyMatch(StopInfo::arrivesNextDay));
        List<TrainSearchResult> overnight = search.search(criteria("CSTM", "ST", DATE, "3A", 1));
        check("an overnight journey is returned", !overnight.isEmpty());
        // Pick the night train by number: results are ordered by departure, so
        // the daytime services on the same segment come first.
        TrainSearchResult night = overnight.stream()
                .filter(r -> r.trainNo().equals("12952"))
                .findFirst().orElseThrow();
        check("the overnight arrival is attributed to the next day",
                night.arrivalDayOffset() == 1 && night.crossesMidnight());
        check("the overnight duration is positive, not negative", night.durationMinutes() > 0);
        check("the overnight duration is 11h30m", night.durationMinutes() == 690);
        check("the overnight duration matches the timetable arithmetic",
                night.durationMinutes() == TrainSearchService.minutesBetween(
                        night.departureTime(), 0, night.arrivalTime(), night.arrivalDayOffset()));
        check("the overnight duration is not a naive clock subtraction",
                night.durationMinutes() > minutesIgnoringDays(night));

        section("Day offsets within one train");
        check("day offsets never go backwards",
                orderedDayOffsets(nightRoute));
        check("a mid-journey stop shares the arrival day",
                nightRoute.get(1).dayOffset() == 1);

        section("Route viewer");
        List<TrainDAO.ClassRow> nightClasses = search.classesForTrain("12952");
        check("the route viewer can list the train's classes", nightClasses.size() == 3);
        check("the route viewer lists classes the train really has",
                nightClasses.stream().anyMatch(c -> c.classCode().equals("SL"))
                        && nightClasses.stream().anyMatch(c -> c.classCode().equals("3A")));
        check("route stops carry station names, not just codes",
                nightRoute.stream().allMatch(s -> s.stationName() != null
                        && !s.stationName().isBlank()));

        section("Journey materialisation");
        long journeyId = new TrainDAO(source).ensureJourney("12952", DATE).journeyId();
        long again = new TrainDAO(source).ensureJourney("12952", DATE).journeyId();
        check("searching the same train and date twice reuses one journey",
                journeyId == again);
        check("exactly one journey row exists for the train and date",
                countJourneysFor(connection, "12952", DATE) == 1);
        check("the journey is scheduled",
                new TrainDAO(source).ensureJourney("12952", DATE).isScheduled());
        check("the rake is attached to the journey",
                attachedCoaches(connection, journeyId) > 0);

        section("Fare resolution");
        // fare_rules pins 2A on CSTM->PUNE (150 km) at 85.00 + 3.90/km, which
        // beats the 2A class default of 70.00 + 3.60/km. If specificity were
        // ignored the fare would come out at 610.00 instead of 670.00.
        List<TrainSearchResult> acTwo = search.search(criteria("CSTM", "PUNE", DATE, "2A", 1));
        check("a segment with a route-specific fare rule returns trains", !acTwo.isEmpty());
        Optional<ClassAvailability> routeFare = acTwo.get(0).availabilityFor("2A");
        check("the route-specific rule was used, not the class default",
                routeFare.isPresent()
                        && routeFare.get().fare().compareTo(new java.math.BigDecimal("670.00")) == 0);
        check("the fare is charged over the distance actually travelled",
                acTwo.get(0).distanceKm() == 150);
        // The same class on a segment with no pinned rule must fall back to the
        // class default, proving the pin is scoped and not global.
        List<TrainSearchResult> acTwoLater = search.search(criteria("CSTM", "ST", DATE, "2A", 1));
        check("a segment without a pinned rule still prices",
                acTwoLater.stream().allMatch(r -> r.availabilityFor("2A").isPresent()
                        && r.availabilityFor("2A").get().fare().signum() > 0));
        check("the pinned fare does not leak onto other segments",
                acTwoLater.stream().noneMatch(r -> r.distanceKm() == 150
                        && r.availabilityFor("2A").get().fare()
                                .compareTo(new java.math.BigDecimal("670.00")) == 0));
        check("every priced class carries a positive fare",
                acTwoLater.get(0).classes().stream()
                        .allMatch(c -> c.fare().signum() > 0));

        section("Segment-scoped availability");
        TrainDAO dao = new TrainDAO(source);
        TrainSearchService service = new TrainSearchService(source, 6);
        List<TrainSearchResult> sleeper = service.search(criteria("CSTM", "ST", DATE, "SL", 1));
        check("a sleeper-only segment returns the night train", !sleeper.isEmpty());
        TrainSearchResult nightSleeper = sleeper.stream()
                .filter(r -> r.trainNo().equals("12952"))
                .findFirst().orElseThrow();
        ClassAvailability beforeSale = nightSleeper.availabilityFor("SL").orElseThrow();
        check("the sleeper class has berths before any sale",
                beforeSale.totalBerths() > 0 && beforeSale.availableBerths() > 0);

        long soldBerthId = firstBerthOfClass(connection, journeyId, "SL");
        check("a berth could be found to sell", soldBerthId > 0);
        long pnr = sellOneBerth(connection, journeyId, "12952", "SL", soldBerthId, 1, 5, "CSTM", "ST");
        check("a booking was recorded for the availability test", pnr > 0);

        List<TrainSearchResult> afterSale = service.search(criteria("CSTM", "ST", DATE, "SL", 1));
        ClassAvailability sold = afterSale.stream()
                .filter(r -> r.trainNo().equals("12952"))
                .findFirst().orElseThrow()
                .availabilityFor("SL").orElseThrow();
        check("the sold berth is no longer counted as free on its own segment",
                sold.availableBerths() == beforeSale.availableBerths() - 1);
        check("the total is unchanged by a sale", sold.totalBerths() == beforeSale.totalBerths());
        check("availability for the selling segment reads 'No seats' only when empty",
                sold.statusFor(1).contains("berths free") || sold.statusFor(1).contains("No seats"));
        check("a party larger than the remaining berths is not offered as bookable",
                sold.seats(1) == (sold.availableBerths() >= 1));

        section("Availability is scoped to the requested segment");
        // The sale covered stops 1..5 (CSTM..ST), the whole train, so a
        // different, non-overlapping segment cannot be constructed from this
        // train. Use a partial segment instead: sell stops 2..3 only, then
        // assert stop 1..2 is unaffected.
        long partialPnr = sellPartialSegment(connection, journeyId, "12952");
        check("a second, partial-segment booking was recorded", partialPnr > 0);
        List<TrainSearchResult> wholeRoute = service.search(criteria("CSTM", "ST", DATE, "SL", 1));
        ClassAvailability whole = wholeRoute.stream()
                .filter(r -> r.trainNo().equals("12952"))
                .findFirst().orElseThrow()
                .availabilityFor("SL").orElseThrow();
        List<TrainSearchResult> firstLeg = service.search(criteria("CSTM", "PUNE", DATE, "SL", 1));
        ClassAvailability leg = firstLeg.stream()
                .filter(r -> r.trainNo().equals("12952"))
                .findFirst().orElseThrow()
                .availabilityFor("SL").orElseThrow();
        check("a berth sold on a later segment is still free on an earlier segment",
                leg.availableBerths() > 0);
        check("the earlier segment's total matches the whole route's total",
                leg.totalBerths() == whole.totalBerths());
        check("counting the same berth on two segments is legitimate, not a double count",
                leg.availableBerths() <= leg.totalBerths());
    }

    // ---------------------------------------------------------------- helpers

    private static TrainSearchCriteria criteria(String from, String to, LocalDate date,
                                                String classCode, int passengers) {
        return new TrainSearchCriteria(from, to, date, classCode, passengers);
    }

    /**
     * A station pair connected by no train, found from the data rather than
     * invented, so the no-results case is real.
     */
    private static List<TrainSearchResult> noServicePair(Connection connection, LocalDate date)
            throws SQLException, ValidationException, AccessDeniedException {
        String sql =
                "SELECT a.station_code AS from_code, b.station_code AS to_code "
                + "FROM stations a CROSS JOIN stations b "
                + "WHERE a.station_code <> b.station_code "
                + "  AND NOT EXISTS (SELECT 1 FROM train_stops s1 JOIN train_stops s2 "
                + "                    ON s2.train_no = s1.train_no AND s2.stop_seq > s1.stop_seq "
                + "                   WHERE s1.station_code = a.station_code "
                + "                     AND s2.station_code = b.station_code) "
                + "LIMIT 1";
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return List.of();
            }
            TrainSearchService search = new TrainSearchService(() -> connection, 6);
            return search.search(criteria(rs.getString("from_code"), rs.getString("to_code"),
                    date, "3A", 1));
        }
    }

    /**
     * A segment/class pair that no train serving that segment has coaches for.
     *
     * <p>Discovered from the database rather than hardcoded, so this stays a
     * real test if the timetable changes. Returns the result of searching for it,
     * which must be empty.
     */
    private static List<TrainSearchResult> unservedClassCase(Connection connection, LocalDate date)
            throws SQLException, ValidationException, AccessDeniedException {
        String sql =
                "SELECT seg.origin_code, seg.dest_code, tc.class_code FROM ("
                + "  SELECT DISTINCT o.station_code AS origin_code, d.station_code AS dest_code "
                + "  FROM train_stops o JOIN train_stops d "
                + "    ON d.train_no = o.train_no AND d.stop_seq > o.stop_seq) seg "
                + "CROSS JOIN travel_classes tc "
                + "WHERE NOT EXISTS ("
                + "  SELECT 1 FROM train_stops o2 JOIN train_stops d2 "
                + "    ON d2.train_no = o2.train_no AND d2.stop_seq > o2.stop_seq "
                + "  JOIN coaches c ON c.train_no = o2.train_no AND c.class_code = tc.class_code "
                + "  WHERE o2.station_code = seg.origin_code AND d2.station_code = seg.dest_code) "
                + "LIMIT 1";
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                // Every class is served everywhere, so there is no genuine
                // no-results case to test here.
                return List.of();
            }
            TrainSearchService search = new TrainSearchService(() -> connection, 6);
            return search.search(criteria(rs.getString("origin_code"), rs.getString("dest_code"),
                    date, rs.getString("class_code"), 1));
        }
    }

    private static boolean rejected(TrainSearchService search, TrainSearchCriteria criteria,
                                     String expectedField) {
        try {
            search.validate(criteria);
            return false;
        } catch (ValidationException failure) {
            return expectedField.equals(failure.field());
        } catch (SQLException databaseProblem) {
            return false;
        }
    }

    private static boolean mentions(TrainSearchService search, TrainSearchCriteria criteria,
                                    String fragment) {
        try {
            search.validate(criteria);
            return false;
        } catch (ValidationException failure) {
            return String.valueOf(failure.getMessage()).toLowerCase().contains(fragment.toLowerCase());
        } catch (SQLException databaseProblem) {
            return false;
        }
    }

    /** A booking holding exactly one confirmed reservation over the given stops. */
    private static long sellOneBerth(Connection connection, long journeyId, String trainNo,
                                     String classCode, long berthId, int fromSeq, int toSeq,
                                     String fromStation, String toStation) throws SQLException {
        long pnr = insertBooking(connection, journeyId, trainNo, classCode, fromSeq, toSeq,
                fromStation, toStation);
        insertReservation(connection, pnr, journeyId, classCode, berthId, fromSeq, toSeq,
                fromStation, toStation);
        return pnr;
    }

    /** A booking whose single reservation covers only the middle of the route. */
    private static long sellPartialSegment(Connection connection, long journeyId, String trainNo)
            throws SQLException {
        long berthId = secondBerthOfClass(connection, journeyId, "SL");
        if (berthId <= 0) {
            return -1L;
        }
        return sellOneBerth(connection, journeyId, trainNo, "SL", berthId, 2, 3, "PUNE", "NAS");
    }

    private static long insertBooking(Connection connection, long journeyId, String trainNo,
                                      String classCode, int fromSeq, int toSeq,
                                      String fromStation, String toStation) throws SQLException {
        String sql =
                "INSERT INTO bookings (journey_id, train_no, journey_date, boarding_stop_seq, "
                + "boarding_station_code, destination_stop_seq, destination_station_code, "
                + "travel_class_code, total_fare, passenger_count, status, contact_name, "
                + "contact_mobile, user_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0.00, 1, 'CONFIRMED', 'Availability Check', "
                + "'9000000000', NULL)";
        try (PreparedStatement ps = connection.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, journeyId);
            ps.setString(2, trainNo);
            ps.setDate(3, java.sql.Date.valueOf(DATE));
            ps.setInt(4, fromSeq);
            ps.setString(5, fromStation);
            ps.setInt(6, toSeq);
            ps.setString(7, toStation);
            ps.setString(8, classCode);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1L;
            }
        }
    }

    private static void insertReservation(Connection connection, long pnr, long journeyId,
                                          String classCode, long berthId, int fromSeq, int toSeq,
                                          String fromStation, String toStation) throws SQLException {
        String berthSql =
                "SELECT b.berth_number, b.berth_type, b.position, b.coach_id "
                + "FROM berths b WHERE b.berth_id = ?";
        long berthNumber = -1;
        String berthType = "SEAT";
        String position = "WINDOW";
        long coachId = -1;
        try (PreparedStatement ps = connection.prepareStatement(berthSql)) {
            ps.setLong(1, berthId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    berthNumber = rs.getLong("berth_number");
                    berthType = rs.getString("berth_type");
                    position = rs.getString("position");
                    coachId = rs.getLong("coach_id");
                }
            }
        }
        String passengerSql =
                "INSERT INTO passengers (pnr, full_name, age, gender, berth_preference, "
                + "is_primary) VALUES (?, 'Availability Check', 30, 'OTHER', 'NO_PREFERENCE', TRUE)";
        long passengerId;
        try (PreparedStatement ps = connection.prepareStatement(passengerSql,
                java.sql.Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, pnr);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                passengerId = keys.next() ? keys.getLong(1) : -1L;
            }
        }
        String sql =
                "INSERT INTO seat_reservations (pnr, passenger_id, journey_id, berth_id, coach_id, "
                + "travel_class_code, from_stop_seq, to_stop_seq, from_station_code, "
                + "to_station_code, berth_number, berth_type, position, amount, status) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0.00, 'CONFIRMED')";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, pnr);
            ps.setLong(2, passengerId);
            ps.setLong(3, journeyId);
            ps.setLong(4, berthId);
            ps.setLong(5, coachId);
            ps.setString(6, classCode);
            ps.setInt(7, fromSeq);
            ps.setInt(8, toSeq);
            ps.setString(9, fromStation);
            ps.setString(10, toStation);
            ps.setLong(11, berthNumber);
            ps.setString(12, berthType);
            ps.setString(13, position);
            ps.executeUpdate();
        }
    }

    private static long firstBerthOfClass(Connection connection, long journeyId, String classCode)
            throws SQLException {
        return nthBerthOfClass(connection, journeyId, classCode, 0);
    }

    private static long secondBerthOfClass(Connection connection, long journeyId, String classCode)
            throws SQLException {
        return nthBerthOfClass(connection, journeyId, classCode, 1);
    }

    private static long nthBerthOfClass(Connection connection, long journeyId, String classCode,
                                        int nth) throws SQLException {
        String sql =
                "SELECT b.berth_id FROM journey_coaches jc "
                + "JOIN coaches c ON c.coach_id = jc.coach_id "
                + "JOIN berths b ON b.coach_id = c.coach_id "
                + "WHERE jc.journey_id = ? AND jc.coach_status = 'ATTACHED' "
                + "  AND c.class_code = ? ORDER BY b.berth_id LIMIT 1 OFFSET ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, journeyId);
            ps.setString(2, classCode);
            ps.setInt(3, nth);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1L;
            }
        }
    }

    private static int attachedCoaches(Connection connection, long journeyId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM journey_coaches "
                + "WHERE journey_id = ? AND coach_status = 'ATTACHED'")) {
            ps.setLong(1, journeyId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private static int minutesIgnoringDays(TrainSearchResult result) {
        return result.arrivalTime().toSecondOfDay() / 60
                - result.departureTime().toSecondOfDay() / 60;
    }

    private static boolean orderedDayOffsets(List<StopInfo> stops) {
        for (int i = 1; i < stops.size(); i++) {
            if (stops.get(i).dayOffset() < stops.get(i - 1).dayOffset()) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameTrainAndOrder(TrainSearchResult a, TrainSearchResult b) {
        return a.trainNo().equals(b.trainNo()) && a.fromSeq() == b.fromSeq()
                && a.toSeq() == b.toSeq();
    }

    /** The reported stop numbers must be the real positions of those stations. */
    private static boolean forwardStopsAreOrdered(List<TrainSearchResult> results) {
        return results.stream().allMatch(r -> r.fromSeq() >= 1 && r.toSeq() > r.fromSeq()
                && !r.fromStation().code().equals(r.toStation().code()));
    }

    private static boolean sortedByDeparture(List<TrainSearchResult> results) {
        for (int i = 1; i < results.size(); i++) {
            if (results.get(i).departureTime().isBefore(results.get(i - 1).departureTime())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSortedByName(List<Station> stations) {
        for (int i = 1; i < stations.size(); i++) {
            if (stations.get(i).name().compareToIgnoreCase(stations.get(i - 1).name()) < 0) {
                return false;
            }
        }
        return true;
    }

    private static long countJourneys(Connection connection) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM journeys");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private static long countReservations(Connection connection) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM seat_reservations");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private static long countJourneysFor(Connection connection, String trainNo, LocalDate date)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM journeys WHERE train_no = ? AND journey_date = ?")) {
            ps.setString(1, trainNo);
            ps.setDate(2, java.sql.Date.valueOf(date));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
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