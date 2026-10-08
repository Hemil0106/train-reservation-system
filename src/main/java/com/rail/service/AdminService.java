package com.rail.service;

import com.rail.db.AdminDAO;
import com.rail.db.ConnectionSource;
import com.rail.db.DatabaseConnection;
import com.rail.model.AdminView;
import com.rail.model.StopInfo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The administration area's only door.
 *
 * <p>Every method begins with {@link SessionManager#requireAdmin()}. That is the
 * whole authorisation design, and it is deliberately in the service rather than
 * in the panel: hiding a button stops an honest user from clicking it, whereas a
 * check on the way in stops <em>any</em> caller, including a future screen, a
 * script, or a mistake. {@code AdminSelfCheck} proves the second half of that by
 * calling these methods as a signed-in traveller.
 *
 * <p>The panel also checks, because an administrator's screen should not offer
 * what will be refused. That is presentation, not security, and it is the only
 * part that may be skipped.
 *
 * <h2>Validation lives here too</h2>
 *
 * <p>Every rule below is checked before a statement runs, so a bad request
 * produces a sentence the administrator can act on and no partial write. The
 * schema's constraints are still the layer that decides: they are what refuse a
 * duplicate key or a bad CHECK when something slips past, and they are what make
 * the rules hold for a second application sharing the database.
 *
 * <h2>What may never be destroyed</h2>
 *
 * <p>A booking names its train, its two stops, its coach and its berth. Any
 * change that would leave one of those pointing at nothing is refused with an
 * explanation, and so is a change that would make an existing ticket mean
 * something different:
 *
 * <ul>
 *   <li>A train or station code is immutable. Both are foreign-key targets for
 *       rows that may already exist.</li>
 *   <li>A train is deactivated, never deleted.</li>
 *   <li>A route may not be rebuilt once the train has bookings - {@code stop_seq}
 *       is on the ticket. Its <em>timings</em> may still be corrected, since
 *       rescheduling a train does not change what was sold.</li>
 *   <li>A coach or berth with history cannot be removed. Both cascade to their
 *       children, so removing one would take a reservation's snapshot with it.</li>
 *   <li>A fare rule that has already taken effect cannot be deleted, only
 *       superseded. Nothing references a rule, so this is about the past being
 *       readable rather than about integrity.</li>
 *   <li>Stations are never removed at all. The application account is not even
 *       granted {@code DELETE} on {@code stations}; see
 *       {@link #whyStationCannotBeRemoved(String)}.</li>
 * </ul>
 *
 * <h2>No audit trail</h2>
 *
 * <p>Nothing here records who made a change. There is no table for it and this
 * milestone does not add one, so an administrator's edits are not attributable
 * after the fact. Recorded as a limitation in the README rather than half-built.
 */
public final class AdminService {

    private static final Pattern TRAIN_NO = Pattern.compile("^[A-Z0-9]{2,10}$");
    private static final Pattern STATION_CODE = Pattern.compile("^[A-Z]{2,4}$");

    /** Sleeper positions, from {@code berths.position} for an AC/sleeper class. */
    private static final Set<String> SLEEPER_POSITIONS = Set.of("LOWER", "MIDDLE", "UPPER");

    /** Seating positions, for a class whose coaches hold seats. */
    private static final Set<String> SEATING_POSITIONS = Set.of("WINDOW", "AISLE", "SIDE");

    private final ConnectionSource connections;

    public AdminService() {
        this(DatabaseConnection::getConnection);
    }

    /** See {@link AuthService#AuthService(ConnectionSource)}. */
    public AdminService(ConnectionSource connections) {
        this.connections = connections;
    }

    // ---------------------------------------------------------------- reading

    public AdminView.Summary summary() throws AccessDeniedException, AdminException {
        SessionManager.get().requireAdmin();
        return read(AdminDAO::summary);
    }

    public List<AdminView.TrainRow> trains() throws AccessDeniedException, AdminException {
        SessionManager.get().requireAdmin();
        return read(AdminDAO::listTrains);
    }

    public List<AdminView.StationRow> stations() throws AccessDeniedException, AdminException {
        SessionManager.get().requireAdmin();
        return read(AdminDAO::listStations);
    }

    public List<StopInfo> stops(String trainNo) throws AccessDeniedException, AdminException {
        SessionManager.get().requireAdmin();
        return read(dao -> dao.listStops(trainNo));
    }

    public List<AdminView.CoachRow> coaches(String trainNo)
            throws AccessDeniedException, AdminException {
        SessionManager.get().requireAdmin();
        return read(dao -> dao.listCoaches(trainNo));
    }

    public List<AdminView.BerthRow> berths(long coachId)
            throws AccessDeniedException, AdminException {
        SessionManager.get().requireAdmin();
        return read(dao -> dao.listBerths(coachId));
    }

    public List<AdminView.FareRule> fareRules() throws AccessDeniedException, AdminException {
        SessionManager.get().requireAdmin();
        return read(AdminDAO::listFareRules);
    }

    /** The travel classes a coach may be built in. Read-only: the catalogue is fixed. */
    public List<String> travelClasses() throws AccessDeniedException, AdminException {
        SessionManager.get().requireAdmin();
        return read(AdminDAO::travelClasses);
    }

    /**
     * Why a station cannot be removed, in a sentence fit to show.
     *
     * <p>Not an error: the station list shows this beside every station, so the
     * reason is visible without anyone trying a delete and reading a driver
     * message. Returns {@code null} for a station nothing references, which is
     * the only case in which removal would even be safe.
     *
     * <p>Removal is still not offered, for a station or any other: the
     * application account holds no {@code DELETE} on {@code stations}, so the
     * capability does not exist to be misused, and {@code fk_stops_station}
     * ON DELETE RESTRICT is the second layer under that.
     */
    public String whyStationCannotBeRemoved(String code)
            throws AccessDeniedException, AdminException {
        SessionManager.get().requireAdmin();
        String clean = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        return read(dao -> {
            List<String> reasons = new ArrayList<>();
            int stops = dao.countStopsAt(clean);
            int bookings = dao.bookingsNaming(clean);
            int fares = dao.fareRulesNaming(clean);
            if (stops > 0) {
                reasons.add(stops + (stops == 1 ? " train calls here" : " trains call here"));
            }
            if (bookings > 0) {
                reasons.add(bookings + (bookings == 1
                        ? " booking travels to or from here"
                        : " bookings travel to or from here"));
            }
            if (fares > 0) {
                reasons.add(fares + (fares == 1
                        ? " fare rule prices a route through here"
                        : " fare rules price routes through here"));
            }
            return reasons.isEmpty() ? null
                    : "Station " + clean + " cannot be removed: "
                            + String.join(", ", reasons) + ".";
        });
    }

    // ---------------------------------------------------------------- trains

    public void addTrain(AdminView.TrainInput train)
            throws AccessDeniedException, ValidationException, AdminException {
        SessionManager.get().requireAdmin();
        AdminView.TrainInput clean = validateTrain(train);
        write("The train could not be added. Nothing was changed.", (dao) -> {
            if (dao.trainExists(clean.trainNo())) {
                throw new ValidationException("trainNo",
                        "Train number " + clean.trainNo() + " is already configured.");
            }
            requireFreeTrainName(dao, clean.trainNo(), clean.trainName());
            dao.insertTrain(clean);
        });
    }

    public void editTrain(AdminView.TrainInput train)
            throws AccessDeniedException, ValidationException, AdminException {
        SessionManager.get().requireAdmin();
        AdminView.TrainInput clean = validateTrain(train);
        write("The train could not be saved. Nothing was changed.", (dao) -> {
            if (!dao.trainExists(clean.trainNo())) {
                throw new ValidationException("trainNo",
                        "There is no train numbered " + clean.trainNo() + ".");
            }
            requireFreeTrainName(dao, clean.trainNo(), clean.trainName());
            dao.updateTrain(clean);
        });
    }

    /**
     * Deactivates or reactivates a train.
     *
     * <p>Allowed even when the train has bookings, because it destroys nothing:
     * the tickets stay exactly as they are and the train stops appearing in
     * search. A passenger with a confirmed booking is not silently refunded -
     * that is a cancellation, and it is a separate, deliberate act.
     */
    public void setTrainActive(String trainNo, boolean active)
            throws AccessDeniedException, ValidationException, AdminException {
        SessionManager.get().requireAdmin();
        String clean = requireTrainNo(trainNo);
        write("The train could not be " + (active ? "activated" : "deactivated")
                + ". Nothing was changed.", (dao) -> {
            if (!dao.trainExists(clean)) {
                throw new ValidationException("trainNo",
                        "There is no train numbered " + clean + ".");
            }
            dao.setTrainActive(clean, active);
        });
    }

    // ---------------------------------------------------------------- stations

    public void addStation(AdminView.StationInput station)
            throws AccessDeniedException, ValidationException, AdminException {
        SessionManager.get().requireAdmin();
        AdminView.StationInput clean = validateStation(station);
        write("The station could not be added. Nothing was changed.", (dao) -> {
            if (dao.findStation(clean.code()).isPresent()) {
                throw new ValidationException("code",
                        "Station code " + clean.code() + " is already in use.");
            }
            requireFreeStationName(dao, clean.name(), clean.code());
            dao.insertStation(clean);
        });
    }

    /**
     * Renames a station, moves it to a city, or re-zones it.
     *
     * <p>The code is never touched: it is the key every stop, booking and fare
     * rule refers to, and renaming the key of a table with history behind it is
     * how tickets quietly stop matching their stations.
     */
    public void editStation(AdminView.StationInput station)
            throws AccessDeniedException, ValidationException, AdminException {
        SessionManager.get().requireAdmin();
        AdminView.StationInput clean = validateStation(station);
        write("The station could not be saved. Nothing was changed.", (dao) -> {
            if (dao.findStation(clean.code()).isEmpty()) {
                throw new ValidationException("code",
                        "There is no station with code " + clean.code() + ".");
            }
            requireFreeStationName(dao, clean.name(), clean.code());
            dao.updateStation(clean);
        });
    }

    // ---------------------------------------------------------------- stops

    /**
     * Replaces a train's whole ordered route.
     *
     * <p>Refused outright once the train has bookings: {@code boarding_stop_seq}
     * and {@code destination_stop_seq} are stored on the ticket, so inserting,
     * removing or reordering a stop would silently renumber what a passenger
     * bought. {@code fk_bookings_boarding_stop} would refuse the delete anyway;
     * checking first is what turns that into a sentence.
     *
     * <p>Use {@link #editStopTiming} for a train that already sells tickets.
     */
    public void replaceStops(String trainNo, List<AdminView.StopInput> stops)
            throws AccessDeniedException, ValidationException, AdminException {
        SessionManager.get().requireAdmin();
        String clean = requireTrainNo(trainNo);
        if (stops == null || stops.size() < 2) {
            throw new ValidationException("stops",
                    "A train needs at least an origin and a destination.");
        }
        write("The route could not be saved. Nothing was changed.", (dao) -> {
            if (!dao.trainExists(clean)) {
                throw new ValidationException("trainNo",
                        "There is no train numbered " + clean + ".");
            }
            int sold = dao.bookingsOnStopsOf(clean);
            if (sold > 0) {
                throw new ValidationException("stops",
                        "This train has " + sold + " booking(s), and a stop number is part of "
                                + "every ticket, so its route cannot be rebuilt. Correct the "
                                + "timings of individual stops instead.");
            }
            validateRoute(stops, dao);
            dao.replaceStops(clean, stops);
        });
    }

    /**
     * Edits one stop's timings without touching the order.
     *
     * <p>The whole route is re-read and re-validated around the change, because a
     * single stop cannot be checked alone: making stop 2 arrive at 09:00 when
     * stop 3 is reached at 08:00 the same day is only visible by looking at
     * both.
     */
    public void editStopTiming(String trainNo, int stopSeq, LocalTime arrival,
                               LocalTime departure, int dayOffset, int distanceKm)
            throws AccessDeniedException, ValidationException, AdminException {
        SessionManager.get().requireAdmin();
        String clean = requireTrainNo(trainNo);
        write("The stop could not be saved. Nothing was changed.", (dao) -> {
            if (!dao.trainExists(clean)) {
                throw new ValidationException("trainNo",
                        "There is no train numbered " + clean + ".");
            }
            List<AdminView.StopInput> route = toInputs(dao.listStops(clean));
            int index = -1;
            for (int i = 0; i < route.size(); i++) {
                if (route.get(i).stopSeq() == stopSeq) {
                    index = i;
                }
            }
            if (index < 0) {
                throw new ValidationException("stopSeq",
                        "Stop " + stopSeq + " is not part of this train's route.");
            }
            route.set(index, new AdminView.StopInput(stopSeq, route.get(index).stationCode(),
                    arrival, departure, dayOffset, distanceKm));
            validateRoute(route, dao);
            dao.updateStopTiming(clean, stopSeq, arrival, departure, dayOffset, distanceKm);
        });
    }

    // ---------------------------------------------------------------- the rake

    public void addCoach(AdminView.CoachInput coach)
            throws AccessDeniedException, ValidationException, AdminException {
        SessionManager.get().requireAdmin();
        AdminView.CoachInput clean = validateCoach(coach);
        write("The coach could not be added. Nothing was changed.", (dao) -> {
            if (!dao.trainExists(clean.trainNo())) {
                throw new ValidationException("trainNo",
                        "There is no train numbered " + clean.trainNo() + ".");
            }
if (!dao.classExists(clean.classCode())) {
                   throw new ValidationException("classCode",
                           "There is no travel class " + clean.classCode() + " configured.");
               }
               if (dao.coachNumberInUse(clean.trainNo(), clean.coachNumber())) {
                   throw new ValidationException("coachNumber",
                           "Coach " + clean.coachNumber() + " is already used on train "
                                   + clean.trainNo() + ". Coach numbers are per train.");
               }
               dao.insertCoach(clean);
        });
    }

    /**
     * Removes a coach and, with it, its berths.
     *
     * <p>Refused if anything has been sold on it or it has run on a journey. Both
     * are enforced by {@code fk_res_coach} and {@code fk_jc_coach}; checking here
     * only decides which sentence the administrator reads.
     */
    public void removeCoach(long coachId)
            throws AccessDeniedException, ValidationException, AdminException {
        SessionManager.get().requireAdmin();
        requirePositive(coachId, "coachId");
write("The coach could not be removed. Nothing was changed.", (dao) -> {
               if (!dao.coachExists(coachId)) {
                   throw new ValidationException("coachId", "There is no such coach.");
               }
               int sold = dao.reservationsOnCoach(coachId);
            if (sold > 0) {
                throw new ValidationException("coachId",
                        "This coach has " + sold + " reservation(s) against it. Cancel them, or "
                                + "detach the coach from future journeys instead of removing it.");
            }
            int journeys = dao.journeysUsingCoach(coachId);
            if (journeys > 0) {
                throw new ValidationException("coachId",
                        "This coach has run on " + journeys + " journey(s), so removing it would "
                                + "leave those journeys with a coach they never had.");
            }
            dao.deleteCoach(coachId);
        });
    }

    public void addBerth(AdminView.BerthInput berth)
            throws AccessDeniedException, ValidationException, AdminException {
        SessionManager.get().requireAdmin();
        AdminView.BerthInput clean = validateBerth(berth);
        write("The berth could not be added. Nothing was changed.", (dao) -> {
            String classCode = dao.classCodeOfCoach(clean.coachId());
            if (classCode == null) {
                throw new ValidationException("coachId", "There is no such coach.");
            }
requirePositionMatchesClass(clean.position(), dao.isSleeperClass(classCode));
               if (dao.berthNumberInUse(clean.coachId(), clean.berthNumber())) {
                   throw new ValidationException("berthNumber",
                           "Berth " + clean.berthNumber() + " already exists in this coach.");
               }
               dao.insertBerth(clean);
        });
    }

    public void removeBerth(long berthId)
            throws AccessDeniedException, ValidationException, AdminException {
        SessionManager.get().requireAdmin();
        requirePositive(berthId, "berthId");
write("The berth could not be removed. Nothing was changed.", (dao) -> {
               if (!dao.berthExists(berthId)) {
                   throw new ValidationException("berthId", "There is no such berth.");
               }
               int sold = dao.reservationsOnBerth(berthId);
            if (sold > 0) {
                throw new ValidationException("berthId",
                        "This berth has " + sold + " reservation(s) against it. A sold berth is "
                                + "part of a ticket and cannot be taken away.");
            }
            dao.deleteBerth(berthId);
        });
    }

    // ---------------------------------------------------------------- fares

    /**
     * Saves a fare rule: a new one when {@code ruleId} is 0, otherwise an edit.
     *
     * <p>One method rather than two, because the validation is identical and the
     * only difference is whether the row exists - which the key already says.
     */
    public void saveFareRule(AdminView.FareRule rule)
            throws AccessDeniedException, ValidationException, AdminException {
        SessionManager.get().requireAdmin();
        AdminView.FareRule clean = validateFareRule(rule);
        write("The fare rule could not be saved. Nothing was changed.", (dao) -> {
            if (clean.ruleId() != 0 && dao.findFareRule(clean.ruleId()).isEmpty()) {
                throw new ValidationException("ruleId",
                        "There is no fare rule " + clean.ruleId() + " to update.");
            }
            if (clean.ruleId() == 0) {
                dao.insertFareRule(clean);
            } else {
                dao.updateFareRule(clean);
            }
        });
    }

    /**
     * Removes a fare rule that has not taken effect yet.
     *
     * <p>No foreign key points at a rule - a booking keeps the amount it was
     * charged - so this cannot corrupt anything. It is refused anyway once the
     * rule has started applying, because the fares people were quoted should stay
     * traceable to the rule that produced them.
     */
    public void removeFareRule(long ruleId)
            throws AccessDeniedException, ValidationException, AdminException {
        SessionManager.get().requireAdmin();
        requirePositive(ruleId, "ruleId");
        write("The fare rule could not be removed. Nothing was changed.", (dao) -> {
            AdminView.FareRule rule = dao.findFareRule(ruleId).orElseThrow(
                    () -> new ValidationException("ruleId",
                            "There is no fare rule " + ruleId + "."));
            if (!rule.validFrom().isAfter(LocalDate.now())) {
                throw new ValidationException("ruleId",
                        "This rule has been in force since " + rule.validFrom()
                                + ", so it is part of the record of what people were charged. "
                                + "Add a new rule that supersedes it instead of removing this one.");
            }
            dao.deleteFareRule(ruleId);
        });
    }

    // ---------------------------------------------------------------- validation

    /**
     * The train fields, checked and normalised.
     *
     * <p>Numbers are upper-cased before the pattern is applied, so an
     * administrator typing {@code 1295a} is not told their train number is
     * invalid by a rule they cannot see.
     */
    private static AdminView.TrainInput validateTrain(AdminView.TrainInput train)
            throws ValidationException {
        String no = upper(text(train.trainNo(), "trainNo", "A train number is required."));
        if (!TRAIN_NO.matcher(no).matches()) {
            throw new ValidationException("trainNo",
                    "A train number is 2 to 10 letters and digits, like 12951 or MMCT.");
        }
        String name = text(train.trainName(), "trainName", "A train name is required.");
        if (name.length() > 100) {
            throw new ValidationException("trainName",
                    "A train name can be at most 100 characters.");
        }
        String type = text(train.trainType(), "trainType", "Choose a train type.");
        if (!AdminView.TRAIN_TYPES.contains(type)) {
            throw new ValidationException("trainType",
                    "Train type must be one of " + String.join(", ", AdminView.TRAIN_TYPES) + ".");
        }
        Integer distance = train.distanceKm();
        if (distance != null && (distance < 0 || distance > 100_000)) {
            throw new ValidationException("distanceKm",
                    "Route distance must be between 0 and 100000 km, or left blank.");
        }
        return new AdminView.TrainInput(no, name, type, train.runsDaily(), distance);
    }

    private static AdminView.StationInput validateStation(AdminView.StationInput station)
            throws ValidationException {
        String code = upper(text(station.code(), "code", "A station code is required."));
        if (!STATION_CODE.matcher(code).matches()) {
            throw new ValidationException("code",
                    "A station code is 2 to 4 capital letters, like CSTM or PUNE.");
        }
        String name = text(station.name(), "name", "A station name is required.");
        if (name.length() > 80) {
            throw new ValidationException("name", "A station name can be at most 80 characters.");
        }
        String city = text(station.city(), "city", "A city is required.");
        if (city.length() > 60) {
            throw new ValidationException("city", "A city can be at most 60 characters.");
        }
        String zone = trimmed(station.zone());
        if (zone != null && zone.length() > 20) {
            throw new ValidationException("zone", "A zone can be at most 20 characters.");
        }
        return new AdminView.StationInput(code, name, city, zone);
    }

    private static AdminView.CoachInput validateCoach(AdminView.CoachInput coach)
            throws ValidationException {
        String trainNo = text(coach.trainNo(), "trainNo", "A train number is required.");
        String number = text(coach.coachNumber(), "coachNumber", "A coach number is required.");
        if (number.length() > 6) {
            throw new ValidationException("coachNumber",
                    "A coach number can be at most 6 characters, like AC1 or B2.");
        }
        String classCode = text(coach.classCode(), "classCode", "Choose a travel class.");
        String type = text(coach.coachType(), "coachType", "A coach type is required.");
        if (type.length() > 30) {
            throw new ValidationException("coachType",
                    "A coach type can be at most 30 characters.");
        }
        if (coach.position() < 1) {
            throw new ValidationException("position",
                    "Position in the rake starts at 1 and counts forwards.");
        }
        return new AdminView.CoachInput(trainNo, number, classCode, type,
                coach.position(), coach.hasToilet());
    }

    private static AdminView.BerthInput validateBerth(AdminView.BerthInput berth)
            throws ValidationException {
        if (berth.coachId() < 1) {
            throw new ValidationException("coachId", "Choose the coach this berth belongs to.");
        }
        if (berth.berthNumber() < 1 || berth.berthNumber() > 999) {
            throw new ValidationException("berthNumber",
                    "Berth numbers start at 1 and are at most 999.");
        }
        String type = text(berth.berthType(), "berthType", "Choose a berth type.");
        if (!AdminView.BERTH_TYPES.contains(type)) {
            throw new ValidationException("berthType",
                    "Berth type must be one of " + String.join(", ", AdminView.BERTH_TYPES) + ".");
        }
        String position = text(berth.position(), "position", "Choose a berth position.");
        return new AdminView.BerthInput(berth.coachId(), berth.berthNumber(), type, position);
    }

    private static AdminView.FareRule validateFareRule(AdminView.FareRule rule)
            throws ValidationException {
        String classCode = text(rule.classCode(), "classCode", "Choose a travel class.");
        String origin = trimmed(rule.origin());
        String destination = trimmed(rule.destination());
        if (destination != null && origin == null) {
            throw new ValidationException("destination",
                    "A fare for one destination needs an origin as well. Leave both blank for a "
                            + "class-wide default.");
        }
        Integer distance = rule.distanceKm();
        if (distance != null && distance < 0) {
            throw new ValidationException("distanceKm",
                    "A rule distance cannot be negative.");
        }
        BigDecimal baseFare = money(rule.baseFare(), "baseFare");
        BigDecimal perKm = money(rule.perKmFare(), "perKmFare");
        if (rule.quotaSeats() < 0 || rule.tatkalSeats() < 0) {
            throw new ValidationException("quotaSeats",
                    "Reserved quota per coach cannot be negative.");
        }
        if (rule.tatkalSeats() > rule.quotaSeats()) {
            throw new ValidationException("tatkalSeats",
                    "Tatkal quota is part of the reserved quota, so it cannot be larger than it.");
        }
        LocalDate validFrom = rule.validFrom();
        if (validFrom == null) {
            throw new ValidationException("validFrom", "A start date is required.");
        }
        if (rule.validTo() != null && rule.validTo().isBefore(validFrom)) {
            throw new ValidationException("validTo",
                    "A rule cannot end before it starts.");
        }
        return new AdminView.FareRule(rule.ruleId(), classCode, origin, destination, distance,
                baseFare, perKm, rule.quotaSeats(), rule.tatkalSeats(), validFrom, rule.validTo());
    }

    /**
     * A train name has to be unique across the system.
     *
     * <p>{@code uq_trains_name} would refuse the write anyway; asking first is
     * what turns "Duplicate entry" into the name that is already taken. The
     * train's own row is excluded so saving an edit without renaming it is not
     * refused as a duplicate of itself.
     */
    private static void requireFreeTrainName(AdminDAO dao, String trainNo, String name)
            throws SQLException, ValidationException {
        String owner = dao.trainNumberNamed(name);
        if (owner != null && !owner.equals(trainNo)) {
            throw new ValidationException("trainName",
                    "Train " + owner + " is already called " + name
                            + ". Train names must be different, because a passenger searches by name.");
        }
    }

    /** {@code uq_stations_name}, explained the same way. */
    private static void requireFreeStationName(AdminDAO dao, String name, String stationCode)
            throws SQLException, ValidationException {
        String owner = dao.stationCodeNamed(name);
        // Keeping its own name is not a clash with itself.
        if (owner != null && !owner.equals(stationCode)) {
            throw new ValidationException("name",
                    "Station " + owner + " is already called " + name
                            + ". Two stations cannot share a name, so a booked ticket stays unambiguous.");
        }
    }

    /**
     * The whole route, checked as a route.
     *
     * <p>Five rules, all of which the schema cannot express:
     *
     * <ol>
     *   <li>Stop numbers are 1, 2, 3... with no gaps, so "stop 4" means one
     *       thing on every train.</li>
     *   <li>No station twice on one train - a one-way loop would make two stops
     *       mean the same place.</li>
     *   <li>Only the origin may arrive at nothing; every later stop needs both
     *       times or the segment before it has no duration.</li>
     *   <li>Distances increase and day offsets never go backwards, or the
     *       "cumulative distance" column becomes a lie.</li>
     *   <li>The train is actually somewhere sensible: each stop is reached after
     *       the previous one leaves, comparing wall-clock times through the day
     *       offset so an overnight leg works.</li>
     * </ol>
     */
    private static void validateRoute(List<AdminView.StopInput> stops, AdminDAO dao)
            throws SQLException, ValidationException {
        Set<String> known = new HashSet<>(dao.stationCodes());
        Set<String> visited = new HashSet<>();
        int previousArrival = Integer.MIN_VALUE;
        int previousDistance = -1;
        int previousDay = -1;

        for (int i = 0; i < stops.size(); i++) {
            AdminView.StopInput stop = stops.get(i);
            int position = i + 1;
            if (stop.stopSeq() != position) {
                throw new ValidationException("stopSeq",
                        "Stops must be numbered 1, 2, 3... with no gaps. Row " + position
                                + " is numbered " + stop.stopSeq() + ".");
            }

            String code = text(stop.stationCode(), "stationCode",
                    "Every stop needs a station.");
            if (!known.contains(code)) {
                throw new ValidationException("stationCode",
                        "There is no station with code " + code + " configured.");
            }
            if (!visited.add(code)) {
                throw new ValidationException("stationCode",
                        "This train calls at " + code + " more than once. Either it is a loop, "
                                + "which this system does not model, or one of the stops is wrong.");
            }

            boolean origin = position == 1;
            if (origin && stop.arrivalTime() != null) {
                throw new ValidationException("arrivalTime",
                        "The first stop is where the train starts, so it has no arrival time.");
            }
            if (!origin && stop.arrivalTime() == null) {
                throw new ValidationException("arrivalTime",
                        "Stop " + position + " needs an arrival time - only the first stop "
                                + "starts the journey.");
            }
            LocalTime departure = stop.departureTime();
            if (departure == null) {
                throw new ValidationException("departureTime",
                        "Stop " + position + " needs a departure time.");
            }

            if (stop.dayOffset() < 0) {
                throw new ValidationException("dayOffset",
                        "A stop cannot be before the day the train leaves.");
            }
            if (stop.dayOffset() < previousDay) {
                throw new ValidationException("dayOffset",
                        "Stop " + position + " is reached on an earlier day than stop "
                                + (position - 1) + ". The route runs forwards.");
            }
            if (stop.distanceKm() <= previousDistance) {
                throw new ValidationException("distanceKm",
                        "Stop " + position + " is no further from the origin than stop "
                                + (position - 1) + ". Distances must increase along the route.");
            }

            int departureAt = clock(departure, stop.dayOffset());
            if (origin) {
                // The origin's own arrival is absent; its departure starts the count.
                previousArrival = departureAt;
            } else {
                // arrivalTime is non-null here: ck_stops_arrival and the check
                // above both insist on it for every stop but the first.
                int arrivalAt = clock(stop.arrivalTime(), stop.dayOffset());
                if (arrivalAt <= previousArrival) {
                    throw new ValidationException("arrivalTime",
                            "Stop " + position + " is reached before stop " + (position - 1)
                                    + " leaves. Check the times and the day offsets.");
                }
                previousArrival = departureAt;
            }
            previousDistance = stop.distanceKm();
            previousDay = stop.dayOffset();
        }
    }

    /**
     * A berth's position has to suit its class, and MySQL cannot say so.
     *
     * <p>schema.sql says so itself: no subqueries in a CHECK, so the rule is
     * enforced here and asserted over the whole table by {@code DatabaseSelfCheck}.
     * A LOWER berth in a seated class would be offered to a passenger and could
     * never be allocated honestly.
     */
    private static void requirePositionMatchesClass(String position, boolean sleeper)
            throws ValidationException {
        Set<String> allowed = sleeper ? SLEEPER_POSITIONS : SEATING_POSITIONS;
        if (!allowed.contains(position)) {
            throw new ValidationException("position",
                    (sleeper ? "A sleeper class has " : "A seating class has ")
                            + String.join("/", sorted(allowed)) + " berths, not " + position + ".");
        }
    }

    private static List<String> sorted(Set<String> values) {
        List<String> ordered = new ArrayList<>(values);
        ordered.sort(String::compareTo);
        return ordered;
    }

    // ---------------------------------------------------------------- field checks

    private static String requireTrainNo(String trainNo) throws ValidationException {
        String clean = upper(text(trainNo, "trainNo", "A train number is required."));
        if (!TRAIN_NO.matcher(clean).matches()) {
            throw new ValidationException("trainNo",
                    "A train number is 2 to 10 letters and digits, like 12951 or MMCT.");
        }
        return clean;
    }

    private static void requirePositive(long value, String field) throws ValidationException {
        if (value < 1) {
            throw new ValidationException(field, "No such record.");
        }
    }

    private static String text(String value, String field, String complaint)
            throws ValidationException {
        String clean = trimmed(value);
        if (clean == null) {
            throw new ValidationException(field, complaint);
        }
        return clean;
    }

    private static String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String clean = value.trim();
        return clean.isEmpty() ? null : clean;
    }

    /**
     * Codes are capitals in the schema, so they are capitals here.
     *
     * <p>Silently upper-casing rather than refusing: an administrator typing
     * {@code pune} meant PUNE, and {@code ck_stations_code} would have thrown the
     * request away for a distinction the schema itself resolves by comparison.
     */
    private static String upper(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    private static BigDecimal money(BigDecimal value, String field) throws ValidationException {
        if (value == null) {
            throw new ValidationException(field, "A fare amount is required.");
        }
        if (value.signum() < 0) {
            throw new ValidationException(field, "A fare cannot be negative.");
        }
        if (value.scale() > 2) {
            throw new ValidationException(field,
                    "Use at most two decimal places, like 275.50.");
        }
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }

    /** Minutes since the train departed, so an overnight leg stays positive. */
    private static int clock(LocalTime time, int dayOffset) {
        return dayOffset * 24 * 60 + time.getHour() * 60 + time.getMinute();
    }

    private static List<AdminView.StopInput> toInputs(List<StopInfo> stops) {
        List<AdminView.StopInput> inputs = new ArrayList<>();
        for (StopInfo stop : stops) {
            inputs.add(new AdminView.StopInput(stop.stopSeq(), stop.stationCode(),
                    stop.arrivalTime(), stop.departureTime(),
                    stop.dayOffset(), stop.distanceKm()));
        }
        return inputs;
    }

    // ---------------------------------------------------------------- plumbing

    private <T> T read(SqlRead<T> query) throws AdminException {
        try (Connection connection = connections.open()) {
            return query.read(new AdminDAO(connection));
        } catch (SQLException failure) {
            throw new AdminException(
                    "Could not read the administration data. Please try again.", failure);
        }
    }

    private void write(String failureMessage, SqlWrite body)
            throws ValidationException, AdminException {
        try (Connection connection = connections.open()) {
            connection.setAutoCommit(false);
            try {
                body.write(new AdminDAO(connection));
                connection.commit();
            } catch (ValidationException | SQLException | RuntimeException failure) {
                safeRollback(connection);
                if (failure instanceof ValidationException invalid) {
                    throw invalid;
                }
                throw new AdminException(failureMessage, failure);
            }
        } catch (SQLException failure) {
            throw new AdminException(
                    "Could not reach the administration database. Nothing was changed.", failure);
        }
    }

    private static void safeRollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // Reported through the original failure, not this one.
        }
    }

    /** A read that needs the DAO. */
    @FunctionalInterface
    private interface SqlRead<T> {
        T read(AdminDAO dao) throws SQLException;
    }

    /** A write that needs the DAO. */
    @FunctionalInterface
    private interface SqlWrite {
        void write(AdminDAO dao) throws SQLException, ValidationException;
    }

    /**
     * Every refusal the database itself produced.
     *
     * <p>One type so the panel shows one message. The driver's text is kept as
     * the cause and never shown: it names tables, columns and constraint
     * symbols, which is more about the schema than the administrator needs and
     * more than a traveller should ever read.
     */
    public static final class AdminException extends Exception {

        private static final long serialVersionUID = 1L;

        public AdminException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}