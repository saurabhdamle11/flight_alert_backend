package com.tracker.tracker_backend.client;

import com.tracker.tracker_backend.dto.OpenSkyResponse;
import com.tracker.tracker_backend.model.BoundingBox;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Base64;

/**
 * REST client for the OpenSky Network /states/all endpoint.
 *
 * Implements 3-step exponential backoff on HTTP 429:
 *   attempt 1 → wait 5 s → attempt 2 → wait 15 s → attempt 3 → wait 30 s → give up
 *
 * Credentials are optional. Authenticated accounts get ~4 000 req/day;
 * unauthenticated accounts get ~400 req/day.
 */
@Slf4j
@Component
public class OpenSkyClient {

    private static final int[] BACKOFF_SECONDS = {5, 15, 30};

    private final RestTemplate restTemplate;

    @Value("${opensky.base-url}")
    private String baseUrl;

    @Value("${opensky.username:}")
    private String username;

    @Value("${opensky.password:}")
    private String password;

    public OpenSkyClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * Fetches all flight states within the given bounding box.
     * Returns null if the request ultimately fails after all retries.
     */
    public OpenSkyResponse fetchStates(BoundingBox box) {
        String url = String.format(
                "%s/states/all?lamin=%.6f&lomin=%.6f&lamax=%.6f&lomax=%.6f",
                baseUrl, box.latMin(), box.lonMin(), box.latMax(), box.lonMax()
        );

        HttpHeaders headers = new HttpHeaders();
        if (!username.isBlank()) {
            String credentials = Base64.getEncoder().encodeToString((username + ":" + password).getBytes());
            headers.set(HttpHeaders.AUTHORIZATION, "Basic " + credentials);
        }
        HttpEntity<Void> request = new HttpEntity<>(headers);

        for (int attempt = 0; attempt <= BACKOFF_SECONDS.length; attempt++) {
            try {
                ResponseEntity<OpenSkyResponse> response = restTemplate.exchange(
                        url, HttpMethod.GET, request, OpenSkyResponse.class);
                return response.getBody();
            } catch (HttpClientErrorException.TooManyRequests e) {
                if (attempt < BACKOFF_SECONDS.length) {
                    int wait = BACKOFF_SECONDS[attempt];
                    log.warn("OpenSky 429 — backing off {}s (attempt {}/{})", wait, attempt + 1, BACKOFF_SECONDS.length);
                    sleep(wait);
                } else {
                    log.error("OpenSky 429 — exhausted retries for region [{},{}],[{},{}]",
                            box.latMin(), box.latMax(), box.lonMin(), box.lonMax());
                }
            } catch (Exception e) {
                log.error("OpenSky request failed: {}", e.getMessage());
                return null;
            }
        }
        return null;
    }

    private void sleep(int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
