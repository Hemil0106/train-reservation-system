package com.rail.model;

/**
 * A station the search form can offer.
 *
 * <p>Loaded from the {@code stations} table rather than hardcoded, so a station
 * added to the database appears in the UI without a code change.
 *
 * @param code the 2-4 character code used throughout the schema
 * @param name the display name
 */
public record Station(String code, String name) implements Comparable<Station> {

    public Station {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("station code must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("station name must not be blank");
        }
    }

    /** "PUNE - Pune Junction": unambiguous when two codes share a name. */
    public String display() {
        return code + " - " + name;
    }

    /** Sorts by display name so the dropdown is alphabetical. */
    @Override
    public int compareTo(Station other) {
        return name.compareToIgnoreCase(other.name);
    }
}