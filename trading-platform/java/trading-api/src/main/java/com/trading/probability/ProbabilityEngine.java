package com.trading.probability;

import lombok.Builder;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * ProbabilityEngine
 *
 * Purpose:
 *  - Converts your existing pipeline outputs into practical probability estimates.
 *  - This is intentionally additive: it does NOT overwrite existing recommendation/final_action/rank columns.
 *  - Designed to run after BusinessLogicService, AlphaVantage enrichment, analytics, FinalActionEngine,
 *    RankingEngine, and optional RankingEngineV3.
 *
 * Adds columns:
 *  - probability_10pct_30d
 *  - probability_20pct_90d
 *  - probability_30pct_180d
 *  - probability_stop_loss_hit_first
 *  - probability_expected_return_pct
 *  - probability_expected_drawdown_pct
 *  - probability_reward_risk_score
 *  - probability_confidence_percentile
 *  - probability_edge_score
 *  - probability_decision_band
 *  - probability_position_size_hint
 *  - probability_reason
 *
 * Important:
 *  - These are model-inspired rule probabilities, not guaranteed market outcomes.
 *  - For true calibration, later compare these probabilities against realized forward returns.
 */
@Slf4j
@Service
public class ProbabilityEngine {

    public static final String P10_30D = "probability_10pct_30d";
    public static final String P20_90D = "probability_20pct_90d";
    public static final String P30_180D = "probability_30pct_180d";
    public static final String PSTOP = "probability_stop_loss_hit_first";
    public static final String EXP_RETURN = "probability_expected_return_pct";
    public static final String EXP_DRAWDOWN = "probability_expected_drawdown_pct";
    public static final String RR_SCORE = "probability_reward_risk_score";
    public static final String CONF_PERCENTILE = "probability_confidence_percentile";
    public static final String EDGE_SCORE = "probability_edge_score";
    public static final String DECISION_BAND = "probability_decision_band";
    public static final String POSITION_HINT = "probability_position_size_hint";
    public static final String REASON = "probability_reason";

    /**
     * Mutates every row by adding probability columns.
     */
    public void applyProbabilities(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            log.warn("PROBABILITY_ENGINE_SKIP rows empty");
            return;
        }

        int processed = 0;
        for (Map<String, Object> row : rows) {
            if (row == null) continue;
            ProbabilityResult result = evaluate(row);
            merge(row, result);
            processed++;
        }

