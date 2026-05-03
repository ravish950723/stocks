package com.trading.logic;

import lombok.Builder;
import lombok.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * ChildScoringEngine v2
 *
 * Purpose:
 * - Refines raw child-substage score from StageEngine.
 * - Penalizes weak / failed / no-volume / exhaustion / contradictory children.
 * - Boosts children aligned with selected stage + selected parent substage.
 * - Produces explainable adjustment fields for Excel/debug logs.
 *
 * This class is intentionally independent from YAML loading. StageEngine still remains
 * the YAML reader/scorer. This class only applies final arbitration/tie-break rules.
 */
@Component
public class ChildScoringEngine {

    public ChildScoreDecision refine(ChildScoreInput in) {
        if (in == null) {
            return ChildScoreDecision.empty();
        }

        String stage = upper(in.getStage());
        String parentSubstage = upper(in.getParentSubstage());
        String child = upper(in.getChildSubstage());
        String qualifiedKey = nonBlank(in.getQualifiedKey())
                ? in.getQualifiedKey()
                : stage + "." + parentSubstage + "." + child;

        double rawScore = safe(in.getRawScore());
        double adjusted = rawScore;

        Map<String, Double> adjustments = new LinkedHashMap<>();

        // 1) Parent/substage alignment: selected parent should not lose to unrelated equal-score child.
        if (equalsAny(parentSubstage, in.getWinningSubstage())) {
            add(adjustments, "WINNING_SUBSTAGE_ALIGNMENT", 1.25);
        } else {
            add(adjustments, "NON_WINNING_SUBSTAGE_PENALTY", -0.75);
        }

        // 2) Strong bullish continuation / clean structure boosts.
        if (isBullishStage(stage)) {
            if (containsAny(child, "EMA_STACK", "RSI_50_HOLD", "TREND_ACCELERATION", "ORDERLY_CONTINUATION",
                    "CONFIRMED_HIGHER_LOW", "HL_LOW_RISK_ENTRY", "CLEAN_BREAKOUT", "SUCCESSFUL_RETEST")) {
                add(adjustments, "BULLISH_STRUCTURE_BOOST", 1.50);
            }
            if (containsAny(parentSubstage, "STRONG_TREND", "TREND_CONTINUATION", "HIGHER_LOW", "BREAKOUT", "RETEST")) {
                add(adjustments, "BULLISH_PARENT_BOOST", 0.75);
            }
        }

        // 3) Bearish structure boosts only when stage is bearish.
        if (isBearishStage(stage)) {
            if (containsAny(child, "LOWER_LOW", "LOWER_HIGH", "BREAKDOWN", "SUPPORT_FAILURE",
                    "MOMENTUM_DROP", "ORDERLY_CONTINUATION", "FAILED_RETEST")) {
                add(adjustments, "BEARISH_STRUCTURE_BOOST", 1.50);
            }
        }

        // 4) Weak / failed / no-volume children are warnings; do not allow them to win by tie.
        if (containsAny(child, "WEAK", "NO_VOLUME", "FAILED", "LOST_VWAP")) {
            add(adjustments, "WEAK_OR_FAILED_CHILD_PENALTY", -2.25);
        }

        // 5) Overextension/exhaustion children should not become BUY-style leaders unless score is very strong.
        if (containsAny(parentSubstage, "OVEREXTENSION", "EXHAUSTION", "CLIMAX")
                || containsAny(child, "OVEREXTENDED", "EXHAUSTION", "BLOWOFF", "FINAL_PUSH")) {
            add(adjustments, "EXHAUSTION_RISK_PENALTY", -1.75);
        }

        // 6) Distribution/markdown children under MARKUP are suspicious mixed-regime signals.
        if (isBullishStage(stage) && containsAny(child, "MARKDOWN", "DISTRIBUTION")) {
            add(adjustments, "CROSS_REGIME_CHILD_PENALTY", -2.50);
        }

        // 7) Default child is a fallback only.
        if ("DEFAULT".equals(child) || child.endsWith("_DEFAULT")) {
            add(adjustments, "DEFAULT_CHILD_PENALTY", -1.50);
        }

        // 8) Market context based gates.
        double rsi = safe(in.getRsi());
        double adx = safe(in.getAdx());
        double volumeSurgeRatio = safe(in.getVolumeSurgeRatio());

        if (isBullishStage(stage)) {
            if (rsi >= 50 && rsi <= 70) {
                add(adjustments, "RSI_BULLISH_RANGE_BOOST", 0.65);
            } else if (rsi < 45) {
                add(adjustments, "RSI_WEAK_FOR_MARKUP_PENALTY", -0.85);
            } else if (rsi >= 75) {
                add(adjustments, "RSI_OVERHEATED_PENALTY", -0.85);
            }

            if (adx >= 20) {
                add(adjustments, "ADX_TREND_CONFIRMATION_BOOST", 0.65);
            }

            if (volumeSurgeRatio >= 1.20) {
                add(adjustments, "VOLUME_CONFIRMATION_BOOST", 0.75);
            } else if (containsAny(child, "BREAKOUT", "SURGE", "ACCELERATION") && volumeSurgeRatio < 1.0) {
                add(adjustments, "NO_VOLUME_FOR_BREAKOUT_PENALTY", -1.25);
            }
        }

        // 9) Explicit metadata bias from child_substages.yml if available.
        String actionBias = upper(in.getActionBias());
        String riskProfile = upper(in.getRiskProfile());
        if (containsAny(actionBias, "BUY", "WATCH_BUY", "SCALE_IN")) {
            add(adjustments, "ACTION_BIAS_BULLISH_BOOST", 0.80);
        }
        if (containsAny(actionBias, "AVOID", "SELL", "SHORT", "REDUCE")) {
            add(adjustments, "ACTION_BIAS_RISK_PENALTY", -1.00);
        }
        if (containsAny(riskProfile, "HIGH", "VERY_HIGH")) {
            add(adjustments, "HIGH_RISK_PROFILE_PENALTY", -0.60);
        }

        for (double v : adjustments.values()) {
            adjusted += v;
        }

        adjusted = clamp(adjusted, 0.0, 20.0);

        return ChildScoreDecision.builder()
                .qualifiedKey(qualifiedKey)
                .stage(stage)
                .parentSubstage(parentSubstage)
                .childSubstage(child)
                .rawScore(round(rawScore))
                .adjustedScore(round(adjusted))
                .adjustments(adjustments)
                .reason(toReason(adjustments))
                .build();
    }

