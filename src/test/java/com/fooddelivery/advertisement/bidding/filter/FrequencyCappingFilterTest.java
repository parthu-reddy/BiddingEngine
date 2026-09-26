package com.fooddelivery.advertisement.bidding.filter;

import com.fooddelivery.advertisement.bidding.model.BidRequest;
import com.fooddelivery.advertisement.bidding.model.CampaignIndexData;
import com.fooddelivery.advertisement.bidding.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class FrequencyCappingFilterTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private FrequencyCappingFilter filter;

    @BeforeEach
    void setUp() {
        filter = new FrequencyCappingFilter(redisTemplate);
    }

    @Test
    void testEvaluate_BelowCap_ReturnsTrue() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("ad:cap:device123:camp456")).thenReturn("4");

        BidRequest request = new BidRequest();
        request.user = new User();
        request.user.id = "device123";

        CampaignIndexData campaign = new CampaignIndexData("camp456", null, null, 1.0, false, null, null, null, null, "Asia/Kolkata");

        assertTrue(filter.evaluate(request, campaign));
    }

    @Test
    void testEvaluate_AtCap_ReturnsFalse() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("ad:cap:device123:camp456")).thenReturn("5");

        BidRequest request = new BidRequest();
        request.user = new User();
        request.user.id = "device123";

        CampaignIndexData campaign = new CampaignIndexData("camp456", null, null, 1.0, false, null, null, null, null, "Asia/Kolkata");

        assertFalse(filter.evaluate(request, campaign));
    }

    @Test
    void testEvaluate_AboveCap_ReturnsFalse() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("ad:cap:device123:camp456")).thenReturn("6");

        BidRequest request = new BidRequest();
        request.user = new User();
        request.user.id = "device123";

        CampaignIndexData campaign = new CampaignIndexData("camp456", null, null, 1.0, false, null, null, null, null, "Asia/Kolkata");

        assertFalse(filter.evaluate(request, campaign));
    }

    @Test
    void testEvaluate_NoUserIdentifier_ReturnsTrue() {
        BidRequest request = new BidRequest(); // no user
        CampaignIndexData campaign = new CampaignIndexData("camp456", null, null, 1.0, false, null, null, null, null, "Asia/Kolkata");

        assertTrue(filter.evaluate(request, campaign));
    }

    @Test
    void testEvaluate_NullCount_ReturnsTrue() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("ad:cap:device123:camp456")).thenReturn(null);

        BidRequest request = new BidRequest();
        request.user = new User();
        request.user.id = "device123";

        CampaignIndexData campaign = new CampaignIndexData("camp456", null, null, 1.0, false, null, null, null, null, "Asia/Kolkata");

        assertTrue(filter.evaluate(request, campaign));
    }
}
