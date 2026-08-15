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
import java.util.Base64;

@RestController
@RequestMapping("/api/v1/ads")
@lombok.extern.slf4j.Slf4j
public class InternalAdController {
    @java.lang.SuppressWarnings("all")

    private final CampaignMatcher matcher;
    private final List<TargetingFilter> filterChain;
    private final BidPricer bidPricer;
    private final CacheProvider cacheProvider;

    public InternalAdController(CampaignMatcher matcher, List<TargetingFilter> filterChain, BidPricer bidPricer, CacheProvider cacheProvider) {
        this.matcher = matcher;
        this.filterChain = filterChain;
        this.bidPricer = bidPricer;
        this.cacheProvider = cacheProvider;
    }

    @PostMapping("/serve")
    public Mono<ResponseEntity<List<SponsoredListingDTO>>> serveAds(@RequestBody AdRequestDTO request) {
        String geo = (request.geo != null && !request.geo.isEmpty()) ? request.geo : BiddingConstants.DEFAULT_GEO;
        List<CampaignIndexData> matchedCampaigns = matcher.match(geo);
        
        if (matchedCampaigns.isEmpty()) {
            return Mono.just(ResponseEntity.ok(List.of()));
        }

        return Flux.fromIterable(matchedCampaigns)
            .take(3)
            .flatMap(campaignData -> {
                String campaignId = campaignData.campaignId;
                Mono<String> pacingMono = cacheProvider.get(String.format(RedisKeyConstants.PREFIX_AD_CAMPAIGN_PACING, campaignId))
                    .defaultIfEmpty(BiddingConstants.DEFAULT_PACING_MULTIPLIER_STRING);
                Mono<String> maxBidMono = cacheProvider.get(String.format(RedisKeyConstants.PREFIX_AD_CAMPAIGN_MAX_BID, campaignId))
                    .defaultIfEmpty("");

                return Mono.zip(pacingMono, maxBidMono).flatMap(tuple -> {
                    String pacingStr = tuple.getT1();
                    String maxBidStr = tuple.getT2();
                    if (maxBidStr.isEmpty()) {
                        return Mono.empty();
                    }
                    
                    double pacingS = Double.parseDouble(pacingStr);
                    BigDecimal maxBid = new BigDecimal(maxBidStr);
                    BigDecimal bidPrice = bidPricer.price(PricingStrategyType.FIRST_PRICE_SHADED_STRATEGY, campaignId, maxBid, pacingS);
                    
                    String encryptedPrice = Base64.getUrlEncoder().encodeToString(bidPrice.toPlainString().getBytes());
                    
                    SponsoredListingDTO dto = new SponsoredListingDTO(
                        BiddingConstants.PREFIX_AD_ID + System.currentTimeMillis() + "-" + campaignId,
                        campaignId,
                        String.format(TrackingUrlConstants.IMPRESSION_TRACKING_URL_TEMPLATE, campaignId, campaignData.advertiserId) + "&wp=" + encryptedPrice,
                        String.format(TrackingUrlConstants.CLICK_TRACKING_URL_TEMPLATE, campaignId, campaignData.advertiserId) + "&wp=" + encryptedPrice,
                        String.format(TrackingUrlConstants.CDN_SPONSORED_IMAGE_TEMPLATE, campaignId)
                    );
                    return Mono.just(dto);
                });
            })
            .collectList()
            .map(ResponseEntity::ok);
    }
}
