package com.rail.model;

import java.util.List;

/**
 * What a passenger would like, which is not the same thing as what they get.
 *
 * <p>A request is a hint passed to allocation later. Nothing here reserves a
 * berth, and {@link #dbValue()} is a lossy projection, so a preference must
 * never be presented to a passenger as a confirmed seat.
 *
 * <p>The list covers the six preferences the brief asks for plus the two a
 * seated class needs. Whether one is offered at all is decided by the data:
 * {@link #forPosition(String)} maps what a class's coaches actually contain, so
 * AC 2-Tier never offers a middle berth because no 2A coach has one.
 */
public enum BerthPreference {

    NO_PREFERENCE("No Preference", "NO_PREFERENCE"),
    LOWER("Lower", "LOWER"),
    MIDDLE("Middle", "MIDDLE"),
    UPPER("Upper", "UPPER"),
    SIDE_LOWER("Side Lower", "SIDE"),
    SIDE_UPPER("Side Upper", "SIDE"),
    WINDOW("Window", "WINDOW"),
    AISLE("Aisle", "AISLE");

    private final String label;
    private final String storedValue;

    BerthPreference(String label, String storedValue) {
        this.label = label;
        this.storedValue = storedValue;
    }

    /** The wording shown in the drop-down. */
    public String label() {
        return label;
    }

    /**
     * The value {@code passengers.berth_preference} accepts.
     *
     * <p>Both side variants collapse to {@code SIDE}, because that enum has one
     * side value rather than two. The upper/lower distinction is therefore lost
     * the moment a preference is persisted, which is a further reason to treat
     * preferences as advisory instead of as an allocation.
     */
    public String dbValue() {
        return storedValue;
    }

    /**
     * The single preference that stands for one physical berth position.
     *
     * <p>Used when reading a berth's <em>actual</em> position back, where there
     * is one answer and {@link #forPosition(String)} would return two.
     *
     * <p>{@code SIDE} maps to {@link #SIDE_LOWER} because the schema offers only
     * one side position. That is a real loss of detail - a granted side berth
     * would be labelled "Side Lower" when it might be the upper one - but
     * {@link #dbValue()} still round-trips to {@code SIDE}, so what is stored is
     * correct, and no coach in the sample data has a side berth at all. Returning
     * null or throwing instead would make the allocator fail on a berth it could
     * perfectly well assign.
     *
     * @return the matching preference, or {@link #NO_PREFERENCE} for a position
     *     this project does not model, so a caller always gets something it can
     *     store
     */
    public static BerthPreference fromPosition(String position) {
        if (position == null) {
            return NO_PREFERENCE;
        }
        switch (position.trim().toUpperCase()) {
            case "LOWER":
                return LOWER;
            case "MIDDLE":
                return MIDDLE;
            case "UPPER":
                return UPPER;
            case "WINDOW":
                return WINDOW;
            case "AISLE":
                return AISLE;
            case "SIDE":
                return SIDE_LOWER;
            default:
                return NO_PREFERENCE;
        }
    }

    /**
     * Preferences a class can offer, given the {@code berths.position} values its
     * coaches contain.
     *
     * <p>Never returns null; an unrecognised or absent position simply
     * contributes nothing, because inventing a preference a coach cannot honour
     * is worse than offering fewer.
     */
    public static List<BerthPreference> forPosition(String position) {
        if (position == null) {
            return List.of();
        }
        switch (position.trim().toUpperCase()) {
            case "LOWER":
                return List.of(LOWER);
            case "MIDDLE":
                return List.of(MIDDLE);
            case "UPPER":
                return List.of(UPPER);
            case "WINDOW":
                return List.of(WINDOW);
            case "AISLE":
                return List.of(AISLE);
            // One SIDE position, so both side preferences are on offer.
            case "SIDE":
                return List.of(SIDE_LOWER, SIDE_UPPER);
            default:
                return List.of();
        }
    }
}