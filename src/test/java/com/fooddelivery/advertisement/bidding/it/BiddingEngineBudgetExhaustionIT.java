package com.fooddelivery.advertisement.bidding.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.advertisement.bidding.matcher.CampaignMatcher;
import com.fooddelivery.advertisement.bidding.model.internal.AdRequestDTO;
import com.fooddelivery.advertisement.bidding.model.internal.SponsoredListingDTO;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.awaitility.Awaitility;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.BEFORE_CLASS)
@EmbeddedKafka(adminTimeout = 60, partitions = 1, topics = {"ad-events"})
@ActiveProfiles("test")
public class BiddingEngineBudgetExhaustionIT {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private CampaignMatcher matcher;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void budgetExhaustionEndToEndTest() throws Exception {
        String campaignId = UUID.randomUUID().toString();
        String advertiserId = UUID.randomUUID().toString();

        // 1. Index a campaign via AD_CAMPAIGN_CREATED
        String createEvent = String.format("""
            {
                "eventType": "AD_CAMPAIGN_CREATED",
                "payload": {
                    "campaignId": "%s",
                    "advertiserId": "%s",
                    "status": "ACTIVE",
                    "maxBid": 1.5,
                    "budget": 100.0,
                    "geo": "DEFAULT_GEO"
                }
            }
        """, campaignId, advertiserId);
        
        kafkaTemplate.send("ad-events", campaignId, createEvent);

        AdRequestDTO req = new AdRequestDTO();
        req.geo = "DEFAULT_GEO";

        // Wait until it's indexed
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            ResponseEntity<List<SponsoredListingDTO>> res = restTemplate.exchange(
                    "/api/v1/ads/serve", HttpMethod.POST, new HttpEntity<>(req),
                    new ParameterizedTypeReference<List<SponsoredListingDTO>>() {}
            );
            assertThat(res.getBody()).isNotNull();
            assertThat(res.getBody().stream().anyMatch(l -> campaignId.equals(l.campaignId))).isTrue();
        });

        // 2. Publish AD_CAMPAIGN_BUDGET_EXHAUSTED in the canonical shape (payload.campaignId)
        String exhaustCanonical = String.format("""
            {
                "eventType": "AD_CAMPAIGN_BUDGET_EXHAUSTED",
                "payload": {
                    "campaignId": "%s",
                    "budgetExhausted": true
                }
            }
        """, campaignId);

        kafkaTemplate.send("ad-events", campaignId, exhaustCanonical);

        // 3. Await matcher.getBudgetExhausted(campaignId) == true
        Awaitility.await().atMost(10, TimeUnit.SECONDS).until(() -> matcher.getBudgetExhausted(campaignId));

        // 4. POST /api/v1/ads/serve no longer returns it
        ResponseEntity<List<SponsoredListingDTO>> res2 = restTemplate.exchange(
                "/api/v1/ads/serve", HttpMethod.POST, new HttpEntity<>(req),
                new ParameterizedTypeReference<List<SponsoredListingDTO>>() {}
        );
        assertThat(res2.getBody()).isNotNull();
        assertThat(res2.getBody().stream().anyMatch(l -> campaignId.equals(l.campaignId))).isFalse();

        // 5. Repeat 2 with the legacy shape (payload.id)
        String campaignId2 = UUID.randomUUID().toString();
        String createEvent2 = String.format("""
            {
                "eventType": "AD_CAMPAIGN_CREATED",
                "payload": {
                    "id": "%s",
                    "advertiserId": "%s",
                    "status": "ACTIVE",
                    "maxBid": 1.5,
                    "budget": 100.0,
                    "geo": "DEFAULT_GEO"
                }
            }
        """, campaignId2, advertiserId);
        kafkaTemplate.send("ad-events", campaignId2, createEvent2);

        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            ResponseEntity<List<SponsoredListingDTO>> res = restTemplate.exchange(
                    "/api/v1/ads/serve", HttpMethod.POST, new HttpEntity<>(req),
                    new ParameterizedTypeReference<List<SponsoredListingDTO>>() {}
            );
            assertThat(res.getBody()).isNotNull();
            assertThat(res.getBody().stream().anyMatch(l -> campaignId2.equals(l.campaignId))).isTrue();
        });

        String exhaustLegacy = String.format("""
            {
                "eventType": "AD_CAMPAIGN_BUDGET_EXHAUSTED",
                "payload": {
                    "id": "%s",
                    "budgetExhausted": true
                }
            }
        """, campaignId2);

        kafkaTemplate.send("ad-events", campaignId2, exhaustLegacy);
        Awaitility.await().atMost(10, TimeUnit.SECONDS).until(() -> matcher.getBudgetExhausted(campaignId2));

        // 6. campaign_event_dropped_total == 0 throughout
        double dropped = meterRegistry.counter("campaign_event_dropped_total", "reason", "no_campaign_id").count();
        assertThat(dropped).isEqualTo(0.0);
    }
}
