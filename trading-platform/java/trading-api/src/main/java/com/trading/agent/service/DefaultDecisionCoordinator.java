package com.trading.agent.service;

import com.trading.agent.api.AnalystAgent;
import com.trading.agent.api.DecisionCoordinator;
import com.trading.agent.api.RiskAgent;
import com.trading.agent.model.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DefaultDecisionCoordinator implements DecisionCoordinator {

    private final AnalystAgent analystAgent;
    private final RiskAgent riskAgent;

    @Override
    public FinalTradeDecision evaluate(MarketContext marketContext) {
        AnalystDecision analystDecision = analystAgent.act(marketContext);

        RiskContext riskContext = RiskContext.builder()
                .marketContext(marketContext)
                .analystDecision(analystDecision)
                .build();

        RiskDecision riskDecision = riskAgent.act(riskContext);

        if (!riskDecision.isApproved()) {
            return FinalTradeDecision.builder()
                    .symbol(marketContext.getSymbol())
                    .analystScore(analystDecision.getOpportunityScore())
                    .riskScore(riskDecision.getRiskScore())
                    .entryPrice(analystDecision.getCandidateEntry())
                    .stopLoss(riskDecision.getStopLoss())
                    .target1(riskDecision.getTarget1())
                    .target2(riskDecision.getTarget2())
                    .riskReward(riskDecision.getRiskReward())
                    .finalAction("WATCH")
                    .confidenceBand("LOW")
                    .summary("Interesting setup rejected by risk agent: " + String.join(", ", riskDecision.getVetoReasons()))
                    .build();
        }

        if (analystDecision.getOpportunityScore() >= 75 && riskDecision.getRiskScore() >= 70) {
            return FinalTradeDecision.builder()
                    .symbol(marketContext.getSymbol())
                    .analystScore(analystDecision.getOpportunityScore())
                    .riskScore(riskDecision.getRiskScore())
                    .entryPrice(analystDecision.getCandidateEntry())
                    .stopLoss(riskDecision.getStopLoss())
                    .target1(riskDecision.getTarget1())
                    .target2(riskDecision.getTarget2())
                    .riskReward(riskDecision.getRiskReward())
                    .finalAction("BUY")
                    .confidenceBand("HIGH")
                    .summary("Analyst and risk agents approved the setup.")
                    .build();
        } else if (analystDecision.getOpportunityScore() >= 60) {
            return FinalTradeDecision.builder()
                    .symbol(marketContext.getSymbol())
                    .analystScore(analystDecision.getOpportunityScore())
                    .riskScore(riskDecision.getRiskScore())
                    .entryPrice(analystDecision.getCandidateEntry())
                    .stopLoss(riskDecision.getStopLoss())
                    .target1(riskDecision.getTarget1())
                    .target2(riskDecision.getTarget2())
                    .riskReward(riskDecision.getRiskReward())
                    .finalAction("WATCH")
                    .confidenceBand("MEDIUM")
                    .summary("Analyst and risk agents approved the setup.")
                    .build();
        }

        return FinalTradeDecision.builder()
                .symbol(marketContext.getSymbol())
                .analystScore(analystDecision.getOpportunityScore())
                .riskScore(riskDecision.getRiskScore())
                .entryPrice(analystDecision.getCandidateEntry())
                .stopLoss(riskDecision.getStopLoss())
                .target1(riskDecision.getTarget1())
                .target2(riskDecision.getTarget2())
                .riskReward(riskDecision.getRiskReward())
                .finalAction("AVOID")
                .confidenceBand("LOW")
                .summary("Analyst and risk agents approved the setup.")
                .build();
    }
}