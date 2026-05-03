package com.trading.analytics;

import org.springframework.stereotype.Service;

@Service
public class FinalScoringEngine {

    public double calculateFinalScore(
            Double stageScore,
            Double substageScore,
            Double signalScore,
            Double confidenceScore,
            Double institutionalScore,
            Double relativeStrengthVsSpy,
            Double ninetyDayGainPct
    ) {

        double score =
                0.20 * normalize(stageScore, 100) +
                        0.15 * normalize(substageScore, 100) +
                        0.20 * normalize(signalScore, 100) +
                        0.15 * normalize(confidenceScore, 100) +
                        0.15 * normalize(institutionalScore, 100) +
                        0.10 * normalize(relativeStrengthVsSpy, 20) +
                        0.05 * normalize(ninetyDayGainPct, 30);

        return round(score * 100.0);
    }

    private double normalize(Double value, double max) {
        if (value == null || value.isNaN() || value.isInfinite()) {
            return 0.0;
        }

        return Math.max(0.0, Math.min(1.0, value / max));
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}