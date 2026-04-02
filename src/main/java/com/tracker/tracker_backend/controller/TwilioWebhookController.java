package com.tracker.tracker_backend.controller;

import com.tracker.tracker_backend.service.WebhookConversationService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/webhook/twilio")
public class TwilioWebhookController {

    private final WebhookConversationService conversationService;

    public TwilioWebhookController(WebhookConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @PostMapping(
            value = "/whatsapp",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_XML_VALUE
    )
    public ResponseEntity<String> handleIncoming(
            @RequestParam("From") String from,
            @RequestParam(value = "Body", required = false, defaultValue = "") String body,
            @RequestParam(value = "Latitude", required = false) Double latitude,
            @RequestParam(value = "Longitude", required = false) Double longitude
    ) {
        conversationService.handle(from, body, latitude, longitude);

        // Empty TwiML — reply is sent via the REST API in WebhookConversationService.
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_XML)
                .body("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response/>");
    }
}
