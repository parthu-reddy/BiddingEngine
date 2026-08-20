package com.fooddelivery.ad.bidding;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        classes = com.fooddelivery.advertisement.bidding.BiddingEngineApplication.class,
        properties = {
                "spring.redis.enabled=false",
                "spring.main.allow-bean-definition-overriding=true",
                "eureka.client.enabled=false",
                "spring.cloud.config.enabled=false"
        })
class BiddingEngineApplicationTests {

    /* MultiTierCacheProvider needs this; Redis autoconfig is off in this test. */
    @org.springframework.boot.test.mock.mockito.MockBean
    private org.springframework.data.redis.core.ReactiveStringRedisTemplate reactiveStringRedisTemplate;

    /* IndexBootstrapService calls CampaignService over the network on ApplicationReadyEvent and
       rethrows on failure, which would abort the context. Stub it so the context can start. */
    @org.springframework.boot.test.mock.mockito.MockBean
    private com.fooddelivery.advertisement.bidding.client.CampaignServiceClient campaignServiceClient;

    /* RateLimitingService is @ConditionalOnProperty("spring.redis.enabled"), which this test turns
       off -- but the controllers require it. */
    @org.springframework.boot.test.mock.mockito.MockBean
    private com.fooddelivery.common.service.RateLimitingService rateLimitingService;

    @org.springframework.boot.test.mock.mockito.MockBean
    private org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    @Test
    void contextLoads() {
    }
}
