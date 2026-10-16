package com.tracker.tracker_backend.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PushSubscriptionServiceTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "https://fcm.googleapis.com/fcm/send/dXN1cjE6YWJj",
            "https://updates.push.services.mozilla.com/wpush/v2/gAAAAABk",
            "https://web.push.apple.com/QGuQyavXutnMH_Eb4R7oXyFR",
            "https://wns2-par02p.notify.windows.com/w/?token=BQYAAA"
    })
    void acceptsKnownPushServices(String endpoint) {
        assertThatCode(() -> PushSubscriptionService.validateEndpoint(endpoint)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://fcm.googleapis.com/fcm/send/x",          // not https
            "https://evil.example.com/push",                  // unknown host
            "https://googleapis.com.evil.com/x",              // suffix spoof
            "https://fcm.googleapis.com:8443/x",              // custom port
            "https://169.254.169.254/latest/meta-data",       // metadata SSRF
            "not a url"
    })
    void rejectsOtherEndpoints(String endpoint) {
        assertThatThrownBy(() -> PushSubscriptionService.validateEndpoint(endpoint))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
