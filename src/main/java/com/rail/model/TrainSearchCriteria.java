package com.rail.model;

import java.time.LocalDate;

/**
 * What the user asked for, before any validation.
 *
 * <p>Deliberately a plain carrier: the form produces raw, possibly nonsense
 * input (a blank station, a date string that never parsed) and
 * {@code TrainSearchService} decides whether it is acceptable. Keeping the
 * judgement in one place means the UI cannot validate more loosely than the
 * service does.
 *
 * @param journeyDate null when the text in the date field was not a date
 */
public record TrainSearchCriteria(
        String fromCode,
        String toCode,
        LocalDate journeyDate,
        String classCode,
        int passengers) {

    public TrainSearchCriteria {
        if (fromCode == null) {
            fromCode = "";
        }
        if (toCode == null) {
            toCode = "";
        }
        if (classCode == null) {
            classCode = "";
        }
    }

    /** Whether both endpoints are filled in, checked before the database is touched. */
    public boolean hasEndpoints() {
        return !fromCode.isBlank() && !toCode.isBlank();
    }

    public boolean isSameStation() {
        return fromCode.equalsIgnoreCase(toCode);
    }

    /** The compact single-line summary shown while results load. */
    public String summary() {
        return fromCode + " to " + toCode
                + (journeyDate == null ? "" : " on " + journeyDate);
    }
}