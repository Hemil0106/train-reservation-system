package com.rail.service;

/**
 * A booking that could not be made, described in words fit to show a passenger.
 *
 * <p>The message in here is the <em>only</em> thing the user ever sees about a
 * failure. A {@code SQLException} carries the SQL statement, the table, the
 * constraint name and sometimes a fragment of a value; putting any of that on
 * screen tells an attacker the schema and tells a passenger nothing useful. So
 * the cause is kept as the exception's cause for the log, and the message is
 * written for the person who wanted a train.
 *
 * <p>Thrown after the transaction has been rolled back, never before, so catching
 * it always means "nothing was written".
 */
public class BookingException extends Exception {

    private static final long serialVersionUID = 1L;

    public BookingException(String message) {
        super(message);
    }

    public BookingException(String message, Throwable cause) {
        super(message, cause);
    }
}