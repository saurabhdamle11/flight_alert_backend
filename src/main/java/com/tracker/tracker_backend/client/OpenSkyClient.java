package com.tracker.tracker_backend.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.tracker.tracker_backend.dto.OpenSkyResponse;
import com.tracker.tracker_backend.model.BoundingBox;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;

/**
 * REST client for the OpenSky Network /states/all endpoint.
 *
 * Authentication uses the OAuth2 client credentials flow (OpenSky no longer
 * accepts Basic auth). Tokens live 30 minutes and are cached until shortly
 * before expiry; a 401 forces a refresh and one retry.
 *
 * Rate limiting: on HTTP 429 the client does NOT block — it records the
 * server's X-Rate-Limit-Retry-After-Seconds and returns null for every call
 * until that time has passed, so the scheduler thread stays free.
 *
 * Quotas (credits per day, /states/* only): anonymous 400, standard account
 * 4 000, active feeder 8 000. A bounding box of ≤ 25 sq° costs 1 credit.
 */
@Slf4j
@Component
public class OpenSkyClient {

    private static final Duration DEFAULT_RATE_LIMIT_PAUSE = Duration.ofMinutes(5);
    private static final Duration TOKEN_EXPIRY_MARGIN = Duration.ofSeconds(60);

    private final RestTemplate restTemplate;

    @Value("${opensky.base-url}")
    private String baseUrl;

    @Value("${opensky.token-url}")
    private String tokenUrl;

    @Value("${opensky.client-id:}")
    private String clientId;

    @Value("${opensky.client-secret:}")
    private String clientSecret;

    private String accessToken;
    private Instant tokenExpiresAt = Instant.EPOCH;
    private volatile Instant pausedUntil = Instant.EPOCH;

    public OpenSkyClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public boolean isAuthenticated() {
        return !clientId.isBlank() && !clientSecret.isBlank();
    }

    /** Credit cost of a /states/all call for the given box, per OpenSky's pricing table. */
    public static int creditCost(BoundingBox box) {
        double area = box.latSpan() * box.lonSpan();
        if (area <= 25) return 1;
        if (area <= 100) return 2;
        if (area <= 400) return 3;
        return 4;
    }

    /**
     * Fetches all flight states within the given bounding box.
     * Returns null if the request fails or the client is paused after a 429.
     */
    public OpenSkyResponse fetchStates(BoundingBox box) {
        if (Instant.now().isBefore(pausedUntil)) {
            log.debug("OpenSky rate-limited until {} — skipping request", pausedUntil);
            return null;
        }

        String url = String.format(
                "%s/states/all?lamin=%.6f&lomin=%.6f&lamax=%.6f&lomax=%.6f",
                baseUrl, box.latMin(), box.lonMin(), box.latMax(), box.lonMax()
        );

        try {
            return exchange(url);
        } catch (HttpClientErrorException.Unauthorized e) {
            if (!isAuthenticated()) {
                log.error("OpenSky returned 401 for an anonymous request: {}", e.getMessage());
                return null;
            }
            log.info("OpenSky 401 — refreshing access token and retrying once");
            invalidateToken();
            try {
                return exchange(url);
            } catch (Exception retryEx) {
                log.error("OpenSky request failed after token refresh: {}", retryEx.getMessage());
                return null;
            }
        } catch (HttpClientErrorException.TooManyRequests e) {
            Duration pause = retryAfter(e.getResponseHeaders());
            pausedUntil = Instant.now().plus(pause);
            log.warn("OpenSky 429 — pausing all requests for {}s (until {})", pause.toSeconds(), pausedUntil);
            return null;
        } catch (Exception e) {
            log.error("OpenSky request failed: {}", e.getMessage());
            return null;
        }
    }

    private OpenSkyResponse exchange(String url) {
        HttpHeaders headers = new HttpHeaders();
        if (isAuthenticated()) {
            headers.setBearerAuth(token());
        }
        ResponseEntity<OpenSkyResponse> response = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), OpenSkyResponse.class);

        String remaining = response.getHeaders().getFirst("X-Rate-Limit-Remaining");
        if (remaining != null) {
            log.debug("OpenSky credits remaining today: {}", remaining);
        }
        return response.getBody();
    }

    private synchronized String token() {
        if (accessToken != null && Instant.now().isBefore(tokenExpiresAt)) {
            return accessToken;
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId.strip());
        form.add("client_secret", clientSecret.strip());

        TokenResponse body = restTemplate.postForObject(tokenUrl, new HttpEntity<>(form, headers), TokenResponse.class);
        if (body == null || body.accessToken() == null) {
            throw new IllegalStateException("OpenSky token endpoint returned no access_token");
        }

        long expiresIn = body.expiresIn() != null ? body.expiresIn() : 1800;
        accessToken = body.accessToken();
        tokenExpiresAt = Instant.now().plusSeconds(expiresIn).minus(TOKEN_EXPIRY_MARGIN);
        log.info("Obtained OpenSky access token (expires in {}s)", expiresIn);
        return accessToken;
    }

    private synchronized void invalidateToken() {
        accessToken = null;
        tokenExpiresAt = Instant.EPOCH;
    }

    private static Duration retryAfter(HttpHeaders headers) {
        if (headers != null) {
            String value = headers.getFirst("X-Rate-Limit-Retry-After-Seconds");
            if (value != null) {
                try {
                    return Duration.ofSeconds(Math.max(1, Long.parseLong(value.trim())));
                } catch (NumberFormatException ignored) {
                    // fall through to default
                }
            }
        }
        return DEFAULT_RATE_LIMIT_PAUSE;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") Long expiresIn
    ) {}
}
