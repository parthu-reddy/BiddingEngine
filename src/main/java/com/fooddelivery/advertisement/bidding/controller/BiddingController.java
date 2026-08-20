package com.fooddelivery.advertisement.bidding.controller;

import com.fooddelivery.advertisement.bidding.model.BidRequest;
import com.fooddelivery.advertisement.bidding.model.BidResponse;
import com.fooddelivery.advertisement.bidding.service.BiddingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/bidding")
@lombok.extern.slf4j.Slf4j
public class BiddingController {
    @java.lang.SuppressWarnings("all")

    private final BiddingService biddingService;
    private final OpenRtbRequestParser openRtbParser;
    private final com.fooddelivery.common.service.RateLimitingService rateLimitingService;

    public BiddingController(BiddingService biddingService, OpenRtbRequestParser openRtbParser, com.fooddelivery.common.service.RateLimitingService rateLimitingService) {
        this.biddingService = biddingService;
        this.openRtbParser = openRtbParser;
        this.rateLimitingService = rateLimitingService;
    }

    @PostMapping(value = "/rtb", consumes = "application/x-protobuf")
    public Mono<ResponseEntity<BidResponse>> handleBidRequestProto(@RequestBody byte[] payload) {
        try {
            com.google.openrtb.OpenRtb.BidRequest protoRequest = com.google.openrtb.OpenRtb.BidRequest.parseFrom(payload);
            BidRequest request = openRtbParser.parse(protoRequest);
            return processRequest(request);
        } catch (Exception e) {
            return Mono.just(ResponseEntity.noContent().build());
        }
    }

    @PostMapping(value = "/rtb", consumes = "application/json")
    public Mono<ResponseEntity<BidResponse>> handleBidRequestJson(@RequestBody String jsonPayload) {
        try {
            BidRequest request = openRtbParser.parseJson(jsonPayload);
            return processRequest(request);
        } catch (Exception e) {
            return Mono.just(ResponseEntity.noContent().build());
        }
    }

    private Mono<ResponseEntity<BidResponse>> processRequest(BidRequest request) {
        try {
            rateLimitingService.enforceRateLimit("exchange", "rtb");
        } catch (RuntimeException e) {
            return Mono.just(ResponseEntity.status(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS).build());
        }
        
        if (request.imp == null || request.imp.isEmpty()) {
            return Mono.just(ResponseEntity.noContent().build());
        }
        
        Optional<BidResponse> responseOpt = biddingService.processBidSync(request);
        if (responseOpt.isPresent()) {
            return Mono.just(ResponseEntity.ok(responseOpt.get()));
        } else {
            return Mono.just(ResponseEntity.noContent().build());
        }
    }
}
