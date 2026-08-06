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

@RestController
@RequestMapping("/api/v1/ads")
public class InternalAdController {

    private final CampaignMatcher matcher;
    private final List<TargetingFilter> filterChain;

    public InternalAdController(CampaignMatcher matcher, List<TargetingFilter> filterChain) {
        this.matcher = matcher;
        this.filterChain = filterChain;
    }

    @PostMapping("/serve")
    public Mono<ResponseEntity<List<SponsoredListingDTO>>> serveAds(@RequestBody AdRequestDTO request) {
        String geo = (request.geo != null && !request.geo.isEmpty()) ? request.geo : BiddingConstants.DEFAULT_GEO;
        
        List<CampaignIndexData> matchedCampaigns = matcher.match(geo);
        
        // Internal filtering logic without full OpenRTB BidRequest object mapping
        // In a real system, we might map AdRequestDTO to a subset of BidRequest for the filters
        // Here we just return the first matched campaign for the sponsored listing
        if (matchedCampaigns.isEmpty()) {
            return Mono.just(ResponseEntity.ok(List.of()));
        }
        
        List<SponsoredListingDTO> listings = matchedCampaigns.stream()
            .limit(3) // Serve up to 3 ads
            .map(campaignData -> new SponsoredListingDTO(
                BiddingConstants.PREFIX_AD_ID + System.currentTimeMillis() + "-" + campaignData.campaignId,
                campaignData.campaignId,
                String.format(TrackingUrlConstants.IMPRESSION_TRACKING_URL_TEMPLATE, campaignData.campaignId),
                String.format(TrackingUrlConstants.CLICK_TRACKING_URL_TEMPLATE, campaignData.campaignId),
                String.format(TrackingUrlConstants.CDN_SPONSORED_IMAGE_TEMPLATE, campaignData.campaignId)
            ))
            .collect(Collectors.toList());

        return Mono.just(ResponseEntity.ok(listings));
    }
}
