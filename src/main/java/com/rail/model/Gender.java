package com.rail.model;

/**
 * The gender values this project accepts.
 *
 * <p>These three are the application's documented allowed values, and they match
 * the {@code passengers.gender} enum exactly. Declaring them as an enum means
 * the form, the validation and the schema cannot drift apart: an unrecognised
 * value simply has no constant to bind to.
 *
 * <p>The list is deliberately short. Gender is collected because the schema
 * records it, not because allocation logic depends on it, so there is no
 * category beyond these three.
 */
public enum Gender {

    MALE("Male"),
    FEMALE("Female"),
    OTHER("Other");

    private final String label;

    Gender(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** The value {@code passengers.gender} stores, which is the constant name. */
    public String dbValue() {
        return name();
    }
}