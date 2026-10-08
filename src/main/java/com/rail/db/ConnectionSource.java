package com.rail.db;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Supplies a connection to the services.
 *
 * <p>The default implementation is {@link DatabaseConnection#getConnection()},
 * which is all the running application needs. This exists as a seam so a
 * self-check can hand every service call the <em>same</em> connection with
 * auto-commit off, run a whole registration-and-login scenario, and roll the
 * lot back. That is why verification leaves no test accounts behind, and it is
 * why the application account never needs DELETE privilege.
 *
 * <p>Deliberately a single-method interface, so a lambda is the whole
 * implementation.
 */
@FunctionalInterface
public interface ConnectionSource {

    Connection open() throws SQLException;
}