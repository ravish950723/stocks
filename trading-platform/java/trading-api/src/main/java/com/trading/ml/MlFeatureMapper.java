package com.trading.ml;

import com.trading.ml.MlFeaturesRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
public class MlFeatureMapper {

    public com.trading.ml.MlFeaturesRequest toRequest(String symbol, Map<String, Object> row) {
        Map<String, Object> data = row == null ? Map.of() : row;
        log.debug("ML_FEATURE_MAP symbol={} sourceFieldCount={}", symbol, data.size());

        double currentPrice = firstDouble(data, "current_price", "currentPrice", "close", "price");
        double atr14 = firstDouble(data, "atr14", "atr", "ATR14");
        double atr14Pct = firstDouble(data, "atr14_pct", "atr14Pct");
        if (atr14Pct == 0.0 && currentPrice > 0 && atr14 > 0) {
            atr14Pct = (atr14 / currentPrice) * 100.0;
        }

        double vwap = firstDouble(data, "vwap", "VWAP");
        double vwapDistancePct = firstDouble(data, "vwap_distance_pct", "vwapDistancePct");
        if (vwapDistancePct == 0.0 && currentPrice > 0 && vwap > 0) {
            vwapDistancePct = ((currentPrice - vwap) / vwap) * 100.0;
        }

        double dma20 = firstDouble(data, "dma20", "sma20", "ema20");
        double dma50 = firstDouble(data, "dma50", "sma50", "ema50");
        double dma200 = firstDouble(data, "dma200", "sma200", "ema200");

        return MlFeaturesRequest.builder()
                .symbol(symbol)
                .schemaVersion("v8-java-hedge")
                .asOfDate(firstString(data, "asof_date", "asOfDate", "date", "run_date", LocalDate.now().toString()))
                .assetType(firstString(data, "asset_type", "assetType", "EQUITY"))

                .currentPrice(currentPrice)
                .rsi(firstDouble(data, "rsi", "RSI"))
                .adx(firstDouble(data, "adx", "ADX"))
                .atr14Pct(atr14Pct)
                .vwapDistancePct(vwapDistancePct)
                .volSurgeRatio(firstDouble(data, "vol_surge_ratio", "volume_surge_ratio", "volSurgeRatio"))
                .emaUptrend(firstBooleanAsDouble(data, "ema_uptrend", "emaUptrend", "bullStack"))
                .ema21Slope(firstDouble(data, "ema21_slope", "ema21Slope"))
                .dma20(dma20)
                .dma50(dma50)
                .dma200(dma200)
                .pctFromDma20(firstOrCalcPct(data, currentPrice, dma20, "pct_from_dma20", "pctFromDma20"))
                .pctFromDma50(firstOrCalcPct(data, currentPrice, dma50, "pct_from_dma50", "pctFromDma50"))
                .pctFromDma200(firstOrCalcPct(data, currentPrice, dma200, "pct_from_dma200", "pctFromDma200"))
                .breakout(firstBoolean(data, "breakout"))
                .bullishEngulfing(firstBoolean(data, "bullish_engulfing", "bullishEngulfing"))
                .hammer(firstBoolean(data, "hammer"))
                .nearSupport(firstBoolean(data, "near_support", "nearSupport"))
                .macdCross(firstBoolean(data, "macd_cross", "macdCross"))

                .regimeQualityScore(firstDouble(data, "regime_quality_score", "regimeQualityScore"))
                .signalScore(firstDouble(data, "signal_score", "signalScore"))
                .confidenceScore(firstDouble(data, "confidence_score", "confidenceScore"))
                .institutionalScore(firstDouble(data, "institutional_score", "institutionalScore"))

                .stageConfidence(firstDouble(data, "stage_confidence", "stageConfidence", "stage_alignment_score"))
                .substageConfidence(firstDouble(data, "substage_confidence", "substageConfidence"))
                .childSubstageConfidence(firstDouble(data, "child_substage_confidence", "childSubstageConfidence", "best_child_confidence"))
                .stageScore(firstDouble(data, "stage_score", "stageScore"))
                .substageScore(firstDouble(data, "substage_score", "substageScore"))
                .childSubstageScore(firstDouble(data, "child_substage_score", "childSubstageScore", "child_signal_score"))
                .stageRank(firstDouble(data, "stage_rank", "stageRank"))
                .substageRank(firstDouble(data, "substage_rank", "substageRank"))
                .childSubstageRank(firstDouble(data, "child_substage_rank", "childSubstageRank"))

                .entryQualityScore(firstDouble(data, "entry_quality_score", "entryQualityScore", "Entry Quality Score"))
                .entryConfidenceScore(firstDouble(data, "entry_confidence_score", "entryConfidenceScore", "Entry Confidence Score"))
                .bestRiskReward(firstDouble(data, "best_risk_reward", "bestRiskReward", "long_rr_ratio", "risk_reward_ratio"))
                .riskRewardRatio(firstDouble(data, "risk_reward_ratio", "riskRewardRatio", "best_risk_reward", "long_rr_ratio"))
                .invalidationDistancePct(firstDouble(data, "invalidation_distance_pct", "invalidationDistancePct"))
                .daysToPeakScore(firstDouble(data, "days_to_peak_score", "daysToPeakScore"))

                .relativeStrengthVsSpy(firstDouble(data, "relative_strength_vs_spy", "relativeStrengthVsSpy"))
                .gapPercent(firstDouble(data, "gap_percent", "gapPercent", "Gap Percent"))
                .volatilityScore(firstDouble(data, "volatility_score", "volatilityScore"))
                .momentumScore(firstDouble(data, "momentum_score", "momentumScore", "final_recommendation_score"))
                .volumeScore(firstDouble(data, "volume_score", "volumeScore", "volume_weight"))
                .sectorRotationScore(firstDouble(data, "sector_rotation_score", "sectorRotationScore"))
                .institutionalFlowScore(firstDouble(data, "institutional_flow_score", "institutionalFlowScore", "institutional_score"))

                .avPeRatio(firstDouble(data, "av_pe_ratio", "pe_ratio", "PERatio"))
                .avEps(firstDouble(data, "av_eps", "eps", "EPS"))
                .avMarketCap(firstDouble(data, "av_market_cap", "market_cap", "MarketCapitalization"))
                .avRevenueTtm(firstDouble(data, "av_revenue_ttm", "revenue_ttm", "RevenueTTM"))
                .avAnalystTargetPrice(firstDouble(data, "av_analyst_target_price", "analyst_target_price", "AnalystTargetPrice"))
                .avAvgSentiment(firstDouble(data, "av_avg_sentiment", "avg_sentiment"))
                .epsQualityScore(firstDouble(data, "eps_quality_score", "epsQualityScore"))
                .fundamentalBoost(firstDouble(data, "fundamental_boost", "fundamentalBoost"))
                .newsSentimentScore(firstDouble(data, "news_sentiment_score", "newsSentimentScore", "News Sentiment Score"))
                .newsPositiveRatio(firstDouble(data, "news_positive_ratio", "newsPositiveRatio", "News Positive Ratio"))
                .sentimentConfidence(firstDouble(data, "sentiment_confidence", "sentimentConfidence", "Sentiment Confidence"))

                .etf(firstBoolean(data, "etf", "is_etf"))
                .fundamentalsAvailable(firstBoolean(data, "fundamentals_available", "fundamentalsAvailable", "EPS_AVAILABLE"))
                .build();
    }

