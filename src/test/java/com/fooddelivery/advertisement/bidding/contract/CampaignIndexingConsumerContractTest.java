package com.fooddelivery.advertisement.bidding.contract;

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
import static org.mockito.Mockito.verify;

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
        properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration")
@ActiveProfiles("contract-test")
@AutoConfigureStubRunner(ids = "com.fooddelivery:campaign-service:+:stubs",
        stubsMode = StubRunnerProperties.StubsMode.LOCAL)
@EmbeddedKafka(partitions = 1, topics = {"ad-events"})
class CampaignIndexingConsumerContractTest {

    @org.springframework.boot.SpringBootConfiguration
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

    @Autowired
    private StubTrigger stubTrigger;

    @Test
    void indexesTheCampaignFromTheProducerStub() {
        stubTrigger.trigger("ad_events");

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                verify(matcher).indexCampaign(anyString(), any(), anyString()));
    }
}
