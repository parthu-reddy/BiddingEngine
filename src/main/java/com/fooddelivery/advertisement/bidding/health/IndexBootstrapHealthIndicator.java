package com.fooddelivery.advertisement.bidding.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("indexBootstrapHealthIndicator")
public class IndexBootstrapHealthIndicator implements HealthIndicator {
    private volatile boolean bootstrapped = false;

    public void setBootstrapped(boolean bootstrapped) {
        this.bootstrapped = bootstrapped;
    }

    @Override
    public Health health() {
        if (bootstrapped) {
            return Health.up().withDetail("index", "bootstrapped").build();
        }
        return Health.down().withDetail("index", "not-bootstrapped").build();
    }
}
