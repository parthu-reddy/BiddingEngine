package com.fooddelivery.advertisement.bidding.cache;

import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Primary
@Component
public class MultiTierCacheProvider implements CacheProvider {

    private final CaffeineCacheProvider l1Cache;
    private final RedisCacheProvider l2Cache;

    public MultiTierCacheProvider(ReactiveStringRedisTemplate redisTemplate) {
        this.l2Cache = new RedisCacheProvider(redisTemplate);
        // Fallback loader for L1 cache: query L2
        this.l1Cache = new CaffeineCacheProvider(l2Cache::get);
    }

    @Override
    public Mono<String> get(String key) {
        return l1Cache.get(key)
            .switchIfEmpty(Mono.defer(() -> l2Cache.get(key))); 
    }
}
