package com.fooddelivery.advertisement.bidding.controller;

import com.fooddelivery.advertisement.bidding.model.BidRequest;
import com.fooddelivery.advertisement.bidding.model.BidResponse;
import com.fooddelivery.advertisement.bidding.service.DisruptorService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/v1/bidding")
@Slf4j
public class BiddingController {

    private final DisruptorService disruptorService;
    private final OpenRtbRequestParser openRtbParser;
    
    public BiddingController(DisruptorService disruptorService, OpenRtbRequestParser openRtbParser) {
        this.disruptorService = disruptorService;
        this.openRtbParser = openRtbParser;
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
        if (request.imp == null || request.imp.isEmpty()) {
            return Mono.just(ResponseEntity.noContent().build());
        }
        
        long tmax = request.tmax != null ? request.tmax : 100L;
        
        return Mono.<ResponseEntity<BidResponse>>create(sink -> {
            disruptorService.publish(request, sink);
        })
        .timeout(Duration.ofMillis(tmax))
        .onErrorResume(TimeoutException.class, e -> {
            return Mono.just(ResponseEntity.noContent().build());
        });
    }
}

