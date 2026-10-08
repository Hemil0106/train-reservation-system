package com.rail;

import com.rail.db.DbConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Proves that {@link DbConfig} resolves a configuration file from anywhere, and
 * that it never invents, guesses or leaks a credential.
 *
 * <p>This exists because the packaged application once reported "no database is
 * configured" on a machine that had {@code database/local.properties} sitting
 * right next to the jar: {@code Paths.get("database/local.properties")} resolves
 * against the working directory, so launching the jar from anywhere else missed
 * the file. The fix searches the project the code came from as well.
 *
 * <p>Run it from a second directory to see that path search work:
 *
 * <pre>{@code
 * cd %TEMP%
 * java -cp "path\to\target\classes;path\to\target\lib\*" com.rail.DbConfigSelfCheck
 * }</pre>
 *
 * <p>No real credential is needed and no connection is opened. The passwords
 * below are deliberately fake strings, and nothing here prints a password: the
 * one assertion that reads one reads it to prove it does <em>not</em> appear in
 * a summary.
 */
public class DbConfigSelfCheck {

    /** Obviously fake, and checked for by name so it cannot leak unnoticed. */
    private static final String FAKE_PASSWORD = "not-a-real-secret-2f8c";

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws IOException {
        // First, because DatabaseConnection resolves its settings once per JVM
        // and the scenario below needs a URL that cannot possibly connect.
        checkStartupSaysWhichProblemItIs();

        section("Where the configuration file is looked for");

        DbConfig here = DbConfig.load();
        Path resolved = here.getConfigFile();

        see("the resolved path is absolute, not relative to wherever you ran from",
                resolved.isAbsolute());
        see("it is named database/local.properties",
                resolved.endsWith(Path.of("database", "local.properties")));
        see("the message an operator would read names that exact path",
                here.isConfigured() || here.missingSettingMessage()
                        .contains(resolved.toString()));

        boolean besideTheWorkingDirectory =
                Files.isReadable(Path.of(DbConfig.DEFAULT_CONFIG_FILE));
        System.out.println("  matched: " + (besideTheWorkingDirectory
                ? "the working directory"
                : "beside the running code - the fix for a jar started elsewhere"));
        see("the file it settled on is readable", Files.isReadable(resolved));

        section("A valid configuration file");
        Path valid = write("db.url=jdbc:mysql://db.example.invalid:3307/railway\n"
                + "db.user=someone\n"
                + "db.password=" + FAKE_PASSWORD + "\n"
                + "search.max.passengers=9\n"
                + "booking.min.age=2\n"
                + "booking.max.age=60\n");
        try {
            DbConfig config = load(valid);
            see("a whole db.url is used as given",
                    "jdbc:mysql://db.example.invalid:3307/railway".equals(config.getUrl()));
            see("the user is read", "someone".equals(config.getUser()));
            see("the password is read", FAKE_PASSWORD.equals(config.getPassword()));
            see("the passenger cap is read", config.maxPassengers() == 9);
            see("the age window is read",
                    config.minPassengerAge() == 2 && config.maxPassengerAge() == 60);
            see("it counts as configured", config.isConfigured());
            see("the summary reports a password as set and shows nothing",
                    config.describe().contains("password=<set, not shown>"));
            see("and the password itself never appears in the summary",
                    !config.describe().contains(FAKE_PASSWORD));
        } finally {
            delete(valid);
        }

        section("A configuration file assembled from parts");
        Path parts = write("db.host=db.example.invalid\n"
                + "db.port=3307\ndb.name=timetable\ndb.user=someone\ndb.password=x\n");
        try {
            DbConfig config = load(parts);
            see("host, port and name become one URL",
                    "jdbc:mysql://db.example.invalid:3307/timetable".equals(config.getUrl()));
        } finally {
            delete(parts);
        }

        section("A malformed configuration file");
        Path junk = write("this line is not a property\n"
                + "db.user=someone\n"
                + "db.password=\n"
                + "search.max.passengers=lots\n"
                + "booking.min.age=-4\n");
        try {
            DbConfig config = load(junk);
            see("an unparseable line is ignored rather than fatal", config.isConfigured());
            see("an unparseable cap falls back to the default",
                    config.maxPassengers() == DbConfig.DEFAULT_MAX_PASSENGERS);
            see("an age below the floor falls back to the default",
                    config.minPassengerAge() == DbConfig.DEFAULT_MIN_PASSENGER_AGE);
        } finally {
            delete(junk);
        }

        // A minimum above the maximum would reject every age there is, so both
        // ends fall back together rather than leaving an impossible window.
        Path inverted = write("db.user=someone\ndb.password=x\n"
                + "booking.min.age=50\nbooking.max.age=20\n");
        try {
            DbConfig config = load(inverted);
            see("an inverted age window falls back to both defaults",
                    config.minPassengerAge() == DbConfig.DEFAULT_MIN_PASSENGER_AGE
                            && config.maxPassengerAge() == DbConfig.DEFAULT_MAX_PASSENGER_AGE);
        } finally {
            delete(inverted);
        }

        // Above the schema's own ceiling, so the setting may not describe an
        // age the passengers table would refuse.
        Path greedy = write("db.user=someone\ndb.password=x\nbooking.max.age=999\n");
        try {
            DbConfig config = load(greedy);
            see("an age above the schema ceiling is capped at it",
                    config.maxPassengerAge() == DbConfig.SCHEMA_MAX_PASSENGER_AGE);
        } finally {
            delete(greedy);
        }

        section("No configuration at all");
        Path absent = Path.of("target", "definitely-not-here.properties").toAbsolutePath();
        DbConfig nothing = load(absent);
        see("a missing file leaves the application unconfigured", !nothing.isConfigured());
        see("the operator is told the absolute path to create",
                nothing.missingSettingMessage().contains(absent.toString()));
        see("and is told which properties matter",
                nothing.missingSettingMessage().contains(DbConfig.PROP_USER)
                        && nothing.missingSettingMessage().contains("db.url"));
        see("no account is invented", nothing.getUser() == null);
        see("no password is invented",
                nothing.describe().contains("password=<not set>"));
        see("no configuration file was created for it", !Files.exists(absent));

        report();
    }

