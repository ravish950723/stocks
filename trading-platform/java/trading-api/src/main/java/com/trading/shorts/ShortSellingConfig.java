package com.trading.shorts;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "short-selling")
public class ShortSellingConfig {

    private double atrInvalidationBuffer = 0.35;

    private double weakShortThreshold = 0.55;
    private double shortThreshold = 0.68;
    private double strongShortThreshold = 0.78;

    private double minRrRatio = 1.50;
    private double minFeasibility = 0.50;

    private double mediumBorrowFeePct = 5.0;
    private double highBorrowFeePct = 10.0;
    private double maxBorrowFeePct = 25.0;

    private double highShortInterestPct = 20.0;
    private double squeezeRelativeVolumeThreshold = 2.5;

    private double minDollarVolume = 5_000_000;
    private double goodDollarVolume = 25_000_000;
    private double excellentDollarVolume = 100_000_000;

    private Map<String, Double> weights = new HashMap<>();

    public double weight(String key, double defaultValue) {
        if (weights == null || !weights.containsKey(key)) {
            return defaultValue;
        }
        return weights.get(key);
    }
}