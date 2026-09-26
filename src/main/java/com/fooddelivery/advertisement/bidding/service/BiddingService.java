package com.fooddelivery.advertisement.bidding.service;

import com.fooddelivery.advertisement.bidding.model.*;
import com.fooddelivery.advertisement.bidding.matcher.CampaignMatcher;
import com.fooddelivery.advertisement.bidding.pricing.BidPricer;
import com.fooddelivery.advertisement.bidding.pricing.PricingStrategyType;
import com.fooddelivery.advertisement.bidding.filter.TargetingFilter;
import com.fooddelivery.advertisement.bidding.constants.BiddingConstants;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Duration;
import com.fooddelivery.common.security.AuctionTokenService;

@Service
@lombok.extern.slf4j.Slf4j
public class BiddingService {
    @java.lang.SuppressWarnings("all")

    private final CampaignMatcher matcher;
    private final BidPricer bidPricer;
    private final List<TargetingFilter> filterChain;
    private final com.fooddelivery.advertisement.bidding.constants.TrackingUrlConstants trackingUrlConstants;
    private final MeterRegistry meterRegistry;
    private final AuctionTokenService auctionTokenService;

    public BiddingService(CampaignMatcher matcher, BidPricer bidPricer, List<TargetingFilter> filterChain, com.fooddelivery.advertisement.bidding.constants.TrackingUrlConstants trackingUrlConstants, MeterRegistry meterRegistry, AuctionTokenService auctionTokenService) {
        this.matcher = matcher;
        this.bidPricer = bidPricer;
        this.filterChain = filterChain;
        this.trackingUrlConstants = trackingUrlConstants;
        this.meterRegistry = meterRegistry;
        this.auctionTokenService = auctionTokenService;
    }

    @jakarta.annotation.PostConstruct
    public void initMetrics() {
        meterRegistry.counter("auction.bids.count").increment(0);
        meterRegistry.counter("auction.requests.count").increment(0);
    }

    public Optional<BidResponse> processBidSync(BidRequest request) {
        meterRegistry.counter("auction.requests.count").increment();
        String geo = (request.user != null && request.user.geo != null) ? request.user.geo : BiddingConstants.DEFAULT_GEO;

        
        List<CampaignIndexData> matchedCampaigns = new java.util.ArrayList<>();
        matchedCampaigns.addAll(matcher.match(geo));
        if (!BiddingConstants.DEFAULT_GEO.equals(geo)) {
            matchedCampaigns.addAll(matcher.match(BiddingConstants.DEFAULT_GEO));
        }
        if (!"GLOBAL".equals(geo)) {
            matchedCampaigns.addAll(matcher.match("GLOBAL"));
        }
        if (matchedCampaigns.isEmpty()) {
            meterRegistry.counter("bid_skipped_total", "reason", "no_match").increment();
            return Optional.empty();
        }

        // Real Auction: price all matching & passing campaigns, and pick the highest bid
        CampaignIndexData bestCampaignData = null;
        BigDecimal highestBid = BigDecimal.ZERO;
        
        for (CampaignIndexData campaignData : matchedCampaigns) {
            boolean passed = true;
            for (TargetingFilter filter : filterChain) {
                if (!filter.evaluate(request, campaignData)) {
                    passed = false;
                    break;
                }
            }
            if (!passed) continue;
            
            BigDecimal maxBid = campaignData.maxBid;
            if (maxBid == null) continue;
            
            double pacingMultiplier = campaignData.pacingMultiplier;
            double pacingS = pacingMultiplier >= 0 ? Math.max(0.0, Math.min(1.0, pacingMultiplier)) : 1.0;
            BigDecimal bidPrice = bidPricer.price(PricingStrategyType.FIRST_PRICE_SHADED_STRATEGY, campaignData.campaignId, maxBid, pacingS);
            
            if (bidPrice.compareTo(maxBid) > 0) {
                bidPrice = maxBid;
            }
            
            if (bidPrice.compareTo(highestBid) > 0) {
                highestBid = bidPrice;
                bestCampaignData = campaignData;
            }
        }

        if (bestCampaignData == null) {
            meterRegistry.counter("bid_skipped_total", "reason", "no_valid_bids").increment();
            return Optional.empty();
        }

        CampaignIndexData selectedCampaignData = bestCampaignData;
        String selectedCampaign = selectedCampaignData.campaignId;
        String advertiserId = selectedCampaignData.advertiserId;
        
        if (advertiserId == null) {
            log.warn("No advertiserId found for campaign {}. Skipping bid.", selectedCampaign);
            meterRegistry.counter("bid_skipped_total", "reason", "missing_advertiser").increment();
            return Optional.empty();
        }
        
        BigDecimal bidPrice = highestBid;
        
        Bid bid = new Bid();
        bid.id = UUID.randomUUID().toString();
        bid.impid = request.imp.get(0).id;
        bid.price = bidPrice;
        bid.adid = BiddingConstants.PREFIX_AD_ID + selectedCampaign;
        
        String token = auctionTokenService.issue(
                UUID.fromString(selectedCampaign),
                UUID.fromString(advertiserId),
                bidPrice,
                UUID.fromString(bid.id),
                Duration.ofHours(24),
                // signed in so the tracker books the spend to the advertiser's day
                java.time.ZoneId.of(selectedCampaignData.timeZone)
        );
        String impUrl = String.format(trackingUrlConstants.impressionTrackingUrlTemplate, selectedCampaign, advertiserId, com.fooddelivery.common.constants.AdMacroConstants.MACRO_AUCTION_PRICE);
        bid.nurl = impUrl.contains("wp=" + com.fooddelivery.common.constants.AdMacroConstants.MACRO_AUCTION_PRICE) ? 
                   impUrl.replace("wp=" + com.fooddelivery.common.constants.AdMacroConstants.MACRO_AUCTION_PRICE, "wp=" + token) : 
                   impUrl + "&wp=" + token;
        String clickUrl = String.format(trackingUrlConstants.clickTrackingUrlTemplate, selectedCampaign, advertiserId);
        clickUrl = clickUrl.contains("wp=" + com.fooddelivery.common.constants.AdMacroConstants.MACRO_AUCTION_PRICE) ? 
                   clickUrl.replace("wp=" + com.fooddelivery.common.constants.AdMacroConstants.MACRO_AUCTION_PRICE, "wp=" + token) : 
                   clickUrl + "&wp=" + token;

        if ("VIDEO_VAST".equals(selectedCampaignData.creativeFormat)) {
            bid.adm = selectedCampaignData.creativeVastXml;
        } else if (selectedCampaignData.creativeAssetUrl != null) {
            bid.adm = "<a href='" + clickUrl + "' target='_blank'><img src='" + selectedCampaignData.creativeAssetUrl + "' /></a>";
        } else {
            bid.adm = "<a href='" + clickUrl + "' target='_blank'><img src='" + String.format(trackingUrlConstants.cdnSponsoredImageTemplate, selectedCampaign) + "' /></a>";
        }
        
        SeatBid seatBid = new SeatBid(List.of(bid));
        BidResponse response = new BidResponse(request.id, List.of(seatBid));
        
        meterRegistry.counter("auction.bids.count").increment();
        return Optional.of(response);
    }
}
