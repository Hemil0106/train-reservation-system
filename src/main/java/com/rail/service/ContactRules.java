package com.rail.service;

import java.util.regex.Pattern;

/**
 * The one place that decides what a valid mobile number and email address look
 * like.
 *
 * <p>Registration and booking contact details are collected by different screens
 * but describe the same person, so the rules live here rather than being written
 * twice. A second copy of the email pattern would inevitably drift from the
 * first.
 */
public final class ContactRules {

    /** Indian mobile numbers are ten digits, with an optional 91 country code. */
    public static final int MOBILE_DIGITS = 10;

    /**
     * Deliberately permissive: one {@code @}, no whitespace, and a dotted
     * domain with a two-character-or-longer final label.
     */
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[A-Za-z]{2,}$");

    private ContactRules() {
    }

    /**
     * Reduces typed input to the ten digits stored in the database, or
     * {@code null} when it is not a usable number.
     *
     * <p>Punctuation and spaces are ignored, and a leading 91 is dropped, so
     * {@code +91 98765 43210} and {@code 9876543210} store identically. Returns
     * {@code null} rather than a partially-correct value, so a caller cannot
     * accidentally persist a truncated number.
     */
    public static String normaliseMobile(String typed) {
        if (typed == null) {
            return null;
        }
        String digits = typed.replaceAll("\\D", "");
        if (digits.length() == MOBILE_DIGITS + 2 && digits.startsWith("91")) {
            digits = digits.substring(2);
        }
        return digits.length() == MOBILE_DIGITS ? digits : null;
    }

    public static boolean isValidMobile(String typed) {
        return normaliseMobile(typed) != null;
    }

    /**
     * Whether an address is well formed.
     *
     * @param value {@code null} is allowed, because the email column is optional
     */
    public static boolean isValidEmail(String value) {
        return value == null || EMAIL_PATTERN.matcher(value).matches();
    }

    /** Trims, and turns blank into {@code null} so optional fields stay absent. */
    public static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}