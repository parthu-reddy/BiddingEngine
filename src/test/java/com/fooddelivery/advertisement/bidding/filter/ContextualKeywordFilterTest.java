package com.fooddelivery.advertisement.bidding.filter;

import com.fooddelivery.advertisement.bidding.model.BidRequest;
import com.fooddelivery.advertisement.bidding.model.CampaignIndexData;
import com.fooddelivery.common.dto.targeting.ContextualKeywords;
import com.fooddelivery.common.dto.targeting.TargetingSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextualKeywordFilterTest {

    private ContextualKeywordFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ContextualKeywordFilter();
    }

    private CampaignIndexData createCampaignData(TargetingSummary summary) {
        return new CampaignIndexData("camp1", "adv1", BigDecimal.ONE, 1.0, false, summary, "BANNER", "http://asset", null, "Asia/Kolkata");
    }

    @Test
    void shouldReturnTrueWhenNoTargetingSet() {
        BidRequest request = new BidRequest();
        request.context = "sports";
        CampaignIndexData campaignData = createCampaignData(new TargetingSummary());

        assertTrue(filter.evaluate(request, campaignData));
    }

    @Test
    void shouldReturnFalseWhenTargetingSetButNoContextInRequest() {
        BidRequest request = new BidRequest();
        // request.context is null
        TargetingSummary summary = new TargetingSummary();
        summary.setContextualKeywords(new ContextualKeywords(List.of("food", "dining")));
        CampaignIndexData campaignData = createCampaignData(summary);

        assertFalse(filter.evaluate(request, campaignData));
    }

    @Test
    void shouldReturnFalseWhenContextDoesNotMatchKeywords() {
        BidRequest request = new BidRequest();
        request.context = "sports";
        
        TargetingSummary summary = new TargetingSummary();
        summary.setContextualKeywords(new ContextualKeywords(List.of("food", "dining")));
        CampaignIndexData campaignData = createCampaignData(summary);

        assertFalse(filter.evaluate(request, campaignData));
    }

    @Test
    void shouldReturnTrueWhenContextMatchesKeywords() {
        BidRequest request = new BidRequest();
        request.context = "Food"; // Testing case insensitivity
        
        TargetingSummary summary = new TargetingSummary();
        summary.setContextualKeywords(new ContextualKeywords(List.of("food", "dining")));
        CampaignIndexData campaignData = createCampaignData(summary);

        assertTrue(filter.evaluate(request, campaignData));
    }
}
