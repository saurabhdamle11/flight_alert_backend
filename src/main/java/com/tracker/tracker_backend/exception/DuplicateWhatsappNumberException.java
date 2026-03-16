package com.tracker.tracker_backend.exception;

public class DuplicateWhatsappNumberException extends RuntimeException {
    public DuplicateWhatsappNumberException(String number) {
        super("WhatsApp number already registered: " + number);
    }
}
