package com.trading.ml;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.LinkedHashMap;

/**
 * Stable Java -> Python ML/DL request contract.
 * Python accepts camelCase and snake_case, so keep Java camelCase here.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MlFeaturesRequest {

    private String symbol;
    @Builder.Default
    private String schemaVersion = "v8-java-hedge";
    private String asOfDate;
    @Builder.Default
    private String assetType = "EQUITY";

    // Required core fields expected by Python /schema
    private double currentPrice;
    private double rsi;
    private double adx;
    private double atr14Pct;
    private double vwapDistancePct;
    private double volSurgeRatio;
    private double emaUptrend;
    private double ema21Slope;
    private double dma20;
    private double dma50;
    private double dma200;
    private double pctFromDma20;
    private double pctFromDma50;
    private double pctFromDma200;
    private boolean breakout;
    private boolean bullishEngulfing;
    private boolean hammer;
    private boolean nearSupport;
    private boolean macdCross;
    private double regimeQualityScore;
    private double signalScore;
    private double confidenceScore;
    private double institutionalScore;

    // High-value Java features for hedge-safe Python model
    private double stageConfidence;
    private double substageConfidence;
    private double childSubstageConfidence;
    private double stageScore;
    private double substageScore;
    private double childSubstageScore;
    private double stageRank;
    private double substageRank;
    private double childSubstageRank;

    // Entry / risk
    private double entryQualityScore;
    private double entryConfidenceScore;
    private double bestRiskReward;
    private double riskRewardRatio;
    private double invalidationDistancePct;
    private double daysToPeakScore;

    // Analytics
    private double relativeStrengthVsSpy;
    private double gapPercent;
    private double volatilityScore;
    private double momentumScore;
    private double volumeScore;
    private double sectorRotationScore;
    private double institutionalFlowScore;

    // Alpha Vantage / fundamentals / sentiment
    private double avPeRatio;
    private double avEps;
    private double avMarketCap;
    private double avRevenueTtm;
    private double avAnalystTargetPrice;
    private double avAvgSentiment;
    private double epsQualityScore;
    private double fundamentalBoost;
    private double newsSentimentScore;
    private double newsPositiveRatio;
    private double sentimentConfidence;

    private boolean etf;
    private boolean fundamentalsAvailable;



    public Map<String, Double> getFeatures() {
        Map<String, Double> m = new LinkedHashMap<>();

        m.put("current_price", currentPrice);
        m.put("rsi", rsi);
        m.put("adx", adx);
        m.put("atr14_pct", atr14Pct);
        m.put("vwap_distance_pct", vwapDistancePct);
        m.put("vol_surge_ratio", volSurgeRatio);
        m.put("ema_uptrend", emaUptrend);
        m.put("ema21_slope", ema21Slope);
        m.put("dma20", dma20);
        m.put("dma50", dma50);
        m.put("dma200", dma200);
        m.put("pct_from_dma20", pctFromDma20);
        m.put("pct_from_dma50", pctFromDma50);
        m.put("pct_from_dma200", pctFromDma200);

        m.put("breakout", breakout ? 1.0 : 0.0);
        m.put("bullish_engulfing", bullishEngulfing ? 1.0 : 0.0);
        m.put("hammer", hammer ? 1.0 : 0.0);
        m.put("near_support", nearSupport ? 1.0 : 0.0);
        m.put("macd_cross", macdCross ? 1.0 : 0.0);

        m.put("regime_quality_score", regimeQualityScore);
        m.put("signal_score", signalScore);
        m.put("confidence_score", confidenceScore);
        m.put("institutional_score", institutionalScore);

        m.put("stage_confidence", stageConfidence);
        m.put("substage_confidence", substageConfidence);
        m.put("child_substage_confidence", childSubstageConfidence);
        m.put("stage_score", stageScore);
        m.put("substage_score", substageScore);
        m.put("child_substage_score", childSubstageScore);

        m.put("entry_quality_score", entryQualityScore);
        m.put("entry_confidence_score", entryConfidenceScore);
        m.put("best_risk_reward", bestRiskReward);
        m.put("risk_reward_ratio", riskRewardRatio);
        m.put("invalidation_distance_pct", invalidationDistancePct);
        m.put("days_to_peak_score", daysToPeakScore);

        m.put("relative_strength_vs_spy", relativeStrengthVsSpy);
        m.put("gap_percent", gapPercent);
        m.put("volatility_score", volatilityScore);
        m.put("momentum_score", momentumScore);
        m.put("volume_score", volumeScore);
        m.put("sector_rotation_score", sectorRotationScore);
        m.put("institutional_flow_score", institutionalFlowScore);

        m.put("eps_quality_score", epsQualityScore);
        m.put("fundamental_boost", fundamentalBoost);
        m.put("news_sentiment_score", newsSentimentScore);
        m.put("news_positive_ratio", newsPositiveRatio);
        m.put("sentiment_confidence", sentimentConfidence);
        m.put("fundamentals_available", fundamentalsAvailable ? 1.0 : 0.0);
        m.put("etf", etf ? 1.0 : 0.0);

        return m;
    }

}
