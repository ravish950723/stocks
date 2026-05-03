package com.trading.logic;

import org.springframework.stereotype.Service;

import java.util.Map;

/** Refactor target for stage + fundamentals + sentiment fusion. */
@Service
public class StageFusionEngine {
    public boolean isBullish(Map<String, Object> row) {
        String stage = String.valueOf(row.getOrDefault("market_stage", "")).toUpperCase();
        return stage.contains("MARKUP") || stage.contains("ACCUMULATION");
    }
}
