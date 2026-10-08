package com.rail.service;

import com.rail.db.AvailabilityDAO;
import com.rail.db.ConnectionSource;
import com.rail.db.DatabaseConnection;
import com.rail.db.DbConfig;
import com.rail.db.TrainDAO;
import com.rail.model.ClassAvailability;
import com.rail.model.Station;
import com.rail.model.StopInfo;
import com.rail.model.TrainSearchCriteria;
import com.rail.model.TrainSearchResult;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Validates a search request and turns it into priced, seat-counted results.
 *
 * <p>Every rule the brief lists is enforced here rather than in the form, so the
 * screen cannot accept something the service would refuse and the self-check
 * exercises the same code path the UI does.
 *
 * <p>All database work happens inside the service methods below. Swing callers
 * must invoke them off the event dispatch thread; nothing here touches a
 * component.
 *
 * <p>Availability is this project's own simulation over the {@code berths} and
 * {@code seat_reservations} tables. It is not live IRCTC availability and the UI
 * must say so.
 */
public class TrainSearchService {

    private static final int MINUTES_PER_DAY = 24 * 60;

    private final TrainDAO trains;
    private final ConnectionSource connections;
    private final int maxPassengers;

    public TrainSearchService() {
        this(DatabaseConnection::getConnection,
             DatabaseConnection.config().maxPassengers());
    }

    public TrainSearchService(ConnectionSource connections, int maxPassengers) {
        this.trains = new TrainDAO(connections);
        this.connections = connections;
        this.maxPassengers = Math.max(1, maxPassengers);
    }

    /** The configured cap on passengers, surfaced so the form can label its spinner. */
    public int maxPassengers() {
        return maxPassengers;
    }

    // ---------------------------------------------------------------- lookups

    public List<Station> listStations() throws SQLException {
        return trains.listStations();
    }

    public List<TrainDAO.ClassRow> listClasses() throws SQLException {
        return trains.listAllClasses();
    }

    /** The classes a given train actually has coaches for. */
    public List<TrainDAO.ClassRow> classesForTrain(String trainNo) throws SQLException {
        return trains.listTrainClasses(trainNo);
    }

    /** A train's full timetable, for the route viewer. */
    public List<StopInfo> routeOf(String trainNo) throws SQLException {
        return trains.listStops(trainNo);
    }

    // ---------------------------------------------------------------- validation

    /**
     * Checks a request without running it.
     *
     * <p>Used both to guard {@link #search} and to validate as the user edits,
     * so the messages here are the single source of truth for what counts as a
     * legal search.
     *
     * @throws ValidationException naming the field at fault
     */
    public void validate(TrainSearchCriteria criteria)
            throws ValidationException, SQLException {
        if (!criteria.hasEndpoints()) {
            throw new ValidationException("from",
                    "Choose both a departure station and an arrival station.");
        }
        if (criteria.isSameStation()) {
            throw new ValidationException("to",
                    "Origin and destination must be different stations.");
        }

        if (criteria.journeyDate() == null) {
            throw new ValidationException("date",
                    "Enter a valid journey date in the form YYYY-MM-DD.");
        }
        if (criteria.journeyDate().isBefore(LocalDate.now())) {
            throw new ValidationException("date",
                    "The journey date cannot be in the past.");
        }

        if (criteria.passengers() < 1) {
            throw new ValidationException("passengers",
                    "Enter at least one passenger.");
        }
        if (criteria.passengers() > maxPassengers) {
            throw new ValidationException("passengers",
                    "This project books at most " + maxPassengers
                    + " passengers on one booking.");
        }

        if (criteria.classCode().isBlank()) {
            throw new ValidationException("class",
                    "Choose a travel class.");
        }
        requireKnownClass(criteria.classCode());

        requireKnownStation(criteria.fromCode(), "from", "departure");
        requireKnownStation(criteria.toCode(), "to", "arrival");
    }

    /**
     * Refuses a class code the database does not define.
     *
     * <p>Without this, a typo such as {@code 3a} instead of {@code 3A} would
     * simply match nothing and be reported as "no trains found", which sends the
     * traveller looking for a routing problem they do not have.
     */
    private void requireKnownClass(String code) throws ValidationException, SQLException {
        for (TrainDAO.ClassRow offered : trains.listAllClasses()) {
            if (offered.classCode().equalsIgnoreCase(code.trim())) {
                return;
            }
        }
        throw new ValidationException("class",
                "'" + code.trim() + "' is not a travel class in this system. Pick a class from the list.");
    }

    private void requireKnownStation(String code, String field, String label)
            throws ValidationException, SQLException {
        for (Station station : trains.listStations()) {
            if (station.code().equalsIgnoreCase(code)) {
                return;
            }
        }
        throw new ValidationException(field,
                "'" + code.trim() + "' is not a station in this system. Pick a "
                + label + " station from the list.");
    }

    // ---------------------------------------------------------------- search

