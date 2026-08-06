package com.fooddelivery.advertisement.bidding.cache;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Mono;

public class RedisCacheProvider implements CacheProvider {
    private final ReactiveStringRedisTemplate redisTemplate;

    public RedisCacheProvider(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Mono<String> get(String key) {
        return redisTemplate.opsForValue().get(key);
    }
}
