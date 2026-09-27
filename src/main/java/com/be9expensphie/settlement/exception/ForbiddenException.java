package com.be9expensphie.settlement.exception;

/** Thrown when the caller is authenticated but acting on a member that is not theirs. */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
