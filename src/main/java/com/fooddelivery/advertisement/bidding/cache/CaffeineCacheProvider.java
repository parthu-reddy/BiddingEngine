package com.fooddelivery.advertisement.bidding.cache;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public class CaffeineCacheProvider implements CacheProvider {
    

    
    // L1 Cache - AsyncLoadingCache prevents thundering herd on L2 by coalescing requests
    private final AsyncLoadingCache<String, String> l1Cache;
    
    public CaffeineCacheProvider(Function<String, Mono<String>> fallbackLoader) {
        this.l1Cache = Caffeine.newBuilder()
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .refreshAfterWrite(10, TimeUnit.MINUTES)
            .maximumSize(100_000)
            .buildAsync((key, executor) -> fallbackLoader.apply(key).toFuture());
    }
    
    @Override
    public Mono<String> get(String key) {
        CompletableFuture<String> future = l1Cache.get(key);
        return Mono.fromFuture(future);
    }
}
