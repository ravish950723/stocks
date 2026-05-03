package com.trading.logic.substage;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@SuppressWarnings("unchecked")
public class SubstageRuleEngine {

    private final SubstageFeatureEvaluator featureEvaluator;

    public String classify(String stage,
                           Map<String, Object> substageRulesRoot,
                           SubstageEvaluationContext baseContext,
                           String defaultSubstage) {

        Map<String, Object> rulesForStage = asMap(substageRulesRoot.get(stage));
        if (rulesForStage.isEmpty()) {
            return defaultSubstage;
        }

        String bestSubstage = defaultSubstage;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (Map.Entry<String, Object> entry : rulesForStage.entrySet()) {
            String substage = entry.getKey();
            Map<String, Object> rule = asMap(entry.getValue());

            SubstageEvaluationContext ctx = SubstageEvaluationContext.builder()
                    .stage(stage)
                    .substage(substage)
                    .candles(baseContext.getCandles())
                    .metrics(baseContext.getMetrics())
                    .price(baseContext.getPrice())
                    .vwap(baseContext.getVwap())
                    .adx(baseContext.getAdx())
                    .rsi(baseContext.getRsi())
                    .dma20(baseContext.getDma20())
                    .dma50(baseContext.getDma50())
                    .dma200(baseContext.getDma200())
                    .atr14(baseContext.getAtr14())
                    .volSurgeRatio(baseContext.getVolSurgeRatio())
                    .compressionRangeMaxPct(baseContext.getCompressionRangeMaxPct())
                    .tightCompressionRangeMaxPct(baseContext.getTightCompressionRangeMaxPct())
                    .adxTrendMin(baseContext.getAdxTrendMin())
                    .adxStrongTrendMin(baseContext.getAdxStrongTrendMin())
                    .rsiBullMin(baseContext.getRsiBullMin())
                    .rsiBearMax(baseContext.getRsiBearMax())
                    .rsiExhaustionHigh(baseContext.getRsiExhaustionHigh())
                    .rsiExhaustionLow(baseContext.getRsiExhaustionLow())
                    .strongVolumeSurgeMin(baseContext.getStrongVolumeSurgeMin())
                    .build();

            double score = score(rule, ctx);
            if (score > bestScore) {
                bestScore = score;
                bestSubstage = substage;
            }
        }

        return bestSubstage;
    }

    public double score(Map<String, Object> rule, SubstageEvaluationContext ctx) {
        Map<String, Object> gates = asMap(rule.get("gates"));
        if (!passesGates(gates, ctx)) {
            return -1_000_000.0;
        }

        Map<String, Object> signals = asMap(rule.get("signals"));
        double score = 0.0;

        for (Map.Entry<String, Object> signal : signals.entrySet()) {
            double weight = signalWeight(signal.getValue());
            if (weight <= 0.0) weight = 1.0;

            double matchScore = signalMatchScore(signal.getKey(), signal.getValue(), ctx);
            score += weight * clamp(matchScore, 0.0, 1.0);
        }

        for (Object penalty : asList(rule.get("penalties"))) {
            score -= penaltyValue(String.valueOf(penalty), ctx);
        }

        return score;
    }

    public double matchRatio(Map<String, Object> rule, SubstageEvaluationContext ctx) {
        Map<String, Object> signals = asMap(rule.get("signals"));
        if (signals.isEmpty()) return 0.0;

        double total = 0.0;
        double matched = 0.0;

        for (Map.Entry<String, Object> signal : signals.entrySet()) {
            double weight = signalWeight(signal.getValue());
            if (weight <= 0.0) weight = 1.0;
            total += weight;
            matched += weight * clamp(signalMatchScore(signal.getKey(), signal.getValue(), ctx), 0.0, 1.0);
        }

        return total <= 0.0 ? 0.0 : matched / total;
    }

    private boolean passesGates(Map<String, Object> gates, SubstageEvaluationContext ctx) {
        for (Map.Entry<String, Object> gate : gates.entrySet()) {
            if (!featureEvaluator.matches(gate.getKey(), gate.getValue(), ctx)) {
                return false;
            }
        }
        return true;
    }

    private double signalMatchScore(String feature, Object descriptor, SubstageEvaluationContext ctx) {
        if (descriptor instanceof Boolean b) {
            return featureEvaluator.bool(feature, ctx) == b ? 1.0 : 0.0;
        }

        if (descriptor instanceof Number n) {
            return featureEvaluator.numeric(feature, ctx) >= n.doubleValue() ? 1.0 : 0.0;
        }

        if (descriptor instanceof Map<?, ?> m) {
            Object min = m.get("min");
            Object max = m.get("max");
            if (min != null || max != null) {
                double value = featureEvaluator.numeric(feature, ctx);
                if (min != null && value < asDouble(min, 0.0)) return 0.0;
                if (max != null && value > asDouble(max, 0.0)) return 0.0;
                return 1.0;
            }
        }

        return featureEvaluator.score(feature, ctx);
    }

    private double signalWeight(Object descriptor) {
        if (descriptor instanceof Map<?, ?> m && m.get("weight") instanceof Number n) {
            return n.doubleValue();
        }
        return 1.0;
    }

    private double penaltyValue(String penalty, SubstageEvaluationContext ctx) {
        return switch (penalty == null ? "" : penalty.trim()) {
            case "failed_spring" -> featureEvaluator.bool("falseBreakdown", ctx) ? 10.0 : 0.0;
            case "failed_breakout" -> featureEvaluator.bool("falseBreakout", ctx) ? 8.0 : 0.0;
            case "failed_breakdown" -> featureEvaluator.bool("falseBreakdown", ctx) ? 8.0 : 0.0;
            case "late_exhaustion" -> ctx.getRsi() >= ctx.getRsiExhaustionHigh() ? 6.0 : 0.0;
            case "panic" -> ctx.getRsi() <= ctx.getRsiExhaustionLow()
                    && ctx.getVolSurgeRatio() >= ctx.getStrongVolumeSurgeMin() ? 6.0 : 0.0;
            default -> 0.0;
        };
    }

    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
    }

    private List<Object> asList(Object value) {
        return value instanceof List<?> list ? (List<Object>) list : List.of();
    }

    private double asDouble(Object value, double defaultValue) {
        if (value instanceof Number n) return n.doubleValue();
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (Exception ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