        log.info("PROBABILITY_ENGINE_DONE rows={}", processed);
    }

    /**
     * Computes probabilities for one symbol row.
     */
    public ProbabilityResult evaluate(Map<String, Object> row) {
        RowView v = new RowView(row == null ? new LinkedHashMap<>() : row);

        double stageQuality = stageQuality(v);
        double substageQuality = substageQuality(v);
        double childQuality = childQuality(v);
        double finalActionQuality = finalActionQuality(v);
        double fundamentalsQuality = fundamentalsQuality(v);
        double relativeStrengthQuality = relativeStrengthQuality(v);
        double sentimentQuality = sentimentQuality(v);
        double volumeQuality = volumeQuality(v);
        double rrQuality = riskRewardQuality(v);
        double mlQuality = mlQuality(v);
        double analyticsQuality = analyticsQuality(v);

        double bullishComposite = clamp01(
                0.18 * stageQuality +
                0.13 * substageQuality +
                0.12 * childQuality +
                0.12 * finalActionQuality +
                0.11 * fundamentalsQuality +
                0.10 * relativeStrengthQuality +
                0.07 * sentimentQuality +
                0.07 * volumeQuality +
                0.06 * rrQuality +
                0.04 * mlQuality
        );

        double trendPersistence = clamp01(
                0.30 * stageQuality +
                0.25 * substageQuality +
                0.20 * childQuality +
                0.15 * relativeStrengthQuality +
                0.10 * analyticsQuality
        );

        double trapRisk = trapRisk(v);
        double technicalWeakness = technicalWeakness(v);
        double overextensionRisk = overextensionRisk(v);
        double stopRisk = clamp01(
                0.35 * trapRisk +
                0.25 * technicalWeakness +
                0.20 * overextensionRisk +
                0.12 * weakFundamentals(v) +
                0.08 * negativeSentiment(v)
        );

        double p10 = clamp01(0.12 + 0.70 * bullishComposite + 0.16 * trendPersistence - 0.28 * stopRisk);
        double p20 = clamp01(0.06 + 0.66 * bullishComposite + 0.22 * trendPersistence - 0.34 * stopRisk);
        double p30 = clamp01(0.03 + 0.58 * bullishComposite + 0.26 * trendPersistence + 0.10 * fundamentalsQuality - 0.38 * stopRisk);
        double pStopFirst = clamp01(0.10 + 0.70 * stopRisk + 0.15 * technicalWeakness - 0.25 * bullishComposite);

        double expectedReturnPct = estimateExpectedReturnPct(v, p10, p20, p30, bullishComposite, trendPersistence);
        double expectedDrawdownPct = estimateExpectedDrawdownPct(v, pStopFirst, technicalWeakness, overextensionRisk);
        double rewardRiskScore = expectedDrawdownPct <= 0.1 ? 0.0 : expectedReturnPct / expectedDrawdownPct;
        double edgeScore = clamp100((p20 * 42.0) + (p30 * 33.0) + (bullishComposite * 25.0) - (pStopFirst * 35.0));
        double confidencePercentile = clamp100((bullishComposite * 45.0) + (trendPersistence * 25.0) + (rrQuality * 15.0) + (mlQuality * 15.0));

        String band = decisionBand(edgeScore, p20, p30, pStopFirst, v);
        String positionHint = positionSizeHint(edgeScore, pStopFirst, rewardRiskScore, band);
        String reason = buildReason(v, bullishComposite, trendPersistence, stopRisk, edgeScore, rewardRiskScore, band);

        return ProbabilityResult.builder()
                .symbol(v.symbol())
                .probability10Pct30d(roundPct(p10))
                .probability20Pct90d(roundPct(p20))
                .probability30Pct180d(roundPct(p30))
                .probabilityStopLossHitFirst(roundPct(pStopFirst))
                .expectedReturnPct(round2(expectedReturnPct))
                .expectedDrawdownPct(round2(expectedDrawdownPct))
                .rewardRiskScore(round2(rewardRiskScore))
                .confidencePercentile(round2(confidencePercentile))
                .edgeScore(round2(edgeScore))
                .decisionBand(band)
                .positionSizeHint(positionHint)
                .reason(reason)
                .build();
    }

    private void merge(Map<String, Object> row, ProbabilityResult r) {
        row.put(P10_30D, r.getProbability10Pct30d());
        row.put(P20_90D, r.getProbability20Pct90d());
        row.put(P30_180D, r.getProbability30Pct180d());
        row.put(PSTOP, r.getProbabilityStopLossHitFirst());
        row.put(EXP_RETURN, r.getExpectedReturnPct());
        row.put(EXP_DRAWDOWN, r.getExpectedDrawdownPct());
        row.put(RR_SCORE, r.getRewardRiskScore());
        row.put(CONF_PERCENTILE, r.getConfidencePercentile());
        row.put(EDGE_SCORE, r.getEdgeScore());
        row.put(DECISION_BAND, r.getDecisionBand());
        row.put(POSITION_HINT, r.getPositionSizeHint());
        row.put(REASON, r.getReason());
    }

    private double stageQuality(RowView v) {
        String stage = normalize(v.stage());
        double raw = v.stageScore();
        double scoreFromRaw = normalizeRaw(raw, 50.0, 5.0);

        if (stage.equals("MARKUP")) return Math.max(0.70, scoreFromRaw);
        if (stage.equals("ACCUMULATION")) return Math.max(0.62, scoreFromRaw * 0.92);
        if (stage.equals("DISTRIBUTION")) return 0.12;
        if (stage.equals("MARKDOWN")) return 0.06;
        return scoreFromRaw * 0.45;
    }

    private double substageQuality(RowView v) {
        String s = normalize(v.substage());
        double raw = normalizeRaw(v.substageScore(), 30.0, 2.0);

        if (containsAny(s, "BREAKOUT", "EARLY_TREND", "STRONG_TREND", "TREND_CONTINUATION", "PULLBACK", "HIGHER_LOW", "RETEST")) {
            return Math.max(0.62, raw);
        }
        if (containsAny(s, "BASE", "ABSORPTION", "PRE_BREAKOUT", "SPRING", "BOTTOMING", "RANGE_FORMING")) {
            return Math.max(0.55, raw * 0.95);
        }
        if (containsAny(s, "EXHAUSTION", "OVEREXTENSION", "CLIMAX")) return Math.max(0.20, raw * 0.45);
        if (containsAny(s, "FALSE_BREAKOUT", "FAILED_BREAKOUT", "TOP", "MOMENTUM_LOSS", "BREAKDOWN", "SUPPORT_FAILURE", "WEAK_BOUNCE", "LOWER_HIGH", "LOWER_LOW")) return 0.08;
        return raw * 0.45;
    }

    private double childQuality(RowView v) {
        String c = normalize(v.childSubstage());
        double raw = normalizeRaw(v.childScore(), 12.0, 1.0);

        if (containsAny(c, "CLEAN_BREAKOUT", "SUCCESSFUL_RETEST", "EMA_STACK", "RSI_50_HOLD", "ORDERLY_CONTINUATION",
                "PULLBACK_TO_TREND", "SHALLOW_PULLBACK", "CONFIRMED_HIGHER_LOW", "HL_LOW_RISK_ENTRY", "TREND_ACCELERATION",
                "VOLUME_CONFIRMED_TREND", "TIGHT_BASE", "SUPPORT_DEFENSE", "ABSORPTION")) {
            return Math.max(0.65, raw);
        }
        if (containsAny(c, "DISTRIBUTION", "EXHAUSTION", "OVEREXTENDED", "FAILED", "LOST_VWAP", "SUPPORT_LOST", "LOWER_HIGH", "DEAD_CAT", "WEAK_BOUNCE")) {
            return Math.max(0.05, raw * 0.25);
        }
        return raw * 0.50;
    }

    private double finalActionQuality(RowView v) {
        String a = normalize(v.finalAction());
        if (containsAny(a, "STRONG_BUY")) return 1.0;
        if (containsAny(a, "BUY")) return 0.78;
        if (containsAny(a, "WATCH_BUY")) return 0.58;
        if (containsAny(a, "WATCH")) return 0.42;
        if (containsAny(a, "HOLD")) return 0.35;
        if (containsAny(a, "AVOID", "SELL", "SHORT")) return 0.02;
        return normalizeMaybePct(v.finalProbability());
    }

    private double fundamentalsQuality(RowView v) {
        double direct = firstPositive(
                normalizeMaybePct(v.firstDouble("eps_quality_score", "EPS Quality Score")),
                normalizeMaybePct(v.firstDouble("fundamental_boost", "FUNDAMENTAL_BOOST"))
        );
        if (direct > 0) return direct;

        double qoq = clamp01(v.firstDouble("eps_growth_qoq", "EPS Growth QoQ", "eps_growth_qoq_pct") / 25.0);
        double inc2 = asBoolish(v.first("eps_increase_2q", "EPS Increase 2Q"));
        double inc3 = asBoolish(v.first("eps_increase_3q", "EPS Increase 3Q"));
        double inc4 = asBoolish(v.first("eps_increase_4q", "EPS Increase 4Q"));
        double surprise = clamp01(Math.max(0, v.firstDouble("eps_surprise_pct_last", "EPS Surprise % Last", "eps_surprise_pct")) / 20.0);
        double epsAvailable = asBoolish(v.first("eps_available", "EPS_AVAILABLE", "epsAvailable"));

        return clamp01(0.35 * qoq + 0.35 * ((inc2 + inc3 + inc4) / 3.0) + 0.20 * surprise + 0.10 * epsAvailable);
    }

    private double relativeStrengthQuality(RowView v) {
        double rs = v.firstDouble("Relative Strength vs SPY", "relative_strength_vs_spy", "rs_vs_spy", "relativeStrengthVsSpy");
        if (rs == 0.0) return 0.50;
        if (rs > 3.0) return clamp01(rs / 100.0);
        return clamp01((rs - 0.75) / 0.65);
    }

    private double sentimentQuality(RowView v) {
        double s = v.firstDouble("News Sentiment Score", "news_sentiment_score", "sentiment_score", "Sentiment Score");
        if (s != 0) {
            if (s >= -1.0 && s <= 1.0) return clamp01((s + 1.0) / 2.0);
            return normalizeMaybePct(s);
        }
        String label = normalize(v.firstString("Sentiment Label", "sentiment_label", "news_sentiment_label"));
        if (containsAny(label, "POSITIVE", "BULLISH")) return 0.75;
        if (containsAny(label, "NEGATIVE", "BEARISH")) return 0.20;
        return 0.50;
    }

    private double volumeQuality(RowView v) {
        double vol = v.firstDouble("volume_surge_ratio", "Volume Surge Ratio", "relative_volume", "Relative Volume", "volume_surge", "Volume Surge");
        if (vol <= 0) return 0.35;
        if (vol <= 5.0) return clamp01(vol / 2.5);
        return normalizeMaybePct(vol);
    }

    private double riskRewardQuality(RowView v) {
        double rr = v.firstDouble("Best_Risk_Reward", "best_risk_reward", "risk_reward", "Risk Reward", "rr_ratio", "agent_risk_reward");
        if (rr <= 0) return 0.35;
        return clamp01(rr / 3.0);
    }

    private double mlQuality(RowView v) {
        double mp = v.firstDouble("model_probability", "final_probability", "probability_final_edge");
        return normalizeMaybePct(mp);
    }

    private double analyticsQuality(RowView v) {
        double finalScore = v.firstDouble("Final Score", "final_score");
        double hit = asBoolish(v.first("90D Hit", "90d_hit"));
        double gain = clamp01(v.firstDouble("90D Gain (%)", "90D Gain", "90d_gain") / 30.0);
        return clamp01(0.40 * normalizeMaybePct(finalScore) + 0.30 * hit + 0.30 * gain);
    }

    private double trapRisk(RowView v) {
        String stage = normalize(v.stage());
        String sub = normalize(v.substage());
        String child = normalize(v.childSubstage());
        String action = normalize(v.finalAction());

        double risk = 0.0;
        if (stage.equals("DISTRIBUTION")) risk += 0.40;
        if (stage.equals("MARKDOWN")) risk += 0.45;
        if (containsAny(sub, "EXHAUSTION", "OVEREXTENSION", "CLIMAX", "FALSE_BREAKOUT", "FAILED_BREAKOUT", "TOP", "MOMENTUM_LOSS", "WEAK_BOUNCE", "DEAD_CAT")) risk += 0.28;
        if (containsAny(child, "DISTRIBUTION", "EXHAUSTION", "OVEREXTENDED", "FAILED", "LOST_VWAP", "BLOWOFF", "FINAL_PUSH", "SUPPORT_LOST", "WEAK_BOUNCE")) risk += 0.24;
        if (containsAny(action, "SELL", "SHORT", "AVOID")) risk += 0.30;
        if (v.rsi() >= 78) risk += 0.12;
        if (v.beta() >= 2.2) risk += 0.06;
        return clamp01(risk);
    }

    private double technicalWeakness(RowView v) {
        double price = v.price();
        double weakness = 0;
        if (price > 0 && v.vwap() > 0 && price < v.vwap()) weakness += 0.35;
        if (price > 0 && v.ema21() > 0 && price < v.ema21()) weakness += 0.25;
        if (price > 0 && v.ema50() > 0 && price < v.ema50()) weakness += 0.25;
        if (v.rsi() > 0 && v.rsi() < 42) weakness += 0.15;
        return clamp01(weakness);
    }

    private double overextensionRisk(RowView v) {
        double risk = 0.0;
        double price = v.price();
        if (v.rsi() >= 80) risk += 0.35;
        else if (v.rsi() >= 74) risk += 0.22;

        if (price > 0 && v.ema21() > 0 && ((price - v.ema21()) / v.ema21()) > 0.18) risk += 0.20;
        if (price > 0 && v.ema50() > 0 && ((price - v.ema50()) / v.ema50()) > 0.28) risk += 0.20;
        if (containsAny(v.substage(), "EXHAUSTION", "OVEREXTENSION", "CLIMAX")) risk += 0.25;
        if (containsAny(v.childSubstage(), "BLOWOFF", "FINAL_PUSH", "RSI_OVEREXTENSION", "ADX_OVEREXTENSION", "EXTENDED_WITHOUT_VOLUME")) risk += 0.18;
        return clamp01(risk);
    }

    private double weakFundamentals(RowView v) {
        return 1.0 - fundamentalsQuality(v);
    }

    private double negativeSentiment(RowView v) {
        return 1.0 - sentimentQuality(v);
    }

    private double estimateExpectedReturnPct(RowView v, double p10, double p20, double p30, double bullishComposite, double trendPersistence) {
        double explicit = firstPositive(
                v.firstDouble("expected_return_pct", "Expected Return %", "upside_pct", "Upside %", "analyst_upside_pct"),
                v.firstDouble("90D Gain (%)", "90D Gain", "90d_gain")
        );
        if (explicit > 0) {
            return clamp(explicit, 0, 120);
        }

        double base = 4.0 + 10.0 * p10 + 14.0 * p20 + 18.0 * p30 + 8.0 * bullishComposite + 5.0 * trendPersistence;
        if (containsAny(v.stage(), "ACCUMULATION")) base += 3.0;
        if (containsAny(v.stage(), "MARKUP") && containsAny(v.substage(), "EARLY_TREND", "BREAKOUT", "STRONG_TREND")) base += 2.5;
        return clamp(base, 0, 90);
    }

    private double estimateExpectedDrawdownPct(RowView v, double pStop, double technicalWeakness, double overextensionRisk) {
        double price = v.price();
        double stop = v.firstDouble("stop", "Stop", "stop_loss", "Stop Loss", "Invalidation_Level", "invalidation_level");
        if (price > 0 && stop > 0 && stop < price) {
            return clamp(((price - stop) / price) * 100.0, 1.0, 60.0);
        }
        double base = 4.5 + 9.0 * pStop + 5.0 * technicalWeakness + 5.0 * overextensionRisk;
        if (v.beta() > 1.5) base += Math.min(6.0, (v.beta() - 1.5) * 4.0);
        return clamp(base, 3.0, 45.0);
    }

    private String decisionBand(double edgeScore, double p20, double p30, double pStop, RowView v) {
        if (containsAny(v.finalAction(), "SELL", "SHORT", "AVOID") || pStop >= 0.62) return "AVOID_OR_SHORT_BIAS";
        if (edgeScore >= 70 && p20 >= 0.62 && pStop <= 0.35) return "HIGH_CONVICTION_BUY";
        if (edgeScore >= 58 && p20 >= 0.52 && pStop <= 0.45) return "BUY_ON_PULLBACK";
        if (edgeScore >= 45 && p30 >= 0.45) return "WATCH_BUY";
        if (edgeScore >= 30) return "WATCH_ONLY";
        return "AVOID";
    }

    private String positionSizeHint(double edgeScore, double pStop, double rr, String band) {
        if (band.equals("AVOID_OR_SHORT_BIAS") || band.equals("AVOID")) return "0% long; evaluate short/avoid separately";
        if (edgeScore >= 70 && pStop <= 0.30 && rr >= 2.0) return "Full starter: 3-5% max portfolio risk-controlled position";
        if (edgeScore >= 58 && pStop <= 0.42 && rr >= 1.5) return "Starter: 1.5-3% position; add after confirmation";
        if (edgeScore >= 45) return "Small watch position only: 0.5-1.5%";
        return "No position; wait for better setup";
    }

    private String buildReason(RowView v, double bullishComposite, double trendPersistence, double stopRisk, double edgeScore, double rr, String band) {
        return String.format(Locale.US,
                "%s edge=%.2f rr=%.2f bull=%.2f trend=%.2f stopRisk=%.2f stage=%s substage=%s child=%s action=%s rsi=%.2f beta=%.2f",
                band,
                edgeScore,
                rr,
                bullishComposite,
                trendPersistence,
                stopRisk,
                v.stage(),
                v.substage(),
                v.childSubstage(),
                v.finalAction(),
                v.rsi(),
                v.beta());
    }

    private double normalizeRaw(double raw, double maxRaw, double minUseful) {
        if (raw <= 0) return 0;
        if (raw < minUseful) return clamp01(raw / minUseful * 0.35);
        return clamp01(raw / maxRaw);
    }

    private static double firstPositive(double... values) {
        for (double v : values) if (v > 0) return v;
        return 0;
    }

    private static boolean containsAny(String value, String... tokens) {
        String v = normalize(value);
        for (String token : tokens) {
            if (v.contains(normalize(token))) return true;
        }
        return false;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.US).replace('-', '_').replace(' ', '_');
    }

    private static double normalizeMaybePct(double x) {
        if (Double.isNaN(x) || Double.isInfinite(x)) return 0;
        if (x >= -1.0 && x <= 1.0) return clamp01(x);
        return clamp01(x / 100.0);
    }

    private static double asBoolish(Object v) {
        if (v == null) return 0;
        if (v instanceof Boolean b) return b ? 1 : 0;
        if (v instanceof Number n) return n.doubleValue() > 0 ? 1 : 0;
        String s = normalize(String.valueOf(v));
        return (s.equals("TRUE") || s.equals("YES") || s.equals("Y") || s.equals("1")) ? 1 : 0;
    }

    private static double toDouble(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.doubleValue();
        String s = String.valueOf(v).trim();
        if (s.isBlank() || s.equalsIgnoreCase("null") || s.equalsIgnoreCase("nan")) return 0;
        try {
            return Double.parseDouble(s.replace("%", "").replace(",", ""));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static double clamp01(double x) {
        return clamp(x, 0.0, 1.0);
    }

    private static double clamp100(double x) {
        return clamp(x, 0.0, 100.0);
    }

    private static double clamp(double x, double min, double max) {
        if (Double.isNaN(x) || Double.isInfinite(x)) return min;
        return Math.max(min, Math.min(max, x));
    }

    private static double roundPct(double p) {
        return round2(clamp01(p) * 100.0);
    }

    private static double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    @Value
    @Builder
    public static class ProbabilityResult {
        String symbol;
        double probability10Pct30d;
        double probability20Pct90d;
        double probability30Pct180d;
        double probabilityStopLossHitFirst;
        double expectedReturnPct;
        double expectedDrawdownPct;
        double rewardRiskScore;
        double confidencePercentile;
        double edgeScore;
        String decisionBand;
        String positionSizeHint;
        String reason;
    }

    private static class RowView {
        private final Map<String, Object> row;

        RowView(Map<String, Object> row) {
            this.row = row;
        }

        String symbol() { return firstString("symbol", "Symbol", "ticker", "Ticker"); }
        String stage() { return firstString("Market Stage", "market_stage", "stage", "Stage", "winning_stage"); }
        String substage() { return firstString("Market Substage", "market_substage", "substage", "Substage", "winning_substage"); }
        String childSubstage() { return firstString("Child Substage", "child_substage", "childSubstage", "winning_child_substage", "child"); }
        String finalAction() { return firstString("Final Action", "final_action", "Rule Recommendation", "rule_recommendation", "recommendation", "entry_decision"); }

        double stageScore() { return firstDouble("winning_stage_score", "stage_score", "Stage Score", "market_stage_score"); }
        double substageScore() { return firstDouble("winning_substage_score", "substage_score", "Substage Score", "market_substage_score"); }
        double childScore() { return firstDouble("winning_child_score", "child_substage_score", "Child Score", "child_score", "bestChildConfidence"); }
        double finalProbability() { return firstDouble("final_probability", "model_probability", "rule_probability"); }
        double price() { return firstDouble("currentPrice", "current_price", "Current Price", "price", "Price", "Close", "close"); }
        double ema21() { return firstDouble("ema21", "EMA21", "EMA 21", "ema_21"); }
        double ema50() { return firstDouble("ema50", "EMA50", "EMA 50", "ema_50"); }
        double vwap() { return firstDouble("vwap", "VWAP"); }
        double rsi() { return firstDouble("rsi", "RSI"); }
        double beta() { return firstDouble("Beta", "beta", "av_beta"); }

        Object first(String... keys) {
            for (String key : keys) {
                if (row.containsKey(key) && row.get(key) != null && !String.valueOf(row.get(key)).isBlank()) {
                    return row.get(key);
                }
            }
            return null;
        }

        String firstString(String... keys) {
            Object v = first(keys);
            return v == null ? "" : String.valueOf(v).trim();
        }

        double firstDouble(String... keys) {
            Object v = first(keys);
            return toDouble(v);
        }
    }
}
