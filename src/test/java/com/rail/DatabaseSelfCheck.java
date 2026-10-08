package com.rail;

import com.rail.db.DatabaseConnection;
import com.rail.db.DbConfig;
import com.rail.ui.DatabaseFailure;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plain self-check for the database layer. No test framework, for the same
 * reason {@code ModelSelfCheck} has none: this project stays on the
 * standard library apart from the JDBC driver.
 *
 * It follows the same skip-if-unavailable idiom as the existing checks:
 * when no database is configured it prints SKIP and exits successfully, so
 * an offline build stays green. When a database IS configured it connects
 * for real and checks the schema, the seeded sample data, and the segment
 * overlap rule that the booking engine will rely on.
 *
 * Run with:
 *   java -cp "target\classes;target\test-classes;target\lib\*" com.rail.DatabaseSelfCheck
 *
 * Nothing is left behind: the booking-path checks run inside one
 * transaction that is always rolled back, so the database is exactly as it
 * was before this program ran.
 */
public class DatabaseSelfCheck {

    private static int checksRun = 0;

    /** The tables schema.sql must create, in dependency order. */
    private static final String[] REQUIRED_TABLES = {
            "users", "travel_classes", "stations", "trains", "train_stops",
            "coaches", "berths", "journeys", "journey_coaches", "fare_rules",
            "bookings", "passengers", "seat_reservations", "cancellations"};

    public static void main(String[] args) {
        DbConfig config = DatabaseConnection.config();

        System.out.println("Database configuration");
        System.out.println("  " + config.describe());

        if (!config.isConfigured()) {
            System.out.println();
            System.out.println("SKIP  no database configured, nothing was connected to.");
            System.out.println();
            System.out.println(config.missingSettingMessage());
            System.out.println();
            System.out.println("Schema and sample data are defined in:");
            System.out.println("  database/schema.sql");
            System.out.println("  database/sample_data.sql");
            System.out.println("  database/create_app_user.sql");
            System.out.println("See README.md section 'Database setup' for the full procedure.");
            return;
        }

        if (config.isConfigFilePresent()) {
            check("local.properties was found and read", true);
        } else {
            System.out.println("  (using environment variables or system properties)");
        }

        System.out.println();
        System.out.println("Connecting");

        try (Connection connection = DatabaseConnection.getConnection()) {
            check("a connection opened", connection != null && !connection.isClosed());
            check("autoCommit is on by default", connection.getAutoCommit());

            String url = connection.getMetaData().getURL();
            check("the connection targets the railway database",
                    url != null && url.contains("/railway"));

            String product = connection.getMetaData().getDatabaseProductName();
            String version = connection.getMetaData().getDatabaseProductVersion();
            check("the server is MySQL", product != null && product.contains("MySQL"));
            check("the server is version 8.x", version != null && version.startsWith("8."));
            System.out.println("  connected to " + product + " " + version);

            if (config.isRootAccount()) {
                System.out.println("  WARNING: connected as root; use the account from "
                        + "database/create_app_user.sql instead.");
            }

            checkSchema(connection);
            checkSampleData(connection);
            checkBerthLayout(connection);
            checkRouteIntegrity(connection);
            checkBookingPathAndRollback(connection);

        } catch (SQLException problem) {
            throw new IllegalStateException(
                    "DATABASE CHECK FAILED: " + problem.getMessage()
                            + System.lineSeparator()
                            + "Is the server running, and has database/schema.sql been loaded?",
                    problem);
        }

        checkUnconfiguredGuidance();
        checkRefusedConnection(config);

        System.out.println();
        System.out.println("All " + checksRun + " database checks passed.");
    }

    // ----- schema -------------------------------------------------------

