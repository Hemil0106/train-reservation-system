package com.rail.model;

import java.util.ArrayList;
import java.util.List;

/**
 * What a travel class can actually offer, read from the database rather than
 * assumed in code.
 *
 * <p>{@code isSleeper} and {@code hasPantry} come from {@code travel_classes};
 * {@code supportedPreferences} is derived from the {@code berths.position} values
 * present in that class's coaches. That distinction is the point: AC 2-Tier is
 * flagged as a sleeper class, yet has no middle berths, so a preference list
 * built from {@code isSleeper} alone would offer something unbookable.
 *
 * @param supportedPreferences the class's real berth positions, with
 *     {@link BerthPreference#NO_PREFERENCE} guaranteed first
 */
public record ClassProfile(String classCode,
                           String className,
                           boolean isSleeper,
                           boolean hasPantry,
                           List<BerthPreference> supportedPreferences) {

    public ClassProfile {
        supportedPreferences = withNoPreferenceFirst(supportedPreferences);
    }

    public boolean supports(BerthPreference preference) {
        return supportedPreferences.contains(preference);
    }

    /**
     * No Preference leads the list because it is the one option every class has,
     * whatever its coaches contain. Enforced here rather than left to callers,
     * so a drop-down can never end up with no "don't care" choice.
     */
    private static List<BerthPreference> withNoPreferenceFirst(List<BerthPreference> preferences) {
        List<BerthPreference> ordered = new ArrayList<>();
        if (!preferences.contains(BerthPreference.NO_PREFERENCE)) {
            ordered.add(BerthPreference.NO_PREFERENCE);
        }
        ordered.addAll(preferences);
        return List.copyOf(ordered);
    }
}