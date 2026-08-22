package com.fooddelivery.advertisement.bidding.filter;

import com.fooddelivery.advertisement.bidding.model.BidRequest;
import com.fooddelivery.advertisement.bidding.model.CampaignIndexData;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(3)
public class ContextualKeywordFilter implements TargetingFilter {

    @Override
    public boolean evaluate(BidRequest request, CampaignIndexData campaignData) {
        if (campaignData.targeting == null
                || campaignData.targeting.getContextualKeywords() == null
                || campaignData.targeting.getContextualKeywords().getKeywords() == null
                || campaignData.targeting.getContextualKeywords().getKeywords().isEmpty()) {
            return true;                       // no keyword targeting set: everything matches
        }
        
        if (request.context == null || request.context.isEmpty()) {
            return false;                      // targeting set but no context: cannot match
        }
        
        return campaignData.targeting.getContextualKeywords().getKeywords().stream()
                .anyMatch(k -> request.context.equalsIgnoreCase(k));
    }
}
