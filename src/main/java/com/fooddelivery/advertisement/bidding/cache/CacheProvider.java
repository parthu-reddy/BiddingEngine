package com.fooddelivery.advertisement.bidding.cache;

import reactor.core.publisher.Mono;

public interface CacheProvider {
    Mono<String> get(String key);
}
