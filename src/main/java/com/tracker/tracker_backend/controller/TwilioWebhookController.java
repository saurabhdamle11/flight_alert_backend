package com.tracker.tracker_backend.controller;

import com.tracker.tracker_backend.service.WhatsAppService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/webhook/twilio")
public class TwilioWebhookController {

    private final WhatsAppService whatsAppService;

    @Value("${sample.flight.callsign}")
    private String callsign;

    @Value("${sample.flight.altitude-ft}")
    private int altitudeFt;

    @Value("${sample.flight.speed-kmph}")
    private int speedKmph;

    @Value("${sample.flight.direction}")
    private String direction;

    @Value("${sample.flight.fr24-code}")
    private String fr24Code;

    public TwilioWebhookController(WhatsAppService whatsAppService) {
        this.whatsAppService = whatsAppService;
    }

    @PostMapping(
            value = "/whatsapp",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_XML_VALUE
    )
    public ResponseEntity<String> handleIncoming(
            @RequestParam("From") String from,
            @RequestParam("Body") String body
    ) {
        String message = buildFlightStatus();
        whatsAppService.sendMessage(from, message);

        // Return an empty TwiML response — the actual reply is sent via the REST API above.
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_XML)
                .body("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response/>");
    }

    private String buildFlightStatus() {
        return "Flight overhead: " + callsign + "\n" +
               "Altitude: " + String.format("%,d", altitudeFt) + " ft\n" +
               "Speed: " + speedKmph + " km/h\n" +
               "Direction: " + direction + "\n" +
               "Track it: https://www.flightradar24.com/" + fr24Code;
    }
}