    /**
     * Runs a validated search.
     *
     * <p>Returns every train that serves the ordered segment in the requested
     * class, priced and counted for that exact segment. An empty list means no
     * train matches, which the UI reports as a no-results state rather than an
     * error.
     *
     * <p>Each matching train's {@code journeys} row is materialised before its
     * availability is counted, so a first-ever search for a date returns real
     * numbers instead of an empty result.
     */
    public List<TrainSearchResult> search(TrainSearchCriteria criteria)
            throws ValidationException, AccessDeniedException, SQLException {
        SessionManager.get().requireUser();
        validate(criteria);

        Map<String, Station> stations = new LinkedHashMap<>();
        for (Station station : trains.listStations()) {
            stations.put(station.code(), station);
        }
        String fromCode = criteria.fromCode().trim().toUpperCase();
        String toCode = criteria.toCode().trim().toUpperCase();
        LocalDate date = criteria.journeyDate();
        String classCode = criteria.classCode().trim().toUpperCase();

        List<TrainSearchResult> results = new ArrayList<>();
        for (TrainDAO.RouteMatch match : trains.findRouteMatches(fromCode, toCode, classCode)) {
            results.add(describe(match, stations.get(fromCode), stations.get(toCode),
                                 date, classCode, criteria.passengers()));
        }
        return results;
    }

    /**
     * Attaches timetable detail, fares and availability to one route match.
     *
     * <p>Fares and berth counts are computed for <em>every</em> class the train
     * serves, not just the class that was searched for, so the result card can
     * show the alternatives the traveller may switch to.
     */
    private TrainSearchResult describe(TrainDAO.RouteMatch match, Station from,
                                       Station to, LocalDate date,
                                       String searchedClass, int passengers)
            throws SQLException {
        int durationMinutes = minutesBetween(match.departureTime(), 0,
                                              match.arrivalTime(), match.arrivalDayOffset());

        List<ClassAvailability> classes = new ArrayList<>();
        TrainDAO.JourneyRef journey = trains.ensureJourney(match.trainNo(), date);
        for (TrainDAO.ClassRow offered : trains.listTrainClasses(match.trainNo())) {
            priceAndCount(offered, from.code(), to.code(), match.segmentKm(),
                          date, journey, match.fromSeq(), match.toSeq())
                    .ifPresent(classes::add);
        }

        // Keep the class the traveller asked for at the top of the card.
        classes.sort((a, b) -> {
            if (a.classCode().equals(searchedClass)) {
                return -1;
            }
            if (b.classCode().equals(searchedClass)) {
                return 1;
            }
            return a.classCode().compareTo(b.classCode());
        });

        return new TrainSearchResult(match.trainNo(), match.trainName(), from, to,
                match.fromSeq(), match.toSeq(),
                match.departureTime(), match.arrivalTime(),
                match.arrivalDayOffset(), durationMinutes, match.segmentKm(), classes);
    }

    /**
     * Fare and berth counts for one class, or empty when no fare rule applies.
     *
     * <p>A class with no published fare rule is left out of the result instead
     * of being offered at zero. Booking it would mean charging nothing, which is
     * worse than not showing it.
     *
     * <p>{@code ponytail}: a journey whose {@code service_status} is not
     * {@code SCHEDULED} reports zero availability, so a cancelled train reads
     * as "no seats". Splitting that into a distinct state is Milestone 5's job,
     * when cancellations can actually be recorded.
     */
    private Optional<ClassAvailability> priceAndCount(TrainDAO.ClassRow offered, String fromCode,
                                                      String toCode, int segmentKm, LocalDate date,
                                                      TrainDAO.JourneyRef journey,
                                                      int fromSeq, int toSeq) throws SQLException {
        Optional<BigDecimal> fare = trains.farePerPassenger(offered.classCode(),
                fromCode, toCode, segmentKm, date);
        if (fare.isEmpty()) {
            return Optional.empty();
        }
        AvailabilityDAO.BerthCounts berths = journey.isScheduled()
                ? AvailabilityDAO.countForSegment(connections, journey.journeyId(),
                        offered.classCode(), fromSeq, toSeq)
                : new AvailabilityDAO.BerthCounts(0, 0);
        return Optional.of(new ClassAvailability(offered.classCode(), offered.className(),
                fare.get(), berths.available(), berths.total()));
    }

    /**
     * Elapsed minutes between two timetable readings, honouring the day offset.
     *
     * <p>Naively subtracting the clock times of a train that leaves at 21:15 and
     * arrives at 08:45 the next day gives a negative duration. Working in
     * minutes-from-departure, with the arrival's {@code dayOffset} folded in, is
     * what makes an overnight journey report 690 minutes rather than -450.
     */
    public static int minutesBetween(java.time.LocalTime from, int fromDayOffset,
                                   java.time.LocalTime to, int toDayOffset) {
        int fromMinutes = fromDayOffset * MINUTES_PER_DAY + from.toSecondOfDay() / 60;
        int toMinutes = toDayOffset * MINUTES_PER_DAY + to.toSecondOfDay() / 60;
        return toMinutes - fromMinutes;
    }
}