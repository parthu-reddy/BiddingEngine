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
        int maxRetries = 24;
        for (int retries = 0; retries < maxRetries; retries++) {
            try {
                List<CampaignServiceClient.ActiveCampaignDTO> activeCampaigns = campaignServiceClient.getActiveCampaignsForBidding();
                if (activeCampaigns != null) {
                    int count = 0;
                    for (CampaignServiceClient.ActiveCampaignDTO campaign : activeCampaigns) {
                        double pacing = campaign.pacingMultiplier != null ? campaign.pacingMultiplier : -1.0;
                        java.util.List<String> geos = java.util.List.of(BiddingConstants.DEFAULT_GEO);
                        if (campaign.targeting != null && campaign.targeting.getGeoTargeting() != null 
                            && campaign.targeting.getGeoTargeting().getRegions() != null 
                            && !campaign.targeting.getGeoTargeting().getRegions().isEmpty()) {
                            geos = new java.util.ArrayList<>(campaign.targeting.getGeoTargeting().getRegions());
                        }
                        campaignMatcher.indexCampaign(campaign.id, geos, campaign.advertiserId, campaign.maxBid, pacing, false, campaign.targeting, null, null, null);
                        count++;
                    }
                    healthIndicator.setBootstrapped(true);
                    log.info("Successfully bootstrapped {} campaigns into the index.", count);
                    return; // Success
                }
            } catch (Exception e) {
                log.warn("Failed to bootstrap campaign index (attempt {}/{}). Retrying in 5 seconds... Error: {}", retries + 1, maxRetries, e.getMessage());
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.error("Exhausted retries for bootstrapping campaign index. The index will remain empty until events are received.");
    }
}
