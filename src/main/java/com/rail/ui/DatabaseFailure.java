package com.rail.ui;

/**
 * Turns a database failure into something a traveller can be told.
 *
 * <p>One rule, three call sites: the sentence a user reads is written here and
 * carries no part of the driver's message. A {@link java.sql.SQLException} from
 * Connector/J names the account, the host and often the statement that failed,
 * which is a configuration detail rather than an explanation - and an operator
 * reading "Access denied for user 'railway_app'@'localhost'" learns nothing they
 * can act on that "check your configuration" does not tell them, while a user
 * staring at raw SQL learns nothing at all.
 *
 * <p>The detail is not thrown away, only moved: it goes to {@code stderr}, where
 * whoever is at the console sees it. That is the whole arrangement. Nothing here
 * logs a credential, because a {@link java.sql.SQLException} never carries the
 * password it was refused over - the driver says "(using password: NO)".
 */
public final class DatabaseFailure {

    private DatabaseFailure() {
    }

    /**
     * The sentence for a user, plus the detail on {@code stderr}.
     *
     * @param context  what was being attempted, in words a user recognises
     * @param failure  the failure, usually a {@link java.sql.SQLException}
     * @return a sentence naming nothing but the situation and the next step
     */
    public static String report(String context, Throwable failure) {
        System.err.println("RailReserve: " + context + " failed: " + failure);
        return context + " could not be completed because the database could not be reached. "
                + "Nothing was changed. Check that MySQL is running and that "
                + "database/local.properties is correct.";
    }
}