package com.fooddelivery.advertisement.bidding.contract;

import com.fooddelivery.common.contract.KafkaStubMessageSender;

import com.fooddelivery.advertisement.bidding.matcher.CampaignMatcher;
import com.fooddelivery.advertisement.bidding.messaging.CampaignEventConsumer;
import com.fooddelivery.advertisement.bidding.messaging.RedisIdempotencyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cloud.contract.stubrunner.StubTrigger;
import org.springframework.cloud.contract.stubrunner.spring.AutoConfigureStubRunner;
import org.springframework.cloud.contract.stubrunner.spring.StubRunnerProperties;
import org.springframework.cloud.contract.verifier.messaging.MessageVerifierSender;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.messaging.Message;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Consumes CampaignService's real ad_events stub and asserts the campaign reaches the bidding matcher.
 *
 * Before the EventPayloadUtils fix this consumer was guarded by
 * {@code root.has("eventType") && root.has("payload")}, which is never true against the flat
 * Campaign that CampaignService publishes. Campaigns were therefore never indexed into or removed
 * from the matcher, so paused and deleted campaigns kept serving ads and kept spending budget.
 */
@SpringBootTest(classes = CampaignIndexingConsumerContractTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration")
@ActiveProfiles("contract-test")
@AutoConfigureStubRunner(ids = {
        "com.fooddelivery:campaign-service:+:stubs",
        "com.fooddelivery:budget-pacing-service:+:stubs",
        "com.fooddelivery:wallet-service:+:stubs"
}, stubsMode = StubRunnerProperties.StubsMode.LOCAL)
@EmbeddedKafka(partitions = 1, topics = {"ad-events"})
class CampaignIndexingConsumerContractTest {

    @org.springframework.boot.test.context.TestConfiguration
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration
    @Import(CampaignEventConsumer.class)
    static class TestConfig {
        @Bean
        public MessageVerifierSender<Message<?>> kafkaStubMessageSender(KafkaTemplate<String, String> t) {
            return new KafkaStubMessageSender(t);
        }
    }

    @MockBean
    private CampaignMatcher matcher;

    @MockBean
    private RedisIdempotencyService redisIdempotencyService;

    /* The full application context is loaded here; these two reach outside the JVM. */
    @MockBean
    private org.springframework.data.redis.core.ReactiveStringRedisTemplate reactiveStringRedisTemplate;

    @MockBean
    private com.fooddelivery.advertisement.bidding.client.CampaignServiceClient campaignServiceClient;

    /* @ConditionalOnProperty("spring.redis.enabled") keeps this out of the context; the
       controllers require it. */
    @MockBean
    private com.fooddelivery.common.service.RateLimitingService rateLimitingService;

    @Autowired
    private StubTrigger stubTrigger;

    @Test
    void indexesTheCampaignOnCreated() {
        stubTrigger.trigger("ad_events");
        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                verify(matcher).indexCampaign(anyString(), any(), anyString(), any(), anyDouble(), anyBoolean(), any(), any(), any(), any()));
    }

    @Test
    void removesTheCampaignOnPaused() {
        stubTrigger.trigger("ad_events_paused");
        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                verify(matcher).removeCampaign(anyString()));
    }

    @Test
    void marksTheCampaignExhaustedOnBudgetExhausted() {
        // Budget exhaustion is reversible (top-up, daily reset), so the campaign is flagged
        // rather than evicted -- removing it would discard its indexed targeting state.
        stubTrigger.trigger("ad_events_exhausted");
        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                verify(matcher).updateBudgetExhausted(anyString(), eq(true)));
    }

    @Test
    void updatesPacingOnPacingUpdated() {
        // AD_CAMPAIGN_PACING_UPDATED carries only the multiplier, not the targeting payload,
        // so the matcher adjusts pacing in place instead of re-indexing the campaign.
        stubTrigger.trigger("ad_events_pacing");
        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                verify(matcher).updatePacing(anyString(), anyDouble(), anyBoolean()));
    }

    /**
     * WalletService publishes AD_BUDGET_ALERT to {@code ad-events} (outbox aggregateType
     * ADVERTISEMENT), so this consumer does receive it -- but a low-balance alert is not itself a
     * serving decision. CampaignService's CampaignAlertConsumer owns it and re-publishes
     * AD_CAMPAIGN_BUDGET_EXHAUSTED, which is what actually flips the matcher. The poll delay lets
     * the event be consumed before asserting, so this is a real negative rather than a race.
     */
    @Test
    void ignoresBudgetAlertBecauseExhaustionIsSignalledSeparately() {
        stubTrigger.trigger("ad_budget_alert");
        await().pollDelay(3, TimeUnit.SECONDS).atMost(15, TimeUnit.SECONDS)
                .untilAsserted(() -> verifyNoInteractions(matcher));
    }
}