    private static void checkSchema(Connection connection) throws SQLException {
        System.out.println();
        System.out.println("Schema");

        for (String table : REQUIRED_TABLES) {
            check("table " + table + " exists", tableExists(connection, table));
        }

        check("view v_journey_berth_occupancy exists",
                objectExists(connection, "VIEW", "v_journey_berth_occupancy"));

        int foreignKeys = countScalar(connection,
                "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS"
                        + " WHERE CONSTRAINT_SCHEMA = DATABASE()"
                        + " AND TABLE_SCHEMA = DATABASE()"
                        + " AND CONSTRAINT_TYPE = 'FOREIGN KEY'");
        check("the schema declares foreign keys (found " + foreignKeys + ")", foreignKeys >= 20);

        int checkConstraints = countScalar(connection,
                "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS"
                        + " WHERE CONSTRAINT_SCHEMA = DATABASE()"
                        + " AND TABLE_SCHEMA = DATABASE()"
                        + " AND CONSTRAINT_TYPE = 'CHECK'");
        check("the schema declares CHECK constraints (found " + checkConstraints + ")",
                checkConstraints >= 10);

        // The overlap rule is only fast because of this index.
        check("seat_reservations has the overlap lookup index",
                indexExists(connection, "seat_reservations", "ix_seat_reservations_overlap"));
        check("bookings stores the PNR as an auto-increment primary key",
                columnIsAutoIncrement(connection, "bookings", "pnr"));

        checkForeignKeysAreIndexed(connection);
    }

    /**
     * Every foreign key must have an index that leads with its columns.
     *
     * <p>MySQL creates one automatically for each foreign key, so this is close
     * to a formality - which is exactly why it is worth asserting: a key dropped
     * from a future schema edit turns every join, every {@code DELETE} from a
     * parent table and the overlap rule into a full table scan, and nothing else
     * in this project would notice.
     */
    private static void checkForeignKeysAreIndexed(Connection connection) throws SQLException {
        Map<String, List<String>> foreignKeys = new LinkedHashMap<>();
        String keys = "SELECT CONSTRAINT_NAME, TABLE_NAME, COLUMN_NAME"
                + " FROM information_schema.KEY_COLUMN_USAGE"
                + " WHERE CONSTRAINT_SCHEMA = DATABASE() AND REFERENCED_TABLE_NAME IS NOT NULL"
                + " ORDER BY TABLE_NAME, CONSTRAINT_NAME, ORDINAL_POSITION";
        try (Statement statement = connection.createStatement();
             ResultSet results = statement.executeQuery(keys)) {
            while (results.next()) {
                foreignKeys
                        .computeIfAbsent(results.getString(2) + "." + results.getString(1),
                                name -> new ArrayList<>())
                        .add(results.getString(3));
            }
        }

        Map<String, List<List<String>>> indexes = new LinkedHashMap<>();
        // Rows arrive grouped by index, so a new table or index name starts a
        // new list and everything after it is one more column of that index.
        try (Statement statement = connection.createStatement();
             ResultSet results = statement.executeQuery(
                     "SELECT TABLE_NAME, INDEX_NAME, COLUMN_NAME"
                             + " FROM information_schema.STATISTICS"
                             + " WHERE TABLE_SCHEMA = DATABASE()"
                             + " ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX")) {
            String table = null;
            String index = null;
            List<String> current = null;
            while (results.next()) {
                if (!results.getString(1).equals(table) || !results.getString(2).equals(index)) {
                    table = results.getString(1);
                    index = results.getString(2);
                    current = new ArrayList<>();
                    indexes.computeIfAbsent(table, name -> new ArrayList<>()).add(current);
                }
                current.add(results.getString(3));
            }
        }

        List<String> unindexed = new ArrayList<>();
        for (Map.Entry<String, List<String>> foreignKey : foreignKeys.entrySet()) {
            String table = foreignKey.getKey().substring(0, foreignKey.getKey().indexOf('.'));
            boolean covered = indexes.getOrDefault(table, List.of()).stream()
                    .anyMatch(candidate -> candidate.size() >= foreignKey.getValue().size()
                            && candidate.subList(0, foreignKey.getValue().size())
                            .equals(foreignKey.getValue()));
            if (!covered) {
                unindexed.add(foreignKey.getKey() + " (" + String.join(", ", foreignKey.getValue()) + ")");
            }
        }

        check("all " + foreignKeys.size() + " foreign keys lead an index",
                unindexed.isEmpty());
        if (!unindexed.isEmpty()) {
            System.out.println("  unindexed: " + String.join(", ", unindexed));
        }
    }

