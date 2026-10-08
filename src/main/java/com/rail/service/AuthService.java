package com.rail.service;

import com.rail.db.BookingDAO;
import com.rail.db.ConnectionSource;
import com.rail.db.DatabaseConnection;
import com.rail.db.UserDao;
import com.rail.model.Role;
import com.rail.model.StoredUser;
import com.rail.model.User;
import com.rail.security.PasswordHasher;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.regex.Pattern;

/**
 * Registration, login and the permission checks that guard everything else.
 *
 * <p>This is the layer the UI is not trusted with. Hiding a button is
 * presentation; every rule that matters is enforced here, so removing a control
 * from a screen, or calling a service directly, still cannot bypass it.
 *
 * <p><strong>No password or password hash is ever logged</strong>, and none is
 * returned to a caller. {@link #login} hands back a hash-free {@link User}.
 *
 * <p>Connections are opened per operation with try-with-resources. Registration
 * and login are single statements, so there is no transaction to manage and no
 * pool to justify.
 */
public final class AuthService {

    /** Field names, used both as labels and as the keys callers match on. */
    public static final String FIELD_FULL_NAME = "fullName";
    public static final String FIELD_USERNAME = "username";
    public static final String FIELD_EMAIL = "email";
    public static final String FIELD_MOBILE = "mobile";
    public static final String FIELD_PASSWORD = "password";

    private static final int FULL_NAME_MAX = 100;
    private static final int USERNAME_MIN = 3;
    private static final int USERNAME_MAX = 50;
    private static final int MOBILE_DIGITS = ContactRules.MOBILE_DIGITS;