    public Map<String, Double> refineAll(Map<String, Double> rawScores,
                                         String stage,
                                         String winningSubstage,
                                         ChildMetaResolver metaResolver,
                                         MarketInputs marketInputs) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (rawScores == null || rawScores.isEmpty()) {
            return out;
        }

        for (Map.Entry<String, Double> e : rawScores.entrySet()) {
            String key = e.getKey();
            ParsedChildKey parsed = parse(key, stage);
            ChildMeta meta = metaResolver == null ? ChildMeta.empty() : metaResolver.resolve(parsed.stage, parsed.parentSubstage, parsed.childSubstage);

            ChildScoreDecision decision = refine(ChildScoreInput.builder()
                    .stage(parsed.stage)
                    .winningSubstage(winningSubstage)
                    .parentSubstage(parsed.parentSubstage)
                    .childSubstage(parsed.childSubstage)
                    .qualifiedKey(key)
                    .rawScore(e.getValue())
                    .actionBias(meta.actionBias)
                    .riskProfile(meta.riskProfile)
                    .rsi(marketInputs == null ? 0.0 : marketInputs.rsi)
                    .adx(marketInputs == null ? 0.0 : marketInputs.adx)
                    .volumeSurgeRatio(marketInputs == null ? 0.0 : marketInputs.volumeSurgeRatio)
                    .build());

            out.put(key, decision.getAdjustedScore());
        }

        return out;
    }

    public ParsedChildKey parse(String qualifiedKey, String fallbackStage) {
        String key = qualifiedKey == null ? "" : qualifiedKey.trim();
        String[] parts = key.split("\\.");
        if (parts.length >= 3) {
            return new ParsedChildKey(parts[0], parts[1], parts[2]);
        }
        if (parts.length == 2) {
            return new ParsedChildKey(fallbackStage, parts[0], parts[1]);
        }
        return new ParsedChildKey(fallbackStage, "", key);
    }

    private static void add(Map<String, Double> adjustments, String key, double value) {
        adjustments.put(key, round(value));
    }

    private static String toReason(Map<String, Double> adjustments) {
        if (adjustments == null || adjustments.isEmpty()) {
            return "NO_ADJUSTMENT";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Double> e : adjustments.entrySet()) {
            if (!sb.isEmpty()) {
                sb.append(" | ");
            }
            sb.append(e.getKey()).append("=").append(e.getValue());
        }
        return sb.toString();
    }

    private static boolean isBullishStage(String stage) {
        return containsAny(stage, "MARKUP", "ACCUMULATION");
    }

    private static boolean isBearishStage(String stage) {
        return containsAny(stage, "MARKDOWN", "DISTRIBUTION");
    }

    private static boolean equalsAny(String value, String candidate) {
        return upper(value).equals(upper(candidate));
    }

    private static boolean containsAny(String value, String... tokens) {
        String s = upper(value);
        for (String token : tokens) {
            if (s.contains(upper(token))) {
                return true;
            }
        }
        return false;
    }

    private static String upper(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean nonBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static double safe(Double v) {
        return v == null || Double.isNaN(v) || Double.isInfinite(v) ? 0.0 : v;
    }

    private static double safe(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? 0.0 : v;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    @Value
    @Builder
    public static class ChildScoreInput {
        String stage;
        String winningSubstage;
        String parentSubstage;
        String childSubstage;
        String qualifiedKey;
        Double rawScore;
        String actionBias;
        String riskProfile;
        Double rsi;
        Double adx;
        Double volumeSurgeRatio;
    }

    @Value
    @Builder
    public static class ChildScoreDecision {
        String qualifiedKey;
        String stage;
        String parentSubstage;
        String childSubstage;
        double rawScore;
        double adjustedScore;
        Map<String, Double> adjustments;
        String reason;

        public static ChildScoreDecision empty() {
            return ChildScoreDecision.builder()
                    .qualifiedKey("")
                    .stage("")
                    .parentSubstage("")
                    .childSubstage("")
                    .rawScore(0.0)
                    .adjustedScore(0.0)
                    .adjustments(Map.of())
                    .reason("EMPTY_INPUT")
                    .build();
        }
    }

    @Value
    public static class ParsedChildKey {
        String stage;
        String parentSubstage;
        String childSubstage;
    }

    @Value
    public static class ChildMeta {
        String actionBias;
        String riskProfile;

        public static ChildMeta empty() {
            return new ChildMeta("", "");
        }
    }

    @Value
    public static class MarketInputs {
        double rsi;
        double adx;
        double volumeSurgeRatio;
    }

    @FunctionalInterface
    public interface ChildMetaResolver {
        ChildMeta resolve(String stage, String parentSubstage, String childSubstage);
    }
}
