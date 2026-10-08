package com.rail.service;

/**
 * A request the person in front of the screen can fix.
 *
 * <p>Distinct from a {@link java.sql.SQLException}: this means "those inputs do
 * not describe something valid", whereas a SQL failure means "the database could
 * not answer". The forms show this one next to the offending field and keep the
 * screen as it was; they show a SQL failure as a separate, clearly different
 * error state.
 *
 * <p>Shared by train search and passenger entry rather than duplicated per
 * screen. Both need the same thing: a field name to highlight and a sentence
 * explaining what to change.
 *
 * <p>Checked, so a caller cannot silently ignore a rejected request.
 */
public class ValidationException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String field;

    public ValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    /** The form field at fault, or {@code null} when the request has no single field. */
    public String field() {
        return field;
    }
}