package com.fooddelivery.advertisement.bidding.controller;

import com.fooddelivery.advertisement.bidding.model.internal.*;
import com.fooddelivery.advertisement.bidding.model.CampaignIndexData;
import com.fooddelivery.advertisement.bidding.matcher.CampaignMatcher;
import com.fooddelivery.advertisement.bidding.filter.TargetingFilter;
import com.fooddelivery.advertisement.bidding.constants.TrackingUrlConstants;
import com.fooddelivery.advertisement.bidding.constants.BiddingConstants;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import com.fooddelivery.advertisement.bidding.pricing.BidPricer;
import com.fooddelivery.advertisement.bidding.pricing.PricingStrategyType;
import com.fooddelivery.advertisement.bidding.cache.CacheProvider;
import com.fooddelivery.common.constants.RedisKeyConstants;
import reactor.core.publisher.Flux;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import com.fooddelivery.common.security.AuctionTokenService;

@RestController
@RequestMapping("/api/v1/ads")
@lombok.extern.slf4j.Slf4j
public class InternalAdController {
    @java.lang.SuppressWarnings("all")

    private final CampaignMatcher matcher;
    private final List<TargetingFilter> filterChain;
    private final BidPricer bidPricer;
    private final CacheProvider cacheProvider;
    private final TrackingUrlConstants trackingUrlConstants;
    private final com.fooddelivery.common.service.RateLimitingService rateLimitingService;
    private final AuctionTokenService auctionTokenService;

    public InternalAdController(CampaignMatcher matcher, List<TargetingFilter> filterChain, BidPricer bidPricer, CacheProvider cacheProvider, TrackingUrlConstants trackingUrlConstants, com.fooddelivery.common.service.RateLimitingService rateLimitingService, AuctionTokenService auctionTokenService) {
        this.matcher = matcher;
        this.filterChain = filterChain;
        this.bidPricer = bidPricer;
        this.cacheProvider = cacheProvider;
        this.trackingUrlConstants = trackingUrlConstants;
        this.rateLimitingService = rateLimitingService;
        this.auctionTokenService = auctionTokenService;
    }

    @PostMapping("/serve")
    public Mono<ResponseEntity<List<SponsoredListingDTO>>> serveAds(@RequestBody AdRequestDTO request) {
        try {
            rateLimitingService.enforceRateLimit("internal", "ads-serve");
        } catch (RuntimeException e) {
            return Mono.just(ResponseEntity.status(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS).build());
        }
        
        String geo = (request.geo != null && !request.geo.isEmpty()) ? request.geo : BiddingConstants.DEFAULT_GEO;
        List<CampaignIndexData> matchedCampaigns = matcher.match(geo);
        // Map AdRequestDTO to a dummy BidRequest for filters
        com.fooddelivery.advertisement.bidding.model.BidRequest dummyBidRequest = new com.fooddelivery.advertisement.bidding.model.BidRequest();
        dummyBidRequest.user = new com.fooddelivery.advertisement.bidding.model.User();
        dummyBidRequest.user.geo = geo;
        dummyBidRequest.user.id = request.deviceId;
        dummyBidRequest.context = request.context;

        List<CampaignIndexData> filteredCampaigns = matchedCampaigns.stream().filter(campaignData -> {
            for (TargetingFilter filter : filterChain) {
                if (!filter.evaluate(dummyBidRequest, campaignData)) {
                    return false;
                }
            }
            return true;
        }).collect(Collectors.toList());

        class PricedCampaign {
            CampaignIndexData data;
            BigDecimal bidPrice;
            String encryptedPrice;
            PricedCampaign(CampaignIndexData d, BigDecimal bp, String ep) { this.data = d; this.bidPrice = bp; this.encryptedPrice = ep; }
        }

        List<PricedCampaign> pricedCampaigns = filteredCampaigns.stream()
            .map(campaignData -> {
                BigDecimal maxBid = campaignData.maxBid;
                if (maxBid == null) return null;
                double pacingS = campaignData.pacingMultiplier >= 0 ? Math.max(0.0, Math.min(1.0, campaignData.pacingMultiplier)) : 1.0;
                BigDecimal bidPrice = bidPricer.price(PricingStrategyType.FIRST_PRICE_SHADED_STRATEGY, campaignData.campaignId, maxBid, pacingS);
                if (bidPrice.compareTo(maxBid) > 0) {
                    bidPrice = maxBid;
                }
                UUID auctionId = UUID.randomUUID();
                String encryptedPrice = auctionTokenService.issue(
                        UUID.fromString(campaignData.campaignId),
                        UUID.fromString(campaignData.advertiserId),
                        bidPrice,
                        auctionId,
                        Duration.ofHours(24)
                );
                return new PricedCampaign(campaignData, bidPrice, encryptedPrice);
            })
            .filter(pc -> pc != null)
            .sorted((a, b) -> b.bidPrice.compareTo(a.bidPrice))
            .limit(3)
            .collect(Collectors.toList());

        List<SponsoredListingDTO> results = pricedCampaigns.stream()
            .map(pc -> {
                String campaignId = pc.data.campaignId;
                String advertiserId = pc.data.advertiserId;
                String encryptedPrice = pc.encryptedPrice;
                
                String impUrl = String.format(trackingUrlConstants.impressionTrackingUrlTemplate, campaignId, advertiserId, com.fooddelivery.common.constants.AdMacroConstants.MACRO_AUCTION_PRICE);
                impUrl = impUrl.contains("wp=" + com.fooddelivery.common.constants.AdMacroConstants.MACRO_AUCTION_PRICE) ? 
                         impUrl.replace("wp=" + com.fooddelivery.common.constants.AdMacroConstants.MACRO_AUCTION_PRICE, "wp=" + encryptedPrice) : 
                         impUrl + "&wp=" + encryptedPrice;
                         
                String clickUrl = String.format(trackingUrlConstants.clickTrackingUrlTemplate, campaignId, advertiserId);
                clickUrl = clickUrl.contains("wp=" + com.fooddelivery.common.constants.AdMacroConstants.MACRO_AUCTION_PRICE) ? 
                           clickUrl.replace("wp=" + com.fooddelivery.common.constants.AdMacroConstants.MACRO_AUCTION_PRICE, "wp=" + encryptedPrice) : 
                           clickUrl + "&wp=" + encryptedPrice;

                String adm = null;
                if ("VIDEO_VAST".equals(pc.data.creativeFormat)) {
                    adm = pc.data.creativeVastXml;
                } else if (pc.data.creativeAssetUrl != null) {
                    adm = pc.data.creativeAssetUrl;
                } else {
                    adm = String.format(trackingUrlConstants.cdnSponsoredImageTemplate, campaignId);
                }

                return new SponsoredListingDTO(
                    BiddingConstants.PREFIX_AD_ID + System.currentTimeMillis() + "-" + campaignId,
                    campaignId,
                    impUrl,
                    clickUrl,
                    adm,
                    pc.data.creativeFormat != null ? pc.data.creativeFormat : "BANNER"
                );
            })
            .collect(Collectors.toList());

        return Mono.just(ResponseEntity.ok(results));
    }
}
