package com.rail.db;

import com.rail.model.Role;
import com.rail.model.StoredUser;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Optional;

/**
 * Reads and writes the {@code users} table.
 *
 * <p>The {@link Connection} is supplied by the caller rather than opened here,
 * for two reasons: the application layer decides transaction boundaries, and it
 * lets the self-check run a whole registration/login scenario inside one
 * transaction it then rolls back, so verification leaves no test accounts
 * behind.
 *
 * <p>Every statement is a {@link PreparedStatement} with bound parameters. No
 * value from a form is ever concatenated into SQL text.
 */
public final class UserDao {

    private final Connection connection;

    public UserDao(Connection connection) {
        this.connection = connection;
    }

    /**
     * Inserts a new account.
     *
     * <p>{@code role} is bound as a parameter rather than chosen by the caller
     * at the call site, so a bug cannot promote a self-registered account to
     * administrator. {@code AuthService} always passes {@link Role#CUSTOMER}.
     *
     * @return the generated {@code user_id}
     */
    public long insert(String username,
                       String fullName,
                       String email,
                       String mobile,
                       String passwordHash,
                       Role role) throws SQLException {

        String sql = "INSERT INTO users (username, full_name, email, mobile, password_hash, role)"
                + " VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement =
                     connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, username);
            statement.setString(2, fullName);
            setNullableString(statement, 3, email);
            statement.setString(4, mobile);
            statement.setString(5, passwordHash);
            statement.setString(6, role.databaseValue());
            statement.executeUpdate();

            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("The account was created but no user_id was returned.");
                }
                return keys.getLong(1);
            }
        }
    }

    /** Whether a username is already taken. Used for a friendly message, not for safety. */
    public boolean usernameExists(String username) throws SQLException {
        return exists("SELECT 1 FROM users WHERE username = ?", username);
    }

    /** Whether an email is already taken. */
    public boolean emailExists(String email) throws SQLException {
        return exists("SELECT 1 FROM users WHERE email = ?", email);
    }

    private boolean exists(String sql, String value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    /**
     * Looks up an account by username for a login attempt.
     *
     * <p>Returns the whole row, hash included, because only the service layer
     * may verify a password. Callers that just need an identity should use
     * {@link #findProfile(long)} instead, which does not read the hash at all.
     */
    public Optional<StoredUser> findForLogin(String username) throws SQLException {
        String sql = "SELECT user_id, username, full_name, email, mobile,"
                + " password_hash, role, is_active FROM users WHERE username = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    return Optional.empty();
                }
                return Optional.of(new StoredUser(
                        results.getLong("user_id"),
                        results.getString("username"),
                        results.getString("full_name"),
                        results.getString("email"),
                        results.getString("mobile"),
                        results.getString("password_hash"),
                        Role.fromDatabase(results.getString("role")),
                        results.getBoolean("is_active")));
            }
        }
    }

    /**
     * Reads an account for display purposes, without selecting the hash.
     *
     * <p>This is the method the rest of the application should use. Keeping the
     * hash out of the query means a display bug cannot leak one even if a hash
     * were written to a log or a label by mistake.
     */
    public Optional<StoredUser> findProfile(long userId) throws SQLException {
        String sql = "SELECT user_id, username, full_name, email, mobile, role, is_active"
                + " FROM users WHERE user_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    return Optional.empty();
                }
                return Optional.of(new StoredUser(
                        results.getLong("user_id"),
                        results.getString("username"),
                        results.getString("full_name"),
                        results.getString("email"),
                        results.getString("mobile"),
                        null,
                        Role.fromDatabase(results.getString("role")),
                        results.getBoolean("is_active")));
            }
        }
    }

    /** Total number of accounts. Used by the self-check, not by the dashboard. */
    public long countAll() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM users");
             ResultSet results = statement.executeQuery()) {
            return results.next() ? results.getLong(1) : 0L;
        }
    }

    /** Convenience for the nullable email column, which is genuinely nullable. */
    private static void setNullableString(PreparedStatement statement, int index, String value)
            throws SQLException {
        if (value == null || value.isBlank()) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    /**
     * True when the failure was a duplicate username.
     *
     * <p>Uniqueness is enforced by the {@code uq_users_username} index, not by
     * the service's earlier existence check. Two registrations submitted at the
     * same moment can both pass the check, so this classification is what
     * actually guarantees the user sees a sensible message instead of a
     * constraint-violation dialog.
     */
    public static boolean isDuplicateUsername(SQLException failure) {
        return isDuplicateOf(failure, "uq_users_username");
    }

    /** True when the failure was a duplicate email. See {@link #isDuplicateUsername}. */
    public static boolean isDuplicateEmail(SQLException failure) {
        return isDuplicateOf(failure, "uq_users_email");
    }

    private static boolean isDuplicateOf(SQLException failure, String indexName) {
        if (!(failure instanceof SQLIntegrityConstraintViolationException)) {
            return false;
        }
        String message = failure.getMessage();
        return message != null && message.contains(indexName);
    }
}