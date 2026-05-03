package com.trading.logic;

import com.trading.config.AppRuntimeConfig;
import com.trading.config.YamlConfigService;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
@SuppressWarnings("unchecked")
public class ChildSubstageSignalEngine {

    private final YamlConfigService yamlConfigService;

    public ChildSignalResult evaluate(ChildSignalInput in) {
        AppRuntimeConfig config = yamlConfigService.load();
        Map<String, Object> childRoot = asMap(config.getChildSubstages());
        Map<String, Object> childMeta = resolveChildMeta(childRoot, in.getMarketSubstage(), in.getChildSubstage());

        double score = 50.0;
        double riskPenalty = 0.0;
        StringBuilder reason = new StringBuilder();

        String actionBias = upper(childMeta.getOrDefault("action_bias", "NEUTRAL"));
        String riskProfile = upper(childMeta.getOrDefault("risk_profile", "MEDIUM"));
        String confidenceBias = upper(childMeta.getOrDefault("confidence_bias", "NEUTRAL"));
        String description = String.valueOf(childMeta.getOrDefault("description", ""));

        if (confidenceBias.contains("POSITIVE")) {
            score += 10.0;
            reason.append("YML confidence bias positive. ");
        } else if (confidenceBias.contains("NEGATIVE")) {
            score -= 10.0;
            riskPenalty += 6.0;
            reason.append("YML confidence bias negative. ");
        }

        if (actionBias.contains("BUY") || actionBias.contains("LONG") || actionBias.contains("ACCUMULATE")) {
            score += 12.0;
            reason.append("YML action bias supports long/accumulate. ");
        } else if (actionBias.contains("WATCH") || actionBias.contains("WAIT")) {
            score -= 2.0;
            riskPenalty += 2.0;
            reason.append("YML action bias says watch/wait. ");
        } else if (actionBias.contains("AVOID") || actionBias.contains("SHORT")) {
            score -= 18.0;
            riskPenalty += 18.0;
            reason.append("YML action bias is avoid/short. ");
        }

        if (riskProfile.contains("VERY_HIGH")) {
            score -= 18.0;
            riskPenalty += 18.0;
        } else if (riskProfile.contains("HIGH")) {
            score -= 10.0;
            riskPenalty += 10.0;
        } else if (riskProfile.contains("LOW")) {
            score += 5.0;
        }

        Map<String, Object> signals = asMap(childMeta.get("signals"));
        SignalScore signalScore = scoreSignals(signals, in);
        score += signalScore.score;
        riskPenalty += signalScore.riskPenalty;
        reason.append(signalScore.reason);

        if (!description.isBlank()) {
            reason.append("YML child description: ").append(description).append(" ");
        }

        score = clamp(score, 0, 100);
        riskPenalty = clamp(riskPenalty, 0, 100);

        return ChildSignalResult.builder()
                .childSignalScore(round(score / 10.0))
                .childRiskPenalty(round(riskPenalty))
                .childActionBias(actionBias.isBlank() ? "NEUTRAL" : actionBias)
                .childSignalReason(reason.toString().trim())
                .build();
    }

    private SignalScore scoreSignals(Map<String, Object> signals, ChildSignalInput in) {
        double score = 0.0;
        double penalty = 0.0;
        StringBuilder reason = new StringBuilder();
        for (Map.Entry<String, Object> e : signals.entrySet()) {
            String key = norm(e.getKey());
            Object expected = e.getValue();
            boolean matched = evaluateSignal(key, expected, in);
            if (matched) {
                score += weight(expected, 6.0);
                reason.append("Matched child YML signal ").append(e.getKey()).append(". ");
            } else {
                penalty += 1.0;
            }
        }
        return new SignalScore(score, penalty, reason.toString());
    }

    private boolean evaluateSignal(String key, Object expected, ChildSignalInput in) {
        if (key.contains("always_true")) return true;
        if (key.contains("rsi")) return compare(in.getRsi(), key, expected);
        if (key.contains("adx")) return compare(in.getAdx(), key, expected);
        if (key.contains("vol") || key.contains("volume")) return compare(in.getVolumeSurgeRatio(), key, expected);
        // Signals requiring candle/market structure are already scored in StageEngine. Do not hardcode child names here.
        return false;
    }

    private boolean compare(double actual, String key, Object expected) {
        if (key.endsWith("_gte") || key.endsWith("_min")) return actual >= asDouble(expected, 0.0);
        if (key.endsWith("_lte") || key.endsWith("_max")) return actual <= asDouble(expected, 0.0);
        if (key.endsWith("_gt")) return actual > asDouble(expected, 0.0);
        if (key.endsWith("_lt")) return actual < asDouble(expected, 0.0);
        if (key.endsWith("_between") && expected instanceof java.util.List<?> l && l.size() >= 2) {
            return actual >= asDouble(l.get(0), Double.NEGATIVE_INFINITY)
                    && actual <= asDouble(l.get(1), Double.POSITIVE_INFINITY);
        }
        return false;
    }

    private Map<String, Object> resolveChildMeta(Map<String, Object> childRoot, String parentSubstage, String childSubstage) {
        Map<String, Object> children = asMap(childRoot.get(parentSubstage));
        if (children.isEmpty()) children = asMap(asMap(childRoot.get("child_substages")).get(parentSubstage));
        if (children.isEmpty()) children = asMap(asMap(childRoot.get("by_substage")).get(parentSubstage));
        Map<String, Object> child = asMap(children.get(childSubstage));
        if (!child.isEmpty()) return child;
        child = asMap(children.get("DEFAULT"));
        if (!child.isEmpty()) return child;
        return Map.of("action_bias", "WAIT", "risk_profile", "UNKNOWN", "confidence_bias", "NEUTRAL", "description", "No child YML rule found");
    }

    private static Map<String, Object> asMap(Object raw) {
        if (raw instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
            return out;
        }
        return Map.of();
    }

    private static double weight(Object expected, double def) {
        Map<String, Object> m = asMap(expected);
        return asDouble(m.get("weight"), def);
    }

    private static double asDouble(Object value, double def) {
        if (value instanceof Number n) return n.doubleValue();
        try { return value == null ? def : Double.parseDouble(String.valueOf(value)); }
        catch (Exception e) { return def; }
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().replace('-', '_').toLowerCase(Locale.ROOT);
    }

    static String upper(Object s) {
        return s == null ? "" : String.valueOf(s).trim().toUpperCase(Locale.ROOT);
    }

    private static double clamp(double v, double min, double max) { return Math.max(min, Math.min(max, v)); }
    private static double round(double v) { return Math.round(v * 100.0) / 100.0; }

    private record SignalScore(double score, double riskPenalty, String reason) {}

    @Data
    @Builder
    public static class ChildSignalInput {
        private String marketStage;
        private String marketSubstage;
        private String childSubstage;
        private double rsi;
        private double adx;
        private double volumeSurgeRatio;
    }

    @Data
    @Builder
    public static class ChildSignalResult {
        private double childSignalScore;
        private double childRiskPenalty;
        private String childActionBias;
        private String childSignalReason;
    }
}
