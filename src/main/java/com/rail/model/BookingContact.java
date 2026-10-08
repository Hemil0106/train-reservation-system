package com.rail.model;

/**
 * Who to contact about the booking.
 *
 * <p>Kept separate from passenger identity on purpose. Contact details belong to
 * the person paying and receiving messages, not to any individual passenger, and
 * merging the two would quietly copy one passenger's phone number onto every
 * traveller.
 *
 * @param mobile ten digits, already normalised by
 *     {@link com.rail.service.ContactRules#normaliseMobile(String)}
 * @param email optional; {@code null} when not supplied
 */
public record BookingContact(String mobile, String email) {
}