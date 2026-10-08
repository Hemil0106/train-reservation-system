package com.rail.model;

/**
 * One passenger as typed into the form, before anything is saved.
 *
 * <p>This is a draft, not a booking. It carries a <em>request</em>:
 * {@link #berthPreference()} is what the passenger asked for, and it is not an
 * allocation. Nothing here guarantees a berth, and no field implies one.
 *
 * <p>Only what the brief asks for is collected. No passport, date of birth,
 * address or government identifier is taken, because none of them is needed to
 * reserve a seat and every extra identifier is another piece of personal data to
 * store and protect.
 *
 * @param fullName required; a passenger cannot be booked anonymously
 * @param age required, checked against the configured range
 * @param gender required, one of {@link Gender}
 * @param berthPreference what was asked for, never a confirmed berth
 * @param mealPreference catering choice, or {@code null} when the class has no
 *     pantry or the passenger left it blank
 */
public record PassengerDraft(String fullName,
                             int age,
                             Gender gender,
                             BerthPreference berthPreference,
                             String mealPreference) {
}