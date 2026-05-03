package com.trading.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinalTradeDecision {
    private String symbol;
    private String finalAction;
    private double analystScore;
    private double riskScore;
    private double entryPrice;
    private double stopLoss;
    private double target1;
    private double target2;
    private double riskReward;
    private String confidenceBand;
    private String summary;
}