package com.tracker.tracker_backend.client;

import com.tracker.tracker_backend.dto.OpenSkyResponse;
import com.tracker.tracker_backend.model.BoundingBox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class OpenSkyClientTest {

    private static final String TOKEN_URL = "https://auth.example/token";
    private static final String TOKEN_JSON = "{\"access_token\":\"tok-%s\",\"expires_in\":1800,\"token_type\":\"Bearer\"}";
    private static final String STATES_JSON = """
            {"time":1700000000,"states":[["abc123","AIC101 ","India",1700000000,1700000000,72.8,19.1,10000.0,false,230.0,90.0,0.0]]}
            """;
    private static final BoundingBox BOX = new BoundingBox(18.9, 19.3, 72.6, 73.0);

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private OpenSkyClient client;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(false).build();
        client = new OpenSkyClient(restTemplate);
        ReflectionTestUtils.setField(client, "baseUrl", "https://api.example");
        ReflectionTestUtils.setField(client, "tokenUrl", TOKEN_URL);
        ReflectionTestUtils.setField(client, "clientId", "id");
        ReflectionTestUtils.setField(client, "clientSecret", "secret");
    }

    @Test
    void fetchesTokenOnceAndSendsBearer() {
        server.expect(ExpectedCount.once(), requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("grant_type=client_credentials")))
                .andRespond(withSuccess(TOKEN_JSON.formatted("1"), MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.twice(), requestTo(org.hamcrest.Matchers.startsWith("https://api.example/states/all")))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tok-1"))
                .andRespond(withSuccess(STATES_JSON, MediaType.APPLICATION_JSON));

        OpenSkyResponse r = client.fetchStates(BOX);
        client.fetchStates(BOX);

        assertThat(r.states()).hasSize(1);
        assertThat(r.icao24(r.states().get(0))).isEqualTo("abc123");
        assertThat(r.altitude(r.states().get(0))).isEqualTo(10000.0);
        server.verify();
    }

    @Test
    void refreshesTokenOn401AndRetries() {
        server.expect(requestTo(TOKEN_URL)).andRespond(withSuccess(TOKEN_JSON.formatted("1"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.example")))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(requestTo(TOKEN_URL)).andRespond(withSuccess(TOKEN_JSON.formatted("2"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.example")))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tok-2"))
                .andRespond(withSuccess(STATES_JSON, MediaType.APPLICATION_JSON));

        assertThat(client.fetchStates(BOX)).isNotNull();
        server.verify();
    }

    @Test
    void pausesWithoutBlockingOn429() {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Rate-Limit-Retry-After-Seconds", "120");
        server.expect(requestTo(TOKEN_URL)).andRespond(withSuccess(TOKEN_JSON.formatted("1"), MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), requestTo(org.hamcrest.Matchers.startsWith("https://api.example")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(h));

        long start = System.currentTimeMillis();
        assertThat(client.fetchStates(BOX)).isNull();
        assertThat(client.fetchStates(BOX)).isNull(); // paused: no second HTTP call
        assertThat(System.currentTimeMillis() - start).isLessThan(2_000);
        server.verify();
    }

    @Test
    void creditCostFollowsAreaTiers() {
        assertThat(OpenSkyClient.creditCost(new BoundingBox(0, 1.8, 0, 2.5))).isEqualTo(1);
        assertThat(OpenSkyClient.creditCost(new BoundingBox(0, 10, 0, 5))).isEqualTo(2);
        assertThat(OpenSkyClient.creditCost(new BoundingBox(0, 20, 0, 20))).isEqualTo(3);
        assertThat(OpenSkyClient.creditCost(new BoundingBox(-90, 90, -180, 180))).isEqualTo(4);
    }
}
