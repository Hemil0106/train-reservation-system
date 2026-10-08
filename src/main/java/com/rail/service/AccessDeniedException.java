package com.rail.service;

/**
 * Thrown when the signed-in account is not allowed to perform an operation.
 *
 * <p>Deliberately a checked exception. Forgetting a permission check then
 * becomes a compile error rather than a silent authorisation hole - the failure
 * mode of a {@code RuntimeException} here would be a security bug that still
 * builds and still runs.
 */
public class AccessDeniedException extends Exception {

    public AccessDeniedException(String message) {
        super(message);
    }
}