package com.trading.logic;

import com.trading.entry.model.EntryAnalysisResult;
import lombok.Builder;
import lombok.Data;
import org.springframework.stereotype.Service;

@Service
public class FinalRecommendationEngine {

    public FinalRecommendationResult decide(FinalRecommendationInput in) {

        String stage = normalize(in.getMarketStage());
        String substage = normalize(in.getMarketSubstage());
        String child = normalize(in.getChildSubstage());
        String childBias = normalize(in.getChildActionBias());

        double score = 50.0;
        StringBuilder reason = new StringBuilder();

        // 1. Stage / substage structural bias
        if (stage.contains("MARKDOWN")) {
            score -= 15;
            reason.append("Markdown risk. ");

            if (child.contains("SPRING")
                    || child.contains("REVERSAL"
            )
                    || child.contains("ACCUMULATION")) {
                score += 10;
                reason.append("Possible reversal inside markdown. ");
            }
        }

        if (stage.contains("DISTRIBUTION")) {
            score -= 20;
            reason.append("Distribution risk. ");
        }

        if (stage.contains("ACCUMULATION")) {
            score += 15;
            reason.append("Accumulation stage bullish base. ");
        }

        if (stage.contains("MARKUP")) {
            score += 10;
            reason.append("Markup stage supports long bias. ");
        }

        if (substage.contains("PRE_BREAKOUT")
                || child.contains("COILED_BREAKOUT")
                || child.contains("VOLUME_EXPANSION")) {
            score += 15;
            reason.append("Pre-breakout/coiled setup detected. ");
        }

        if (substage.contains("EARLY_TREND")) {
            score += 10;
            reason.append("Early trend is favorable. ");
        }

        if (substage.contains("STRONG_TREND")) {
            score += 6;
            reason.append("Strong trend confirmed. ");

            if (child.contains("LATE_STRONG_TREND")
                    || in.getRsi() > 85
                    || in.getAdx() > 40) {
                score -= 20;
                reason.append("Late strong trend or exhaustion risk. ");
            }
        }

        if (substage.contains("EXHAUSTION")
                || substage.contains("OVEREXTENSION")
                || child.contains("LATE_STRONG_TREND")) {
            score -= 25;
            reason.append("Late/overextended trend risk. ");
        }

        // 2. Pure fusion layer: confidence/regime already include RSI/ADX/volume upstream
        score += (in.getConfidenceScore() - 50.0) * 0.50;
        score += (in.getRegimeQualityScore() - 50.0) * 0.50;

        // Weak trend protection (AAPL case from logs)
        if (in.getAdx() < 10) {
            score -= 10;
            reason.append("Weak trend strength due to low ADX. ");
        }

        // Extreme exhaustion protection (QQQ case from logs)
        if (in.getRsi() > 85 && in.getAdx() > 60) {
            score -= 25;
            reason.append("Extreme overbought + high ADX exhaustion risk. ");
        }


        if (in.getConfidenceScore() < 35) {
            return reject("Very low confidence - avoid trade");
        }


        if (in.getConfidenceScore() >= 70) {
            reason.append("Confidence score supports trade. ");
        } else if (in.getConfidenceScore() < 45) {
            reason.append("Low confidence score. ");
        }

        if (in.getRegimeQualityScore() >= 70) {
            reason.append("Regime quality supports trade. ");
        } else if (in.getRegimeQualityScore() < 45) {
            reason.append("Weak regime quality. ");
        }

        // 3. Entry validation and continuous entry-quality contribution
        EntryAnalysisResult entry = in.getEntryResult();

        if (entry != null) {
            if (entry.getInvalidationLevel() != null
                    && entry.getRefinedBuyPrice() != null
                    && entry.getInvalidationLevel() >= entry.getRefinedBuyPrice()) {
                return reject("Invalidation level is above/equal to refined buy price");
            }

            if (entry.getEntryQualityScore() != null && entry.getEntryQualityScore() < 30) {
                return reject("Poor entry quality - avoid trade");
            }

            if (entry.getEntryQualityScore() != null) {
                score += (entry.getEntryQualityScore() - 50.0) * 0.30;
                reason.append("Entry quality fused. ");
            }

            if (entry.getEntryQualityScore() != null && entry.getEntryQualityScore() < 30) {
                return reject("Poor entry quality - avoid trade");
            }

        }

        // 4. Child-substage signal contribution
        score += (in.getChildSignalScore() - 50.0) * 0.25;
        score -= in.getChildRiskPenalty() * 0.25;

        if ("AVOID_CHASE".equals(childBias)) {
            score -= 12;
            reason.append("Avoid chase child bias. ");
        }

        if ("EARLY_LONG".equals(childBias)) {
            score += 8;
            reason.append("Early long child bias. ");
        }

        if ("WAIT_PULLBACK".equals(childBias)) {
            score -= 10;
            reason.append("Wait for pullback child bias. ");
        }

        // 5. Hard gates after score construction
        if (in.getRegimeQualityScore() < 40) {
            return reject("Weak regime - avoid trade");
        }

        if (in.getConfidenceScore() < 35) {
            return reject("Very low confidence - avoid trade");
        }


        // Avoid chasing extreme momentum
        if (in.getRsi() > 90 && in.getAdx() > 70) {
            return reject("Extreme momentum exhaustion - avoid entry");
        }

        score = clamp(score, 0.0, 100.0);

        String finalAction;

        if (score >= 82) {
            finalAction = "STRONG_BUY";
        } else if (score >= 70) {
            finalAction = "BUY";
        } else if (score >= 58) {
            finalAction = "WATCH";
        } else if (score >= 45) {
            finalAction = "WAIT";
        } else {
            finalAction = "AVOID";
        }

        if (in.getConfidenceScore() < 45 && !"AVOID".equals(finalAction)) {
            finalAction = "WAIT";
            reason.append("Downgraded due to low confidence. ");
        }

        String tradeDirection = "LONG";

        if (stage.contains("MARKDOWN") || stage.contains("DISTRIBUTION")) {
            tradeDirection = "SHORT_OR_AVOID";
        }

        return FinalRecommendationResult.builder()
                .finalAction(finalAction)
                .tradeDirection(tradeDirection)
                .finalScore(round(score))
                .decisionReason(reason.toString().trim())
                .build();
    }

    private FinalRecommendationResult reject(String reason) {
        return FinalRecommendationResult.builder()
                .finalAction("AVOID")
                .tradeDirection("SHORT_OR_AVOID")
                .finalScore(0.0)
                .decisionReason(reason)
                .build();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    @Data
    @Builder
    public static class FinalRecommendationInput {
        private String symbol;
        private String marketStage;
        private String marketSubstage;
        private String childSubstage;

        // Kept for future compatibility, but not directly scored here to avoid double counting.
        private double rsi;
        private double adx;
        private double volumeSurgeRatio;

        private double confidenceScore;
        private double regimeQualityScore;

        private double childSignalScore;
        private double childRiskPenalty;
        private String childActionBias;

        private EntryAnalysisResult entryResult;
    }

    @Data
    @Builder
    public static class FinalRecommendationResult {
        private String finalAction;
        private String tradeDirection;
        private double finalScore;
        private String decisionReason;
    }
}