    private double firstOrCalcPct(Map<String, Object> data, double price, double avg, String... keys) {
        double explicit = firstDouble(data, keys);
        if (explicit != 0.0) return explicit;
        if (price > 0 && avg > 0) return ((price - avg) / avg) * 100.0;
        return 0.0;
    }

    private String firstString(Map<String, Object> data, String key1, String key2, String def) {
        return firstString(data, key1, key2, null, null, def);
    }

    private String firstString(Map<String, Object> data, String key1, String key2, String key3, String key4, String def) {
        for (String key : new String[]{key1, key2, key3, key4}) {
            if (key == null) continue;
            Object v = lookup(data, key);
            if (v != null && !String.valueOf(v).isBlank()) return String.valueOf(v);
        }
        return def;
    }

    private double firstDouble(Map<String, Object> data, String... keys) {
        for (String key : keys) {
            Object value = lookup(data, key);
            Double parsed = parseDouble(value);
            if (parsed != null) return parsed;
        }
        return 0.0;
    }

    private double firstBooleanAsDouble(Map<String, Object> data, String... keys) {
        return firstBoolean(data, keys) ? 1.0 : 0.0;
    }

    private boolean firstBoolean(Map<String, Object> data, String... keys) {
        for (String key : keys) {
            Object value = lookup(data, key);
            if (value == null) continue;
            if (value instanceof Boolean b) return b;
            if (value instanceof Number n) return n.doubleValue() != 0.0;
            String text = String.valueOf(value).trim();
            if ("true".equalsIgnoreCase(text) || "yes".equalsIgnoreCase(text) || "y".equalsIgnoreCase(text) || "1".equals(text)) return true;
            if ("false".equalsIgnoreCase(text) || "no".equalsIgnoreCase(text) || "n".equalsIgnoreCase(text) || "0".equals(text)) return false;
        }
        return false;
    }

    private Object lookup(Map<String, Object> data, String key) {
        if (data == null || key == null) return null;
        if (data.containsKey(key)) return data.get(key);
        String normalized = normalize(key);
        for (Map.Entry<String, Object> e : data.entrySet()) {
            if (normalize(e.getKey()).equals(normalized)) return e.getValue();
        }
        return null;
    }

    private String normalize(String key) {
        return key == null ? "" : key.trim()
                .replace(" ", "_")
                .replace("-", "_")
                .replaceAll("([a-z])([A-Z])", "$1_$2")
                .toLowerCase(Locale.ROOT);
    }

    private Double parseDouble(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.doubleValue();
        String text = String.valueOf(value).trim();
        if (text.isBlank() || "none".equalsIgnoreCase(text) || "null".equalsIgnoreCase(text) || "nan".equalsIgnoreCase(text)) return null;
        text = text.replace(",", "").replace("%", "");
        try {
            return Double.parseDouble(text);
        } catch (Exception ignored) {
            return null;
        }
    }
}
