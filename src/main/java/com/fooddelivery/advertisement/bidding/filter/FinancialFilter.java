package com.fooddelivery.advertisement.bidding.filter;

import com.fooddelivery.advertisement.bidding.model.BidRequest;
import com.fooddelivery.advertisement.bidding.cache.CacheProvider;
import com.fooddelivery.common.constants.RedisKeyConstants;
import com.fooddelivery.advertisement.bidding.model.CampaignIndexData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@lombok.extern.slf4j.Slf4j
public class FinancialFilter implements TargetingFilter {

    private final CacheProvider cache;

    public FinancialFilter(CacheProvider cache) {
        this.cache = cache;
    }

    @Override
    public boolean evaluate(BidRequest request, CampaignIndexData campaignData) {
        String budgetKey = String.format(RedisKeyConstants.PREFIX_AD_WALLET_BALANCE, campaignData.advertiserId);
        
        // Check budget from L1/L2 cache
        String budgetStr = cache.get(budgetKey)
            .block(java.time.Duration.ofMillis(50));
        
        if (budgetStr == null) {
            log.warn("FinancialFilter: Wallet balance not in cache for advertiser {}. Allowing bid cautiously.", campaignData.advertiserId);
            return true;
        }
        
        try {
            BigDecimal budget = new BigDecimal(budgetStr);
            if (budget.compareTo(BigDecimal.ZERO) <= 0) {
                log.info("FinancialFilter: Budget exhausted for advertiser {}. Rejecting campaign {}.", campaignData.advertiserId, campaignData.campaignId);
                return false;
            }
            return true;
        } catch (NumberFormatException e) {
            log.warn("Invalid budget value '{}' for advertiser {}", budgetStr, campaignData.advertiserId);
            return false;
        }
    }
}
