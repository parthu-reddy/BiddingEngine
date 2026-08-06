package com.fooddelivery.advertisement.bidding.cache;

import reactor.core.publisher.Mono;

public class NoOpCacheProvider implements CacheProvider {
    @Override
    public Mono<String> get(String key) {
        return Mono.empty();
    }
}
