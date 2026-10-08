package com.rail.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * The one place the application opens a MySQL connection.
 *
 * Callers are expected to use try-with-resources, which closes the
 * connection when the block ends:
 *
 * <pre>{@code
 * try (Connection connection = DatabaseConnection.getConnection();
 *      PreparedStatement statement = connection.prepareStatement(SQL);
 *      ResultSet results = statement.executeQuery()) {
 *     ...
 * }
 * }</pre>
 *
 * {@link DbConfig} decides the URL, user and password, so no credential is
 * ever written in this source file. Nothing is cached beyond the resolved
 * configuration: a connection is cheap to open for a single-user desktop
 * application, and pooling is not needed until there is concurrency.
 *
 * ponytail: DriverManager with try-with-resources and no pool, because one
 * desktop user runs one booking at a time. Add a pool only if screens
 * start opening connections in bursts.
 */
public final class DatabaseConnection {

    private DatabaseConnection() {
        // Static access only, so this class is never created.
    }

    /**
     * Holds the resolved configuration, loaded once on first use. The
     * holder idiom means an unconfigured environment does not fail while
     * this class is being loaded, only when a connection is attempted.
     */
    private static final class Holder {
        static final DbConfig CONFIG = DbConfig.load();
    }

    /** The resolved settings, for callers that want to report on them. */
    public static DbConfig config() {
        return Holder.CONFIG;
    }

    /** True when a connection could be attempted. */
    public static boolean isConfigured() {
        return Holder.CONFIG.isConfigured();
    }

    /**
     * Opens a new connection. The caller owns it and must close it, so
     * every call site uses try-with-resources.
     *
     * @throws IllegalStateException if no database is configured
     * @throws SQLException          if the server rejects the connection
     */
    public static Connection getConnection() throws SQLException {
        DbConfig config = Holder.CONFIG;

        if (!config.isConfigured()) {
            throw new IllegalStateException(config.missingSettingMessage());
        }
        if (config.isRootAccount()) {
            // Not fatal: a developer may only have root locally. It is
            // still worth saying out loud, because normal application
            // traffic belongs on a least-privilege account.
            System.err.println("WARNING: connecting as MySQL user 'root'. Normal "
                    + "application operations should use a dedicated account. "
                    + "See database/create_app_user.sql.");
        }

        loadDriver(config.getDriver());

        return DriverManager.getConnection(config.getUrl(), config.getUser(), config.getPassword());
    }

    /**
     * Loads the JDBC driver class explicitly. Connector/J registers itself
     * through the service loader on current JDKs, but naming the class
     * keeps this working on older drivers and makes the requirement
     * visible instead of implicit.
     */
    private static void loadDriver(String driverClassName) throws SQLException {
        try {
            Class.forName(driverClassName);
        } catch (ClassNotFoundException exception) {
            SQLException failure = new SQLException(
                    "JDBC driver not found: " + driverClassName
                            + ". Add mysql-connector-j to the classpath (it is declared in pom.xml).");
            failure.initCause(exception);
            throw failure;
        }
    }
}