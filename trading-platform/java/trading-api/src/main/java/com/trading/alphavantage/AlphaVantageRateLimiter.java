package com.trading.alphavantage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

@Slf4j
@Component
public class AlphaVantageRateLimiter {

    private final long minDelayMillis;
    private final AtomicLong nextAllowedTimeMillis = new AtomicLong(0L);

    public AlphaVantageRateLimiter(
            @Value("${alphavantage.rate-limit.min-delay-ms:15000}") long minDelayMillis
    ) {
        this.minDelayMillis = Math.max(1000L, minDelayMillis);
    }

    public void acquire(String symbol, String endpoint) {
        long now = System.currentTimeMillis();

        while (true) {
            long allowedAt = nextAllowedTimeMillis.get();
            long waitMillis = allowedAt - now;

            if (waitMillis <= 0) {
                long newAllowedAt = now + minDelayMillis;

                if (nextAllowedTimeMillis.compareAndSet(allowedAt, newAllowedAt)) {
                    log.debug(
                            "ALPHA_VANTAGE_RATE_LIMIT_GRANTED symbol={} endpoint={} nextAllowedInMs={}",
                            symbol,
                            endpoint,
                            minDelayMillis
                    );
                    return;
                }
            } else {
                log.info(
                        "ALPHA_VANTAGE_RATE_LIMIT_WAIT symbol={} endpoint={} waitMs={}",
                        symbol,
                        endpoint,
                        waitMillis
                );

                LockSupport.parkNanos(Duration.ofMillis(waitMillis).toNanos());
            }

            now = System.currentTimeMillis();
        }
    }
}