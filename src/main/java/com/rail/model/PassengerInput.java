package com.rail.model;

/**
 * One passenger row exactly as typed, before any checking.
 *
 * <p>Everything is a {@link String}, including the age, because this is what a
 * text field actually holds. Converting and validating happens in
 * {@link com.rail.service.BookingPreparationService}, which keeps the form free
 * of parsing rules and lets the checks be exercised without a screen.
 *
 * @param berthPreference the preference label, matched against what the class
 *     supports
 * @param mealPreference optional catering choice; ignored for a class with no
 *     pantry
 */
public record PassengerInput(String fullName,
                             String age,
                             String gender,
                             String berthPreference,
                             String mealPreference) {
}