package com.fooddelivery.advertisement.bidding.filter;

import com.fooddelivery.advertisement.bidding.model.BidRequest;
import com.fooddelivery.advertisement.bidding.cache.CacheProvider;
import com.fooddelivery.common.constants.RedisKeyConstants;
import com.fooddelivery.advertisement.bidding.model.CampaignIndexData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@Order(1)
@lombok.extern.slf4j.Slf4j
public class FinancialFilter implements TargetingFilter {

    public FinancialFilter() {
    }

    @Override
    public boolean evaluate(BidRequest request, CampaignIndexData campaignData) {
        if (campaignData.budgetExhausted) {
            log.info("FinancialFilter: Budget exhausted according to index for campaign {}. Rejecting.", campaignData.campaignId);
            return false;
        }
        return true;
    }
}
