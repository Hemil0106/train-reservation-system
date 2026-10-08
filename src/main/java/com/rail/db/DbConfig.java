package com.rail.db;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Database settings, resolved from three sources without any credential
 * ever being written into this repository.
 *
 * Sources are tried in this order, first hit wins:
 * <ol>
 *   <li>JVM system properties, e.g. {@code -Ddb.user=railway_app}</li>
 *   <li>environment variables, e.g. {@code RAIL_DB_USER}</li>
 *   <li>a local properties file, by default {@code database/local.properties},
 *       which is listed in {@code .gitignore} and must never be committed. It
 *       is looked for beside the working directory <em>and</em> beside the jar,
 *       so the packaged application works from any directory - see
 *       {@link #resolveConfigFilePath()}.</li>
 * </ol>
 *
 * The JDBC URL may be given whole ({@code db.url}) or assembled from
 * {@code db.host}, {@code db.port} and {@code db.name}. A whole URL wins.
 *
 * Nothing here has a default user or a default password. There is no
 * fallback account, which is the point: if the operator has not configured
 * a connection, the application says so instead of quietly trying
 * something. Copy {@code database/local.properties.example} to
 * {@code database/local.properties} to configure a local run.
 *
 * ponytail: one small class rather than a properties framework, because
 * there is exactly one consumer. Add a pool or a config file format only
 * when a second consumer appears.
 */
public final class DbConfig {

    // Property keys for database/local.properties and -D system properties.
    public static final String PROP_URL = "db.url";
    public static final String PROP_USER = "db.user";
    public static final String PROP_PASSWORD = "db.password";
    public static final String PROP_DRIVER = "db.driver";
    public static final String PROP_HOST = "db.host";
    public static final String PROP_PORT = "db.port";
    public static final String PROP_NAME = "db.name";

    /** Overrides where database/local.properties is looked for. */
    public static final String PROP_CONFIG_FILE = "rail.db.config";

    // Environment variable names.
    public static final String ENV_URL = "RAIL_DB_URL";
    public static final String ENV_USER = "RAIL_DB_USER";
    public static final String ENV_PASSWORD = "RAIL_DB_PASSWORD";
    public static final String ENV_DRIVER = "RAIL_DB_DRIVER";
    public static final String ENV_HOST = "RAIL_DB_HOST";
    public static final String ENV_PORT = "RAIL_DB_PORT";
    public static final String ENV_NAME = "RAIL_DB_NAME";
    public static final String ENV_CONFIG_FILE = "RAIL_DB_CONFIG";

    // Non-secret defaults. A host, a port and a schema name are not
    // credentials, so these are safe to ship.
    public static final String DEFAULT_HOST = "localhost";
    public static final String DEFAULT_PORT = "3306";
    public static final String DEFAULT_NAME = "railway";
    public static final String DEFAULT_DRIVER = "com.mysql.cj.jdbc.Driver";
    public static final String DEFAULT_CONFIG_FILE = "database/local.properties";

    // ---- Search limits (Milestone 4) ----------------------------------
    // Not credentials, so they are safe to ship and safe to publish.
    /** Largest party a single booking may cover. Six is one full coach group. */
    public static final int DEFAULT_MAX_PASSENGERS = 6;
    public static final String PROP_MAX_PASSENGERS = "search.max.passengers";
    public static final String ENV_MAX_PASSENGERS = "RAIL_SEARCH_MAX_PASSENGERS";

    // ---- Passenger age limits (Milestone 5) ---------------------------
    /**
     * Hard ceiling the schema itself imposes: {@code passengers.age} is
     * {@code TINYINT UNSIGNED} with {@code CHECK (age <= 120)}. A configurable
     * maximum above this would accept an age that validation approves and the
     * database then rejects, so the setting is clamped rather than trusted.
     */
    public static final int SCHEMA_MAX_PASSENGER_AGE = 120;
    public static final int DEFAULT_MIN_PASSENGER_AGE = 1;
    public static final int DEFAULT_MAX_PASSENGER_AGE = SCHEMA_MAX_PASSENGER_AGE;
    public static final String PROP_MIN_AGE = "booking.min.age";
    public static final String PROP_MAX_AGE = "booking.max.age";
    public static final String ENV_MIN_AGE = "RAIL_BOOKING_MIN_AGE";
    public static final String ENV_MAX_AGE = "RAIL_BOOKING_MAX_AGE";

    private final String url;
    private final String user;
    private final String password;
    private final String driver;
    private final String source;
    private final boolean passwordProvided;
    private final int maxPassengers;
    private final int minPassengerAge;
    private final int maxPassengerAge;
    private final Path configFile;
    private final boolean configFilePresent;

    private DbConfig(String url, String user, String password, String driver,
                     String source, boolean passwordProvided, int maxPassengers,
                     int minPassengerAge, int maxPassengerAge,
                     Path configFile, boolean configFilePresent) {
        this.url = url;
        this.user = user;
        this.password = password;
        this.driver = driver;
        this.source = source;
        this.passwordProvided = passwordProvided;
        this.maxPassengers = maxPassengers;
        this.minPassengerAge = minPassengerAge;
        this.maxPassengerAge = maxPassengerAge;
        this.configFile = configFile;
        this.configFilePresent = configFilePresent;
    }

    /**
     * The configured cap on passengers per search and per booking.
     *
     * <p>Read from a system property, then an environment variable, then
     * {@code database/local.properties}, falling back to
     * {@link #DEFAULT_MAX_PASSENGERS}. A value that is not a positive number is
     * ignored rather than trusted, so a typo cannot remove the limit
     * altogether.
     */
    public int maxPassengers() {
        return maxPassengers;
    }

    /**
     * Inclusive lower bound on a passenger's age, and the inclusive upper bound
     * from {@link #maxPassengerAge()}.
     *
     * <p>Configured the same way as the passenger cap. The upper bound is clamped
     * to {@link #SCHEMA_MAX_PASSENGER_AGE} so this setting cannot describe an
     * age the {@code passengers} table would refuse.
     */
    public int minPassengerAge() {
        return minPassengerAge;
    }

    public int maxPassengerAge() {
        return maxPassengerAge;
    }

    /**
     * Resolves the settings once. Never throws: an incomplete configuration
     * produces an unconfigured instance, and
     * {@link #missingSettingMessage()} explains what to do about it.
     */
    public static DbConfig load() {
Path file = resolveConfigFilePath();
boolean filePresent = Files.isReadable(file);
Properties fileProperties = readProperties(file);

        String url = firstNonBlank(
                System.getProperty(PROP_URL),
                System.getenv(ENV_URL),
                fileProperties.getProperty(PROP_URL));

        String user = firstNonBlank(
                System.getProperty(PROP_USER),
                System.getenv(ENV_USER),
                fileProperties.getProperty(PROP_USER));

        String password = firstNonBlankAllowingEmpty(
                System.getProperty(PROP_PASSWORD),
                System.getenv(ENV_PASSWORD),
                fileProperties.getProperty(PROP_PASSWORD));

        String driver = firstNonBlank(
                System.getProperty(PROP_DRIVER),
                System.getenv(ENV_DRIVER),
                fileProperties.getProperty(PROP_DRIVER),
                DEFAULT_DRIVER);

        if (url == null) {
            url = buildUrlFromParts(fileProperties);
        }

        String source = describeSource(filePresent, url != null, user != null);
        AgeRange ages = readAgeRange(fileProperties);

        return new DbConfig(url, user, password == null ? "" : password, driver,
                source, password != null,
                readBoundedInt(fileProperties, PROP_MAX_PASSENGERS, ENV_MAX_PASSENGERS,
                        DEFAULT_MAX_PASSENGERS, 1, Integer.MAX_VALUE),
                ages.min(), ages.max(),
                file, filePresent);
    }

    /** Both age bounds, kept consistent with each other. */
    private record AgeRange(int min, int max) {
    }

    /**
     * Resolves the passenger age window.
     *
     * <p>Both bounds are clamped to the schema's own ceiling. An inverted
     * configuration, where the minimum exceeds the maximum, would reject every
     * age there is, so it falls back to the defaults instead.
     */
    private static AgeRange readAgeRange(Properties fileProperties) {
        int min = readBoundedInt(fileProperties, PROP_MIN_AGE, ENV_MIN_AGE,
                DEFAULT_MIN_PASSENGER_AGE, 1, SCHEMA_MAX_PASSENGER_AGE);
        int max = readBoundedInt(fileProperties, PROP_MAX_AGE, ENV_MAX_AGE,
                DEFAULT_MAX_PASSENGER_AGE, 1, SCHEMA_MAX_PASSENGER_AGE);
        if (min > max) {
            return new AgeRange(DEFAULT_MIN_PASSENGER_AGE, DEFAULT_MAX_PASSENGER_AGE);
        }
        return new AgeRange(min, max);
    }

    /**
     * Resolves one numeric setting, falling back to {@code fallback} whenever the
     * configured value is missing, unparseable, or outside {@code low} to
     * {@code high}.
     *
     * <p>Failing closed matters here: a mistyped limit must not silently become
     * "unlimited", and a mistyped age window must not become unconstrained.
     */
    private static int readBoundedInt(Properties fileProperties, String property,
                                      String environment, int fallback, int low, int high) {
        String configured = firstNonBlank(
                System.getProperty(property),
                System.getenv(environment),
                fileProperties.getProperty(property));
        if (configured == null) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(configured.trim());
            return value >= low && value <= high ? value : fallback;
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    private static String buildUrlFromParts(Properties fileProperties) {
        String host = firstNonBlank(
                System.getProperty(PROP_HOST),
                System.getenv(ENV_HOST),
                fileProperties.getProperty(PROP_HOST),
                DEFAULT_HOST);
        String port = firstNonBlank(
                System.getProperty(PROP_PORT),
                System.getenv(ENV_PORT),
                fileProperties.getProperty(PROP_PORT),
                DEFAULT_PORT);
        String name = firstNonBlank(
                System.getProperty(PROP_NAME),
                System.getenv(ENV_NAME),
                fileProperties.getProperty(PROP_NAME),
                DEFAULT_NAME);
        return "jdbc:mysql://" + host + ":" + port + "/" + name;
    }

    private static String describeSource(boolean filePresent, boolean hasUrl, boolean hasUser) {
        if (hasUrl && hasUser) {
            if (System.getProperty(PROP_URL) != null || System.getenv(ENV_URL) != null) {
                return "system properties / environment";
            }
            return filePresent ? "database/local.properties" : "built from defaults";
        }
        return "not configured";
    }

    /**
     * Where {@code database/local.properties} is looked for.
     *
     * <p>An explicit override ({@code -Drail.db.config} or
     * {@code RAIL_DB_CONFIG}) wins outright and is used verbatim, so a test or
     * a second installation can point anywhere.
     *
     * <p>Otherwise the file is searched rather than assumed:
     * <ol>
     *   <li>relative to the working directory - the original behaviour, and the
     *       one that keeps a plain {@code mvn} run working</li>
     *   <li>relative to the project the code was loaded from, found by walking
     *       up from the jar in {@code target/} or from {@code target/classes/}</li>
     * </ol>
     *
     * <p>Step 2 is the fix for a real failure: {@code Paths.get("database/...")}
     * alone resolves against the working directory, so launching the packaged
     * jar from anywhere but the project root reported "no database is
     * configured" while the file sat there unread. A relative path is the wrong
     * thing to resolve when the application knows where it was launched from.
     *
     * <p>The returned path is always absolute, so every message that names it
     * tells the operator where to put the file rather than where they happened
     * to be standing.
     *
     * <p>ponytail: three candidates and a bounded walk up. Add an explicit
     * search path only if somebody really needs one.
     */
    private static Path resolveConfigFilePath() {
        String override = firstNonBlank(
                System.getProperty(PROP_CONFIG_FILE),
                System.getenv(ENV_CONFIG_FILE));
        if (override != null) {
            return Paths.get(override).toAbsolutePath().normalize();
        }

        Path relative = Paths.get(DEFAULT_CONFIG_FILE);
        if (Files.isReadable(relative)) {
            return relative.toAbsolutePath().normalize();
        }

        List<Path> roots = projectRoots();
        for (Path root : roots) {
            Path candidate = root.resolve(relative);
            if (Files.isReadable(candidate)) {
                return candidate;
            }
        }
        // Nothing found: name the first project-relative location, because that
        // is where the file belongs.
        return (roots.isEmpty() ? relative.toAbsolutePath() : roots.get(0).resolve(relative))
                .normalize();
    }

    /**
     * Directories above the running code, nearest first: the folder holding the
     * jar, then its parents, then {@code target/classes} and its parents.
     *
     * <p>Capped at three levels because that is all the layout needs - the jar
     * sits one level below the project root and the compiled classes two. A
     * security manager or an unusual class loader can refuse the lookup, and
     * then the working directory is all this ever was.
     */
    private static List<Path> projectRoots() {
        List<Path> roots = new ArrayList<>();
        try {
            Path location = Paths.get(DbConfig.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path directory = Files.isDirectory(location) ? location : location.getParent();
            for (int levels = 0; directory != null && levels < 3; levels++) {
                roots.add(directory);
                directory = directory.getParent();
            }
        } catch (URISyntaxException | RuntimeException unavailable) {
            // Deliberately empty: the working directory is still searched, and
            // the resulting message stays accurate about where to put the file.
        }
        return roots;
    }

    /** Never returns null: an absent file yields empty properties. */
    private static Properties readProperties(Path path) {
        Properties properties = new Properties();
        if (!Files.isReadable(path)) {
            return properties;
        }
        try (InputStream in = Files.newInputStream(path)) {
            properties.load(in);
        } catch (IOException ignored) {
            // An unreadable config file is treated as absent, so the
            // application reports a clear "not configured" message rather
            // than a file error the operator cannot act on.
            properties.clear();
        }
        return properties;
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.trim().isEmpty()) {
                return candidate.trim();
            }
        }
        return null;
    }

    private static String firstNonBlankAllowingEmpty(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    /** True when there is enough to attempt a connection. */
    public boolean isConfigured() {
        return url != null && user != null;
    }

    public String getUrl() {
        return url;
    }

    public String getUser() {
        return user;
    }

    public String getPassword() {
        return password;
    }

    public String getDriver() {
        return driver;
    }

    public Path getConfigFile() {
        return configFile;
    }

    public boolean isConfigFilePresent() {
        return configFilePresent;
    }

    /**
     * True when the configured account is MySQL's root account. The
     * application warns when this is true: root exists for administration,
     * and normal application traffic should use a least-privilege account.
     */
    public boolean isRootAccount() {
        return user != null && "root".equalsIgnoreCase(user.trim());
    }

    /**
     * A safe one-line summary for logs and for the connection self-check.
     * It reports whether a password is set but never the password itself.
     */
    public String describe() {
        return "source=" + source
                + " url=" + (url == null ? "<unset>" : url)
                + " user=" + (user == null ? "<unset>" : user)
                + " password=" + (passwordProvided ? "<set, not shown>" : "<not set>");
    }

    /**
     * Plain-English instructions for whatever is missing, or null when the
     * configuration is complete.
     *
     * <p>The path is the resolved absolute one, because "put it in
     * {@code database/local.properties}" is only useful to somebody already
     * standing in the right directory - which is exactly the mistake that
     * produces this message in the first place.
     */
    public String missingSettingMessage() {
        if (isConfigured()) {
            return null;
        }
        StringBuilder message = new StringBuilder();
        message.append("Database is not configured. Create this file and fill in your own account:")
                .append(System.lineSeparator())
                .append("  ").append(configFile)
                .append(System.lineSeparator())
                .append("Copy ").append(DEFAULT_CONFIG_FILE).append(".example to that path first.")
                .append(System.lineSeparator())
                .append("It needs at least db.user and a password; db.host, db.port and db.name")
                .append(" default to ").append(DEFAULT_HOST).append(", ")
                .append(DEFAULT_PORT).append(", ").append(DEFAULT_NAME).append(", or give the whole db.url.")
                .append(System.lineSeparator())
                .append("Any one of these also works, first hit wins:")
                .append(System.lineSeparator())
                .append("  1. environment variables: ")
                .append(ENV_USER).append(" and (optionally) ")
                .append(ENV_URL)
                .append(System.lineSeparator())
                .append("  2. system properties:     -D").append(PROP_USER)
                .append("=... -D").append(PROP_URL).append("=...")
                .append(System.lineSeparator())
                .append("  3. local file:            the path above");
        return message.toString();
    }

    @Override
    public String toString() {
        return "DbConfig[" + describe() + "]";
    }
}