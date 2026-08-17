package com.fooddelivery.advertisement.bidding.messaging;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.util.concurrent.TimeUnit;

@Service
public class RedisIdempotencyService {

    private final StringRedisTemplate redisTemplate;

    public RedisIdempotencyService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean isDuplicate(String key) {
        Boolean success = redisTemplate.opsForValue().setIfAbsent(key, "PROCESSED", 7, TimeUnit.DAYS);
        return Boolean.FALSE.equals(success);
    }
}
