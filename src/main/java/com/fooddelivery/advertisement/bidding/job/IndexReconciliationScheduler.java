package com.fooddelivery.advertisement.bidding.job;

import com.fooddelivery.advertisement.bidding.client.CampaignServiceClient;
import com.fooddelivery.advertisement.bidding.matcher.CampaignMatcher;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@Slf4j
public class IndexReconciliationScheduler {

    private final CampaignServiceClient campaignServiceClient;
    private final CampaignMatcher campaignMatcher;
    private final MeterRegistry meterRegistry;

    public IndexReconciliationScheduler(CampaignServiceClient campaignServiceClient, CampaignMatcher campaignMatcher, MeterRegistry meterRegistry) {
        this.campaignServiceClient = campaignServiceClient;
        this.campaignMatcher = campaignMatcher;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void init() {
        meterRegistry.gauge("bidding_indexed_campaigns", campaignMatcher, CampaignMatcher::getIndexedCampaignsCount);
    }

    @Scheduled(fixedRateString = "${bidding.reconciliation.interval:300000}")
    public void reconcileIndex() {
        try {
            log.info("Starting index reconciliation job");
            List<CampaignServiceClient.ActiveCampaignDTO> activeCampaigns = campaignServiceClient.getActiveCampaignsForBidding();
            Set<String> activeCampaignIds = activeCampaigns.stream()
                    .map(c -> c.id)
                    .collect(Collectors.toSet());

            List<String> indexedCampaignIds = campaignMatcher.getAllIndexedCampaignIds();

            int removedCount = 0;
            for (String indexedId : indexedCampaignIds) {
                if (!activeCampaignIds.contains(indexedId)) {
                    log.warn("Campaign {} is indexed but no longer active. Removing.", indexedId);
                    campaignMatcher.removeCampaign(indexedId);
                    removedCount++;
                    meterRegistry.counter("bidding_index_reconciled_total", "action", "removed").increment();
                }
            }

            // The other direction matters just as much: a dropped AD_CAMPAIGN_CREATED leaves an
            // active campaign unindexed and therefore unservable, and removals alone never repair
            // that. Re-index from the same endpoint IndexBootstrapService uses at startup.
            //
            // The endpoint carries only id, advertiserId and maxBid -- no targeting and no
            // creative -- so the restored entry is deliberately partial: it serves under the
            // default geo with the fallback creative until the next ad-event enriches it. That is
            // the same entry startup would have produced, and strictly better than absent.
            java.util.Set<String> indexed = new java.util.HashSet<>(indexedCampaignIds);
            int addedCount = 0;
            for (CampaignServiceClient.ActiveCampaignDTO campaign : activeCampaigns) {
                if (indexed.contains(campaign.id)) {
                    continue;
                }
                log.warn("Campaign {} is active but missing from the index. Re-indexing.", campaign.id);
                double pacing = campaign.pacingMultiplier != null ? campaign.pacingMultiplier : -1.0;
                campaignMatcher.indexCampaign(campaign.id,
                        java.util.List.of(com.fooddelivery.advertisement.bidding.constants.BiddingConstants.DEFAULT_GEO),
                        campaign.advertiserId, campaign.maxBid, pacing, false, null, null, null, null);
                addedCount++;
                meterRegistry.counter("bidding_index_reconciled_total", "action", "added").increment();
            }

            log.info("Index reconciliation complete. Removed {} stale, re-indexed {} missing. Index size: {}",
                    removedCount, addedCount, campaignMatcher.getIndexedCampaignsCount());
        } catch (Exception e) {
            log.error("Failed to reconcile campaign index", e);
        }
    }
}