    /** 72 is where BCrypt stops looking at the password; anything past is ignored. */
    private static final int PASSWORD_MIN = 8;
    private static final int PASSWORD_MAX = 72;

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9._-]+$");

    private final ConnectionSource connections;

    /** The application default: a fresh connection per operation. */
    public AuthService() {
        this(DatabaseConnection::getConnection);
    }

    /**
     * Uses a caller-supplied connection strategy.
     *
     * <p>Only the self-check needs this, to run inside one rollback-able
     * transaction. See {@link ConnectionSource}.
     */
    public AuthService(ConnectionSource connections) {
        this.connections = connections;
    }

    /**
     * Creates an account.
     *
     * <p>The new account is always {@link Role#CUSTOMER}. There is no parameter
     * for a caller to pass {@link Role#ADMIN}, which is the point: making an
     * administrator is a DBA action (README 10.6), not something the
     * application can be talked into doing.
     *
     * <p>Does not start a session; the caller sends the new user to the login
     * screen so the password path is always exercised.
     *
     * @return the created account, hash-free
     */
    public User register(String fullName, String username, String email, String mobile, String password)
            throws AuthException {

        String cleanName = ContactRules.trimToNull(fullName);
        String cleanUsername = ContactRules.trimToNull(username);
        String cleanEmail = ContactRules.trimToNull(email);
        String cleanMobile = ContactRules.trimToNull(mobile);

        // --- validate before touching the database -------------------------
        if (cleanName == null) {
            throw new AuthException(FIELD_FULL_NAME, "Please enter your full name.");
        }
        if (cleanName.length() > FULL_NAME_MAX) {
            throw new AuthException(FIELD_FULL_NAME,
                    "Full name must be " + FULL_NAME_MAX + " characters or fewer.");
        }
        if (cleanUsername == null) {
            throw new AuthException(FIELD_USERNAME, "Please choose a username.");
        }
        if (cleanUsername.length() < USERNAME_MIN || cleanUsername.length() > USERNAME_MAX) {
            throw new AuthException(FIELD_USERNAME, "Username must be "
                    + USERNAME_MIN + " to " + USERNAME_MAX + " characters.");
        }
        if (!USERNAME_PATTERN.matcher(cleanUsername).matches()) {
            throw new AuthException(FIELD_USERNAME,
                    "Username may contain letters, digits, dot, underscore and hyphen only.");
        }
        if (cleanEmail != null && !ContactRules.isValidEmail(cleanEmail)) {
            throw new AuthException(FIELD_EMAIL, "That does not look like a valid email address.");
        }
        String normalisedMobile = normaliseMobile(cleanMobile);
        if (normalisedMobile == null) {
            throw new AuthException(FIELD_MOBILE,
                    "Please enter a " + MOBILE_DIGITS + "-digit mobile number.");
        }
        if (password == null || password.length() < PASSWORD_MIN) {
            throw new AuthException(FIELD_PASSWORD,
                    "Please choose a password of at least " + PASSWORD_MIN + " characters.");
        }
        if (password.length() > PASSWORD_MAX) {
            throw new AuthException(FIELD_PASSWORD,
                    "Password must be " + PASSWORD_MAX + " characters or fewer.");
        }

        String passwordHash = PasswordHasher.hash(password);

        try (Connection connection = connections.open()) {
            UserDao users = new UserDao(connection);

            // Friendly pre-checks. The unique indexes remain the real
            // guarantee, because two simultaneous registrations can both pass
            // these reads.
            if (users.usernameExists(cleanUsername)) {
                throw new AuthException(FIELD_USERNAME, "That username is already taken.");
            }
            if (cleanEmail != null && users.emailExists(cleanEmail)) {
                throw new AuthException(FIELD_EMAIL, "An account already uses that email address.");
            }

            try {
                long userId = users.insert(
                        cleanUsername, cleanName, cleanEmail, normalisedMobile, passwordHash, Role.CUSTOMER);
                return new User(userId, cleanUsername, cleanName, Role.CUSTOMER);
            } catch (SQLException failure) {
                // Lost the race against a concurrent registration.
                if (UserDao.isDuplicateUsername(failure)) {
                    throw new AuthException(FIELD_USERNAME, "That username is already taken.");
                }
                if (UserDao.isDuplicateEmail(failure)) {
                    throw new AuthException(FIELD_EMAIL, "An account already uses that email address.");
                }
                throw failure;
            }
        } catch (SQLException failure) {
            throw new AuthException("Could not create the account. Please try again.");
        }
    }

    /**
     * Verifies a password and starts the session.
     *
     * <p>Every failure path returns the identical message in
     * {@link AuthException#GENERIC_LOGIN_FAILURE}, so the screen cannot be used
     * to find out which usernames exist. Missing accounts also spend BCrypt time
     * against a dummy hash so the <em>duration</em> of a failed attempt does not
     * reveal it either.
     *
     * <p>On success {@link SessionManager} holds the new session.
     */
    public User login(String username, String password) throws AuthException {

        String cleanUsername = ContactRules.trimToNull(username);

        if (cleanUsername == null || password == null || password.isEmpty()) {
            PasswordHasher.spendTimeForMissingUser(password);
            throw AuthException.loginFailed();
        }

        StoredUser stored;
        try (Connection connection = connections.open()) {
            stored = new UserDao(connection).findForLogin(cleanUsername).orElse(null);
        } catch (SQLException failure) {
            // A database outage is not an authentication failure, and saying
            // "could not reach the database" leaks nothing about any account.
            throw new AuthException("Could not reach the database. Please try again.");
        }

        if (stored == null) {
            PasswordHasher.spendTimeForMissingUser(password);
            throw AuthException.loginFailed();
        }

        // A NULL hash means "no password set" (the seeded demo accounts).
        // It must never be read as "any password is acceptable".
        if (!stored.hasPassword() || !PasswordHasher.matches(password, stored.passwordHash())) {
            throw AuthException.loginFailed();
        }
        if (!stored.active()) {
            throw AuthException.loginFailed();
        }

        User user = new User(stored.userId(), stored.username(), stored.fullName(), stored.role());
        SessionManager.get().beginSession(user);
        return user;
    }

    /** Ends the session. After this, every guarded service call is refused. */
    public void logout() {
        SessionManager.get().endSession();
    }

    /** Whether anybody is currently signed in. */
    public boolean isSignedIn() {
        return SessionManager.get().isSignedIn();
    }

    /**
     * Total bookings across all accounts.
     *
     * <p>Administrative, so {@link SessionManager#requireAdmin()} runs first and
     * throws for an ordinary user. The check lives here rather than in the
     * dashboard, which means it holds however the screen is reached.
     */
    public long adminAllBookingsCount() throws AccessDeniedException, SQLException {
        SessionManager.get().requireAdmin();
        try (Connection connection = connections.open()) {
            return new BookingDAO(connection).countAllBookings();
        }
    }

    /** Strips spaces, dashes and a leading country code from a typed mobile number. */
    private static String normaliseMobile(String typed) {
        return ContactRules.normaliseMobile(typed);
    }

}