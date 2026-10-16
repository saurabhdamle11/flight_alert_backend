package com.tracker.tracker_backend.exception;

public class RateLimitedException extends RuntimeException {
    public RateLimitedException() {
        super("Too many requests — try again later");
    }
}