    /**
     * A server that is not there must not be reported as a missing template.
     *
     * <p>Points the loader at port 1 on localhost, where nothing listens, so the
     * refusal is immediate and needs no server, no account and no data. The
     * driver's own complaint goes to the console; the dialog text is what is
     * asserted here.
     */
    private static void checkStartupSaysWhichProblemItIs() {
        section("Telling a missing file apart from a missing server");

        System.setProperty(DbConfig.PROP_URL, "jdbc:mysql://127.0.0.1:1/railway");
        System.setProperty(DbConfig.PROP_USER, "someone");
        System.setProperty(DbConfig.PROP_PASSWORD, FAKE_PASSWORD);
        try {
            String problem = Main.startupProblem();
            see("an unreachable server is reported as unreachable",
                    problem != null && problem.contains("could not be reached"));
            see("the sentence names the host and port it tried",
                    problem != null && problem.contains("127.0.0.1:1"));
            see("and never the password", problem != null && !problem.contains(FAKE_PASSWORD));
        } finally {
            System.clearProperty(DbConfig.PROP_URL);
            System.clearProperty(DbConfig.PROP_USER);
            System.clearProperty(DbConfig.PROP_PASSWORD);
        }
    }

    /** Loads a configuration from an explicit file, then puts the JVM back. */
    private static DbConfig load(Path file) {
        String previous = System.getProperty(DbConfig.PROP_CONFIG_FILE);
        System.setProperty(DbConfig.PROP_CONFIG_FILE, file.toString());
        try {
            return DbConfig.load();
        } finally {
            if (previous == null) {
                System.clearProperty(DbConfig.PROP_CONFIG_FILE);
            } else {
                System.setProperty(DbConfig.PROP_CONFIG_FILE, previous);
            }
        }
    }

    private static Path write(String contents) throws IOException {
        Path file = Files.createTempFile("rail-dbconfig-check", ".properties");
        Files.writeString(file, contents);
        return file.toAbsolutePath();
    }

    private static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // A leftover temp file in the system temp directory is not worth
            // failing a configuration check over.
        }
    }

    private static void section(String title) {
        System.out.println();
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

    private static void report() {
        System.out.println();
        if (failed > 0) {
            System.out.println(failed + " of " + (passed + failed) + " configuration checks FAILED.");
            System.exit(1);
        }
        System.out.println("All " + passed + " configuration checks passed.");
    }
}