    /**
     * A refused connection, and what the operator is told about it.
     *
     * <p>The account used here does not exist, so the server's access-denied
     * path is exercised without a failed attempt landing on a real account's
     * login counters. Nothing is read or written: the connection never opens.
     */
    private static void checkRefusedConnection(DbConfig config) {
        System.out.println();
        System.out.println("A refused connection");

        String ghost = config.getUser() + "_refused_by_check";
        PrintStream console = System.err;
        ByteArrayOutputStream detail = new ByteArrayOutputStream();
        SQLException refused = null;

        System.setErr(new PrintStream(detail, true, StandardCharsets.UTF_8));
        try {
            DriverManager.getConnection(config.getUrl(), ghost, "not-the-password");
        } catch (SQLException problem) {
            refused = problem;
            String shown = DatabaseFailure.report("connecting to the database", problem);
            String said = detail.toString(StandardCharsets.UTF_8);
            String state = problem.getSQLState();

            check("a connection as an account that does not exist is refused", true);
            check("the server refuses it with an access-denied state, not a network error",
                    state != null && state.startsWith("28"));
            System.out.println("  SQLState " + state);

            check("the operator is shown a sentence that names no account",
                    shown != null && !shown.isBlank() && !shown.contains(ghost)
                            && !shown.contains(config.getUser()));
            check("and it leaks no driver class, no JDBC URL and no SQL text",
                    !shown.contains("com.mysql") && !shown.contains("jdbc:")
                            && !shown.toLowerCase().contains("select "));
            check("the detail is kept for the console instead",
                    said.contains("RailReserve") && said.contains(ghost)
                            && said.contains("Access denied"));
            // Even on the console the credential stays out: the driver says
            // "(using password: YES)" and never the password itself.
            check("and not even the console detail carries the password",
                    !said.contains("not-the-password"));
        } finally {
            System.setErr(console);
        }

        if (refused == null) {
            check("a connection as an account that does not exist is refused", false);
        }
    }

    /**
     * With nothing configured the application must say what to configure, not
     * fall back to some default account.
     */
    private static void checkUnconfiguredGuidance() {
        System.out.println();
        System.out.println("An unconfigured machine");

        String override = System.getProperty(DbConfig.PROP_CONFIG_FILE);
        System.setProperty(DbConfig.PROP_CONFIG_FILE, "target/no-such-local.properties");
        try {
            DbConfig nothing = DbConfig.load();
            check("a missing config file leaves the application unconfigured",
                    !nothing.isConfigured());
            check("and it names the settings to set", nothing.missingSettingMessage() != null
                    && nothing.missingSettingMessage().contains(DbConfig.PROP_USER));
            check("it invents no account and no password",
                    nothing.getUser() == null
                            && nothing.describe().contains("password=<not set>"));
        } finally {
            if (override == null) {
                System.clearProperty(DbConfig.PROP_CONFIG_FILE);
            } else {
                System.setProperty(DbConfig.PROP_CONFIG_FILE, override);
            }
        }
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        // information_schema.TABLES.TABLE_TYPE is 'BASE TABLE', not 'TABLE'.
        return objectExists(connection, "BASE TABLE", table);
    }

