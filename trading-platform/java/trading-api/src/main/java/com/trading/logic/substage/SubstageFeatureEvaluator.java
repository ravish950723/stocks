package com.trading.logic.substage;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

@Component
public class SubstageFeatureEvaluator {

    public double score(String rawFeature, SubstageEvaluationContext c) {
        String feature = normalize(rawFeature);

        if (feature.endsWith("_eq")) {
            return bool(base(feature, "_eq"), c) ? 1.0 : 0.0;
        }
        if (feature.endsWith("_gte")) {
            return numeric(base(feature, "_gte"), c);
        }
        if (feature.endsWith("_lte")) {
            return 1.0 - clamp01(numeric(base(feature, "_lte"), c));
        }
        if (feature.endsWith("_between")) {
            return numeric(base(feature, "_between"), c);
        }

        return switch (feature) {
            case "rsi" -> normalized(c.getRsi(), 30.0, 70.0);
            case "adx" -> normalized(c.getAdx(), c.getAdxTrendMin(), c.getAdxStrongTrendMin());
            case "vol", "volume", "volume_surge" -> normalized(c.getVolSurgeRatio(), 1.0, c.getStrongVolumeSurgeMin());
            case "strong_volume" ->
                    normalized(c.getVolSurgeRatio(), c.getStrongVolumeSurgeMin(), c.getStrongVolumeSurgeMin() * 1.5);
            case "compression" -> inverseRatio(c.range20Pct(), c.getCompressionRangeMaxPct());
            case "tight_compression" -> inverseRatio(c.range20Pct(), c.getTightCompressionRangeMaxPct());
            case "rsi_exhaustion_high" ->
                    normalized(c.getRsi(), c.getRsiExhaustionHigh(), c.getRsiExhaustionHigh() + 10.0);
            case "rsi_exhaustion_low" -> normalized(c.getRsiExhaustionLow() - c.getRsi(), 0.0, 12.0);
            default -> bool(feature, c) ? 1.0 : numeric(feature, c);
        };
    }

