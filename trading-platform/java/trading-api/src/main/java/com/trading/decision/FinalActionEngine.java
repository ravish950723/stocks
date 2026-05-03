package com.trading.decision;

import com.trading.decision.FinalActionResult;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class FinalActionEngine {

    public FinalActionResult decide(Map<String, Object> row) {

        boolean isEtf = getBoolean(row, "is_etf");

        double finalScore = firstAvailable(row, "Final Score", "final_score");
        double finalProbability = getDouble(row, "final_probability");
        double modelProbability = getDouble(row, "model_probability");
        double ruleProbability = getDouble(row, "rule_probability");

        double confidenceScore = firstAvailable(row, "Confidence Score", "confidence_score");
        double signalScore = firstAvailable(row, "Signal Score", "signal_score");
        double institutionalScore = firstAvailable(row, "Institutional Score", "institutional_score");

        double relativeStrengthVsSpy = firstAvailable(row, "Relative Strength vs SPY", "relative_strength_vs_spy");
        double fundamentalBoost = firstAvailable(row, "FUNDAMENTAL_BOOST", "fundamental_boost");

        if (isEtf) {
            fundamentalBoost = 0.0;
        }

        double sentimentScore = firstAvailable(row, "News Sentiment Score", "news_sentiment_score");
        double sentimentConfidence = firstAvailable(row, "Sentiment Confidence", "sentiment_confidence");
        double entryQualityScore = firstAvailable(row, "Entry Quality Score", "entry_quality_score");

        double shortScore = firstAvailable(row, "SHORT_SCORE", "short_score");
        double shortRr = firstAvailable(row, "SHORT_RR_RATIO", "short_rr_ratio");
        double shortFeasibility = firstAvailable(row, "SHORT_FEASIBILITY", "short_feasibility");

        Integer daysToPeak = getInteger(row, "Days to Peak", "days_to_peak");

        String recommendation = firstString(row, "recommendation", "rule_recommendation", "Rule Recommendation");
        String agentAction = firstString(row, "agent_final_action");
        String shortVerdict = firstString(row, "SHORT_VERDICT", "short_verdict");
        String stage = firstString(row, "market_stage", "Market Stage");
        String substage = firstString(row, "market_substage", "Market Sub-Stage");
        String childSubstage = firstString(row, "child_substage", "Child Substage");
        String sentimentLabel = firstString(row, "Sentiment Label", "sentiment_label");

        double closePrice = firstAvailable(row, "Close", "close", "last_price", "Last Price", "Current Price", "current_price");
        double ema21 = firstAvailable(row, "EMA21", "ema21", "EMA_21", "ema_21");
        double ema50 = firstAvailable(row, "EMA50", "ema50", "EMA_50", "ema_50", "50DMA", "50 DMA");
        double vwap = firstAvailable(row, "VWAP", "vwap");
        double rsi = firstAvailable(row, "RSI", "rsi");

        double childContradictionPenalty = firstAvailable(row, "child_contradiction_penalty");
        double bestChildConfidence = firstAvailable(row, "best_child_confidence");
        double bullishChildScore = firstAvailable(row, "bullish_child_score");
        double bearishChildScore = firstAvailable(row, "bearish_child_score");

        double fusionScore = calculateFusionScore(
                isEtf,
                finalScore,
                finalProbability,
                modelProbability,
                ruleProbability,
                confidenceScore,
                signalScore,
                institutionalScore,
                relativeStrengthVsSpy,
                fundamentalBoost,
                sentimentScore,
                sentimentConfidence,
                entryQualityScore,
                daysToPeak,
                stage,
                substage
        );

        boolean bullishStage = isAny(stage, "MARKUP", "ACCUMULATION");
        boolean bearishStage = isAny(stage, "MARKDOWN", "DISTRIBUTION");

        boolean breakoutSetup =
                isAny(substage, "PRE_BREAKOUT", "BREAKOUT", "EARLY_TREND")
                        || upper(childSubstage).contains("BREAKOUT")
                        || upper(childSubstage).contains("COILED");

        boolean exhaustedOrOverextended =
                upper(substage).contains("EXHAUSTION")
                        || upper(substage).contains("OVEREXTENSION")
                        || upper(childSubstage).contains("EXHAUSTION")
                        || upper(childSubstage).contains("OVEREXTENSION")
                        || upper(childSubstage).contains("LATE")
                        || rsi >= 82.0;

        boolean childConflict =
                childContradictionPenalty >= 12.0
                        || (bullishChildScore > 0.0 && bearishChildScore > 0.0
                            && Math.min(bullishChildScore, bearishChildScore) / Math.max(bullishChildScore, bearishChildScore) >= 0.35);

        boolean bearishChildDominance =
                bearishChildScore > 0.0
                        && bearishChildScore >= bullishChildScore * 1.15;

        boolean belowEma21 = closePrice > 0.0 && ema21 > 0.0 && closePrice < ema21;
        boolean belowEma50 = closePrice > 0.0 && ema50 > 0.0 && closePrice < ema50;
        boolean belowVwap = closePrice > 0.0 && vwap > 0.0 && closePrice < vwap;

        boolean longTechnicalBlock =
                belowEma50
                        || (belowEma21 && belowVwap)
                        || (belowEma21 && bearishChildDominance);

        boolean longBlocked =
                bearishStage
                        || childConflict
                        || bearishChildDominance
                        || exhaustedOrOverextended
                        || longTechnicalBlock;

        boolean shortTechnicalSupport =
                bearishStage
                        || bearishChildDominance
                        || belowEma50
                        || (belowEma21 && belowVwap)
                        || exhaustedOrOverextended;

        boolean strongLong =
                !longBlocked
                        && bestChildConfidence >= 0.55
                        && bullishStage
                        && bullishChildScore > bearishChildScore * 1.5
                        && (fusionScore >= 88
                            || finalProbability >= 0.88
                            || modelProbability >= 0.88
                            || recommendation.equalsIgnoreCase("STRONG_BUY"));

        boolean longCandidate =
                !longBlocked
                        && bullishStage
                        && bestChildConfidence >= 0.40
                        && bullishChildScore >= bearishChildScore
                        && (fusionScore >= 74
                            || finalProbability >= 0.74
                            || modelProbability >= 0.74
                            || ruleProbability >= 0.74
                            || recommendation.equalsIgnoreCase("BUY")
                            || recommendation.equalsIgnoreCase("EARLY_BUY"));

        boolean strongShort =
                shortVerdict.equalsIgnoreCase("STRONG_SHORT")
                        && shortTechnicalSupport
                        && bearishChildScore >= bullishChildScore
                        && shortScore >= 78
                        && shortRr >= 1.6
                        && shortFeasibility >= 65;

        boolean shortCandidate =
                (shortVerdict.equalsIgnoreCase("SHORT") || shortVerdict.equalsIgnoreCase("WEAK_SHORT"))
                        && shortTechnicalSupport
                        && bearishChildScore >= bullishChildScore
                        && shortScore >= 65
                        && shortRr >= 1.4
                        && shortFeasibility >= 55;

        if (isEtf) {
            strongLong = strongLong
                    && relativeStrengthVsSpy >= 2
                    && signalScore >= 60
                    && fusionScore >= 82;

            longCandidate = longCandidate
                    && relativeStrengthVsSpy >= 0
                    && signalScore >= 50;

            shortCandidate = shortCandidate
                    && bearishStage
                    && relativeStrengthVsSpy < 0;
        } else {
            strongLong = strongLong
                    && (fundamentalBoost >= 20 || confidenceScore >= 72)
                    && signalScore >= 55;

            longCandidate = longCandidate
                    && (fundamentalBoost >= 5 || confidenceScore >= 60 || signalScore >= 60)
                    && sentimentScore >= -0.20;
        }

        if (breakoutSetup && bullishStage && !longBlocked) {
            longCandidate = longCandidate || (fusionScore >= 76 && signalScore >= 55 && bestChildConfidence >= 0.45);

            if (fusionScore >= 90 && signalScore >= 65 && bestChildConfidence >= 0.60) {
                strongLong = true;
            }
        }

        // Hard production guard: no BUY/STRONG_BUY is allowed when the stage/child/technical context is bearish.
        if (longBlocked) {
            strongLong = false;
            longCandidate = false;
        }

        if (bearishStage && !shortCandidate && !strongShort && fusionScore >= 72) {
            // Bearish stages with high score are usually disagreement / transition zones, not clean long entries.
            // Force WATCH unless the short engine confirms.
            fusionScore = Math.min(fusionScore, 71.0);
        }

        boolean avoid =
                (fusionScore < 45
                        && finalProbability < 0.55
                        && modelProbability < 0.55
                        && shortScore < 55)
                        || (bearishStage && fusionScore < 65)
                        || (bearishChildDominance && !strongShort && !shortCandidate)
                        || (longTechnicalBlock && !strongShort && !shortCandidate)
                        || (childConflict && fusionScore < 78)
                        || (exhaustedOrOverextended && !strongShort);

        String finalAction;
        String tradeDirection;

        if (strongShort) {
            finalAction = "STRONG_SHORT";
            tradeDirection = "SHORT";
        } else if (shortCandidate) {
            finalAction = "SHORT";
            tradeDirection = "SHORT";
        } else if (strongLong) {
            finalAction = "STRONG_BUY";
            tradeDirection = "LONG";
        } else if (longCandidate) {
            finalAction = "BUY";
            tradeDirection = "LONG";
        } else if (avoid) {
            finalAction = "AVOID";
            tradeDirection = "NEUTRAL";
        } else if (agentAction.equalsIgnoreCase("HOLD")) {
            finalAction = "HOLD";
            tradeDirection = "NEUTRAL";
        } else {
            finalAction = "WATCH";
            tradeDirection = "NEUTRAL";
        }

        String confidenceGrade = confidenceGrade(
                confidenceScore,
                institutionalScore,
                fusionScore,
                finalProbability
        );

        String confidenceBand = confidenceBand(finalProbability, fusionScore);

        String reason = buildReason(
                finalAction,
                isEtf,
                breakoutSetup,
                stage,
                substage,
                childSubstage,
                fusionScore,
                finalScore,
                finalProbability,
                modelProbability,
                ruleProbability,
                confidenceScore,
                signalScore,
                institutionalScore,
                relativeStrengthVsSpy,
                fundamentalBoost,
                sentimentScore,
                sentimentLabel,
                entryQualityScore,
                daysToPeak,
                closePrice,
                ema21,
                ema50,
                vwap,
                rsi,
                longBlocked,
                longTechnicalBlock,
                bearishChildDominance,
                shortVerdict,
                shortScore,
                shortRr,
                shortFeasibility,
                childContradictionPenalty,
                bestChildConfidence,
                bullishChildScore,
                bearishChildScore
        );

        return FinalActionResult.builder()
                .finalAction(finalAction)
                .tradeDirection(tradeDirection)
                .ruleRecommendation(recommendation)
                .executionAction(finalAction)
                .finalScore(round(fusionScore))
                .finalProbability(round(finalProbability))
                .confidenceGrade(confidenceGrade)
                .confidenceBand(confidenceBand)
                .decisionReason(reason)
                .build();
    }

    private double calculateFusionScore(
            boolean isEtf,
            double finalScore,
            double finalProbability,
            double modelProbability,
            double ruleProbability,
            double confidenceScore,
            double signalScore,
            double institutionalScore,
            double relativeStrengthVsSpy,
            double fundamentalBoost,
            double sentimentScore,
            double sentimentConfidence,
            double entryQualityScore,
            Integer daysToPeak,
            String stage,
            String substage
    ) {
        double probabilityScore = Math.max(finalProbability, Math.max(modelProbability, ruleProbability)) * 100.0;
        double technicalScore = averageNonZero(finalScore, confidenceScore, signalScore, entryQualityScore);
        double institutional = normalize100(institutionalScore) * 100.0;
        double rsScore = clamp(50.0 + relativeStrengthVsSpy, 0.0, 100.0);
        double sentimentComponent = clamp((sentimentScore + 1.0) * 50.0, 0.0, 100.0);
        double sentimentWeighted = 0.70 * sentimentComponent + 0.30 * sentimentConfidence;

        double daysScore;

        if (daysToPeak == null) {
            daysScore = 70.0;
        } else if (daysToPeak <= 20) {
            daysScore = 90.0;
        } else if (daysToPeak <= 60) {
            daysScore = 70.0;
        } else {
            daysScore = clamp(100.0 - daysToPeak, 20.0, 60.0);
        }

        double stageScore = stageBonus(stage, substage);

        double score;

        if (isEtf) {
            score =
                    0.30 * technicalScore
                            + 0.25 * rsScore
                            + 0.20 * probabilityScore
                            + 0.10 * sentimentWeighted
                            + 0.10 * stageScore
                            + 0.05 * daysScore;
        } else {
            score =
                    0.25 * technicalScore
                            + 0.20 * probabilityScore
                            + 0.20 * fundamentalBoost
                            + 0.15 * institutional
                            + 0.10 * sentimentWeighted
                            + 0.05 * rsScore
                            + 0.05 * daysScore;
        }

        if (!isEtf && upper(substage).contains("PRE_BREAKOUT")) {
            score += 5.0;
        }

        return clamp(score, 0.0, 100.0);
    }

    private double stageBonus(String stage, String substage) {
        String s = upper(stage);
        String sub = upper(substage);

        double score = 50.0;

        if ("MARKUP".equals(s)) score += 25.0;
        if ("ACCUMULATION".equals(s)) score += 15.0;
        if ("DISTRIBUTION".equals(s)) score -= 20.0;
        if ("MARKDOWN".equals(s)) score -= 30.0;

        if (sub.contains("BREAKOUT")) score += 10.0;
        if (sub.contains("STRONG_TREND")) score += 10.0;
        if (sub.contains("EARLY_TREND")) score += 8.0;
        if (sub.contains("PRE_BREAKOUT")) score += 7.0;
        if (sub.contains("EXHAUSTION")) score -= 10.0;
        if (sub.contains("OVEREXTENSION")) score -= 10.0;

        return clamp(score, 0.0, 100.0);
    }

    private String buildReason(
            String finalAction,
            boolean isEtf,
            boolean breakoutSetup,
            String stage,
            String substage,
            String childSubstage,
            double fusionScore,
            double finalScore,
            double finalProbability,
            double modelProbability,
            double ruleProbability,
            double confidenceScore,
            double signalScore,
            double institutionalScore,
            double relativeStrengthVsSpy,
            double fundamentalBoost,
            double sentimentScore,
            String sentimentLabel,
            double entryQualityScore,
            Integer daysToPeak,
            double closePrice,
            double ema21,
            double ema50,
            double vwap,
            double rsi,
            boolean longBlocked,
            boolean longTechnicalBlock,
            boolean bearishChildDominance,
            String shortVerdict,
            double shortScore,
            double shortRr,
            double shortFeasibility,
            double childContradictionPenalty,
            double bestChildConfidence,
            double bullishChildScore,
            double bearishChildScore
    ) {
        return "FinalAction=" + finalAction
                + " | IsETF=" + isEtf
                + " | BreakoutSetup=" + breakoutSetup
                + " | Stage=" + stage
                + " | Substage=" + substage
                + " | ChildSubstage=" + childSubstage
                + " | FusionScore=" + round(fusionScore)
                + " | FinalScoreRaw=" + round(finalScore)
                + " | FinalProbability=" + round(finalProbability)
                + " | MLProbability=" + round(modelProbability)
                + " | RuleProbability=" + round(ruleProbability)
                + " | ConfidenceScore=" + round(confidenceScore)
                + " | SignalScore=" + round(signalScore)
                + " | InstitutionalScore=" + round(institutionalScore)
                + " | RS_vs_SPY=" + round(relativeStrengthVsSpy)
                + " | FundamentalBoost=" + round(fundamentalBoost)
                + " | SentimentScore=" + round(sentimentScore)
                + " | SentimentLabel=" + sentimentLabel
                + " | EntryQuality=" + round(entryQualityScore)
                + " | DaysToPeak=" + (daysToPeak == null ? "NA" : daysToPeak)
                + " | Close=" + round(closePrice)
                + " | EMA21=" + round(ema21)
                + " | EMA50=" + round(ema50)
                + " | VWAP=" + round(vwap)
                + " | RSI=" + round(rsi)
                + " | LongBlocked=" + longBlocked
                + " | LongTechnicalBlock=" + longTechnicalBlock
                + " | BearishChildDominance=" + bearishChildDominance
                + " | ShortVerdict=" + shortVerdict
                + " | ShortScore=" + round(shortScore)
                + " | ShortRR=" + round(shortRr)
                + " | ShortFeasibility=" + round(shortFeasibility)
                + " | ChildConflictPenalty=" + round(childContradictionPenalty)
                + " | BestChildConfidence=" + round(bestChildConfidence)
                + " | BullishChildScore=" + round(bullishChildScore)
                + " | BearishChildScore=" + round(bearishChildScore);
    }

    private String confidenceGrade(
            double confidenceScore,
            double institutionalScore,
            double fusionScore,
            double finalProbability
    ) {
        double composite =
                0.30 * normalize100(confidenceScore)
                        + 0.25 * normalize100(institutionalScore)
                        + 0.30 * normalize100(fusionScore)
                        + 0.15 * clamp01(finalProbability);

        double score = composite * 100.0;

        if (score >= 85) return "A+";
        if (score >= 75) return "A";
        if (score >= 65) return "B";
        if (score >= 50) return "C";
        return "D";
    }

    private String confidenceBand(double finalProbability, double fusionScore) {
        if (finalProbability >= 0.80 || fusionScore >= 80) return "HIGH";
        if (finalProbability >= 0.65 || fusionScore >= 65) return "MEDIUM";
        if (finalProbability >= 0.50 || fusionScore >= 50) return "LOW";
        return "VERY_LOW";
    }

    private double averageNonZero(double... values) {
        double sum = 0.0;
        int count = 0;

        for (double value : values) {
            if (value != 0.0) {
                sum += normalize100(value) * 100.0;
                count++;
            }
        }

        return count == 0 ? 0.0 : sum / count;
    }

    private boolean isAny(String value, String... options) {
        String v = upper(value);
        for (String option : options) {
            if (v.equals(upper(option))) {
                return true;
            }
        }
        return false;
    }

    private boolean getBoolean(Map<String, Object> row, String key) {
        Object value = row.get(key);

        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.doubleValue() != 0.0;
        if (value == null) return false;

        String s = String.valueOf(value).trim();
        return "true".equalsIgnoreCase(s)
                || "yes".equalsIgnoreCase(s)
                || "y".equalsIgnoreCase(s)
                || "1".equals(s);
    }

    private Integer getInteger(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            Object value = row.get(key);

            if (value instanceof Number n) {
                return n.intValue();
            }

            if (value instanceof String s) {
                try {
                    if (!s.isBlank()) {
                        return Integer.parseInt(s.trim());
                    }
                } catch (Exception ignored) {
                    // ignore invalid number
                }
            }
        }
        return null;
    }

    private double firstAvailable(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            double value = getDouble(row, key);
            if (value != 0.0) {
                return value;
            }
        }
        return 0.0;
    }

    private String firstString(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            String value = getString(row, key);
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private double getDouble(Map<String, Object> row, String key) {
        Object value = row.get(key);

        if (value instanceof Number number) {
            return number.doubleValue();
        }

        if (value instanceof String str) {
            try {
                if (str.isBlank()) return 0.0;
                return Double.parseDouble(str.trim());
            } catch (Exception ignored) {
                return 0.0;
            }
        }

        return 0.0;
    }

    private String getString(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    private double normalize100(double value) {
        if (value > 1.0) return clamp(value / 100.0, 0.0, 1.0);
        return clamp(value, 0.0, 1.0);
    }

    private double clamp01(double value) {
        return clamp(value, 0.0, 1.0);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}