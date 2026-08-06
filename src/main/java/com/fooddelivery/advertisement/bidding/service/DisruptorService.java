package com.fooddelivery.advertisement.bidding.service;

import com.fooddelivery.advertisement.bidding.model.*;
import com.fooddelivery.advertisement.bidding.matcher.CampaignMatcher;
import com.fooddelivery.advertisement.bidding.pricing.BidPricer;
import com.fooddelivery.advertisement.bidding.pricing.PricingStrategyType;
import com.fooddelivery.advertisement.bidding.cache.CacheProvider;
import com.fooddelivery.advertisement.bidding.filter.TargetingFilter;
import com.fooddelivery.advertisement.bidding.constants.BiddingConstants;
import com.fooddelivery.common.constants.RedisKeyConstants;
import com.fooddelivery.common.constants.AdMacroConstants;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.util.DaemonThreadFactory;
import com.lmax.disruptor.YieldingWaitStrategy;
import com.lmax.disruptor.dsl.ProducerType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class DisruptorService {
public static class BiddingEvent {
        public BidRequest request;
        public MonoSink<ResponseEntity<BidResponse>> sink;
        
        // Pre-allocated mutable objects to ensure zero-allocation in the critical path
        public final Bid preAllocatedBid;
        public final SeatBid preAllocatedSeatBid;
        public final BidResponse preAllocatedResponse;

        public BiddingEvent() {
            this.preAllocatedBid = new Bid();
            this.preAllocatedSeatBid = new SeatBid(List.of(this.preAllocatedBid));
            this.preAllocatedResponse = new BidResponse("", List.of(this.preAllocatedSeatBid));
        }
    }

    private Disruptor<BiddingEvent> disruptor;
    private RingBuffer<BiddingEvent> ringBuffer;

    private final CampaignMatcher matcher;
    private final BidPricer bidPricer;
    private final CacheProvider cacheProvider;
    private final List<TargetingFilter> filterChain;

    public DisruptorService(CampaignMatcher matcher, BidPricer bidPricer, CacheProvider cacheProvider, List<TargetingFilter> filterChain) {
        this.matcher = matcher;
        this.bidPricer = bidPricer;
        this.cacheProvider = cacheProvider;
        this.filterChain = filterChain;
    }

    @PostConstruct
    public void init() {
        int bufferSize = 262144;
        disruptor = new Disruptor<>(BiddingEvent::new, bufferSize, DaemonThreadFactory.INSTANCE, ProducerType.MULTI, new YieldingWaitStrategy());
        
        disruptor.handleEventsWith((event, sequence, endOfBatch) -> {
            try {
                processBid(event);
            } catch (Exception e) {
                log.error("Error processing bid request in disruptor", e);
                event.sink.success(ResponseEntity.noContent().build());
            }
        });
        
        ringBuffer = disruptor.start();
    }

    @PreDestroy
    public void shutdown() {
        if (disruptor != null) {
            disruptor.shutdown();
        }
    }

    public void publish(BidRequest request, MonoSink<ResponseEntity<BidResponse>> sink) {
        long sequence = ringBuffer.next();
        try {
            BiddingEvent event = ringBuffer.get(sequence);
            event.request = request;
            event.sink = sink;
        } finally {
            ringBuffer.publish(sequence);
        }
    }

    private void processBid(BiddingEvent event) {
        BidRequest request = event.request;
        MonoSink<ResponseEntity<BidResponse>> sink = event.sink;
        String geo = (request.user != null && request.user.geo != null) ? request.user.geo : BiddingConstants.DEFAULT_GEO;
        
        // 1. CPU-intensive matching on Disruptor thread
        List<CampaignIndexData> matchedCampaigns = matcher.match(geo);
        
        // 2. CPU-intensive filter chain evaluation
        Optional<CampaignIndexData> bestCampaignOpt = matchedCampaigns.stream().filter(campaignData -> {
            for (TargetingFilter filter : filterChain) {
                if (!filter.evaluate(request, campaignData)) {
                    return false;
                }
            }
            return true;
        }).findFirst();

        if (bestCampaignOpt.isEmpty()) {
            sink.success(ResponseEntity.noContent().build());
            return;
        }
        
        CampaignIndexData selectedCampaignData = bestCampaignOpt.get();
        String selectedCampaign = selectedCampaignData.campaignId;
        String advertiserId = selectedCampaignData.advertiserId;
        
        if (advertiserId == null) {
            log.warn("No advertiserId found for campaign {}. Skipping bid.", selectedCampaign);
            sink.success(ResponseEntity.noContent().build());
            return;
        }
        
        // 3. Pricing & Cache I/O (handoff to reactive chain)
        Mono<String> pacingMono = cacheProvider.get(String.format(RedisKeyConstants.PREFIX_AD_CAMPAIGN_PACING, selectedCampaign))
            .defaultIfEmpty(BiddingConstants.DEFAULT_PACING_MULTIPLIER_STRING);
        Mono<String> maxBidMono = cacheProvider.get(String.format(RedisKeyConstants.PREFIX_AD_CAMPAIGN_MAX_BID, selectedCampaign))
            .defaultIfEmpty("");
            
        Mono.zip(pacingMono, maxBidMono)
            .subscribe(tuple -> {
                String pacingStr = tuple.getT1();
                String maxBidStr = tuple.getT2();
                
                if (maxBidStr.isEmpty()) {
                    log.warn("No maxBid found in cache for campaign {}. Refusing to bid.", selectedCampaign);
                    sink.success(ResponseEntity.noContent().build());
                    return;
                }
                
                double pacingS = Double.parseDouble(pacingStr);
                BigDecimal maxBid = new BigDecimal(maxBidStr);
                
                PricingStrategyType strategyType = PricingStrategyType.FIRST_PRICE_SHADED_STRATEGY; 
                BigDecimal bidPrice = bidPricer.price(strategyType, selectedCampaign, maxBid, pacingS);
                
                // Mutate the pre-allocated objects instead of creating new ones
                event.preAllocatedBid.id = UUID.randomUUID().toString();
                event.preAllocatedBid.impid = request.imp.get(0).id;
                event.preAllocatedBid.price = bidPrice;
                event.preAllocatedBid.adid = BiddingConstants.PREFIX_AD_ID + selectedCampaign;
                
                event.preAllocatedBid.nurl = String.format(
                    "http://event-tracking-service/api/v1/tracking/impression?campaignId=%s&advertiserId=%s&wp=%s",
                    selectedCampaign, advertiserId, AdMacroConstants.MACRO_AUCTION_PRICE
                );
                event.preAllocatedBid.adm = String.format("<img src='http://cdn.com/sponsored-%s.png' />", selectedCampaign);
                
                event.preAllocatedResponse.id = request.id;
                
                // Construct a new ResponseEntity, but the body objects are pre-allocated
                sink.success(ResponseEntity.ok(event.preAllocatedResponse));
            }, error -> {
                log.error("Error during cache lookup for pricing", error);
                sink.success(ResponseEntity.noContent().build());
            });
    }
}

