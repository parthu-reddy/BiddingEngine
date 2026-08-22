package com.fooddelivery.advertisement.bidding.matcher;

import com.fooddelivery.advertisement.bidding.client.CampaignServiceClient;
import com.fooddelivery.advertisement.bidding.constants.BiddingConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import com.fooddelivery.advertisement.bidding.health.IndexBootstrapHealthIndicator;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class IndexBootstrapService {
    private static final Logger log = LoggerFactory.getLogger(IndexBootstrapService.class);

    private final CampaignMatcher campaignMatcher;
    private final CampaignServiceClient campaignServiceClient;
    private final IndexBootstrapHealthIndicator healthIndicator;

    public IndexBootstrapService(CampaignMatcher campaignMatcher, CampaignServiceClient campaignServiceClient, IndexBootstrapHealthIndicator healthIndicator) {
        this.campaignMatcher = campaignMatcher;
        this.campaignServiceClient = campaignServiceClient;
        this.healthIndicator = healthIndicator;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrapIndex() {
        log.info("Bootstrapping campaign index from CampaignService...");
        try {
            List<CampaignServiceClient.ActiveCampaignDTO> activeCampaigns = campaignServiceClient.getActiveCampaignsForBidding();
            if (activeCampaigns != null) {
                int count = 0;
                for (CampaignServiceClient.ActiveCampaignDTO campaign : activeCampaigns) {
                    double pacing = campaign.pacingMultiplier != null ? campaign.pacingMultiplier : -1.0;
                    campaignMatcher.indexCampaign(campaign.id, java.util.List.of(BiddingConstants.DEFAULT_GEO), campaign.advertiserId, campaign.maxBid, pacing, false, null, null, null, null);
                    count++;
                }
                healthIndicator.setBootstrapped(true);
                log.info("Successfully bootstrapped {} campaigns into the index.", count);
            }
        } catch (Exception e) {
            log.error("Failed to bootstrap campaign index. The index will remain empty until events are received.", e);
            // Consider throwing here if we want to fail readiness probe
            throw e;
        }
    }
}
