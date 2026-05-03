package com.trading.logic;

import org.springframework.stereotype.Service;

/**
 * Refactor target for BusinessLogicService risk/reward code.
 * Keep the public class now so future changes can move calculateDynamicRiskReward()
 * out of BusinessLogicService without changing PipelineService wiring.
 */
@Service
public class RiskRewardEngine {
    public double safeRiskReward(double entry, double stop, double target) {
        if (entry <= 0 || stop <= 0 || target <= 0 || stop >= entry || target <= entry) return 0.0;
        return Math.round(((target - entry) / (entry - stop)) * 100.0) / 100.0;
    }
}
