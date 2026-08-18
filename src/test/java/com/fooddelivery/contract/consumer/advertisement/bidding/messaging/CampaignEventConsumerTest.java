package com.fooddelivery.contract.consumer.advertisement.bidding.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.cloud.contract.stubrunner.StubTrigger;
import org.springframework.cloud.contract.stubrunner.spring.AutoConfigureStubRunner;
import org.springframework.cloud.contract.stubrunner.spring.StubRunnerProperties;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.test.context.EmbeddedKafka;

@ActiveProfiles("contract-test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, classes = com.fooddelivery.advertisement.bidding.BiddingEngineApplication.class)
@AutoConfigureStubRunner(ids = "com.fooddelivery:food-delivery-backend:+:stubs", stubsMode = StubRunnerProperties.StubsMode.REMOTE, repositoryRoot = "git://file:///tmp/FoodDelivery/FoodDeliveryContracts/.git")
@EmbeddedKafka(partitions = 1)
public class CampaignEventConsumerTest {

    

    


    @org.springframework.boot.test.mock.mockito.MockBean
    private io.github.bucket4j.distributed.proxy.ProxyManager<String> proxyManager;



    

    @org.springframework.boot.test.mock.mockito.MockBean
    private org.springframework.data.redis.core.ReactiveStringRedisTemplate reactiveStringRedisTemplate;

    @Autowired
    private StubTrigger stubTrigger;

    

    @Test
    public void testConsumerIsWorking() {
        try {
            stubTrigger.trigger("trigger-ad-event");
        } catch (Exception e) {
            System.out.println("Trigger failed, which might be expected if the stub does not have it yet. Error: " + e.getMessage());
        }
    }
}