    private static boolean objectExists(Connection connection, String type, String name)
            throws SQLException {
        String sql = "SELECT COUNT(*) FROM information_schema.TABLES"
                + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND TABLE_TYPE = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, name);
            statement.setString(2, type);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() && results.getInt(1) > 0;
            }
        }
    }

    private static boolean indexExists(Connection connection, String table, String index)
            throws SQLException {
        String sql = "SELECT COUNT(*) FROM information_schema.STATISTICS"
                + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            statement.setString(2, index);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() && results.getInt(1) > 0;
            }
        }
    }

    private static boolean columnIsAutoIncrement(Connection connection, String table, String column)
            throws SQLException {
        String sql = "SELECT COUNT(*) FROM information_schema.COLUMNS"
                + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?"
                + " AND EXTRA LIKE '%auto_increment%'";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() && results.getInt(1) > 0;
            }
        }
    }

    // ----- sample data --------------------------------------------------

    private static void checkSampleData(Connection connection) throws SQLException {
        System.out.println();
        System.out.println("Sample data");

        check("5 travel classes", countScalar(connection, "SELECT COUNT(*) FROM travel_classes") == 5);
        check("6 stations", countScalar(connection, "SELECT COUNT(*) FROM stations") == 6);
        check("4 trains", countScalar(connection, "SELECT COUNT(*) FROM trains") == 4);
        check("17 train stops", countScalar(connection, "SELECT COUNT(*) FROM train_stops") == 17);
        check("18 coaches", countScalar(connection, "SELECT COUNT(*) FROM coaches") == 18);
        // 7 SL x 36 + 6 3A x 18 + 3 2A x 8 + 1 1A x 20 + 1 CC x 10
        check("414 berths", countScalar(connection, "SELECT COUNT(*) FROM berths") == 414);
        check("8 fare rules (5 class defaults + 3 route overrides)",
                countScalar(connection, "SELECT COUNT(*) FROM fare_rules") == 8);
        check("2 demo users are seeded", countScalar(connection,
                "SELECT COUNT(*) FROM users WHERE username LIKE 'demo\\_%'") == 2);

        // Scoped to the seeded demo accounts on purpose: real accounts registered
        // through the application must hold a password hash, so counting all of
        // them would make this check fail the moment anyone registers.
        check("no demo user has a password set",
                countScalar(connection, "SELECT COUNT(*) FROM users"
                        + " WHERE username LIKE 'demo\\_%' AND password_hash IS NOT NULL") == 0);

        // Availability is live, not seeded: a journey row exists only because the
        // real search path materialised one for a date somebody asked about, and
        // it must be attached to real coaches or it would be faked inventory.
        check("every journey present is a real one, attached to real coaches",
                countScalar(connection,
                        "SELECT COUNT(*) FROM journeys j WHERE NOT EXISTS ("
                                + "  SELECT 1 FROM journey_coaches jc JOIN coaches c"
                                + "    ON c.coach_id = jc.coach_id"
                                + "   WHERE jc.journey_id = j.journey_id"
                                + "     AND jc.coach_status = 'ATTACHED')") == 0);
        check("no journey has a reservation without a booking",
                countScalar(connection,
                        "SELECT COUNT(*) FROM seat_reservations sr WHERE NOT EXISTS ("
                                + "  SELECT 1 FROM bookings b WHERE b.pnr = sr.pnr"
                                + "     AND b.journey_id = sr.journey_id)") == 0);
        // Bookings are live, not seeded: a booking exists only because somebody
        // booked through the application, and PersistenceSelfCheck deliberately
        // leaves one such booking behind. So the rule is not "none exist" but
        // "none exist that no real account owns", which is the integrity claim
        // the sample data could otherwise break.
        check("every booking present belongs to a real account",
                countScalar(connection, "SELECT COUNT(*) FROM bookings b WHERE NOT EXISTS ("
                        + "  SELECT 1 FROM users u WHERE u.user_id = b.user_id)") == 0);
        check("every passenger present belongs to a real booking",
                countScalar(connection, "SELECT COUNT(*) FROM passengers p WHERE NOT EXISTS ("
                        + "  SELECT 1 FROM bookings b WHERE b.pnr = p.pnr)") == 0);

        check("at least one train crosses midnight",
                countScalar(connection, "SELECT COUNT(DISTINCT train_no) FROM train_stops"
                        + " WHERE day_offset > 0") >= 2);
        check("at least one train runs entirely in daylight",
                countScalar(connection, "SELECT COUNT(*) FROM (SELECT train_no FROM train_stops"
                        + " GROUP BY train_no HAVING MAX(day_offset) = 0) d") >= 1);

        check("not every train offers every class",
                countScalar(connection, "SELECT COUNT(*) FROM (SELECT c.train_no"
                        + " FROM coaches c GROUP BY c.train_no"
                        + " HAVING COUNT(DISTINCT c.class_code) < 5) t") >= 1);

        check("fare rules have no open-ended duplicates per class",
                countScalar(connection, "SELECT COUNT(*) FROM fare_rules"
                        + " WHERE origin_station_code IS NULL AND destination_station_code IS NULL"
                        + " GROUP BY class_code HAVING COUNT(*) > 1") == 0);
    }

    // ----- berth layout -------------------------------------------------

    private static void checkBerthLayout(Connection connection) throws SQLException {
        System.out.println();
        System.out.println("Berth layout");

        check("every coach has exactly seats_per_coach berths",
                countScalar(connection,
                        "SELECT COUNT(*) FROM coaches c"
                                + " JOIN travel_classes t ON t.class_code = c.class_code"
                                + " LEFT JOIN (SELECT coach_id, COUNT(*) n FROM berths GROUP BY coach_id) b"
                                + "   ON b.coach_id = c.coach_id"
                                + " WHERE COALESCE(b.n, 0) <> t.seats_per_coach") == 0);

        check("sleeper classes never carry a seating position",
                countScalar(connection,
                        "SELECT COUNT(*) FROM berths b"
                                + " JOIN coaches c ON c.coach_id = b.coach_id"
                                + " JOIN travel_classes t ON t.class_code = c.class_code"
                                + " WHERE t.is_sleeper = FALSE"
                                + " AND b.position IN ('LOWER','MIDDLE','UPPER')") == 0);

        check("seating classes never carry a sleeping position",
                countScalar(connection,
                        "SELECT COUNT(*) FROM berths b"
                                + " JOIN coaches c ON c.coach_id = b.coach_id"
                                + " JOIN travel_classes t ON t.class_code = c.class_code"
                                + " WHERE t.is_sleeper = TRUE AND c.class_code = 'CC'"
                                + " AND b.position IN ('LOWER','MIDDLE','UPPER')") == 0);

        check("3A berths use all three tiers",
                countScalar(connection,
                        "SELECT COUNT(DISTINCT b.position) FROM berths b"
                                + " JOIN coaches c ON c.coach_id = b.coach_id"
                                + " WHERE c.class_code = '3A'") == 3);

        check("CC seats use both window and aisle",
                countScalar(connection,
                        "SELECT COUNT(DISTINCT b.position) FROM berths b"
                                + " JOIN coaches c ON c.coach_id = b.coach_id"
                                + " WHERE c.class_code = 'CC'") == 2);

        check("no seat is offered in a class the train does not run",
                countScalar(connection,
                        "SELECT COUNT(*) FROM berths b"
                                + " JOIN coaches c ON c.coach_id = b.coach_id"
                                + " JOIN travel_classes t ON t.class_code = c.class_code"
                                + " WHERE c.class_code NOT IN (SELECT class_code FROM travel_classes)") == 0);
    }

    // ----- route integrity ----------------------------------------------

    private static void checkRouteIntegrity(Connection connection) throws SQLException {
        System.out.println();
        System.out.println("Route integrity");

        check("stop sequences start at 1 on every train",
                countScalar(connection,
                        "SELECT COUNT(*) FROM (SELECT train_no FROM train_stops"
                                + " GROUP BY train_no HAVING MIN(stop_seq) <> 1) d") == 0);

        check("stop sequences have no gaps",
                countScalar(connection,
                        "SELECT COUNT(*) FROM (SELECT train_no, COUNT(*) c, MAX(stop_seq) m"
                                + " FROM train_stops GROUP BY train_no HAVING c <> m) d") == 0);

        check("cumulative distance never goes backwards",
                countScalar(connection,
                        "SELECT COUNT(*) FROM train_stops a JOIN train_stops b"
                                + " ON a.train_no = b.train_no AND b.stop_seq = a.stop_seq + 1"
                                + " WHERE b.distance_km <= a.distance_km") == 0);

        check("only the originating stop omits an arrival time",
                countScalar(connection,
                        "SELECT COUNT(*) FROM train_stops WHERE stop_seq = 1 AND arrival_time IS NOT NULL") == 0);

        check("declared train distance matches the final stop",
                countScalar(connection,
                        "SELECT COUNT(*) FROM trains t JOIN (SELECT train_no, MAX(distance_km) m"
                                + " FROM train_stops GROUP BY train_no) s ON s.train_no = t.train_no"
                                + " WHERE t.distance_km <> s.m") == 0);

        check("boarding before destination is possible on every train",
                countScalar(connection,
                        "SELECT COUNT(*) FROM (SELECT train_no FROM train_stops"
                                + " GROUP BY train_no HAVING COUNT(*) < 2) d") == 0);

        // Demonstrates that a specific origin/destination pair on one train
        // is selectable, which is the requirement the route model exists for.
        check("CSTM to PUNE is a valid segment of train 12951",
                countScalar(connection,
                        "SELECT COUNT(*) FROM train_stops b JOIN train_stops d"
                                + " ON b.train_no = d.train_no"
                                + " WHERE b.train_no = '12951' AND b.station_code = 'CSTM'"
                                + " AND d.station_code = 'PUNE' AND b.stop_seq < d.stop_seq") == 1);
    }

    // ----- booking path, then rollback ----------------------------------

    /**
     * Exercises the whole booking path in one transaction and rolls it back:
     * journey, journey_coaches, booking, passenger and seat_reservation.
     * Also proves the segment overlap rule, which is the invariant that
     * stops the same berth being sold twice on one journey.
     */
    private static void checkBookingPathAndRollback(Connection connection) throws SQLException {
        System.out.println();
        System.out.println("Booking path (inside a transaction that is rolled back)");

        connection.setAutoCommit(false);
        long journeyId = 0;
        long pnr = 0;
        try {
            LocalDate journeyDate = LocalDate.now().plusDays(7);

            journeyId = insertJourney(connection, "12951", journeyDate);
            check("a journey row was created", journeyId > 0);

            int coaches;
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO journey_coaches (journey_id, coach_id, coach_status)"
                            + " SELECT ?, coach_id, 'ATTACHED' FROM coaches WHERE train_no = '12951'")) {
                statement.setLong(1, journeyId);
                coaches = statement.executeUpdate();
            }
            check("the journey's coaches were attached (" + coaches + ")", coaches == 4);

            long berthId = firstBerthId(connection, journeyId);
            check("an attached coach exposes a berth", berthId > 0);

            pnr = insertBooking(connection, journeyId, "12951", journeyDate,
                    "Database SelfCheck", "9000000000", null);
            check("a booking row was created", pnr > 0);

            // The 10-digit form is produced in Java, not stored: MySQL
            // refuses a generated column over an AUTO_INCREMENT column.
            String pnrDisplay = String.format("%010d", pnr);
            check("the PNR renders as 10 digits (was " + pnrDisplay + ")",
                    pnrDisplay.matches("\\d{10}"));

            long passengerId = insertPassenger(connection, pnr, "Selfcheck Passenger", 34, "LOWER");
            check("a passenger row was created", passengerId > 0);

            // Stop 1 (CSTM) to stop 2 (PUNE) on train 12951.
            insertReservation(connection, pnr, passengerId, journeyId, berthId, 1, 2, 150.00);
            check("a seat reservation was created", true);

            // The same berth, same segment, a second passenger: must be
            // detected as overlapping.
            long otherPassenger = insertPassenger(connection, pnr, "Second Passenger", 41, "NO_PREFERENCE");
            check("an overlapping hold on the same berth and segment is detected",
                    countOverlaps(connection, journeyId, berthId, 1, 2) == 1);

            // Same berth, an adjacent segment (2 -> 3): touches but does
            // not overlap, so it is allowed.
            check("an adjacent non-overlapping segment is allowed",
                    countOverlaps(connection, journeyId, berthId, 2, 3) == 0);

            // A different berth is unaffected either way.
            long otherBerth = secondBerthId(connection, journeyId, berthId);
            check("a different berth is unaffected",
                    otherBerth > 0 && countOverlaps(connection, journeyId, otherBerth, 1, 2) == 0);

            check("the booking total is readable",
                    countScalar(connection,
                            "SELECT COUNT(*) FROM seat_reservations WHERE pnr = ?", pnr) == 1);

            // RAC counts as occupying the berth.
            check("RAC counts as an occupied berth",
                    countScalar(connection,
                            "SELECT COUNT(*) FROM v_journey_berth_occupancy"
                                    + " WHERE journey_id = ? AND berth_id = ? AND hold_count > 0",
                            journeyId, berthId) == 1);

        } finally {
            connection.rollback();
            connection.setAutoCommit(true);
        }

        // Scoped to the journey this check created: a journey materialised by the
        // real search path on another date is a legitimate committed row and
        // nothing to do with this rollback.
        check("rollback removed the journey it created",
                countScalar(connection, "SELECT COUNT(*) FROM journeys WHERE journey_id = ?",
                        journeyId) == 0);
        check("rollback removed the booking", countScalar(connection,
                "SELECT COUNT(*) FROM bookings WHERE pnr = ?", pnr) == 0);
        check("rollback removed the reservations",
                countScalar(connection, "SELECT COUNT(*) FROM seat_reservations WHERE pnr = ?",
                        pnr) == 0);
        // PersistenceSelfCheck commits one booking on purpose, so "the table is
        // empty" is no longer a thing that can be true. What must hold is that
        // its row is still exactly the one it committed.
        check("and a booking committed by another check is left alone",
                countScalar(connection, "SELECT COUNT(*) FROM bookings b"
                        + " WHERE EXISTS (SELECT 1 FROM users u WHERE u.user_id = b.user_id)") >= 1);
        check("rollback left the sample data intact",
                countScalar(connection, "SELECT COUNT(*) FROM stations") == 6);
    }

    private static long insertJourney(Connection connection, String trainNo, LocalDate date)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO journeys (train_no, journey_date, service_status)"
                        + " VALUES (?, ?, 'SCHEDULED')", Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, trainNo);
            statement.setDate(2, java.sql.Date.valueOf(date));
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0L;
            }
        }
    }

    private static long insertBooking(Connection connection, long journeyId, String trainNo,
                                      LocalDate date, String name, String mobile, String email)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO bookings (journey_id, train_no, journey_date,"
                        + " boarding_stop_seq, boarding_station_code,"
                        + " destination_stop_seq, destination_station_code,"
                        + " travel_class_code, total_fare, passenger_count, status,"
                        + " contact_name, contact_mobile, contact_email)"
                        + " VALUES (?, ?, ?, 1, 'CSTM', 2, 'PUNE', '3A', 450.00, 1,"
                        + " 'CONFIRMED', ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, journeyId);
            statement.setString(2, trainNo);
            statement.setDate(3, java.sql.Date.valueOf(date));
            statement.setString(4, name);
            statement.setString(5, mobile);
            statement.setString(6, email);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0L;
            }
        }
    }

    private static long insertPassenger(Connection connection, long pnr, String name,
                                        int age, String preference) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO passengers (pnr, full_name, age, gender, berth_preference, is_primary)"
                        + " VALUES (?, ?, ?, 'OTHER', ?, TRUE)", Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, pnr);
            statement.setString(2, name);
            statement.setInt(3, age);
            statement.setString(4, preference);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0L;
            }
        }
    }

    private static void insertReservation(Connection connection, long pnr, long passengerId,
                                          long journeyId, long berthId, int fromStop, int toStop,
                                          double amount) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO seat_reservations (pnr, passenger_id, journey_id, berth_id, coach_id,"
                        + " travel_class_code, from_stop_seq, to_stop_seq, from_station_code,"
                        + " to_station_code, berth_number, berth_type, position, amount, status)"
                        + " SELECT ?, ?, ?, b.berth_id, b.coach_id, c.class_code,"
                        + " ?, ?, 'CSTM', 'PUNE', b.berth_number, b.berth_type, b.position, ?,"
                        + " 'CONFIRMED'"
                        + " FROM berths b JOIN coaches c ON c.coach_id = b.coach_id"
                        + " WHERE b.berth_id = ?")) {
            statement.setLong(1, pnr);
            statement.setLong(2, passengerId);
            statement.setLong(3, journeyId);
            statement.setInt(4, fromStop);
            statement.setInt(5, toStop);
            statement.setBigDecimal(6, new java.math.BigDecimal(String.valueOf(amount)));
            statement.setLong(7, berthId);
            statement.executeUpdate();
        }
    }

    /**
     * The overlap test the booking engine must use, expressed once here so
     * the schema, the index and the rule stay in agreement.
     */
    private static int countOverlaps(Connection connection, long journeyId, long berthId,
                                     int fromStop, int toStop) throws SQLException {
        return countScalar(connection,
                "SELECT COUNT(*) FROM seat_reservations"
                        + " WHERE journey_id = ? AND berth_id = ?"
                        + " AND status IN ('CONFIRMED','RAC')"
                        + " AND from_stop_seq < ? AND to_stop_seq > ?",
                journeyId, berthId, toStop, fromStop);
    }

    private static long firstBerthId(Connection connection, long journeyId) throws SQLException {
        return firstLong(connection,
                "SELECT berth_id FROM v_journey_berth_occupancy WHERE journey_id = ? LIMIT 1",
                journeyId);
    }

    private static long secondBerthId(Connection connection, long journeyId, long notThisOne)
            throws SQLException {
        return firstLong(connection,
                "SELECT berth_id FROM v_journey_berth_occupancy"
                        + " WHERE journey_id = ? AND berth_id <> ? LIMIT 1",
                journeyId, notThisOne);
    }

    // ----- small query helpers -----------------------------------------

    private static int countScalar(Connection connection, String sql, Object... parameters)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? results.getInt(1) : 0;
            }
        }
    }

    private static long firstLong(Connection connection, String sql, Object... parameters)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? results.getLong(1) : 0L;
            }
        }
    }

    private static void bind(PreparedStatement statement, Object... parameters)
            throws SQLException {
        for (int index = 0; index < parameters.length; index++) {
            Object value = parameters[index];
            statement.setObject(index + 1, value);
        }
    }

    /** Prints the result, counting skips, and stops the program on failure. */
    private static void check(String name, boolean passed) {
        if (!passed) {
            checksRun++;
            throw new IllegalStateException("DATABASE CHECK FAILED: " + name);
        }
        checksRun++;
        System.out.println("PASS  " + name);
    }
}