    public boolean matches(String rawFeature, Object expected, SubstageEvaluationContext c) {
        String feature = normalize(rawFeature);

        if (expected instanceof Boolean b) {
            return bool(removeComparatorSuffix(feature), c) == b;
        }
        if (expected instanceof Number n) {
            return numeric(removeComparatorSuffix(feature), c) >= n.doubleValue();
        }
        if (expected instanceof Map<?, ?> m) {
            if (m.containsKey("min") && numeric(feature, c) < asDouble(m.get("min"), 0.0)) return false;
            return !m.containsKey("max") || !(numeric(feature, c) > asDouble(m.get("max"), 0.0));
        }

        String text = expected == null ? "" : String.valueOf(expected).trim();
        if (text.isBlank()) return true;
        if ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)) {
            return bool(removeComparatorSuffix(feature), c) == Boolean.parseBoolean(text);
        }
        if (text.startsWith(">=")) return numeric(feature, c) >= asDouble(text.substring(2), 0.0);
        if (text.startsWith("<=")) return numeric(feature, c) <= asDouble(text.substring(2), 0.0);
        if (text.startsWith(">")) return numeric(feature, c) > asDouble(text.substring(1), 0.0);
        if (text.startsWith("<")) return numeric(feature, c) < asDouble(text.substring(1), 0.0);
        return bool(removeComparatorSuffix(feature), c);
    }

    public double numeric(String rawFeature, SubstageEvaluationContext c) {
        String feature = removeComparatorSuffix(normalize(rawFeature));
        return switch (feature) {
            case "price", "current_price", "last_price" -> c.getPrice();
            case "vwap" -> c.getVwap();
            case "adx" -> c.getAdx();
            case "rsi" -> c.getRsi();
            case "dma20", "ema20" -> c.getDma20();
            case "dma50", "ema50" -> c.getDma50();
            case "dma200", "ema200" -> c.getDma200();
            case "atr", "atr14" -> c.getAtr14();
            case "vol", "volume", "vol_surge_ratio", "volume_ratio" -> c.getVolSurgeRatio();
            case "range20", "range20_pct" -> c.range20Pct();
            case "bull_stack", "bullstack" -> c.bullStack() ? 1.0 : 0.0;
            case "bear_stack", "bearstack" -> c.bearStack() ? 1.0 : 0.0;
            case "price_above_vwap", "priceabovevwap" -> c.priceAboveVwap() ? 1.0 : 0.0;
            case "price_below_vwap", "pricebelowvwap" -> c.priceBelowVwap() ? 1.0 : 0.0;
            default -> metricNumber(c.getMetrics(), rawFeature);
        };
    }

    public boolean bool(String rawFeature, SubstageEvaluationContext c) {
        String feature = removeComparatorSuffix(normalize(rawFeature));
        return switch (feature) {
            case "bull_stack", "bullstack" -> c.bullStack();
            case "bear_stack", "bearstack" -> c.bearStack();
            case "price_above_vwap", "priceabovevwap" -> c.priceAboveVwap();
            case "price_below_vwap", "pricebelowvwap" -> c.priceBelowVwap();
            case "breakout" -> metricBool(c.getMetrics(), "breakout") || CandleStructure.breakout(c.getCandles(), 20);
            case "breakdown" ->
                    metricBool(c.getMetrics(), "breakdown") || CandleStructure.breakdown(c.getCandles(), 20);
            case "macd_cross", "macdcross" -> metricBool(c.getMetrics(), "macd_cross");
            case "near_support", "nearsupport" -> metricBool(c.getMetrics(), "near_support");
            case "spring" -> CandleStructure.spring(c.getCandles());
            case "falsebreakdown", "false_breakdown" -> CandleStructure.falseBreakdown(c.getCandles());
            case "falsebreakout", "false_breakout" -> CandleStructure.falseBreakout(c.getCandles());
            case "liquiditygrablow", "liquidity_grab_low" -> CandleStructure.liquidityGrabLow(c.getCandles());
            case "liquiditygrabhigh", "liquidity_grab_high" -> CandleStructure.liquidityGrabHigh(c.getCandles());
            case "supportfailure", "support_failure" -> CandleStructure.supportFailure(c.getCandles());
            case "breakoutretest", "breakout_retest", "retest" -> CandleStructure.breakoutRetest(c.getCandles());
            case "higherlow", "higher_low" -> CandleStructure.higherLow(c.getCandles());
            case "higherhigh", "higher_high" -> CandleStructure.higherHigh(c.getCandles());
            case "lowerhigh", "lower_high" -> CandleStructure.lowerHigh(c.getCandles());
            case "lowerlow", "lower_low" -> CandleStructure.lowerLow(c.getCandles());
            case "pullback" -> CandleStructure.pullback(c.getCandles(), c.getPrice(), c.getDma20());
            case "rangeexpansion", "range_expansion" -> CandleStructure.rangeExpansion(c.getCandles());
            case "momentumsurge", "momentum_surge" ->
                    c.getRsi() >= c.getRsiBullMin() && c.getAdx() >= c.getAdxTrendMin() && c.getVolSurgeRatio() >= 1.1;
            case "momentumdrop", "momentum_drop" -> c.getRsi() <= c.getRsiBearMax() && c.getAdx() >= c.getAdxTrendMin();
            case "momentumloss", "momentum_loss" -> c.getRsi() < 55.0 && c.getAdx() < c.getAdxStrongTrendMin();
            case "bottoming" -> CandleStructure.bottoming(c.getCandles());
            case "deadcatbounce", "dead_cat_bounce" -> CandleStructure.deadCatBounce(c.getCandles());
            case "demandcandle", "demand_candle" -> CandleStructure.demandCandle(c.getCandles());
            case "pricereversal", "price_reversal" -> CandleStructure.priceReversal(c.getCandles());
            default -> metricBool(c.getMetrics(), rawFeature);
        };
    }

    private double metricNumber(Map<String, Object> metrics, String key) {
        Object value = metrics == null ? null : metrics.get(key);
        if (value instanceof Number n) return n.doubleValue();
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (Exception ignored) {
                return 0.0;
            }
        }
        return 0.0;
    }

    private boolean metricBool(Map<String, Object> metrics, String key) {
        Object value = metrics == null ? null : metrics.get(key);
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.doubleValue() != 0.0;
        if (value instanceof String s) {
            String v = s.trim();
            return "true".equalsIgnoreCase(v) || "yes".equalsIgnoreCase(v) || "1".equals(v);
        }
        return false;
    }

    private String normalize(String feature) {
        return feature == null ? "" : feature.trim().replace('-', '_').toLowerCase(Locale.ROOT);
    }

    private String removeComparatorSuffix(String feature) {
        return feature
                .replaceAll("_eq$", "")
                .replaceAll("_gte$", "")
                .replaceAll("_lte$", "")
                .replaceAll("_between$", "");
    }

    private String base(String feature, String suffix) {
        return feature.endsWith(suffix) ? feature.substring(0, feature.length() - suffix.length()) : feature;
    }

    private double normalized(double value, double min, double max) {
        if (max <= min) return value >= min ? 1.0 : 0.0;
        return clamp01((value - min) / (max - min));
    }

    private double inverseRatio(double value, double maxGood) {
        if (maxGood <= 0.0) return 0.0;
        return clamp01(1.0 - (value / maxGood));
    }

    private double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
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
}
