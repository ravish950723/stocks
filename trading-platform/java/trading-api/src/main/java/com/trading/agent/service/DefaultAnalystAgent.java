package com.trading.agent.service;

import com.trading.agent.api.AnalystAgent;
import com.trading.agent.model.AnalystDecision;
import com.trading.agent.model.MarketContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class DefaultAnalystAgent implements AnalystAgent {

    @Override
    public AnalystDecision act(MarketContext ctx) {
        double score = 0.0;
        List<String> reasons = new ArrayList<>();

        if (ctx.getLastPrice() > ctx.getEma50()) {
            score += 15;
            reasons.add("PRICE_ABOVE_50EMA");
        }
        if (ctx.getAdx() >= 20) {
            score += 12;
            reasons.add("ADX_TREND_CONFIRMED");
        }
        if (ctx.getVolume() > ctx.getAvgVolume() * 1.2) {
            score += 10;
            reasons.add("VOLUME_SURGE");
        }
        if (ctx.getModelProbability() >= 0.70) {
            score += 18;
            reasons.add("MODEL_PROBABILITY_HIGH");
        }
        if ("ADVANCING".equalsIgnoreCase(ctx.getMarketStage()) || "MARKUP".equalsIgnoreCase(ctx.getMarketStage())) {
            score += 10;
            reasons.add("STAGE_SUPPORTIVE");
        }
        if (ctx.getSectorStrength() >= 70) {
            score += 8;
            reasons.add("SECTOR_OUTPERFORMANCE");
        }
        if (ctx.getInstitutionalFlowScore() >= 65) {
            score += 8;
            reasons.add("INSTITUTIONAL_FLOW_SUPPORTIVE");
        }
        if (ctx.getDetectedPatterns() != null && ctx.getDetectedPatterns().contains("BREAKOUT")) {
            score += 10;
            reasons.add("BREAKOUT_PATTERN");
        }
        if (ctx.getDetectedPatterns() != null && ctx.getDetectedPatterns().contains("PULLBACK")) {
            score += 8;
            reasons.add("PULLBACK_PATTERN");
        }
        if (ctx.getWarnings() != null && !ctx.getWarnings().isEmpty()) {
            score -= ctx.getWarnings().size() * 5.0;
            reasons.add("WARNING_PENALTY_APPLIED");
        }

        score = Math.max(0.0, Math.min(score, 100.0));

        String bias = score >= 65 ? "BULLISH" : score <= 35 ? "BEARISH" : "NEUTRAL";
        String setupType = detectSetupType(ctx);
        double confidence = Math.min(score / 100.0, 0.95);
        double candidateEntry = computeCandidateEntry(ctx, setupType);

        return AnalystDecision.builder()
                .symbol(ctx.getSymbol())
                .bias(bias)
                .setupType(setupType)
                .opportunityScore(score)
                .confidence(confidence)
                .candidateEntry(candidateEntry)
                .reasons(reasons)
                .thesis(buildThesis(bias, setupType, reasons))
                .build();
    }

    private String detectSetupType(MarketContext ctx) {
        if (ctx.getDetectedPatterns() != null && ctx.getDetectedPatterns().contains("BREAKOUT")) {
            return "BREAKOUT";
        }
        if (ctx.getDetectedPatterns() != null && ctx.getDetectedPatterns().contains("PULLBACK")) {
            return "PULLBACK";
        }
        return "TREND_CONTINUATION";
    }

    private double computeCandidateEntry(MarketContext ctx, String setupType) {
        if ("PULLBACK".equalsIgnoreCase(setupType) && ctx.getEma20() > 0) {
            return ctx.getEma20();
        }
        if ("BREAKOUT".equalsIgnoreCase(setupType)) {
            return ctx.getLastPrice() * 1.01;
        }
        return ctx.getLastPrice();
    }

    private String buildThesis(String bias, String setupType, List<String> reasons) {
        return bias + " " + setupType + " setup backed by: " + String.join(", ", reasons);
    }
}