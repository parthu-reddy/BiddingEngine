package com.fooddelivery.advertisement.bidding.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

@FeignClient(name = "campaign-service")
public interface CampaignServiceClient {

    @GetMapping("/api/v1/internal/campaigns/active-for-bidding")
    List<ActiveCampaignDTO> getActiveCampaignsForBidding();

    class ActiveCampaignDTO {
        public String id;
        public String advertiserId;
        public java.math.BigDecimal maxBid;
        public Double pacingMultiplier;
        public com.fooddelivery.common.dto.targeting.TargetingSummary targeting;
    }
}
