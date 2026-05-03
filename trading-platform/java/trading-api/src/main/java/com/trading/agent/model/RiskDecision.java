package com.trading.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskDecision {
    private String symbol;
    private boolean approved;
    private double riskScore;
    private double stopLoss;
    private double target1;
    private double target2;
    private double riskReward;
    private double positionSizePct;

    @Builder.Default
    private List<String> vetoReasons = new ArrayList<>();

    private String notes;
}