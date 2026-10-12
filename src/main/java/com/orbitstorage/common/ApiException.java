package com.orbitstorage.common;

public class ApiException extends RuntimeException {
    public final int status;
    public final long retryAfter;
    public ApiException(int status, String message) { this(status, message, 0); }
    public ApiException(int status, String message, long retryAfter) {
        super(message); this.status = status; this.retryAfter = retryAfter;
    }
}
