package com.trading.decision;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class FinalActionResult {

    private String finalAction;        // STRONG_BUY / BUY / WATCH / HOLD / SHORT / STRONG_SHORT / AVOID
    private String tradeDirection;     // LONG / SHORT / NEUTRAL
    private String ruleRecommendation;
    private String executionAction;

    private Double finalScore;
    private Double finalProbability;
    private String confidenceGrade;
    private String confidenceBand;

    private String decisionReason;
}