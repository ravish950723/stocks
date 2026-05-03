package com.trading.agent.service;

import com.trading.agent.api.RiskAgent;
import com.trading.agent.model.AnalystDecision;
import com.trading.agent.model.MarketContext;
import com.trading.agent.model.RiskContext;
import com.trading.agent.model.RiskDecision;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@Primary
@Data
public class DefaultRiskAgent implements RiskAgent {

    @Override
    public RiskDecision act(RiskContext input) {

        MarketContext ctx = input.getMarketContext();
        AnalystDecision analyst = input.getAnalystDecision();

        List<String> vetoReasons = new ArrayList<>();

        double entry = analyst.getCandidateEntry();
        double atr = Math.max(ctx.getAtr(), 0.01);

        double stopLoss = entry - (1.5 * atr);
        double target1 = entry + (2.0 * atr);
        double target2 = entry + (3.5 * atr);

        double risk = Math.max(entry - stopLoss, 0.01);
        double reward = Math.max(target1 - entry, 0.01);

        double rr = reward / risk;

        boolean approved = true;

        if (rr < 1.80) {
            approved = false;
            vetoReasons.add("LOW_RR");
        }

        if ("MARKDOWN".equalsIgnoreCase(ctx.getMarketStage())
                || "DISTRIBUTION".equalsIgnoreCase(ctx.getMarketStage())) {
            approved = false;
            vetoReasons.add("BAD_STAGE");
        }

        if (ctx.getSubstageConfidence() < 0.60) {
            approved = false;
            vetoReasons.add("LOW_SUBSTAGE_CONFIDENCE");
        }

        double score = Math.min(100, rr * 30.0);
        score -= vetoReasons.size() * 10.0;
        score = Math.max(0, score);

        log.debug("RISK_AGENT symbol={} approved={} rr={}", ctx.getSymbol(), approved, rr);

        return RiskDecision.builder()
                .symbol(ctx.getSymbol())
                .approved(approved)
                .riskScore(score)
                .stopLoss(stopLoss)
                .target1(target1)
                .target2(target2)
                .riskReward(rr)
                .positionSizePct(approved ? 0.03 : 0.00)
                .vetoReasons(vetoReasons)
                .notes(approved ? "PASSED" : "REJECTED")
                .build();
    }


    private double computeStopLoss(MarketContext ctx, AnalystDecision analyst) {
        return analyst.getCandidateEntry() - (1.5 * Math.max(ctx.getAtr(), 0.01));
    }

    private double computeTarget1(MarketContext ctx, AnalystDecision analyst) {
        return analyst.getCandidateEntry() + (2.0 * Math.max(ctx.getAtr(), 0.01));
    }

    private double computeTarget2(MarketContext ctx, AnalystDecision analyst) {
        return analyst.getCandidateEntry() + (3.5 * Math.max(ctx.getAtr(), 0.01));
    }

    private double computePositionSize(double riskReward, List<String> vetoReasons) {
        double base = 0.03;
        if (vetoReasons.contains("HIGH_VOLATILITY_REDUCE_SIZE")) {
            base *= 0.5;
        }
        if (riskReward >= 2.5) {
            base += 0.01;
        }
        return Math.min(base, 0.05);
    }

    private double computeRiskScore(double riskReward, List<String> vetoReasons, boolean approved) {
        double score = Math.min(riskReward * 25.0, 100.0);
        score -= vetoReasons.size() * 8.0;
        if (!approved) {
            score -= 10.0;
        }
        return Math.max(0.0, Math.min(score, 100.0));
    }
}