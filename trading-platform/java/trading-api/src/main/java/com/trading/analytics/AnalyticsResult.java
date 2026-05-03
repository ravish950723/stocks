package com.trading.analytics;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AnalyticsResult {

    private Boolean ninetyDayHit;
    private Double ninetyDayGainPct;
    private Integer daysToPeak;

    private String momentumDecisionReason;
    private Double finalScore;

    private String industry;
    private Double beta;
    private Double relativeStrengthVsSpy;
}