package com.trading.logic;

import com.trading.config.AppRuntimeConfig;
import com.trading.config.YamlConfigService;
import com.trading.entry.Candle;
import com.trading.entry.model.EntryAnalysisResult;
import com.trading.entry.model.EntryEngine;
import com.trading.logic.ChildSubstageClassificationResult;
import com.trading.logic.ChildSubstageSignalEngine;
import com.trading.logic.FinalRecommendationEngine;
import com.trading.logic.StageEngine;
import lombok.Data;
import org.springframework.stereotype.Service;


import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Service
@Data
@SuppressWarnings("unchecked")
public class BusinessLogicService {

    private final YamlConfigService yamlConfigService;
    private final StageEngine stageEngine;
    private final EntryEngine entryEngine;
    private final FinalRecommendationEngine finalRecommendationEngine;
    private final ChildSubstageSignalEngine childSubstageSignalEngine;

    public BusinessLogicService(
            YamlConfigService yamlConfigService,
            StageEngine stageEngine,
            EntryEngine entryEngine,
            FinalRecommendationEngine finalRecommendationEngine,
            ChildSubstageSignalEngine childSubstageSignalEngine
    ) {
        this.yamlConfigService = yamlConfigService;
        this.stageEngine = stageEngine;
        this.entryEngine = entryEngine;
        this.finalRecommendationEngine = finalRecommendationEngine;
        this.childSubstageSignalEngine = childSubstageSignalEngine;
    }

    public Map<String, Object> apply(AppRuntimeConfig config, String symbol, List<Candle> candles, Map<String, Object> metrics) {
        Map<String, Object> row = new LinkedHashMap<>(metrics);
        row.put("symbol", symbol);

        MarketContext context = buildContext(config, candles, metrics);

        // Single source of truth for stage/substage/child-substage classification.
        // StageEngine evaluates every YAML stage, every substage under the selected stage,
        // and every child-substage under every candidate substage for the selected stage.
        StageEngine.StageDecision stageDecision = stageEngine.classify(config, symbol, candles, metrics);

        String marketStage = stageDecision.getMarketStage();
        String marketSubstage = stageDecision.getMarketSubstage();
        String childSubstage = stageDecision.getChildSubstage();

        ChildSubstageSignalEngine.ChildSignalResult childSignal =
                childSubstageSignalEngine.evaluate(
                        ChildSubstageSignalEngine.ChildSignalInput.builder()
                                .marketStage(marketStage)
                                .marketSubstage(marketSubstage)
                                .childSubstage(childSubstage)
                                .rsi(context.rsi)
                                .adx(context.adx)
                                .volumeSurgeRatio(context.vol)
                                .build()
                );

        double substageConfidence = stageDecision.getSubstageConfidence();
        List<String> patterns = detectPatterns(config, metrics, marketStage);
        String trend = classifyTrend(context);

        double signalScore = computeSignalScore(config, context, patterns);
        double institutionalScore = computeInstitutionalScore(config, context, marketStage, marketSubstage);

        double confidenceScore = computeConfidenceScore(config, institutionalScore, context, marketStage, marketSubstage);
        double regimeQualityScore = computeRegimeQuality(config, context, marketStage, marketSubstage, substageConfidence, signalScore, confidenceScore);
        String recommendation = classifyRecommendation(config, context, marketStage, marketSubstage, signalScore, confidenceScore, regimeQualityScore);
        String executionAction = classifyExecutionAction(config, context, marketStage, marketSubstage, recommendation, confidenceScore, regimeQualityScore);
        String finalAction = classifyFinalAction(config, context, marketStage, marketSubstage, recommendation, executionAction, regimeQualityScore);

        // Seed values used only to build the MarketSnapshot for EntryEngine.
        // Final entry/risk/reward values are recalculated dynamically after EntryEngine returns.
        double refinedBuyPrice = refinedBuyPrice(context.price, context.dma20, context.atr14);
        double invalidationLevel = Math.max(0.0, context.price - context.atr14);
        double addOnDipPrice = Math.max(0.0, context.price - (context.atr14 * 0.5));
        double risk = 0.0;
        double reward = 0.0;
        double bestRiskReward = 0.0;
        RiskRewardPlan riskRewardPlan = RiskRewardPlan.empty(context.price);
        boolean ruleBasedBuy = "BUY".equals(recommendation) || "STRONG_BUY".equals(recommendation) || "SCALE_IN".equals(recommendation);

        EntryEngine.EntryRequest entryRequest = EntryEngine.EntryRequest.builder()
                .symbol(symbol)
                .stage(marketStage)
                .substage(marketSubstage)
                .childSubstage(childSubstage)
                .sector(String.valueOf(metrics.getOrDefault("sector", metrics.getOrDefault("av_sector", ""))))
                .industry(String.valueOf(metrics.getOrDefault("industry", metrics.getOrDefault("av_industry", ""))))
                .etf(Boolean.TRUE.equals(metrics.get("is_etf")) || Boolean.TRUE.equals(metrics.get("etf")))
                .currentPrice(context.price)
                .ema9(firstPositive(context.price, context.dma20))
                .ema20(context.dma20)
                .ema21(context.dma20)
                .ema50(context.dma50)
                .ema200(context.dma200)
                .vwap(context.vwap)
                .rsi(context.rsi)
                .adx(context.adx)
                .atr(context.atr14)
                .stageConfidence(stageDecision.getStageConfidence())
                .bestChildConfidence(stageDecision.getBestChildConfidence())
                .candles(toEntryEngineCandles(candles))
                .build();

        EntryEngine.EntryDecision entryDecision = entryEngine.analyze(entryRequest);
        EntryAnalysisResult entryResult = toEntryAnalysisResult(entryDecision, context.price);

        riskRewardPlan = calculateDynamicRiskReward(context, entryResult, marketStage, marketSubstage, childSubstage);
        refinedBuyPrice = riskRewardPlan.entryPrice;
        invalidationLevel = riskRewardPlan.invalidationLevel;
        addOnDipPrice = riskRewardPlan.addOnDipPrice;
        risk = riskRewardPlan.riskPct;
        reward = riskRewardPlan.rewardPct;
        bestRiskReward = riskRewardPlan.bestRiskReward;

        double childScore = childSignal != null ? childSignal.getChildSignalScore() * 10.0 : 50.0;
        double childPenalty = childSignal != null ? childSignal.getChildRiskPenalty() : 0.0;
        String childBias = childSignal != null ? childSignal.getChildActionBias() : "NEUTRAL";

        FinalRecommendationEngine.FinalRecommendationResult finalRec =
                finalRecommendationEngine.decide(
                        FinalRecommendationEngine.FinalRecommendationInput.builder()
                                .symbol(symbol)
                                .marketStage(marketStage)
                                .marketSubstage(marketSubstage)
                                .childSubstage(childSubstage)
                                .rsi(context.rsi)
                                .adx(context.adx)
                                .volumeSurgeRatio(context.vol)
                                .confidenceScore(confidenceScore)
                                .regimeQualityScore(regimeQualityScore)
                                .childSignalScore(childScore)
                                .childRiskPenalty(childPenalty)
                                .childActionBias(childBias)
                                .entryResult(entryResult)
                                .build()
                );
        row.put("market_stage", marketStage);
        row.put("market_substage", marketSubstage);
        row.put("stage_key", marketStage);
        row.put("substage_key", marketSubstage);
        row.put("child_substage_key", childSubstage);
        row.put("qualified_child_key", marketStage + "." + marketSubstage + "." + childSubstage);
        row.put("stage_substage_key", marketStage + "." + marketSubstage);
        row.put("stage_substage_child_key", marketStage + "." + marketSubstage + "." + childSubstage);

        row.put("stage_alignment_score", round(stageDecision.getStageScore()));
        row.put("stage_confidence", round(stageDecision.getStageConfidence()));
        row.put("stage_score", round(stageDecision.getStageScore()));
        row.put("substage_score", round(stageDecision.getSubstageScore()));
        row.put("child_substage_score", round(stageDecision.getChildSubstageScore()));


        row.put("evaluated_stage_count", stageDecision.getEvaluatedStageCount());
        row.put("evaluated_substage_count", stageDecision.getEvaluatedSubstageCount());
        row.put("evaluated_child_substage_count", stageDecision.getEvaluatedChildSubstageCount());
        row.put("stage_score_map", stageDecision.getStageScoreMap().toString());
        row.put("substage_score_map", stageDecision.getSubstageScoreMap().toString());
        row.put("child_substage_score_map", stageDecision.getChildSubstageScoreMap().toString());
        row.put("all_child_substage_score_map", stageDecision.getAllChildSubstageScoreMap().toString());
        row.put("adjusted_all_child_substage_score_map", stageDecision.getAdjustedAllChildSubstageScoreMap().toString());
        row.put("child_scoring_version", stageDecision.getChildScoringVersion());
        row.put("bullish_child_score", round(stageDecision.getBullishChildScore()));
        row.put("bearish_child_score", round(stageDecision.getBearishChildScore()));
        row.put("neutral_child_score", round(stageDecision.getNeutralChildScore()));
        row.put("contradictory_child_score", round(stageDecision.getContradictoryChildScore()));
        row.put("child_contradiction_penalty", round(stageDecision.getChildContradictionPenalty()));
        row.put("best_child_confidence", round(stageDecision.getBestChildConfidence()));
        row.put("ranked_child_confidence_map", stageDecision.getRankedChildConfidenceMap().toString());

        // StageEngine v2 diagnostics: useful in Excel to verify every YAML substage/child was evaluated.
        row.put("ranked_substage_score_map", stageDecision.getRankedSubstageScoreMap().toString());
        row.put("ranked_all_child_substage_score_map", stageDecision.getRankedAllChildSubstageScoreMap().toString());
        row.put("top3_substages", stageDecision.getTop3Substages());
        row.put("top3_child_substages", stageDecision.getTop3ChildSubstages());
        row.put("selected_child_qualified_key", stageDecision.getSelectedChildQualifiedKey());
        row.put("qualified_child_key", stageDecision.getSelectedChildQualifiedKey());
        row.put("stage_substage_key", marketStage + "." + marketSubstage);
        row.put("stage_substage_child_key", stageDecision.getSelectedChildQualifiedKey());
        row.put("child_promotion_reason", stageDecision.getChildPromotionReason());

        row.put("child_conflict_summary", stageDecision.getChildConflictSummary());

        row.put("stage_label", stageDecision.getStageLabel());
        row.put("stage_directional_bias", stageDecision.getStageDirectionalBias());
        row.put("stage_description", stageDecision.getStageDescription());

        row.put("substage_bias", stageDecision.getSubstageBias());
        row.put("entry_style", stageDecision.getEntryStyle());
        row.put("setup_maturity", stageDecision.getSetupMaturity());
        row.put("risk_profile", stageDecision.getRiskProfile());
        row.put("confirmation_needed", false);
        row.put("preferred_trigger", stageDecision.getPreferredTrigger());
        row.put("directional_bias", stageDecision.getSubstageDirectionalBias());
        row.put("substage_description", stageDecision.getSubstageDescription());

        row.put("child_substage", childSubstage);
        row.put("child_substage_confidence", round(stageDecision.getChildSubstageConfidence()));
        row.put("child_substage_family", stageDecision.getChildFamily());
        row.put("child_substage_action_bias", stageDecision.getChildActionBias());
        row.put("child_substage_risk_profile", stageDecision.getChildRiskProfile());
        row.put("child_substage_description", stageDecision.getChildDescription());
        row.put("substage_confidence", round(substageConfidence));

        row.put("child_signal_score", childScore); // normalized (0–100)
        row.put("child_signal_score_raw", childSignal != null ? childSignal.getChildSignalScore() : 0.0);
        row.put("child_risk_penalty", childSignal != null ? childSignal.getChildRiskPenalty() : 0.0);
        row.put("child_action_bias", childSignal != null ? childSignal.getChildActionBias() : "NEUTRAL");
        row.put("child_signal_reason", childSignal != null ? childSignal.getChildSignalReason() : "");

        row.put("regime_quality_score", round(regimeQualityScore));
        row.put("trend", trend);
        row.put("pattern_detected", String.join(", ", patterns));
        row.put("rule_recommendation", recommendation);
        row.put("recommendation", recommendation);
        row.put("execution_action", executionAction);

        row.put("trade_direction", finalRec.getTradeDirection());
        row.put("final_recommendation_score", finalRec.getFinalScore());

        row.put("final_action", finalRec.getFinalAction());
        row.put("decision_reason", finalRec.getDecisionReason());
        row.put("child_score_used", childScore);
        row.put("child_penalty_used", childPenalty);
        row.put("child_bias_used", childBias);

        row.put("rule_based_buy", ruleBasedBuy);
        row.put("signal", recommendation);
        row.put("refined_buy_price", round(refinedBuyPrice));
        row.put("primary_entry_price", round(refinedBuyPrice));
        row.put("invalidation_level_entry", round(invalidationLevel));
        row.put("risk", round(risk));
        row.put("reward", round(reward));
        row.put("best_risk_reward", round(bestRiskReward));
        row.put("buy_window_status", ruleBasedBuy ? 1.0 : 0.0);
        row.put("position_size_class", confidenceScore >= 75 ? "FULL" : confidenceScore >= 55 ? "HALF" : "SMALL");
        row.put("exit_now", marketStage.equals("MARKDOWN"));
        row.put("atr_trailing_stop", round(invalidationLevel));
        row.put("exit_reasons", marketStage.equals("MARKDOWN") ? "DOWNTREND" : "");
        row.put("long_score", round(signalScore));
        row.put("long_setup_tag", marketSubstage);
        row.put("long_verdict", ruleBasedBuy ? "VALID" : "WAIT");
        row.put("long_entry_zone", round(refinedBuyPrice) + " - " + round(context.price));
        row.put("long_entry_zone_low", round(refinedBuyPrice));
        row.put("long_entry_zone_high", round(context.price));
        row.put("long_invalidation", round(invalidationLevel));
        row.put("long_target_1", round(riskRewardPlan.target1));
        row.put("long_target_2", round(riskRewardPlan.target2));
        row.put("long_rr_ratio", round(bestRiskReward));
        row.put("risk_reward_method", riskRewardPlan.method);
        row.put("dynamic_target_1", round(riskRewardPlan.target1));
        row.put("dynamic_target_2", round(riskRewardPlan.target2));
        row.put("short_score", round(100.0 - signalScore));
        row.put("short_setup_tag", marketStage.equals("MARKDOWN") || marketStage.equals("DISTRIBUTION") ? marketSubstage : "NONE");
        row.put("short_verdict", marketStage.equals("MARKDOWN") ? "VALID" : "NO_SETUP");
        row.put("short_entry_zone", "");
        row.put("short_entry_zone_low", 0.0);
        row.put("short_entry_zone_high", 0.0);
        row.put("short_invalidation", 0.0);
        row.put("short_target_1", 0.0);
        row.put("short_target_2", 0.0);
        row.put("short_rr_ratio", 0.0);
        row.put("short_feasibility", marketStage.equals("MARKDOWN") ? "POSSIBLE" : "LOW");
        row.put("shortable_flag", marketStage.equals("MARKDOWN") || marketStage.equals("DISTRIBUTION"));
        row.put("borrow_fee_pct", 0.0);
        row.put("spike_driver", context.breakout && context.vol >= context.strongVol ? "BREAKOUT_VOLUME" : "");
        row.put("drop_driver", marketStage.equals("MARKDOWN") ? "WEAK_RSI" : "");
        row.put("confidence_score", round(confidenceScore));
        row.put("signal_score", round(signalScore));
        row.put("institutional_score", round(institutionalScore));
        row.put("volume_weight", round(context.vol));
        row.put("confidence_grade", confidenceScore >= 75 ? "A" : confidenceScore >= 60 ? "B" : confidenceScore >= 45 ? "C" : "D");
        row.put("expected_holding_period", recommendation.equals("STRONG_BUY") ? "4-8 weeks" : recommendation.equals("BUY") ? "2-6 weeks" : "WAIT");
        row.put("momentum_recommendation", recommendation);
//        row.put("momentum_decision_reason", row.get("decision_reason"));
        row.put("momentum_confidence_grade", confidenceScore >= 75 ? "HIGH" : confidenceScore >= 55 ? "MEDIUM" : "LOW");
        row.put("momentum_expected_holding_period", row.get("expected_holding_period"));
        row.put("invalidation_level_calc", entryResult.getInvalidationLevel());

        row.put("candle_entry_2w", entryResult.getCandleEntry2w());
        row.put("candle_entry_4w", entryResult.getCandleEntry4w());
        row.put("candle_entry_6w", entryResult.getCandleEntry6w());
        row.put("candle_entry_8w", entryResult.getCandleEntry8w());
        row.put("candle_entry_12w", entryResult.getCandleEntry12w());
        row.put("candle_entry_18w", entryResult.getCandleEntry18w());
        row.put("candle_entry_30w", entryResult.getCandleEntry30w());
        // IMPORTANT: Do not overwrite the dynamic risk/reward plan with EntryEngine defaults.
        // EntryEngine still provides candle-window entries and entry-mode diagnostics,
        // but the final Excel entry/stop/RR columns must use RiskRewardPlan.
        row.put("refined_buy_price", round(riskRewardPlan.entryPrice));
        row.put("primary_entry_price", round(riskRewardPlan.entryPrice));
        row.put("primary_entry_source", riskRewardPlan.method);
        row.put("add_on_dip_price", round(riskRewardPlan.addOnDipPrice));
        row.put("aggressive_entry", round(entryResult.getAggressiveEntry()));
        row.put("balanced_entry", round(entryResult.getBalancedEntry()));
        row.put("conservative_entry", round(entryResult.getConservativeEntry()));
        row.put("stop_loss_level", round(riskRewardPlan.invalidationLevel));
        row.put("coil_strength", entryResult.getCoilStrength());
        row.put("entry_confidence_score", entryResult.getEntryConfidenceScore());
        row.put("entry_mode", entryResult.getEntryMode());

// Optional display-name keys if your Excel writer still consumes headers directly:
        row.put("Aggressive Entry", entryResult.getAggressiveEntry());
        row.put("Balanced Entry", entryResult.getBalancedEntry());
        row.put("Conservative Entry", entryResult.getConservativeEntry());
        row.put("Stop Loss Level", round(riskRewardPlan.invalidationLevel));
        row.put("Coil Strength", entryResult.getCoilStrength());
        row.put("Entry Confidence Score", entryResult.getEntryConfidenceScore());
        row.put("Entry Mode", entryResult.getEntryMode());

        // Final Java-owned fusion layer.
        // This fills the quant columns that are not Alpha Vantage fields and upgrades the
        // recommendation using StageEngine + fundamentals/sentiment + relative strength + risk.
        applyFundamentalStageFusion(row, context, candles, marketStage, marketSubstage, childSubstage);

        return row;
    }


    private EntryAnalysisResult toEntryAnalysisResult(EntryEngine.EntryDecision d, double fallbackPrice) {
        double primary = positiveOr(d != null ? d.getPrimaryEntry() : 0.0, fallbackPrice);
        double refined = positiveOr(d != null ? d.getRefinedBuyPrice() : 0.0, primary);
        double stop = positiveOr(d != null ? d.getStopLoss() : 0.0, refined * 0.92);
        if (stop >= refined) {
            stop = refined * 0.92;
        }
        double invalidation = positiveOr(d != null ? d.getInvalidationLevel() : 0.0, stop);
        if (invalidation >= refined) {
            invalidation = stop;
        }
        double riskPerShare = Math.max(0.01, refined - stop);

        return EntryAnalysisResult.builder()
                .symbol(d != null ? d.getSymbol() : "UNKNOWN")
                .candleEntry2w(positiveOr(d != null ? d.getCandleEntry2w() : 0.0, primary))
                .candleEntry4w(positiveOr(d != null ? d.getCandleEntry4w() : 0.0, primary))
                .candleEntry6w(positiveOr(d != null ? d.getCandleEntry6w() : 0.0, primary))
                .candleEntry8w(positiveOr(d != null ? d.getCandleEntry8w() : 0.0, primary))
                .candleEntry12w(positiveOr(d != null ? d.getCandleEntry12w() : 0.0, primary))
                .candleEntry18w(positiveOr(d != null ? d.getCandleEntry18w() : 0.0, primary))
                .candleEntry30w(positiveOr(d != null ? d.getCandleEntry30w() : 0.0, primary))
                .primaryEntryPrice(primary)
                .refinedBuyPrice(refined)
                .invalidationLevel(invalidation)
                .addOnDipPrice(Math.max(0.01, refined - (riskPerShare * 0.50)))
                .aggressiveEntry(positiveOr(d != null ? d.getAggressiveEntry() : 0.0, primary))
                .balancedEntry(positiveOr(d != null ? d.getBalancedEntry() : 0.0, refined))
                .conservativeEntry(positiveOr(d != null ? d.getConservativeEntry() : 0.0, refined - (riskPerShare * 0.25)))
                .stopLossLevel(stop)
                .coilStrength(0.0)
                .entryConfidenceScore(d != null ? d.getConfidence() : 0.0)
                .entryMode(d != null ? d.getMode() : "STANDARD")
                .entryQualityScore(d != null ? d.getQuality() : 0.0)
                .decision(d != null ? d.getDecision() : "WATCH")
                .reason(d != null ? d.getReason() : "ENTRY_DECISION_FALLBACK")
                .build();
    }

    private double positiveOr(double value, double fallback) {
        return Double.isFinite(value) && value > 0.0 ? value : Math.max(0.01, fallback);
    }

    private List<EntryEngine.Candle> toEntryEngineCandles(List<Candle> candles) {
        if (candles == null || candles.isEmpty()) {
            return List.of();
        }

        return candles.stream()
                .filter(c -> c != null)
                .map(c -> EntryEngine.Candle.builder()
                        .open(c.getOpen())
                        .high(c.getHigh())
                        .low(c.getLow())
                        .close(c.getClose())
                        .volume(c.getVolume())
                        .build())
                .toList();
    }

    private double firstPositive(double... values) {
        if (values == null) {
            return 0.0;
        }
        for (double value : values) {
            if (Double.isFinite(value) && value > 0.0) {
                return value;
            }
        }
        return 0.0;
    }

    private MarketContext buildContext(AppRuntimeConfig config, List<Candle> candles, Map<String, Object> metrics) {
        Map<String, Object> thresholds = yamlConfigService.asMap(yamlConfigService.asMap(config.getSubstages()).get("stage_thresholds"));
        MarketContext c = new MarketContext();
        c.candles = candles == null ? List.of() : candles;
        c.metrics = metrics;
        c.price = d(metrics, "current_price");
        c.vwap = d(metrics, "vwap");
        c.adx = d(metrics, "adx");
        c.rsi = d(metrics, "rsi");
        c.dma20 = d(metrics, "dma20");
        c.dma50 = d(metrics, "dma50");
        c.dma200 = d(metrics, "dma200");
        c.atr14 = d(metrics, "atr14");
        c.vol = d(metrics, "vol_surge_ratio");
        c.breakout = b(metrics, "breakout");
        c.hammer = b(metrics, "hammer");
        c.engulf = b(metrics, "bullish_engulfing");
        c.nearSupport = b(metrics, "near_support");
        c.dipReclaim = b(metrics, "dipreclaim");
        c.macdCross = b(metrics, "macd_cross");
        c.compressionRange = get(thresholds, "compression_range_max_pct", 0.08);
        c.tightCompression = get(thresholds, "tight_compression_range_max_pct", 0.04);
        c.adxTrend = get(thresholds, "adx_trend_min", 18);
        c.strongTrendAdx = get(thresholds, "adx_strong_trend_min", 25);
        c.rsiBull = get(thresholds, "rsi_bull_min", 55);
        c.rsiBear = get(thresholds, "rsi_bear_max", 45);
        c.exhaustionHigh = get(thresholds, "rsi_exhaustion_high", 72);
        c.exhaustionLow = get(thresholds, "rsi_exhaustion_low", 28);
        c.strongVol = get(thresholds, "strong_volume_surge_min", 1.60);
        c.range20 = rangePct(c.candles, 20);
        c.bullStack = c.price > c.dma20 && c.dma20 > c.dma50 && c.dma50 > c.dma200;
        c.bearStack = c.price < c.dma20 && c.dma20 < c.dma50 && c.dma50 < c.dma200;
        c.priceAboveVwap = c.price >= c.vwap;
        c.priceBelowVwap = c.price <= c.vwap;
        return c;
    }


    private String classifyStage(AppRuntimeConfig config, MarketContext c) {
        Map<String, Object> root = yamlConfigService.asMap(config.getSubstages());
        Map<String, Object> marketStages = yamlConfigService.asMap(root.get("market_stages"));
        Map<String, Object> stageRules = yamlConfigService.asMap(root.get("stage_rules"));
        Map<String, Object> substageRules = yamlConfigService.asMap(root.get("substage_rules"));
        Map<String, Object> stageBoosts = yamlConfigService.asMap(root.get("stage_boosts"));

        String bestStage = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (String stage : marketStages.keySet()) {
            Map<String, Object> stageMeta = yamlConfigService.asMap(marketStages.get(stage));
            Map<String, Object> substages = yamlConfigService.asMap(stageMeta.get("substages"));
            double score = 0.0;

            // Fully YAML-driven stage boosts. Do not hardcode stage names or substage names.
            score += scoreSignalMap(yamlConfigService.asMap(stageBoosts.get(stage)), c);

            // Consider every explicit stage rule and every substage rule under the stage.
            score += bestRuleScore(yamlConfigService.asMap(stageRules.get(stage)), c);
            score += bestRuleScore(yamlConfigService.asMap(substageRules.get(stage)), c) * 0.85;

            // Consider every substage value from market_stages even if explicit rule YAML is absent.
            double metadataScore = 0.0;
            for (Map.Entry<String, Object> substageEntry : substages.entrySet()) {
                Map<String, Object> substageMeta = yamlConfigService.asMap(substageEntry.getValue());
                metadataScore = Math.max(metadataScore, scoreSubstageMetadata(substageMeta, c));
            }
            score += metadataScore * 0.35;
            score += scoreStageMetadata(stageMeta, c);

            if (score > bestScore) {
                bestScore = score;
                bestStage = stage;
            }
        }

        return bestStage != null ? bestStage : marketStages.keySet().stream().findFirst().orElse("UNKNOWN");
    }

    private boolean higherLowStructure(List<Candle> candles, int lookback) {
        if (candles == null || candles.size() < lookback + 2) {
            return false;
        }

        int start = Math.max(1, candles.size() - lookback);
        int higherLowCount = 0;
        int comparisons = 0;

        for (int i = start; i < candles.size(); i++) {
            double currentLow = candles.get(i).getLow();
            double previousLow = candles.get(i - 1).getLow();

            if (currentLow > previousLow) {
                higherLowCount++;
            }

            comparisons++;
        }

        if (comparisons == 0) {
            return false;
        }

        return ((double) higherLowCount / comparisons) >= 0.60;
    }

    private String classifySubstage(AppRuntimeConfig config, MarketContext c, String stage) {
        Map<String, Object> root = yamlConfigService.asMap(config.getSubstages());
        Map<String, Object> marketStages = yamlConfigService.asMap(root.get("market_stages"));
        Map<String, Object> stageMeta = yamlConfigService.asMap(marketStages.get(stage));
        Map<String, Object> substages = yamlConfigService.asMap(stageMeta.get("substages"));
        Map<String, Object> substageRules = yamlConfigService.asMap(root.get("substage_rules"));
        Map<String, Object> stageRules = yamlConfigService.asMap(root.get("stage_rules"));
        Map<String, Object> rulesForStage = yamlConfigService.asMap(substageRules.get(stage));
        Map<String, Object> stageLevelRulesForStage = yamlConfigService.asMap(stageRules.get(stage));

        String bestSubstage = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        // Iterate every substage from market_stages so no value in substages.yml is ignored.
        for (Map.Entry<String, Object> substageEntry : substages.entrySet()) {
            String substage = substageEntry.getKey();
            Map<String, Object> substageMeta = yamlConfigService.asMap(substageEntry.getValue());

            double score = 0.0;
            score += scoreRuleSet(yamlConfigService.asMap(rulesForStage.get(substage)), c);
            score += scoreRuleSet(yamlConfigService.asMap(stageLevelRulesForStage.get(substage)), c) * 0.40;
            score += scoreSubstageMetadata(substageMeta, c) * 0.30;
            score += sequenceTieBreaker(substageMeta) * 0.001;

            if (score > bestScore) {
                bestScore = score;
                bestSubstage = substage;
            }
        }

        if (bestSubstage != null && isValidSubstage(config, stage, bestSubstage)) {
            return bestSubstage;
        }
        return defaultSubstageForStage(config, stage);
    }

    private double computeSubstageConfidence(AppRuntimeConfig config, MarketContext c, String stage, String substage) {
        Map<String, Object> root = yamlConfigService.asMap(config.getSubstages());
        Map<String, Object> weights = yamlConfigService.asMap(root.get("confidence_weights"));
        Map<String, Object> substageRules = yamlConfigService.asMap(root.get("substage_rules"));
        Map<String, Object> rule = yamlConfigService.asMap(yamlConfigService.asMap(substageRules.get(stage)).get(substage));
        Map<String, Object> marketStages = yamlConfigService.asMap(root.get("market_stages"));
        Map<String, Object> substageMeta = yamlConfigService.asMap(
                yamlConfigService.asMap(
                        yamlConfigService.asMap(marketStages.get(stage)).get("substages")
                ).get(substage)
        );

        double trendWeight = get(weights, "trend_alignment", 22.0);
        double structureWeight = get(weights, "structure_quality", 22.0);
        double volumeWeight = get(weights, "volume_confirmation", 18.0);
        double momentumWeight = get(weights, "momentum_confirmation", 14.0);
        double srWeight = get(weights, "support_resistance_context", 14.0);
        double candleWeight = get(weights, "candle_confirmation", 10.0);

        double totalWeight = trendWeight + structureWeight + volumeWeight + momentumWeight + srWeight + candleWeight;
        double trendAlignment = stageDirectionalAlignment(config, stage, c);
        double structureQuality = Math.max(
                declarativeRuleFit(config, stage, substage, c),
                clamp(scoreSubstageMetadata(substageMeta, c) / 25.0, 0.0, 1.0)
        );
        double volumeConfirmation = clamp(c.vol / Math.max(1.0, c.strongVol), 0.0, 1.0);
        double momentumConfirmation = declarativeMomentumFit(config, stage, substage, c);
        double srContext = declarativeSupportResistanceFit(config, stage, substage, c);
        double candleConfirmation = candleFit(c);

        double rawScore =
                (trendAlignment * trendWeight) +
                        (structureQuality * structureWeight) +
                        (volumeConfirmation * volumeWeight) +
                        (momentumConfirmation * momentumWeight) +
                        (srContext * srWeight) +
                        (candleConfirmation * candleWeight);

        rawScore += declarativeConfidenceAdjustment(config, stage, substage, c);

        return clamp((rawScore / Math.max(1.0, totalWeight)) * 100.0, 0.0, 100.0);
    }


    private double candleFit(MarketContext c) {
        double score = 0.0;

        score += signalStrength("hammer", c) * 0.4;
        score += signalStrength("bullish_engulfing", c) * 0.4;
        score += signalStrength("dip_reclaim", c) * 0.3;
        score += signalStrength("volume_surge", c) * 0.3;

        return clamp(score, 0.0, 1.0);
    }

    private double scoreRuleSet(Map<String, Object> rule, MarketContext c) {
        if (rule == null || rule.isEmpty()) {
            return 0.0;
        }

        Map<String, Object> gates = yamlConfigService.asMap(rule.get("gates"));
        if (!passesRuleGates(gates, c)) {
            return -1_000_000.0;
        }

        double score = 0.0;

        // Preferred YAML shape:
        // signals:
        //   rsi: { min: 50, max: 64, weight: 6 }
        // Also supports compact shape:
        // signals:
        //   rsi_between: [50, 64]
        //   bullStack_eq: true
        Map<String, Object> signals = yamlConfigService.asMap(rule.get("signals"));
        score += scoreSignalMap(signals, c);

        // Backward-compatible support for rules that accidentally placed compact
        // signals directly under the rule instead of under "signals".
        for (Map.Entry<String, Object> entry : rule.entrySet()) {
            String key = entry.getKey();
            if ("signals".equalsIgnoreCase(key) || "gates".equalsIgnoreCase(key) || "penalties".equalsIgnoreCase(key)) {
                continue;
            }
            if (looksLikeSignalKey(key)) {
                score += scoreOneSignal(key, entry.getValue(), c);
            }
        }

        List<Object> penalties = listOf(rule.get("penalties"));
        for (Object penalty : penalties) {
            score -= penaltyValue(String.valueOf(penalty), c);
        }

        return score;
    }

    private double parseSignalWeight(Object raw) {
        return signalWeight(raw);
    }

    private boolean passesRuleGates(Map<String, Object> gates, MarketContext c) {
        if (gates.isEmpty()) return true;

        for (Map.Entry<String, Object> gate : gates.entrySet()) {
            if (!matchesGate(normalizeFeatureName(gate.getKey()), gate.getValue(), c)) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesGate(String feature, Object expected, MarketContext c) {
        if (expected == null) return true;

        String key = normalizeFeatureName(feature);
        if (key.endsWith("_min")) {
            return numericFeature(key.substring(0, key.length() - 4), c) >= numericBound(expected);
        }
        if (key.endsWith("_max")) {
            return numericFeature(key.substring(0, key.length() - 4), c) <= numericBound(expected);
        }
        if (key.endsWith("_gte")) {
            return numericFeature(key.substring(0, key.length() - 4), c) >= numericBound(expected);
        }
        if (key.endsWith("_lte")) {
            return numericFeature(key.substring(0, key.length() - 4), c) <= numericBound(expected);
        }
        if (key.endsWith("_gt")) {
            return numericFeature(key.substring(0, key.length() - 3), c) > numericBound(expected);
        }
        if (key.endsWith("_lt")) {
            return numericFeature(key.substring(0, key.length() - 3), c) < numericBound(expected);
        }
        if (key.endsWith("_eq")) {
            return fallbackMatchGate(key.substring(0, key.length() - 3), expected, c);
        }
        if (key.endsWith("_between")) {
            return fallbackMatchGate(key.substring(0, key.length() - 8), expected, c);
        }

        return fallbackMatchGate(key, expected, c);
    }


    private boolean fallbackMatchGate(String feature, Object expected, MarketContext c) {
        if (expected == null) return true;

        if (expected instanceof Boolean b) {
            return boolFeature(feature, c) == b;
        }

        if (expected instanceof Number n) {
            return numericFeature(feature, c) >= n.doubleValue();
        }

        if (expected instanceof List<?> list) {
            if (list.size() >= 2 && isNumericLike(list.get(0)) && isNumericLike(list.get(1))) {
                double min = safeDouble(list.get(0), Double.NEGATIVE_INFINITY);
                double max = safeDouble(list.get(1), Double.POSITIVE_INFINITY);
                return inRange(numericFeature(feature, c), min, max);
            }

            String enumValue = enumFeature(feature, c);
            for (Object item : list) {
                if (enumValue.equalsIgnoreCase(String.valueOf(item).trim())) {
                    return true;
                }
            }
            return false;
        }

        String raw = String.valueOf(expected).trim();
        if (raw.isEmpty()) return true;

        if (raw.startsWith("!")) return !fallbackMatchGate(feature, raw.substring(1).trim(), c);

        if ("true".equalsIgnoreCase(raw) || "false".equalsIgnoreCase(raw)) {
            return boolFeature(feature, c) == Boolean.parseBoolean(raw);
        }

        if (isBracketRange(raw)) {
            double[] range = parseBracketRange(raw);
            return inRange(numericFeature(feature, c), range[0], range[1]);
        }

        if (startsWithComparator(raw)) {
            return compareNumeric(numericFeature(feature, c), raw);
        }

        if (isPureNumber(raw)) {
            return numericFeature(feature, c) >= safeDouble(raw, 0.0);
        }

        return enumFeature(feature, c).equalsIgnoreCase(raw);
    }

    private double ruleMatchRatio(Map<String, Object> signals, MarketContext c) {
        if (signals == null || signals.isEmpty()) return 0.0;
        double matched = 0.0;
        double total = 0.0;

        for (Map.Entry<String, Object> signal : signals.entrySet()) {
            double weight = signalWeightForKey(signal.getKey(), signal.getValue());
            if (weight <= 0.0) {
                continue;
            }
            total += weight;
            matched += scoreOneSignal(signal.getKey(), signal.getValue(), c);
        }

        return total == 0.0 ? 0.0 : clamp(matched / total, 0.0, 1.0);
    }

    private double scoreSignalMap(Map<String, Object> signals, MarketContext c) {
        if (signals == null || signals.isEmpty()) {
            return 0.0;
        }
        double score = 0.0;
        for (Map.Entry<String, Object> signal : signals.entrySet()) {
            score += scoreOneSignal(signal.getKey(), signal.getValue(), c);
        }
        return score;
    }

    private double scoreOneSignal(String rawKey, Object descriptor, MarketContext c) {
        String key = rawKey == null ? "" : rawKey.trim();
        if (key.isBlank()) {
            return 0.0;
        }

        String feature = extractFeatureFromSignalKey(key);
        double weight = signalWeightForKey(key, descriptor);
        if (weight <= 0.0) {
            return 0.0;
        }

        double matchScore = yamlSignalMatchScore(key, descriptor, c);
        return weight * clamp(matchScore, 0.0, 1.0);
    }

    private double signalWeightForKey(String rawKey, Object descriptor) {
        String operator = extractOperatorFromSignalKey(rawKey);
        if (!operator.isBlank()) {
            Map<String, Object> map = asDescriptorMap(descriptor);
            if (!map.isEmpty() && map.containsKey("weight")) {
                return signalWeight(descriptor);
            }
            return 1.0;
        }
        return signalWeight(descriptor);
    }

    private double bestRuleScore(Map<String, Object> rules, MarketContext c) {
        if (rules == null || rules.isEmpty()) {
            return 0.0;
        }

        double best = 0.0;
        for (Object rawRule : rules.values()) {
            double score = scoreRuleSet(yamlConfigService.asMap(rawRule), c);
            if (score > best) {
                best = score;
            }
        }
        return best;
    }

    private boolean looksLikeSignalKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String normalized = normalizeFeatureName(key);
        return normalized.endsWith("_eq")
                || normalized.endsWith("_gte")
                || normalized.endsWith("_lte")
                || normalized.endsWith("_gt")
                || normalized.endsWith("_lt")
                || normalized.endsWith("_between")
                || normalized.endsWith("_min")
                || normalized.endsWith("_max")
                || signalStrength(extractFeatureFromSignalKey(key), new MarketContext()) > 0.0;
    }

    private String extractFeatureFromSignalKey(String rawKey) {
        String key = normalizeFeatureName(rawKey);
        for (String suffix : List.of("_between", "_gte", "_lte", "_gt", "_lt", "_eq", "_min", "_max")) {
            if (key.endsWith(suffix)) {
                return key.substring(0, key.length() - suffix.length());
            }
        }
        return key;
    }

    private String extractOperatorFromSignalKey(String rawKey) {
        String key = normalizeFeatureName(rawKey);
        for (String suffix : List.of("_between", "_gte", "_lte", "_gt", "_lt", "_eq", "_min", "_max")) {
            if (key.endsWith(suffix)) {
                return suffix.substring(1);
            }
        }
        return "";
    }

    private double yamlSignalMatchScore(String rawKey, Object descriptor, MarketContext c) {
        String operator = extractOperatorFromSignalKey(rawKey);
        String feature = extractFeatureFromSignalKey(rawKey);

        if ("between".equals(operator)) {
            return matchListDescriptor(feature, descriptor instanceof List<?> l ? l : List.of(), c);
        }
        if ("gte".equals(operator) || "min".equals(operator)) {
            return numericFeature(feature, c) >= safeDouble(descriptor, 0.0) ? 1.0 : 0.0;
        }
        if ("lte".equals(operator) || "max".equals(operator)) {
            return numericFeature(feature, c) <= safeDouble(descriptor, 0.0) ? 1.0 : 0.0;
        }
        if ("gt".equals(operator)) {
            return numericFeature(feature, c) > safeDouble(descriptor, 0.0) ? 1.0 : 0.0;
        }
        if ("lt".equals(operator)) {
            return numericFeature(feature, c) < safeDouble(descriptor, 0.0) ? 1.0 : 0.0;
        }
        if ("eq".equals(operator)) {
            return signalMatchScore(feature, descriptor, c);
        }

        return signalMatchScore(feature, descriptor, c);
    }

    private double recentSwingLow(List<Candle> candles, int lookback) {
        if (candles == null || candles.isEmpty()) {
            return 0.0;
        }

        int start = Math.max(0, candles.size() - lookback);

        return candles.subList(start, candles.size())
                .stream()
                .mapToDouble(Candle::getLow)
                .min()
                .orElse(0.0);
    }


    private double scoreStageMetadata(Map<String, Object> stageMeta, MarketContext c) {
        String directionalBias = YamlConfigService.stringValue(stageMeta.get("directional_bias")).toUpperCase();
        double score = 0.0;

        if (directionalBias.contains("BULLISH")) {
            score += (c.bullStack ? 12.0 : 0.0);
            score += (c.priceAboveVwap ? 6.0 : 0.0);
            score += normalized(c.rsi, c.rsiBull, c.exhaustionHigh) * 8.0;
        }
        if (directionalBias.contains("BEARISH")) {
            score += (c.bearStack ? 12.0 : 0.0);
            score += (c.priceBelowVwap ? 6.0 : 0.0);
            score += normalized(50.0 - c.rsi, 50.0 - c.rsiBear, 50.0 - c.exhaustionLow) * 8.0;
        }
        if (directionalBias.contains("REVERSAL") || directionalBias.contains("NEUTRAL")) {
            score += inverseRatio(c.range20, c.compressionRange) * 8.0;
            score += (c.nearSupport ? 6.0 : 0.0);
        }

        return score;
    }

    private double scoreSubstageMetadata(Map<String, Object> substageMeta, MarketContext c) {
        double score = 0.0;
        String bias = YamlConfigService.stringValue(substageMeta.get("bias")).toUpperCase();
        String directionalBias = YamlConfigService.stringValue(substageMeta.get("directional_bias")).toUpperCase();
        String preferredTrigger = YamlConfigService.stringValue(substageMeta.get("preferred_trigger")).toUpperCase();
        String entryStyle = YamlConfigService.stringValue(substageMeta.get("entry_style")).toUpperCase();
        String riskProfile = YamlConfigService.stringValue(substageMeta.get("risk_profile")).toUpperCase();

        if (bias.contains("BULLISH") || directionalBias.contains("BULLISH")) {
            score += (c.bullStack ? 10.0 : 0.0) + (c.priceAboveVwap ? 5.0 : 0.0);
            score += normalized(c.rsi, 45.0, c.exhaustionHigh) * 5.0;
        }
        if (bias.contains("BEARISH") || directionalBias.contains("BEARISH")) {
            score += (c.bearStack ? 10.0 : 0.0) + (c.priceBelowVwap ? 5.0 : 0.0);
            score += normalized(50.0 - c.rsi, 50.0 - c.rsiBear, 50.0 - c.exhaustionLow) * 5.0;
        }
        if (bias.contains("NEUTRAL") || directionalBias.contains("NEUTRAL")) {
            score += inverseRatio(c.range20, c.compressionRange) * 8.0;
        }

        if (preferredTrigger.contains("BREAKOUT") || preferredTrigger.contains("RANGE_EXPANSION")) {
            score += c.breakout ? 8.0 : 0.0;
            score += normalized(c.vol, 1.0, c.strongVol) * 4.0;
        }
        if (preferredTrigger.contains("SUPPORT") || preferredTrigger.contains("RECLAIM") || preferredTrigger.contains("SPRING")) {
            score += c.nearSupport ? 6.0 : 0.0;
            score += (c.hammer || c.engulf || c.dipReclaim) ? 4.0 : 0.0;
        }
        if (preferredTrigger.contains("VOLUME")) {
            score += normalized(c.vol, 1.0, c.strongVol) * 8.0;
        }
        if (preferredTrigger.contains("NONE") || entryStyle.contains("WAIT")) {
            score += 1.0;
        }
        if (riskProfile.contains("VERY_HIGH") && (c.rsi >= c.exhaustionHigh || c.rsi <= c.exhaustionLow)) {
            score += 3.0;
        }

        return score;
    }

    private double sequenceTieBreaker(Map<String, Object> substageMeta) {
        Map<String, Object> granular = yamlConfigService.asMap(substageMeta.get("granular_classification"));
        return safeDouble(granular.get("sequence_rank"), 0.0);
    }


    /**
     * YAML-driven signal strength resolver.
     * <p>
     * No switch-case is required for signal keys. The YAML signal name is normalized and resolved in this order:
     * 1) Numeric metric / derived numeric field when the signal uses numeric operators, e.g. rsi_between, vol_gte.
     * 2) Boolean metric / derived boolean field for _eq:true or plain boolean descriptors.
     * 3) Registered structural candle predicates for advanced Wyckoff/SMC structures.
     * 4) Raw metrics map fallback, so adding a new metric column can be used by YAML without Java changes.
     */
    private double signalStrength(String feature, MarketContext c) {
        String key = normalizeFeatureName(feature);
        if (key.isBlank()) {
            return 0.0;
        }

        Map<String, Function<MarketContext, Double>> numericSignals = numericSignalRegistry();
        Function<MarketContext, Double> numericFn = numericSignals.get(key);
        if (numericFn != null) {
            return clamp(numericFn.apply(c), 0.0, 1.0);
        }

        Map<String, Function<MarketContext, Boolean>> booleanSignals = booleanSignalRegistry();
        Function<MarketContext, Boolean> booleanFn = booleanSignals.get(key);
        if (booleanFn != null) {
            return Boolean.TRUE.equals(booleanFn.apply(c)) ? 1.0 : 0.0;
        }

        if (metricBoolean(c.metrics, key)) {
            return 1.0;
        }

        double metricValue = metricNumber(c.metrics, key);
        if (metricValue != 0.0) {
            return clamp(metricValue, 0.0, 1.0);
        }

        return 0.0;
    }

    private double penaltyValue(String penalty, MarketContext c) {
        String key = normalizeFeatureName(penalty);
        if (key.isBlank()) {
            return 0.0;
        }

        Map<String, Function<MarketContext, Double>> penalties = new LinkedHashMap<>();
        penalties.put("failed_spring", ctx -> isFailedSpring(ctx.candles, ctx.metrics) ? 10.0 : 0.0);
        penalties.put("failed_breakout", ctx -> failedBreakout(ctx.candles) ? 8.0 : 0.0);
        penalties.put("failed_breakdown", ctx -> isFailedBreakdown(ctx.candles) ? 8.0 : 0.0);
        penalties.put("late_exhaustion", ctx -> ctx.rsi >= ctx.exhaustionHigh ? 6.0 : 0.0);
        penalties.put("panic", ctx -> ctx.rsi <= ctx.exhaustionLow && ctx.vol >= ctx.strongVol ? 6.0 : 0.0);

        Function<MarketContext, Double> fn = penalties.get(key);
        return fn == null ? 0.0 : Math.max(0.0, fn.apply(c));
    }

    private double numericFeature(String feature, MarketContext c) {
        String key = normalizeFeatureName(feature);
        if (key.isBlank()) {
            return 0.0;
        }

        Function<MarketContext, Double> fn = rawNumericFeatureRegistry().get(key);
        if (fn != null) {
            return fn.apply(c);
        }

        // Allow new YAML fields to be introduced without changing Java.
        // Example: signals: { my_new_factor_gte: 0.65 } will read metrics["my_new_factor"].
        return metricNumber(c.metrics, key);
    }

    private boolean boolFeature(String feature, MarketContext c) {
        String key = normalizeFeatureName(feature);
        if (key.isBlank()) {
            return false;
        }

        Function<MarketContext, Boolean> fn = booleanSignalRegistry().get(key);
        if (fn != null) {
            return Boolean.TRUE.equals(fn.apply(c));
        }

        // Allow new boolean YAML fields to be introduced without changing Java,
        // as long as the metric is populated before BusinessLogicService.apply().
        return metricBoolean(c.metrics, key);
    }

    private Map<String, Function<MarketContext, Double>> rawNumericFeatureRegistry() {
        Map<String, Function<MarketContext, Double>> map = new LinkedHashMap<>();

        registerNumeric(map, ctx -> ctx.adx, "adx");
        registerNumeric(map, ctx -> ctx.rsi, "rsi");
        registerNumeric(map, ctx -> ctx.vol, "volume_ratio", "vol", "vol_surge_ratio");
        registerNumeric(map, ctx -> ctx.range20, "range20", "range_20");
        registerNumeric(map, ctx -> d(ctx.metrics, "pct_from_dma20"), "pct_from_dma20");
        registerNumeric(map, ctx -> ctx.price, "price", "current_price", "current");
        registerNumeric(map, ctx -> ctx.dma20, "dma20");
        registerNumeric(map, ctx -> ctx.dma50, "dma50");
        registerNumeric(map, ctx -> ctx.dma200, "dma200");
        registerNumeric(map, ctx -> ctx.atr14, "atr14");
        registerNumeric(map, ctx -> ctx.vwap, "vwap");

        return map;
    }

    private Map<String, Function<MarketContext, Double>> numericSignalRegistry() {
        Map<String, Function<MarketContext, Double>> map = new LinkedHashMap<>();

        map.putAll(rawNumericFeatureRegistry());

        registerNumeric(map, ctx -> inverseRatio(ctx.range20, ctx.compressionRange), "compression");
        registerNumeric(map, ctx -> inverseRatio(ctx.range20, ctx.tightCompression), "tight_compression");
        registerNumeric(map, ctx -> normalized(ctx.adx, ctx.adxTrend, ctx.strongTrendAdx), "adx_trend");
        registerNumeric(map, ctx -> normalized(ctx.adx, ctx.strongTrendAdx, ctx.strongTrendAdx * 1.5), "adx_strong");
        registerNumeric(map, ctx -> normalized(ctx.rsi, ctx.rsiBull, ctx.exhaustionHigh), "rsi_bull");
        registerNumeric(map, ctx -> normalized(50.0 - ctx.rsi, 50.0 - ctx.rsiBear, 50.0 - ctx.exhaustionLow), "rsi_bear");
        registerNumeric(map, ctx -> inverseRatio(ctx.rsi, ctx.exhaustionLow), "rsi_exhaustion_low");
        registerNumeric(map, ctx -> normalized(ctx.rsi, ctx.exhaustionHigh, ctx.exhaustionHigh + 10.0), "rsi_exhaustion_high");
        registerNumeric(map, ctx -> normalized(ctx.vol, 1.0, ctx.strongVol), "volume_surge");
        registerNumeric(map, ctx -> normalized(ctx.vol, ctx.strongVol, ctx.strongVol * 1.5), "strong_volume");

        return map;
    }

    private Map<String, Function<MarketContext, Boolean>> booleanSignalRegistry() {
        Map<String, Function<MarketContext, Boolean>> map = new LinkedHashMap<>();

        registerBoolean(map, ctx -> ctx.bullStack, "bull_stack", "bullstack");
        registerBoolean(map, ctx -> ctx.bearStack, "bear_stack", "bearstack");
        registerBoolean(map, ctx -> ctx.priceAboveVwap, "price_above_vwap", "priceabovevwap");
        registerBoolean(map, ctx -> ctx.priceBelowVwap, "price_below_vwap", "pricebelowvwap");
        registerBoolean(map, ctx -> ctx.breakout, "breakout");
        registerBoolean(map, ctx -> ctx.nearSupport, "near_support", "nearsupport");
        registerBoolean(map, ctx -> ctx.dipReclaim, "dip_reclaim", "dipreclaim");
        registerBoolean(map, ctx -> ctx.hammer, "hammer");
        registerBoolean(map, ctx -> ctx.engulf, "bullish_engulfing", "bullishengulfing", "engulf");
        registerBoolean(map, ctx -> ctx.macdCross, "macd_cross", "macdcross");

        registerBoolean(map, ctx -> metricBoolean(ctx.metrics, "price_reversal") || metricBoolean(ctx.metrics, "Price Reversal"),
                "price_reversal", "pricereversal");
        registerBoolean(map, ctx -> isFailedBreakdown(ctx.candles), "false_breakdown", "falsebreakdown", "failed_breakdown", "failedbreakdown");
        registerBoolean(map, ctx -> failedBreakout(ctx.candles), "false_breakout", "falsebreakout", "failed_breakout", "failedbreakout");
        registerBoolean(map, ctx -> isFailedBreakAboveResistance(ctx.candles), "failed_break_above_resistance", "failedbreakaboveresistance");
        registerBoolean(map, ctx -> isSpring(ctx.candles, ctx.metrics), "spring");
        registerBoolean(map, ctx -> isFailedSpring(ctx.candles, ctx.metrics), "failed_spring", "failedspring");
        registerBoolean(map, ctx -> isFailedBreakdown(ctx.candles) || isSpring(ctx.candles, ctx.metrics),
                "liquidity_grab_low", "liquiditygrablow");
        registerBoolean(map, ctx -> failedBreakout(ctx.candles) || isFailedBreakAboveResistance(ctx.candles),
                "liquidity_grab_high", "liquiditygrabhigh");
        registerBoolean(map, ctx -> higherLowStructure(ctx.candles, 30), "higher_low", "higherlow", "higher_low_structure", "higherlowstructure");
        registerBoolean(map, ctx -> lowerHighStructure(ctx.candles, 30), "lower_high", "lowerhigh", "lower_high_structure", "lowerhighstructure");
        registerBoolean(map, ctx -> lowerLowAcceleration(ctx.candles, 20), "lower_low_acceleration", "lowerlowacceleration");
        registerBoolean(map, ctx -> breakdownFromRange(ctx.candles), "breakdown_from_range", "breakdownfromrange");
        registerBoolean(map, ctx -> isDeadCatBounce(ctx.candles), "dead_cat_bounce", "deadcatbounce");
        registerBoolean(map, ctx -> hasReflexRally(ctx.candles, 8), "reflex_rally", "reflexrally");
        registerBoolean(map, ctx -> isRetestingBottomArea(ctx.candles, 12), "retesting_bottom", "retestingbottom");
        registerBoolean(map, ctx -> isRetestingTopZone(ctx.candles, 12), "retesting_top", "retestingtop");
        registerBoolean(map, ctx -> isPauseInsideLargerUptrend(ctx), "pause_in_uptrend", "pauseinuptrend");
        registerBoolean(map, ctx -> isPauseInsideLargerDowntrend(ctx), "pause_in_downtrend", "pauseindowntrend");
        registerBoolean(map, ctx -> isLateFailedPush(ctx.candles), "late_failed_push", "latefailedpush");
        registerBoolean(map, ctx -> isLowVolumeRetest(ctx.candles, 5), "low_volume_retest", "lowvolumeretest");
        registerBoolean(map, ctx -> ctx.engulf || ctx.hammer || ctx.dipReclaim || (ctx.priceAboveVwap && ctx.vol >= 1.05),
                "demand_candle", "demandcandle");
        registerBoolean(map, ctx -> ctx.priceBelowVwap && ctx.vol >= 1.05, "supply_candle", "supplycandle");
        registerBoolean(map, ctx -> ctx.priceAboveVwap && ctx.dipReclaim, "breakout_retest", "breakoutretest");
        registerBoolean(map, MarketContext::pullbackToTrend, "pullback");
        registerBoolean(map, ctx -> true, "always_true", "alwaystrue");

        return map;
    }


    private void registerNumeric(Map<String, Function<MarketContext, Double>> map,
                                 Function<MarketContext, Double> function,
                                 String... aliases) {
        for (String alias : aliases) {
            map.put(normalizeFeatureName(alias), function);
        }
    }

    private void registerBoolean(Map<String, Function<MarketContext, Boolean>> map,
                                 Function<MarketContext, Boolean> function,
                                 String... aliases) {
        for (String alias : aliases) {
            map.put(normalizeFeatureName(alias), function);
        }
    }

    private String enumFeature(String feature, MarketContext c) {
        String normalizedFeature = normalizeFeatureName(feature);
        if ("trend".equals(normalizedFeature)) return classifyTrend(c);
        return "";
    }


    private double signalWeight(Object descriptor) {
        if (descriptor == null) {
            return 0.0;
        }

        if (descriptor instanceof Number n) {
            return Math.max(0.0, n.doubleValue());
        }

        if (descriptor instanceof Boolean) {
            return 1.0;
        }

        if (descriptor instanceof List<?>) {
            return 1.0;
        }

        Map<String, Object> map = asDescriptorMap(descriptor);
        if (!map.isEmpty()) {
            Object explicitWeight = map.get("weight");
            if (explicitWeight instanceof Number n) {
                return Math.max(0.0, n.doubleValue());
            }
            if (explicitWeight != null) {
                return safeDouble(explicitWeight, 1.0);
            }
            return 1.0;
        }

        String text = String.valueOf(descriptor).trim();
        if (text.isEmpty()) {
            return 0.0;
        }
        if ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)) {
            return 1.0;
        }
        if (isBracketRange(text) || startsWithComparator(text)) {
            return 1.0;
        }

        return safeDouble(text, 1.0);
    }

    private double signalMatchScore(String feature, Object descriptor, MarketContext c) {
        if (descriptor == null) {
            return clamp(signalStrength(feature, c), 0.0, 1.0);
        }

        if (descriptor instanceof Boolean b) {
            return boolFeature(feature, c) == b ? 1.0 : 0.0;
        }

        if (descriptor instanceof Number) {
            double base = clamp(signalStrength(feature, c), 0.0, 1.0);
            return base > 0.0 ? 1.0 : 0.0;
        }

        if (descriptor instanceof List<?> list) {
            return matchListDescriptor(feature, list, c);
        }

        Map<String, Object> map = asDescriptorMap(descriptor);
        if (!map.isEmpty()) {
            return matchMapDescriptor(feature, map, c);
        }

        String raw = String.valueOf(descriptor).trim();
        if (raw.isEmpty()) {
            return 0.0;
        }

        if ("true".equalsIgnoreCase(raw) || "false".equalsIgnoreCase(raw)) {
            return boolFeature(feature, c) == Boolean.parseBoolean(raw) ? 1.0 : 0.0;
        }

        if (isBracketRange(raw)) {
            double[] range = parseBracketRange(raw);
            return inRange(numericFeature(feature, c), range[0], range[1]) ? 1.0 : 0.0;
        }

        if (startsWithComparator(raw)) {
            return compareNumeric(numericFeature(feature, c), raw) ? 1.0 : 0.0;
        }

        if (isPureNumber(raw)) {
            double base = clamp(signalStrength(feature, c), 0.0, 1.0);
            return base > 0.0 ? 1.0 : 0.0;
        }

        return enumFeature(feature, c).equalsIgnoreCase(raw) ? 1.0 : 0.0;
    }

    private double matchListDescriptor(String feature, List<?> list, MarketContext c) {
        if (list.isEmpty()) {
            return 0.0;
        }

        if (list.size() >= 2 && isNumericLike(list.get(0)) && isNumericLike(list.get(1))) {
            double min = safeDouble(list.get(0), Double.NEGATIVE_INFINITY);
            double max = safeDouble(list.get(1), Double.POSITIVE_INFINITY);
            return inRange(numericFeature(feature, c), min, max) ? 1.0 : 0.0;
        }

        String enumValue = enumFeature(feature, c);
        for (Object item : list) {
            if (enumValue.equalsIgnoreCase(String.valueOf(item).trim())) {
                return 1.0;
            }
        }

        return 0.0;
    }

    private double matchMapDescriptor(String feature, Map<String, Object> descriptor, MarketContext c) {
        Object expected = descriptor.containsKey("value") ? descriptor.get("value") : descriptor.get("expected");
        Object min = descriptor.get("min");
        Object max = descriptor.get("max");

        if (expected instanceof Boolean b) {
            return boolFeature(feature, c) == b ? 1.0 : 0.0;
        }

        if (expected instanceof List<?> list) {
            return matchListDescriptor(feature, list, c);
        }

        if (expected != null) {
            return signalMatchScore(feature, expected, c);
        }

        if (min != null || max != null) {
            double value = numericFeature(feature, c);
            double minVal = min == null ? Double.NEGATIVE_INFINITY : safeDouble(min, Double.NEGATIVE_INFINITY);
            double maxVal = max == null ? Double.POSITIVE_INFINITY : safeDouble(max, Double.POSITIVE_INFINITY);
            return inRange(value, minVal, maxVal) ? 1.0 : 0.0;
        }

        return clamp(signalStrength(feature, c), 0.0, 1.0);
    }

    private double numericBound(Object expected) {
        if (expected instanceof Number n) {
            return n.doubleValue();
        }

        String raw = String.valueOf(expected).trim()
                .replace(">=", "")
                .replace("<=", "")
                .replace(">", "")
                .replace("<", "");

        return safeDouble(raw, 0.0);
    }

    private boolean compareNumeric(double actual, String expression) {
        String raw = expression.trim();
        if (raw.startsWith(">=")) {
            return actual >= safeDouble(raw.substring(2), Double.NaN);
        }
        if (raw.startsWith("<=")) {
            return actual <= safeDouble(raw.substring(2), Double.NaN);
        }
        if (raw.startsWith(">")) {
            return actual > safeDouble(raw.substring(1), Double.NaN);
        }
        if (raw.startsWith("<")) {
            return actual < safeDouble(raw.substring(1), Double.NaN);
        }
        if (raw.startsWith("=")) {
            return actual == safeDouble(raw.substring(1), Double.NaN);
        }
        return false;
    }

    private boolean startsWithComparator(String raw) {
        return raw.startsWith(">=")
                || raw.startsWith("<=")
                || raw.startsWith(">")
                || raw.startsWith("<")
                || raw.startsWith("=");
    }

    private boolean isBracketRange(String raw) {
        return raw.startsWith("[") && raw.endsWith("]");
    }

    private double[] parseBracketRange(String raw) {
        String cleaned = raw.substring(1, raw.length() - 1).trim();
        String[] parts = cleaned.split(",");
        if (parts.length < 2) {
            double value = safeDouble(cleaned, 0.0);
            return new double[]{value, value};
        }

        double min = safeDouble(parts[0].trim(), Double.NEGATIVE_INFINITY);
        double max = safeDouble(parts[1].trim(), Double.POSITIVE_INFINITY);
        return new double[]{min, max};
    }

    private boolean inRange(double value, double min, double max) {
        return value >= min && value <= max;
    }

    private boolean isPureNumber(String text) {
        try {
            Double.parseDouble(text);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean isNumericLike(Object value) {
        if (value instanceof Number) {
            return true;
        }
        if (value == null) {
            return false;
        }
        return isPureNumber(String.valueOf(value).trim());
    }

    private double safeDouble(Object raw, double defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        if (raw instanceof Number n) {
            return n.doubleValue();
        }

        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) {
            return defaultValue;
        }

        try {
            return Double.parseDouble(text);
        } catch (Exception ex) {
            return defaultValue;
        }
    }

    private Map<String, Object> asDescriptorMap(Object raw) {
        if (raw instanceof Map<?, ?> m) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : m.entrySet()) {
                normalized.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return normalized;
        }
        return Map.of();
    }

    private String normalizeFeatureName(String feature) {
        if (feature == null) return "";
        return feature.trim()
                .replace('-', '_')
                .replaceAll("([a-z])([A-Z])", "$1_$2")
                .toLowerCase();
    }

    private double normalized(double value, double min, double max) {
        if (max <= min) return value >= min ? 1.0 : 0.0;
        return Math.max(0.0, Math.min(1.0, (value - min) / (max - min)));
    }

    private double inverseRatio(double value, double threshold) {
        if (threshold <= 0) return 0.0;
        return Math.max(0.0, Math.min(1.0, 1.0 - (value / threshold)));
    }

    private double parseThreshold(String raw) {
        return raw == null || raw.isBlank() ? 0.0 : Double.parseDouble(raw.trim());
    }

    private double stageDirectionalAlignment(AppRuntimeConfig config, String stage, MarketContext c) {
        Map<String, Object> root = yamlConfigService.asMap(config.getSubstages());
        Map<String, Object> stages = yamlConfigService.asMap(root.get("market_stages"));
        Map<String, Object> stageMeta = yamlConfigService.asMap(stages.get(stage));
        Map<String, Object> stageBoosts = yamlConfigService.asMap(root.get("stage_boosts"));
        Map<String, Object> stageRules = yamlConfigService.asMap(root.get("stage_rules"));

        double metaScore = scoreStageMetadata(stageMeta, c);
        double boostScore = scoreSignalMap(yamlConfigService.asMap(stageBoosts.get(stage)), c);
        double ruleScore = bestRuleScore(yamlConfigService.asMap(stageRules.get(stage)), c);

        double raw = (metaScore * 0.35) + (boostScore * 0.35) + (ruleScore * 0.30);
        return clamp(raw / 30.0, 0.0, 1.0);
    }

    private double declarativeRuleFit(AppRuntimeConfig config, String stage, String substage, MarketContext c) {
        Map<String, Object> root = yamlConfigService.asMap(config.getSubstages());
        Map<String, Object> substageRules = yamlConfigService.asMap(root.get("substage_rules"));
        Map<String, Object> stageRules = yamlConfigService.asMap(root.get("stage_rules"));
        Map<String, Object> subRule = yamlConfigService.asMap(yamlConfigService.asMap(substageRules.get(stage)).get(substage));
        Map<String, Object> stageRule = yamlConfigService.asMap(yamlConfigService.asMap(stageRules.get(stage)).get(substage));

        double subScore = Math.max(0.0, scoreRuleSet(subRule, c));
        double stageScore = Math.max(0.0, scoreRuleSet(stageRule, c));
        return clamp(((subScore * 0.80) + (stageScore * 0.20)) / 12.0, 0.0, 1.0);
    }

    private double declarativeMomentumFit(AppRuntimeConfig config, String stage, String substage, MarketContext c) {
        Map<String, Object> meta = getSubstageMeta(config, stage, substage);
        String text = (YamlConfigService.stringValue(meta.get("bias")) + " " +
                YamlConfigService.stringValue(meta.get("directional_bias")) + " " +
                YamlConfigService.stringValue(meta.get("preferred_trigger"))).toUpperCase();

        double score = 0.0;
        if (text.contains("BULL")) score = Math.max(score, signalStrength("rsi_bull", c));
        if (text.contains("BEAR")) score = Math.max(score, signalStrength("rsi_bear", c));
        if (text.contains("BREAKOUT") || text.contains("EXPANSION"))
            score = Math.max(score, signalStrength("volume_surge", c));
        if (text.contains("TREND")) score = Math.max(score, signalStrength("adx_trend", c));
        if (score == 0.0) score = signalStrength("adx_trend", c);
        return clamp(score, 0.0, 1.0);
    }

    private double declarativeSupportResistanceFit(AppRuntimeConfig config, String stage, String substage, MarketContext c) {
        Map<String, Object> meta = getSubstageMeta(config, stage, substage);
        String text = (YamlConfigService.stringValue(meta.get("preferred_trigger")) + " " +
                YamlConfigService.stringValue(meta.get("description"))).toUpperCase();

        double score = 0.0;
        if (text.contains("SUPPORT") || text.contains("RECLAIM") || text.contains("SPRING") || text.contains("BOTTOM")) {
            score = Math.max(score, boolFeature("near_support", c) ? 1.0 : 0.0);
            score = Math.max(score, boolFeature("price_above_vwap", c) ? 0.70 : 0.0);
        }
        if (text.contains("RESISTANCE") || text.contains("UPTHRUST") || text.contains("TOP")) {
            score = Math.max(score, boolFeature("failed_breakout", c) ? 1.0 : 0.0);
            score = Math.max(score, boolFeature("price_below_vwap", c) ? 0.70 : 0.0);
        }
        if (score == 0.0) {
            score = Math.max(boolFeature("price_above_vwap", c) ? 0.5 : 0.0, boolFeature("price_below_vwap", c) ? 0.5 : 0.0);
        }
        return clamp(score, 0.0, 1.0);
    }

    private double declarativeConfidenceAdjustment(AppRuntimeConfig config, String stage, String substage, MarketContext c) {
        Map<String, Object> root = yamlConfigService.asMap(config.getSubstages());
        Map<String, Object> adjustments = yamlConfigService.asMap(root.get("confidence_adjustments"));
        Map<String, Object> byStage = yamlConfigService.asMap(adjustments.get(stage));
        Map<String, Object> bySubstage = yamlConfigService.asMap(byStage.get(substage));
        return scoreSignalMap(yamlConfigService.asMap(bySubstage.get("signals")), c)
                - scoreSignalMap(yamlConfigService.asMap(bySubstage.get("penalties")), c)
                + safeDouble(bySubstage.get("add"), 0.0);
    }

    private Map<String, Object> getStageMeta(AppRuntimeConfig config, String stage) {
        Map<String, Object> root = yamlConfigService.asMap(config.getSubstages());
        Map<String, Object> stages = yamlConfigService.asMap(root.get("market_stages"));
        Map<String, Object> stageMeta = yamlConfigService.asMap(stages.get(stage));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("label", YamlConfigService.stringValue(stageMeta.get("label")));
        out.put("directional_bias", YamlConfigService.stringValue(stageMeta.get("directional_bias")));
        out.put("description", YamlConfigService.stringValue(stageMeta.get("description")));
        return out;
    }

    private Map<String, Object> getSubstageMeta(AppRuntimeConfig config, String stage, String substage) {
        Map<String, Object> root = yamlConfigService.asMap(config.getSubstages());
        Map<String, Object> defaults = yamlConfigService.asMap(root.get("metadata_defaults"));
        Map<String, Object> stages = yamlConfigService.asMap(root.get("market_stages"));
        Map<String, Object> stageMeta = yamlConfigService.asMap(stages.get(stage));
        Map<String, Object> substageMap = yamlConfigService.asMap(yamlConfigService.asMap(stageMeta.get("substages")).get(substage));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bias", YamlConfigService.stringValue(substageMap.get("bias")));
        out.put("entry_style", stringOrDefault(substageMap.get("entry_style"), defaults.get("entry_style")));
        out.put("maturity", stringOrDefault(substageMap.get("maturity"), defaults.get("maturity")));
        out.put("risk_profile", stringOrDefault(substageMap.get("risk_profile"), defaults.get("risk_profile")));
        out.put("confirmation_needed", substageMap.containsKey("confirmation_needed")
                ? YamlConfigService.boolValue(substageMap.get("confirmation_needed"))
                : YamlConfigService.boolValue(defaults.get("confirmation_needed")));
        out.put("preferred_trigger", stringOrDefault(substageMap.get("preferred_trigger"), defaults.get("preferred_trigger")));
        out.put("directional_bias", stringOrDefault(substageMap.get("directional_bias"), defaults.get("directional_bias")));
        out.put("description", YamlConfigService.stringValue(substageMap.get("description")));
        return out;
    }

    private String safeSubstage(AppRuntimeConfig config, String stage, String substage, String fallback) {
        return isValidSubstage(config, stage, substage) ? substage : fallback;
    }

    private boolean hasYamlSubstage(AppRuntimeConfig config, String stage, String substage) {
        Map<String, Object> root = yamlConfigService.asMap(config.getSubstages());
        Map<String, Object> stages = yamlConfigService.asMap(root.get("market_stages"));
        Map<String, Object> stageMeta = yamlConfigService.asMap(stages.get(stage));
        Map<String, Object> substages = yamlConfigService.asMap(stageMeta.get("substages"));
        return substages.containsKey(substage);
    }

    private double getStageThreshold(AppRuntimeConfig config, String key, double defaultValue) {
        Map<String, Object> root = yamlConfigService.asMap(config.getSubstages());
        Map<String, Object> thresholds = yamlConfigService.asMap(root.get("stage_thresholds"));
        return get(thresholds, key, defaultValue);
    }

    private String stringOrDefault(Object value, Object defaultValue) {
        String v = YamlConfigService.stringValue(value);
        return v.isBlank() ? YamlConfigService.stringValue(defaultValue) : v;
    }

    private boolean hasReflexRally(List<Candle> candles, int lookback) {
        if (candles == null || candles.size() < lookback + 2) return false;
        int start = Math.max(0, candles.size() - lookback - 1);
        double low = candles.get(start).getLow();
        double lastClose = candles.get(candles.size() - 1).getClose();
        return low > 0 && ((lastClose - low) / low) >= 0.05;
    }

    private boolean isRetestingBottomArea(List<Candle> candles, int lookback) {
        if (candles == null || candles.size() < lookback + 2) return false;
        int start = Math.max(0, candles.size() - lookback);
        double low = Double.MAX_VALUE;
        for (int i = start; i < candles.size() - 1; i++) low = Math.min(low, candles.get(i).getLow());
        double lastLow = candles.get(candles.size() - 1).getLow();
        return low > 0 && Math.abs(lastLow - low) / low <= 0.025;
    }

    private boolean isRetestingTopZone(List<Candle> candles, int lookback) {
        if (candles == null || candles.size() < lookback + 2) return false;
        int start = Math.max(0, candles.size() - lookback);
        double high = 0.0;
        for (int i = start; i < candles.size() - 1; i++) high = Math.max(high, candles.get(i).getHigh());
        double lastHigh = candles.get(candles.size() - 1).getHigh();
        return high > 0 && Math.abs(lastHigh - high) / high <= 0.025;
    }

    private boolean isLowVolumeRetest(List<Candle> candles, int bars) {
        if (candles == null || candles.size() < bars + 2) return false;
        int start = Math.max(1, candles.size() - bars);
        double recentAvg = 0.0;
        double priorAvg = 0.0;
        for (int i = start; i < candles.size(); i++) recentAvg += candles.get(i).getVolume();
        recentAvg /= (candles.size() - start);

        int priorStart = Math.max(0, start - bars);
        int priorEnd = start;
        if (priorEnd - priorStart == 0) return false;
        for (int i = priorStart; i < priorEnd; i++) priorAvg += candles.get(i).getVolume();
        priorAvg /= (priorEnd - priorStart);

        return priorAvg > 0 && recentAvg < priorAvg;
    }

    private boolean isPauseInsideLargerUptrend(MarketContext c) {
        return c.dma20 > c.dma50 && c.price >= c.dma50 && c.range20 <= c.compressionRange;
    }

    private boolean isPauseInsideLargerDowntrend(MarketContext c) {
        return c.dma20 < c.dma50 && c.price <= c.dma50 && c.range20 <= c.compressionRange;
    }

    private boolean isFailedBreakAboveResistance(List<Candle> candles) {
        return failedBreakout(candles);
    }

    private boolean isLateFailedPush(List<Candle> candles) {
        if (candles == null || candles.size() < 6) return false;
        Candle last = candles.get(candles.size() - 1);
        Candle prev = candles.get(candles.size() - 2);
        double recentHigh = 0.0;
        for (int i = Math.max(0, candles.size() - 6); i < candles.size() - 1; i++) {
            recentHigh = Math.max(recentHigh, candles.get(i).getHigh());
        }
        return prev.getHigh() >= recentHigh && last.getClose() < prev.getClose();
    }

    private double computeSignalScore(AppRuntimeConfig config, MarketContext c, List<String> patterns) {
        Map<String, Object> quant = yamlConfigService.asMap(config.getQuant());
        Map<String, Object> weights = yamlConfigService.asMap(quant.get("signal_score_weights"));
        Map<String, Object> penalties = yamlConfigService.asMap(quant.get("signal_score_penalties"));

        double score = 0.0;
        score += normalized(c.rsi, 40.0, 72.0) * get(weights, "rsi", 0.18);
        score += normalized(c.adx, c.adxTrend, c.strongTrendAdx + 20.0) * get(weights, "adx", 0.20);
        score += normalized(c.vol, 0.9, c.strongVol) * get(weights, "volume", 0.14);
        score += (c.bullStack ? 1.0 : c.bearStack ? 0.0 : 0.4) * get(weights, "dma_stack", 0.18);
        score += (c.priceAboveVwap ? 1.0 : 0.0) * get(weights, "vwap", 0.10);
        score += (c.breakout ? 1.0 : 0.0) * get(weights, "breakout", 0.10);
        score += (c.macdCross ? 1.0 : 0.0) * get(weights, "macd", 0.05);
        score += (patterns.isEmpty() ? 0.0 : 1.0) * get(weights, "patterns", 0.05);

        if (c.rsi >= c.exhaustionHigh) score -= get(penalties, "exhaustion_high", 0.12);
        if (c.rsi <= c.exhaustionLow) score -= get(penalties, "exhaustion_low", 0.05);
        if (c.bearStack) score -= get(penalties, "bear_stack", 0.20);

        return clamp(score, 0.0, 1.0);
    }

    private double computeInstitutionalScore(AppRuntimeConfig config, MarketContext c, String stage, String substage) {
        Map<String, Object> quant = yamlConfigService.asMap(config.getQuant());
        Map<String, Object> weights = yamlConfigService.asMap(quant.get("institutional_score_weights"));
        Map<String, Object> penalties = yamlConfigService.asMap(quant.get("institutional_score_penalties"));

        double adxComponent = normalized(c.adx, c.adxTrend, c.strongTrendAdx + 20.0) * 100.0;
        double volumeComponent = normalized(c.vol, 1.0, c.strongVol) * 100.0;
        double structureComponent = c.bullStack ? 100.0 : c.bearStack ? 15.0 : 50.0;
        double triggerComponent = c.breakout ? 100.0 : c.macdCross ? 70.0 : c.nearSupport ? 60.0 : 30.0;

        double score =
                adxComponent * get(weights, "adx_component", 0.28) +
                        volumeComponent * get(weights, "volume_component", 0.24) +
                        structureComponent * get(weights, "structure_component", 0.28) +
                        triggerComponent * get(weights, "trigger_component", 0.20);

        if ("EXHAUSTION".equals(substage)) score -= get(penalties, "exhaustion", 12.0);
        if ("UPTHRUST".equals(substage)) score -= get(penalties, "upthrust", 12.0);
        if ("CAPITULATION".equals(substage)) score -= get(penalties, "capitulation", 6.0);
        if ("MARKDOWN".equals(stage)) score -= get(penalties, "markdown_stage", 18.0);

        return clamp(score, 0.0, 100.0);
    }

    private double computeConfidenceScore(AppRuntimeConfig config, double institutionalScore, MarketContext c, String stage, String substage) {
        Map<String, Object> quant = yamlConfigService.asMap(config.getQuant());
        Map<String, Object> weights = yamlConfigService.asMap(quant.get("confidence_score_weights"));
        Map<String, Object> penalties = yamlConfigService.asMap(quant.get("confidence_score_penalties"));

        double volumeWeight = normalized(c.vol, 1.0, c.strongVol) * 100.0;
        double adxComponent = normalized(c.adx, c.adxTrend, c.strongTrendAdx + 20.0) * 100.0;
        double trendFit = stageDirectionalAlignment(config, stage, c) * 100.0;
        double priceLocation = c.priceAboveVwap ? 100.0 : 35.0;

        double score =
                institutionalScore * get(weights, "institutional_score", 0.35) +
                        volumeWeight * get(weights, "volume_weight", 0.20) +
                        adxComponent * get(weights, "adx_component", 0.20) +
                        trendFit * get(weights, "trend_fit", 0.15) +
                        priceLocation * get(weights, "price_location", 0.10);

        if ("EXHAUSTION".equals(substage)) score -= get(penalties, "exhaustion", 15.0);
        if ("UPTHRUST".equals(substage)) score -= get(penalties, "upthrust", 12.0);
        if ("FAILED_BREAKOUT".equals(substage)) score -= get(penalties, "failed_breakout", 8.0);
        if ("MARKDOWN".equals(stage)) score -= get(penalties, "markdown_stage", 20.0);

        return clamp(score, 0.0, 100.0);
    }

    private double computeRegimeQuality(AppRuntimeConfig config, MarketContext c, String stage, String substage, double substageConfidence, double signalScore, double confidenceScore) {
        Map<String, Object> quant = yamlConfigService.asMap(config.getQuant());
        Map<String, Object> weights = yamlConfigService.asMap(quant.get("regime_quality_weights"));
        Map<String, Object> adjustments = yamlConfigService.asMap(quant.get("regime_quality_adjustments"));

        double stageStrength = normalized(c.adx, c.adxTrend, c.strongTrendAdx + 20.0) * 100.0;
        if (c.bullStack || c.bearStack) {
            stageStrength = Math.min(100.0, stageStrength + get(adjustments, "stack_bonus", 15.0));
        }

        double score =
                stageStrength * get(weights, "stage_strength_score", 0.35) +
                        substageConfidence * get(weights, "substage_confidence", 0.25) +
                        (signalScore * 100.0) * get(weights, "signal_score", 0.20) +
                        confidenceScore * get(weights, "confidence_score", 0.20);

        if ("EXHAUSTION".equals(substage)) score -= get(adjustments, "exhaustion_penalty", 12.0);
        if ("UPTHRUST".equals(substage)) score -= get(adjustments, "upthrust_penalty", 12.0);
        if ("MARKDOWN".equals(stage)) score -= get(adjustments, "markdown_penalty", 15.0);
        if ("ACCUMULATION".equals(stage) && "PRE_BREAKOUT".equals(substage))
            score += get(adjustments, "pre_breakout_bonus", 6.0);

        return clamp(score, 0.0, 100.0);
    }


    private String classifyRecommendation(AppRuntimeConfig config, MarketContext c, String stage, String substage, double signalScore, double confidenceScore, double regimeQualityScore) {
        Map<String, Object> rulesRoot = yamlConfigService.asMap(config.getBusinessRules());
        Map<String, Object> rules = yamlConfigService.asMap(rulesRoot.get("rules"));

        double probability = Math.max(signalScore, confidenceScore / 100.0);

        String bestDecision = "AVOID";
        double bestRank = Double.NEGATIVE_INFINITY;

        for (Map.Entry<String, Object> entry : rules.entrySet()) {
            String decision = toDecision(entry.getKey());
            Map<String, Object> rule = yamlConfigService.asMap(entry.getValue());

            if (!recommendationAllowed(rule, stage, substage, c, probability, regimeQualityScore)) {
                continue;
            }

            double rank = recommendationRank(decision, probability, regimeQualityScore, stage, substage, c);
            if (rank > bestRank) {
                bestRank = rank;
                bestDecision = decision;
            }
        }

        if ("MARKDOWN".equals(stage) && bestDecision.equals("BUY")) return "WATCH";
        if (c.rsi >= c.exhaustionHigh + 8.0 && ("STRONG_BUY".equals(bestDecision) || "BUY".equals(bestDecision))) {
            return "WATCH";
        }
        return bestDecision;
    }

    private boolean recommendationAllowed(Map<String, Object> rule, String stage, String substage, MarketContext c, double probability, double regimeQualityScore) {
        List<Object> allowedStages = listOf(rule.get("allowedStages"));
        if (!allowedStages.isEmpty() && !containsIgnoreCase(allowedStages, stage)) return false;

        List<Object> blockedStages = listOf(rule.get("blockedStages"));
        if (containsIgnoreCase(blockedStages, stage)) return false;

        List<Object> allowedSubstages = listOf(rule.get("allowedSubstages"));
        if (!allowedSubstages.isEmpty() && !containsIgnoreCase(allowedSubstages, substage)) return false;

        List<Object> blockedSubstages = listOf(rule.get("blockedSubstages"));
        if (containsIgnoreCase(blockedSubstages, substage)) return false;

        if (probability < get(rule, "minProbability", 0.0)) return false;
        if (c.rsi < get(rule, "minRsi", 0.0)) return false;
        if (c.vol < get(rule, "minVolumeRatio", 0.0)) return false;
        if (regimeQualityScore < get(rule, "minRegimeQualityScore", 0.0)) return false;

        if (rule.containsKey("emaBullish") && YamlConfigService.boolValue(rule.get("emaBullish")) && !c.bullStack)
            return false;
        if (rule.containsKey("requireBreakout") && YamlConfigService.boolValue(rule.get("requireBreakout")) && !c.breakout)
            return false;
        if (rule.containsKey("requireNearSupport") && YamlConfigService.boolValue(rule.get("requireNearSupport")) && !c.nearSupport)
            return false;
        return !rule.containsKey("requireMacdCross") || !YamlConfigService.boolValue(rule.get("requireMacdCross")) || c.macdCross;
    }

    private String classifyExecutionAction(AppRuntimeConfig config, MarketContext c, String stage, String substage, String recommendation, double confidenceScore, double regimeQualityScore) {
        Map<String, Object> root = yamlConfigService.asMap(config.getBusinessRules());
        Map<String, Object> execution = yamlConfigService.asMap(root.get("execution"));

        if ("SELL".equals(recommendation) || "MARKDOWN".equals(stage)) {
            return "EXIT_OR_REDUCE";
        }
        if ("STRONG_BUY".equals(recommendation)
                && confidenceScore >= get(execution, "strong_buy_min_confidence", 72.0)
                && regimeQualityScore >= get(execution, "strong_buy_min_regime", 68.0)
                && !"EXHAUSTION".equals(substage)) {
            return "ENTER_FULL";
        }
        if (("BUY".equals(recommendation) || "SCALE_IN".equals(recommendation))
                && confidenceScore >= get(execution, "buy_min_confidence", 58.0)
                && regimeQualityScore >= get(execution, "buy_min_regime", 55.0)) {
            return c.nearSupport ? "ENTER_ON_PULLBACK" : "ENTER_PARTIAL";
        }
        if ("WATCH".equals(recommendation)) {
            return c.breakout ? "WAIT_FOR_RETEST" : "WATCHLIST";
        }
        return "NO_TRADE";
    }

    private String classifyFinalAction(AppRuntimeConfig config, MarketContext c, String stage, String substage, String recommendation, String executionAction, double regimeQualityScore) {
        Map<String, Object> root = yamlConfigService.asMap(config.getBusinessRules());
        Map<String, Object> risk = yamlConfigService.asMap(root.get("risk_filters"));

        if (c.rsi >= get(risk, "max_buy_rsi", 78.0) && ("ENTER_FULL".equals(executionAction) || "ENTER_PARTIAL".equals(executionAction))) {
            return "WAIT";
        }
        if ("EXHAUSTION".equals(substage) || "UPTHRUST".equals(substage)) {
            return "WAIT";
        }
        if ("MARKDOWN".equals(stage)) {
            return "EXIT";
        }
        if (regimeQualityScore < get(risk, "min_trade_regime_quality", 52.0)) {
            return "WAIT";
        }
        if ("NO_TRADE".equals(executionAction) || "WATCHLIST".equals(executionAction) || "WAIT_FOR_RETEST".equals(executionAction)) {
            return "WAIT";
        }
        if ("EXIT_OR_REDUCE".equals(executionAction)) {
            return "EXIT";
        }
        return "TAKE_TRADE";
    }

    private String defaultSubstageForStage(AppRuntimeConfig config, String stage) {
        Map<String, Object> root = yamlConfigService.asMap(config.getSubstages());
        Map<String, Object> stages = yamlConfigService.asMap(root.get("market_stages"));
        Map<String, Object> stageMeta = yamlConfigService.asMap(stages.get(stage));
        Map<String, Object> substages = yamlConfigService.asMap(stageMeta.get("substages"));
        return substages.keySet().stream().findFirst().orElse("UNKNOWN");
    }


    private boolean containsIgnoreCase(List<Object> values, String target) {
        for (Object value : values) {
            if (String.valueOf(value).equalsIgnoreCase(target)) return true;
        }
        return false;
    }

    private double recommendationRank(String decision, double probability, double regimeQualityScore, String stage, String substage, MarketContext c) {
        double base = switch (decision) {
            case "STRONG_BUY" -> 500.0;
            case "BUY" -> 400.0;
            case "WATCH" -> 300.0;
            case "SELL" -> 250.0;
            default -> 100.0;
        };

        double rank = base + (probability * 100.0) + regimeQualityScore;

        if ("MARKUP".equals(stage) && ("EARLY_TREND".equals(substage) || "PRE_BREAKOUT".equals(substage))) rank += 25.0;
        if ("MARKDOWN".equals(stage) && "SELL".equals(decision)) rank += 35.0;
        if ("EXHAUSTION".equals(substage) && ("BUY".equals(decision) || "STRONG_BUY".equals(decision))) rank -= 60.0;
        if (c.rsi >= c.exhaustionHigh + 5.0 && ("BUY".equals(decision) || "STRONG_BUY".equals(decision))) rank -= 50.0;
        return rank;
    }

    private String toDecision(String key) {
        return switch (key) {
            case "strongBuy" -> "STRONG_BUY";
            case "buy" -> "BUY";
            case "scaleIn" -> "SCALE_IN";
            case "watch" -> "WATCH";
            case "sell" -> "SELL";
            case "avoid" -> "AVOID";
            default -> key.toUpperCase();
        };
    }

    private String classifyTrend(MarketContext c) {
        if (c.bullStack && c.macdCross) return "UPTREND";
        if (c.bearStack || c.rsi < 40) return "DOWNTREND";
        return "SIDEWAYS";
    }

    private List<String> detectPatterns(AppRuntimeConfig config, Map<String, Object> metrics, String marketStage) {
        List<String> patterns = new ArrayList<>();
        Map<String, Object> enabled = yamlConfigService.asMap(config.getPatterns().get("enabled_patterns"));
        if (YamlConfigService.boolValue(enabled.get("HAMMER")) && Boolean.TRUE.equals(metrics.get("hammer")))
            patterns.add("HAMMER");
        if (YamlConfigService.boolValue(enabled.get("BULLISH_ENGULFING")) && Boolean.TRUE.equals(metrics.get("bullish_engulfing")))
            patterns.add("BULLISH_ENGULFING");
        if (YamlConfigService.boolValue(enabled.get("HIGH_VOLUME_BREAKOUT")) && Boolean.TRUE.equals(metrics.get("breakout")))
            patterns.add("HIGH_VOLUME_BREAKOUT");
        if (YamlConfigService.boolValue(enabled.get("VWAP_RECLAIM")) && Boolean.TRUE.equals(metrics.get("dipreclaim")))
            patterns.add("VWAP_RECLAIM");
        if (patterns.isEmpty())
            patterns.add(marketStage.equals("ACCUMULATION") ? "BASE_FORMATION" : marketStage.equals("MARKUP") ? "TREND_CONTINUATION" : marketStage.equals("MARKDOWN") ? "DOWNTREND" : "RANGE_BOUND");
        return patterns;
    }

    private String decisionReason(MarketContext c, String recommendation, String stage, String substage, List<String> patterns) {
        return "rec=" + recommendation + ", stage=" + stage + ", substage=" + substage + ", rsi=" + round(c.rsi) + ", adx=" + round(c.adx) + ", volRatio=" + round(c.vol) + ", patterns=" + String.join("|", patterns);
    }


    private boolean isValidSubstage(AppRuntimeConfig config, String stage, String substage) {
        Map<String, Object> root = yamlConfigService.asMap(config.getSubstages());
        Map<String, Object> stages = yamlConfigService.asMap(root.get("market_stages"));
        Map<String, Object> stageMeta = yamlConfigService.asMap(stages.get(stage));
        Map<String, Object> substages = yamlConfigService.asMap(stageMeta.get("substages"));
        return substages.containsKey(substage);
    }

    private String stageLabel(AppRuntimeConfig config, String stage) {
        Map<String, Object> root = yamlConfigService.asMap(config.getSubstages());
        Map<String, Object> stages = yamlConfigService.asMap(root.get("market_stages"));
        Map<String, Object> stageMap = yamlConfigService.asMap(stages.get(stage));
        return YamlConfigService.stringValue(stageMap.get("label"));
    }

    private double refinedBuyPrice(double currentPrice, double dma20, double atr14) {
        if (dma20 > 0 && currentPrice > dma20) return Math.max(dma20, currentPrice - (0.5 * atr14));
        return Math.max(0.0, currentPrice - (0.5 * atr14));
    }


    private List<Object> listOf(Object value) {
        if (value instanceof List<?> list) return new ArrayList<>(list);
        return new ArrayList<>();
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }


    private ChildSubstageClassificationResult classifyChildSubstage(AppRuntimeConfig config, MarketContext c, String stage, String substage) {
        Map<String, Object> root = yamlConfigService.asMap(config.getChildSubstages());
        Map<String, Object> childRoot = yamlConfigService.asMap(root.get("child_substages"));
        Map<String, Object> stageMap = yamlConfigService.asMap(childRoot.get(stage));
        Map<String, Object> substageMap = yamlConfigService.asMap(stageMap.get(substage));
        Map<String, Object> children = yamlConfigService.asMap(substageMap.get("children"));
        String defaultChild = stringOrDefault(substageMap.get("default_child"), "DEFAULT");

        if (children.isEmpty()) {
            return fallbackChild(stage, substage, defaultChild, "No child_substages.yml mapping found for selected stage/substage.");
        }

        String bestChild = "";
        double bestScore = Double.NEGATIVE_INFINITY;
        Map<String, Object> bestMeta = Map.of();

        for (Map.Entry<String, Object> entry : children.entrySet()) {
            String child = entry.getKey();
            if (child.equalsIgnoreCase(defaultChild) || child.equalsIgnoreCase("DEFAULT")) {
                continue;
            }

            Map<String, Object> meta = yamlConfigService.asMap(entry.getValue());
            Map<String, Object> signals = yamlConfigService.asMap(meta.get("signals"));
            double score = scoreChildSignals(signals, c);
            score = clamp(score + childConfidenceBiasAdjustment(YamlConfigService.stringValue(meta.get("confidence_bias"))), 0.0, 100.0);

            if (score > bestScore) {
                bestScore = score;
                bestChild = child;
                bestMeta = meta;
            }
        }

        double minScore = childMinScore(config);
        if (bestChild.isBlank() || bestScore < minScore) {
            bestChild = children.containsKey(defaultChild) ? defaultChild : children.keySet().stream().findFirst().orElse("DEFAULT");
            bestMeta = yamlConfigService.asMap(children.get(bestChild));
            bestScore = bestChild.equalsIgnoreCase(defaultChild) ? 35.0 : clamp(scoreChildSignals(yamlConfigService.asMap(bestMeta.get("signals")), c), 0.0, 100.0);
        }

        return ChildSubstageClassificationResult.builder()
                .parentStage(stage)
                .parentSubstage(substage)
                .childSubstage(bestChild)
                .confidence(clamp(bestScore, 0.0, 100.0))
                .family(YamlConfigService.stringValue(bestMeta.get("family")))
                .actionBias(YamlConfigService.stringValue(bestMeta.get("action_bias")))
                .riskProfile(YamlConfigService.stringValue(bestMeta.get("risk_profile")))
                .description(YamlConfigService.stringValue(bestMeta.get("description")))
                .build();
    }

    private ChildSubstageClassificationResult fallbackChild(String stage, String substage, String child, String description) {
        return ChildSubstageClassificationResult.builder()
                .parentStage(stage)
                .parentSubstage(substage)
                .childSubstage(child == null || child.isBlank() ? "DEFAULT" : child)
                .confidence(0.0)
                .family("FALLBACK")
                .actionBias("WATCH")
                .riskProfile("MEDIUM")
                .description(description)
                .build();
    }

    private double childMinScore(AppRuntimeConfig config) {
        Map<String, Object> root = yamlConfigService.asMap(config.getChildSubstages());
        Map<String, Object> scoring = yamlConfigService.asMap(root.get("scoring"));
        return get(scoring, "min_child_score", 50.0);
    }

    private double childConfidenceBiasAdjustment(String confidenceBias) {
        Map<String, Double> adjustments = Map.of(
                "POSITIVE", 5.0,
                "NEGATIVE", -5.0,
                "NEUTRAL", 0.0
        );
        return adjustments.getOrDefault(confidenceBias == null ? "" : confidenceBias.trim().toUpperCase(), 0.0);
    }

    private double scoreChildSignals(Map<String, Object> signals, MarketContext c) {
        if (signals == null || signals.isEmpty()) return 0.0;

        double totalWeight = 0.0;
        double matchedWeight = 0.0;
        for (Map.Entry<String, Object> signal : signals.entrySet()) {
            double weight = Math.max(1.0, signalWeightForKey(signal.getKey(), signal.getValue()));
            totalWeight += weight;
            matchedWeight += weight * clamp(yamlSignalMatchScore(signal.getKey(), signal.getValue(), c), 0.0, 1.0);
        }
        return totalWeight <= 0.0 ? 0.0 : clamp((matchedWeight / totalWeight) * 100.0, 0.0, 100.0);
    }

    private double metricNumber(Map<String, Object> metrics, String key) {
        if (metrics == null || metrics.isEmpty()) return 0.0;
        Object value = metrics.get(key);
        if (value == null) value = metrics.get(toSnakeCase(key));
        if (value == null) value = metrics.get(toTitleCase(key));
        return safeDouble(value, 0.0);
    }

    private boolean metricBoolean(Map<String, Object> metrics, String key) {
        if (metrics == null || metrics.isEmpty()) return false;
        Object value = metrics.get(key);
        if (value == null) value = metrics.get(toSnakeCase(key));
        if (value == null) value = metrics.get(toTitleCase(key));
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.doubleValue() != 0.0;
        String raw = String.valueOf(value == null ? "" : value).trim();
        return "true".equalsIgnoreCase(raw) || "yes".equalsIgnoreCase(raw) || "y".equalsIgnoreCase(raw) || "1".equals(raw);
    }

    private String toSnakeCase(String value) {
        if (value == null) return "";
        return value.replaceAll("([a-z])([A-Z])", "$1_$2").replace('-', '_').toLowerCase();
    }

    private String toTitleCase(String value) {
        String snake = toSnakeCase(value).replace('_', ' ').trim();
        if (snake.isBlank()) return "";
        StringBuilder out = new StringBuilder();
        for (String token : snake.split(" ")) {
            if (token.isBlank()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(token.charAt(0))).append(token.substring(1));
        }
        return out.toString();
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private double d(Map<String, Object> m, String k) {
        return YamlConfigService.doubleValue(m.get(k));
    }

    private boolean b(Map<String, Object> m, String k) {
        return Boolean.TRUE.equals(m.get(k));
    }

    private double get(Map<String, Object> m, String k, double def) {
        Object v = m.get(k);
        return v == null ? def : YamlConfigService.doubleValue(v);
    }

    private double rangePct(List<Candle> candles, int n) {
        if (candles == null || candles.size() < n) return 0;
        List<Candle> sub = candles.subList(candles.size() - n, candles.size());
        double hi = sub.stream().mapToDouble(Candle::getHigh).max().orElse(0);
        double lo = sub.stream().mapToDouble(Candle::getLow).min().orElse(0);
        double close = sub.get(sub.size() - 1).getClose();
        return close == 0 ? 0 : (hi - lo) / close;
    }

    private boolean isSpring(List<Candle> candles, Map<String, Object> m) {
        if (candles == null || candles.size() < 20) return false;
        List<Candle> sub = candles.subList(candles.size() - 20, candles.size());
        double support = sub.stream().mapToDouble(Candle::getLow).min().orElse(0);
        Candle last = candles.get(candles.size() - 1);
        return last.getLow() < support && last.getClose() > support;
    }

    private boolean isFailedSpring(List<Candle> candles, Map<String, Object> metrics) {
        if (!isSpring(candles, metrics) || candles.size() < 3) return false;
        Candle last = candles.get(candles.size() - 1);
        Candle prev = candles.get(candles.size() - 2);
        return last.getClose() < prev.getClose();
    }

    private boolean failedBreakout(List<Candle> candles) {
        if (candles == null || candles.size() < 5) return false;
        Candle last = candles.get(candles.size() - 1);
        Candle prev = candles.get(candles.size() - 2);
        return last.getClose() < prev.getHigh();
    }

    private boolean isFailedBreakdown(List<Candle> candles) {
        if (candles == null || candles.size() < 5) return false;
        Candle last = candles.get(candles.size() - 1);
        Candle prev = candles.get(candles.size() - 2);
        return last.getClose() > prev.getLow();
    }

    private boolean isDeadCatBounce(List<Candle> candles) {
        if (candles == null || candles.size() < 5) return false;
        Candle last = candles.get(candles.size() - 1);
        Candle prev = candles.get(candles.size() - 2);
        return last.getClose() > prev.getClose() && last.getClose() < candles.get(Math.max(0, candles.size() - 5)).getClose();
    }

    private boolean lowerHighStructure(List<Candle> candles, int lookback) {
        if (candles == null || candles.size() < lookback) return false;
        List<Candle> sub = candles.subList(candles.size() - lookback, candles.size());
        double firstHalfHigh = sub.subList(0, lookback / 2).stream().mapToDouble(Candle::getHigh).max().orElse(0);
        double secondHalfHigh = sub.subList(lookback / 2, lookback).stream().mapToDouble(Candle::getHigh).max().orElse(0);
        return secondHalfHigh < firstHalfHigh;
    }

    private boolean lowerLowAcceleration(List<Candle> candles, int lookback) {
        if (candles == null || candles.size() < lookback) return false;
        List<Candle> sub = candles.subList(candles.size() - lookback, candles.size());
        double firstHalfLow = sub.subList(0, lookback / 2).stream().mapToDouble(Candle::getLow).min().orElse(0);
        double secondHalfLow = sub.subList(lookback / 2, lookback).stream().mapToDouble(Candle::getLow).min().orElse(0);
        return secondHalfLow < firstHalfLow;
    }

    private boolean breakdownFromRange(List<Candle> candles) {
        if (candles == null || candles.size() < 20) return false;
        List<Candle> prior = candles.subList(candles.size() - 20, candles.size() - 1);
        double rangeLow = prior.stream().mapToDouble(Candle::getLow).min().orElse(0);
        Candle last = candles.get(candles.size() - 1);
        return last.getClose() < rangeLow;
    }


    /**
     * Final recommendation fusion layer.
     * <p>
     * Alpha Vantage provides/derives FUNDAMENTAL_BOOST, sentiment, EPS and ETF proxy fields.
     * This method consumes those fields when present, computes missing technical/quant fields,
     * and writes non-blank Excel values for:
     * - Relative Strength vs SPY
     * - Gap Percent
     * - Volatility Score
     * - Trade Direction
     * - Entry Quality Score
     * - recommendation / final_action after fusion
     * <p>
     * Note: BusinessLogicService cannot fetch SPY candles by itself. Relative Strength vs SPY is
     * therefore read from metrics/row if PipelineService or TechnicalIndicatorService provides it.
     * If absent, it is set to 0.0 instead of blank.
     */
    private void applyFundamentalStageFusion(Map<String, Object> row,
                                             MarketContext context,
                                             List<Candle> candles,
                                             String marketStage,
                                             String marketSubstage,
                                             String childSubstage) {

        double relativeStrengthVsSpy = firstNumber(row,
                "relative_strength_vs_spy", "Relative Strength vs SPY", "rs_vs_spy", "relative_strength_spy");
        double gapPercent = firstNonZero(
                firstNumber(row, "gap_percent", "Gap Percent"),
                computeGapPercent(candles)
        );
        double volatilityScore = firstNonZero(
                firstNumber(row, "volatility_score", "Volatility Score"),
                computeVolatilityScore(candles, 14)
        );

        row.put("Relative Strength vs SPY", round(relativeStrengthVsSpy));
        row.put("relative_strength_vs_spy", round(relativeStrengthVsSpy));
        row.put("Gap Percent", round(gapPercent));
        row.put("gap_percent", round(gapPercent));
        row.put("Volatility Score", round(volatilityScore));
        row.put("volatility_score", round(volatilityScore));

        double stageScore = firstNumber(row, "stage_score", "stage_alignment_score", "Stage Alignment Score");
        double substageScore = firstNumber(row, "substage_score", "Substage Score");
        double childScore = firstNumber(row, "child_substage_score", "Child Substage Score");
        double signalScore = firstNumber(row, "signal_score", "Signal Score", "long_score");
        double confidenceScore = firstNumber(row, "confidence_score", "Confidence Score");
        double regimeQualityScore = firstNumber(row, "regime_quality_score", "Regime Quality Score");
        double fundamentalBoost = firstNumber(row, "FUNDAMENTAL_BOOST", "fundamental_boost");
        double sentimentScore = firstNumber(row, "News Sentiment Score", "news_sentiment_score");
        double sentimentConfidence = firstNumber(row, "Sentiment Confidence", "sentiment_confidence");

        double fusionScore = computeFusionScore(
                marketStage,
                stageScore,
                substageScore,
                childScore,
                signalScore,
                confidenceScore,
                regimeQualityScore,
                fundamentalBoost,
                relativeStrengthVsSpy,
                gapPercent,
                volatilityScore,
                sentimentScore,
                sentimentConfidence
        );

        String ruleRecommendation = stringValue(row.get("rule_recommendation"), stringValue(row.get("recommendation"), "WAIT"));
        double childContradictionPenalty = firstNumber(row, "child_contradiction_penalty");
        double bestChildConfidence = firstNumber(row, "best_child_confidence");
        double bearishChildScore = firstNumber(row, "bearish_child_score");
        double bullishChildScore = firstNumber(row, "bullish_child_score");

        String fusedRecommendation = fusedRecommendation(
                marketStage,
                marketSubstage,
                childSubstage,
                fusionScore,
                volatilityScore,
                gapPercent,
                childContradictionPenalty,
                bestChildConfidence,
                bullishChildScore,
                bearishChildScore
        );
        String tradeDirection = tradeDirectionFromRecommendation(marketStage, fusedRecommendation);
        double entryQualityScore = computeEntryQualityScore(row, fusionScore, fundamentalBoost, relativeStrengthVsSpy, volatilityScore, sentimentConfidence);

        row.put("fusion_score", round(fusionScore));
        row.put("stage_fundamental_fusion_score", round(fusionScore));
        row.put("rule_recommendation", ruleRecommendation);
        row.put("Rule Recommendation", ruleRecommendation);
        row.put("recommendation", fusedRecommendation);
        row.put("final_action", fusedRecommendation);
        row.put("execution_action", fusedRecommendation);
        row.put("signal", fusedRecommendation);
        row.put("Trade Direction", tradeDirection);
        row.put("trade_direction", tradeDirection);
        row.put("Entry Quality Score", round(entryQualityScore));
        row.put("entry_quality_score", round(entryQualityScore));
        row.put("rule_based_buy", isLongBuyAction(fusedRecommendation));
        row.put("buy_window_status", isLongBuyAction(fusedRecommendation) ? 1.0 : 0.0);
        row.put("long_verdict", isLongBuyAction(fusedRecommendation) ? "VALID" : "WAIT");
        row.put("short_verdict", tradeDirection.contains("SHORT") ? "VALID" : "NO_SETUP");

        String reason = stringValue(row.get("decision_reason"), "");
        String fusionReason = "Fusion: stage=" + marketStage
                + ", substage=" + marketSubstage
                + ", child=" + childSubstage
                + ", fusionScore=" + round(fusionScore)
                + ", fundamentalBoost=" + round(fundamentalBoost)
                + ", rsVsSpy=" + round(relativeStrengthVsSpy)
                + ", volatility=" + round(volatilityScore)
                + ", gap=" + round(gapPercent);
        row.put("decision_reason", reason.isBlank() ? fusionReason : reason + " | " + fusionReason);
    }

    private double computeFusionScore(String stage,
                                      double stageScore,
                                      double substageScore,
                                      double childScore,
                                      double signalScore,
                                      double confidenceScore,
                                      double regimeQualityScore,
                                      double fundamentalBoost,
                                      double relativeStrengthVsSpy,
                                      double gapPercent,
                                      double volatilityScore,
                                      double sentimentScore,
                                      double sentimentConfidence) {

        double technicalComposite =
                0.20 * normalize100(stageScore) +
                        0.12 * normalize100(substageScore) +
                        0.08 * normalize100(childScore) +
                        0.15 * normalize100(signalScore) +
                        0.15 * normalize100(confidenceScore) +
                        0.10 * normalize100(regimeQualityScore);

        double fundamentalComposite = 0.12 * normalize100(fundamentalBoost);
        double relativeStrengthComposite = 0.10 * normalizeRelativeStrength(relativeStrengthVsSpy);
        double sentimentComposite = 0.05 * normalizeSignedSentiment(sentimentScore) * (0.50 + 0.50 * normalize100(sentimentConfidence));

        // Very high volatility reduces long-entry quality, but moderate volatility is acceptable.
        double volatilityPenalty = normalize100(Math.max(0.0, volatilityScore - 70.0)) * 0.12;

        // Large gaps are risky for fresh entries unless stage is already a strong markup.
        double gapPenalty = Math.abs(gapPercent) >= 5.0 ? 0.05 : 0.0;
        if ("MARKUP".equalsIgnoreCase(stage) && gapPercent > 0 && gapPercent <= 4.0) {
            gapPenalty = 0.0;
        }

        double raw = technicalComposite + fundamentalComposite + relativeStrengthComposite + sentimentComposite - volatilityPenalty - gapPenalty;
        return clamp(raw * 100.0, 0.0, 100.0);
    }

    private String fusedRecommendation(String stage,
                                       String substage,
                                       String childSubstage,
                                       double fusionScore,
                                       double volatilityScore,
                                       double gapPercent,
                                       double childContradictionPenalty,
                                       double bestChildConfidence,
                                       double bullishChildScore,
                                       double bearishChildScore) {
        String s = stage == null ? "" : stage.trim().toUpperCase();
        String ss = substage == null ? "" : substage.trim().toUpperCase();
        String child = childSubstage == null ? "" : childSubstage.trim().toUpperCase();

        boolean veryHighVolatility = volatilityScore >= 85.0;
        boolean largeGapAgainstEntry = Math.abs(gapPercent) >= 7.0;
        boolean exhaustedOrOverextended = ss.contains("EXHAUSTION")
                || ss.contains("OVEREXTENSION")
                || child.contains("EXHAUSTION")
                || child.contains("OVEREXTENSION")
                || child.contains("LATE");
        boolean childConflict = childContradictionPenalty >= 12.0
                || (bullishChildScore > 0.0 && bearishChildScore > 0.0
                    && Math.min(bullishChildScore, bearishChildScore) / Math.max(bullishChildScore, bearishChildScore) >= 0.35);
        boolean weakChildConfirmation = bestChildConfidence > 0.0 && bestChildConfidence < 0.35;

        if (childConflict && fusionScore < 78.0) {
            return "WATCH";
        }
        if (weakChildConfirmation && fusionScore < 75.0) {
            return "WATCH";
        }

        if ("MARKDOWN".equals(s)) {
            return fusionScore >= 62.0 && bearishChildScore >= bullishChildScore ? "SHORT" : "AVOID";
        }
        if ("DISTRIBUTION".equals(s)) {
            return fusionScore >= 75.0 && bearishChildScore >= bullishChildScore ? "REDUCE" : "WAIT";
        }
        if (veryHighVolatility && largeGapAgainstEntry) {
            return "WAIT";
        }
        if ("ACCUMULATION".equals(s)) {
            if (fusionScore >= 78.0 && bestChildConfidence >= 0.45 && !exhaustedOrOverextended) return "EARLY_BUY";
            if (fusionScore >= 58.0) return "WATCH";
            return "WAIT";
        }
        if ("MARKUP".equals(s)) {
            if (exhaustedOrOverextended) return fusionScore >= 75.0 ? "HOLD" : "WATCH";
            if (fusionScore >= 88.0 && bestChildConfidence >= 0.55 && bullishChildScore > bearishChildScore * 1.50) return "STRONG_BUY";
            if (fusionScore >= 74.0 && bestChildConfidence >= 0.40 && bullishChildScore >= bearishChildScore) return "BUY";
            if (fusionScore >= 62.0) return "WATCH";
            return "HOLD";
        }
        return fusionScore >= 60.0 ? "WATCH" : "WAIT";
    }

    private double computeEntryQualityScore(Map<String, Object> row,
                                            double fusionScore,
                                            double fundamentalBoost,
                                            double relativeStrengthVsSpy,
                                            double volatilityScore,
                                            double sentimentConfidence) {
        double confidenceScore = firstNumber(row, "confidence_score", "Confidence Score");
        double regimeQualityScore = firstNumber(row, "regime_quality_score", "Regime Quality Score");
        double bestRiskReward = firstNumber(row, "best_risk_reward", "Best_Risk_Reward", "long_rr_ratio");

        double rrComponent = clamp(bestRiskReward / 3.0, 0.0, 1.0) * 100.0;
        double volatilityQuality = 100.0 - clamp(volatilityScore, 0.0, 100.0);

        double score =
                0.35 * fusionScore +
                        0.18 * confidenceScore +
                        0.15 * regimeQualityScore +
                        0.12 * normalize100(fundamentalBoost) * 100.0 +
                        0.10 * normalizeRelativeStrength(relativeStrengthVsSpy) * 100.0 +
                        0.06 * rrComponent +
                        0.04 * volatilityQuality;

        // Sentiment confidence is a small stabilizer, not a primary driver.
        score += normalize100(sentimentConfidence) * 2.0;
        return clamp(score, 0.0, 100.0);
    }

    private String tradeDirectionFromRecommendation(String stage, String recommendation) {
        String s = stage == null ? "" : stage.trim().toUpperCase();
        String r = recommendation == null ? "" : recommendation.trim().toUpperCase();

        if (r.contains("SHORT")) return "SHORT";
        if (r.contains("EXIT")) return "EXIT";
        if ("MARKDOWN".equals(s)) return "SHORT_OR_AVOID_LONG";
        if ("DISTRIBUTION".equals(s)) return "REDUCE_OR_WAIT";
        if (r.contains("BUY") || r.contains("ADD") || r.contains("SCALE")) return "LONG";
        if (r.contains("HOLD")) return "HOLD";
        return "WAIT";
    }

    private boolean isLongBuyAction(String recommendation) {
        String r = recommendation == null ? "" : recommendation.trim().toUpperCase();
        return r.contains("BUY") || r.contains("ADD") || r.contains("SCALE");
    }

    private double computeGapPercent(List<Candle> candles) {
        if (candles == null || candles.size() < 2) return 0.0;
        Candle today = candles.get(candles.size() - 1);
        Candle previous = candles.get(candles.size() - 2);
        if (previous.getClose() == 0.0) return 0.0;
        return ((today.getOpen() - previous.getClose()) / previous.getClose()) * 100.0;
    }

    private double computeVolatilityScore(List<Candle> candles, int period) {
        if (candles == null || candles.size() < period + 1) return 0.0;
        double atr = 0.0;
        for (int i = candles.size() - period; i < candles.size(); i++) {
            Candle current = candles.get(i);
            Candle previous = candles.get(i - 1);
            double trueRange = Math.max(
                    current.getHigh() - current.getLow(),
                    Math.max(
                            Math.abs(current.getHigh() - previous.getClose()),
                            Math.abs(current.getLow() - previous.getClose())
                    )
            );
            atr += trueRange;
        }
        atr /= period;
        double lastClose = candles.get(candles.size() - 1).getClose();
        if (lastClose == 0.0) return 0.0;
        double atrPct = (atr / lastClose) * 100.0;
        return clamp((atrPct / 5.0) * 100.0, 0.0, 100.0);
    }

    private RiskRewardPlan calculateDynamicRiskReward(
            MarketContext context,
            EntryAnalysisResult entryResult,
            String marketStage,
            String marketSubstage,
            String childSubstage
    ) {
        double currentPrice = positiveOr(context.price, lastClose(context.candles));
        double entryPrice = firstPositive(
                value(entryResult == null ? null : entryResult.getRefinedBuyPrice()),
                value(entryResult == null ? null : entryResult.getPrimaryEntryPrice()),
                currentPrice
        );
        if (entryPrice <= 0.0) {
            return RiskRewardPlan.empty(currentPrice);
        }

        double atr = positiveOr(context.atr14, estimateAtr(context.candles, 14));
        double atrPct = entryPrice > 0.0 && atr > 0.0 ? atr / entryPrice : 0.025;
        atrPct = clamp(atrPct, 0.008, 0.12);

        double recentSwingLow = recentSwingLow(context.candles, 30);
        double darvasBottom = firstPositive(d(context.metrics, "darvas_box_bottom"), recentSwingLow);
        double emaSupport = firstPositive(context.dma20, context.dma50, entryPrice * (1.0 - atrPct));

        double structuralStop = maxPositive(
                positiveBelow(darvasBottom, entryPrice),
                positiveBelow(recentSwingLow, entryPrice),
                positiveBelow(emaSupport - (0.35 * atr), entryPrice)
        );
        if (structuralStop <= 0.0) {
            structuralStop = entryPrice - (atr * stopAtrMultiple(marketStage, marketSubstage, childSubstage));
        }

        double maxStopPct = containsAny(normalize(marketSubstage), "BREAKOUT", "TREND", "RETEST") ? 0.075 : 0.095;
        double minStopPct = 0.018;
        double stopFloor = entryPrice * (1.0 - maxStopPct);
        double stopCeiling = entryPrice * (1.0 - minStopPct);
        double invalidation = clamp(structuralStop, stopFloor, stopCeiling);

        double riskPerShare = Math.max(0.01, entryPrice - invalidation);
        double resistance20 = highestHigh(context.candles, 20);
        double resistance60 = highestHigh(context.candles, 60);
        double darvasTop = d(context.metrics, "darvas_box_top");
        double measuredMove = entryPrice + Math.max(atr * targetAtrMultiple(marketStage, marketSubstage, childSubstage), rangeDollars(context.candles, 20) * 0.75);

        double target1 = maxPositive(entryPrice + (1.35 * riskPerShare), resistance20 > entryPrice ? resistance20 : 0.0, darvasTop > entryPrice ? darvasTop : 0.0);
        double target2 = maxPositive(entryPrice + (2.20 * riskPerShare), resistance60 > entryPrice ? resistance60 : 0.0, measuredMove);

        if (target1 <= entryPrice) target1 = entryPrice + (1.35 * riskPerShare);
        if (target2 <= target1) target2 = Math.max(target1 + (0.65 * riskPerShare), entryPrice + (2.0 * riskPerShare));

        double weightedTarget = (0.40 * target1) + (0.60 * target2);
        double riskPct = ((entryPrice - invalidation) / entryPrice) * 100.0;
        double rewardPct = ((weightedTarget - entryPrice) / entryPrice) * 100.0;
        double rr = riskPct <= 0.0 ? 0.0 : rewardPct / riskPct;
        double addOnDip = Math.max(invalidation + (0.35 * riskPerShare), entryPrice - (0.50 * riskPerShare));

        return RiskRewardPlan.builder()
                .entryPrice(round(entryPrice))
                .invalidationLevel(round(invalidation))
                .addOnDipPrice(round(addOnDip))
                .target1(round(target1))
                .target2(round(target2))
                .riskPct(round(riskPct))
                .rewardPct(round(rewardPct))
                .bestRiskReward(round(rr))
                .method("STRUCTURAL_STOP_WEIGHTED_TARGET")
                .build();
    }


    /**
     * Compatibility helper for the dynamic RiskRewardPlan methods.
     * Existing YAML/scoring code uses normalizeFeatureName(); the new risk/reward block
     * calls normalize(), so keep this thin wrapper to avoid duplicate normalization logic.
     */
    private String normalize(String value) {
        return normalizeFeatureName(value);
    }

    /**
     * Returns true when normalizedText contains any normalized candidate token.
     * Used by stopAtrMultiple() and targetAtrMultiple() to vary risk/reward by
     * stage/substage/child-substage without hardcoded exact enum matching.
     */
    private boolean containsAny(String normalizedText, String... candidates) {
        if (normalizedText == null || normalizedText.isBlank() || candidates == null) {
            return false;
        }
        String text = normalizeFeatureName(normalizedText);
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank() && text.contains(normalizeFeatureName(candidate))) {
                return true;
            }
        }
        return false;
    }

    private double stopAtrMultiple(String stage, String substage, String child) {
        String text = normalize(stage) + " " + normalize(substage) + " " + normalize(child);
        if (containsAny(text, "BREAKOUT", "CLEAN_BREAKOUT", "SUCCESSFUL_RETEST")) return 1.15;
        if (containsAny(text, "PULLBACK", "HIGHER_LOW", "RETEST")) return 1.35;
        if (containsAny(text, "BASE", "ACCUMULATION", "PRE_BREAKOUT", "COILED")) return 1.55;
        if (containsAny(text, "DISTRIBUTION", "MARKDOWN", "FAILED", "EXHAUSTION")) return 0.95;
        return 1.25;
    }

    private double targetAtrMultiple(String stage, String substage, String child) {
        String text = normalize(stage) + " " + normalize(substage) + " " + normalize(child);
        if (containsAny(text, "STRONG_TREND", "TREND_ACCELERATION", "VOLUME_CONFIRMED")) return 3.20;
        if (containsAny(text, "BREAKOUT", "CLEAN_BREAKOUT")) return 2.75;
        if (containsAny(text, "PRE_BREAKOUT", "COILED", "BASE")) return 2.35;
        if (containsAny(text, "PULLBACK", "RETEST", "HIGHER_LOW")) return 2.50;
        if (containsAny(text, "DISTRIBUTION", "MARKDOWN", "FAILED", "EXHAUSTION")) return 1.25;
        return 2.20;
    }

    private double lastClose(List<Candle> candles) {
        if (candles == null || candles.isEmpty()) return 0.0;
        return candles.get(candles.size() - 1).getClose();
    }

    private double highestHigh(List<Candle> candles, int lookback) {
        if (candles == null || candles.isEmpty()) return 0.0;
        int start = Math.max(0, candles.size() - lookback);
        return candles.subList(start, candles.size()).stream().mapToDouble(Candle::getHigh).max().orElse(0.0);
    }

    private double rangeDollars(List<Candle> candles, int lookback) {
        if (candles == null || candles.isEmpty()) return 0.0;
        int start = Math.max(0, candles.size() - lookback);
        List<Candle> sub = candles.subList(start, candles.size());
        double hi = sub.stream().mapToDouble(Candle::getHigh).max().orElse(0.0);
        double lo = sub.stream().mapToDouble(Candle::getLow).min().orElse(0.0);
        return Math.max(0.0, hi - lo);
    }

    private double estimateAtr(List<Candle> candles, int period) {
        if (candles == null || candles.size() < 2) return 0.0;
        int start = Math.max(1, candles.size() - period);
        double total = 0.0;
        int count = 0;
        for (int i = start; i < candles.size(); i++) {
            Candle c = candles.get(i);
            Candle p = candles.get(i - 1);
            double tr = Math.max(c.getHigh() - c.getLow(), Math.max(Math.abs(c.getHigh() - p.getClose()), Math.abs(c.getLow() - p.getClose())));
            total += tr;
            count++;
        }
        return count == 0 ? 0.0 : total / count;
    }



    private double maxPositive(double... values) {
        double max = 0.0;
        for (double v : values) if (v > max && Double.isFinite(v)) max = v;
        return max;
    }

    private double positiveBelow(double value, double ceiling) {
        return value > 0.0 && value < ceiling ? value : 0.0;
    }



    private double value(Double value) {
        return value == null ? 0.0 : value;
    }

    @Data
    @lombok.Builder
    private static class RiskRewardPlan {
        double entryPrice;
        double invalidationLevel;
        double addOnDipPrice;
        double target1;
        double target2;
        double riskPct;
        double rewardPct;
        double bestRiskReward;
        String method;

        static RiskRewardPlan empty(double price) {
            return RiskRewardPlan.builder()
                    .entryPrice(price)
                    .invalidationLevel(0.0)
                    .addOnDipPrice(0.0)
                    .target1(0.0)
                    .target2(0.0)
                    .riskPct(0.0)
                    .rewardPct(0.0)
                    .bestRiskReward(0.0)
                    .method("EMPTY")
                    .build();
        }
    }

    private double normalize100(double value) {
        return clamp(value / 100.0, 0.0, 1.0);
    }

    private double normalizeRelativeStrength(double rsVsSpy) {
        // -10% relative underperformance -> 0, +10% outperformance -> 1.
        return clamp((rsVsSpy + 10.0) / 20.0, 0.0, 1.0);
    }

    private double normalizeSignedSentiment(double sentimentScore) {
        // Alpha Vantage sentiment score is typically -1..+1.
        return clamp((sentimentScore + 1.0) / 2.0, 0.0, 1.0);
    }

    private double firstNonZero(double preferred, double fallback) {
        return preferred != 0.0 ? preferred : fallback;
    }

    private double firstNumber(Map<String, Object> row, String... keys) {
        if (row == null || row.isEmpty()) return 0.0;
        for (String key : keys) {
            Object value = row.get(key);
            if (value == null) value = row.get(toSnakeCase(key));
            if (value == null) value = row.get(toTitleCase(key));
            if (value == null) {
                String normalized = normalizeFeatureName(key);
                for (Map.Entry<String, Object> entry : row.entrySet()) {
                    if (normalizeFeatureName(entry.getKey()).equals(normalized)) {
                        value = entry.getValue();
                        break;
                    }
                }
            }
            if (value != null) {
                return safeDouble(value, 0.0);
            }
        }
        return 0.0;
    }

    private String stringValue(Object value, String defaultValue) {
        if (value == null) return defaultValue;
        String text = String.valueOf(value).trim();
        return text.isBlank() ? defaultValue : text;
    }

    private static class MarketContext {
        List<Candle> candles;
        Map<String, Object> metrics;
        double price;
        double vwap;
        double adx;
        double rsi;
        double dma20;
        double dma50;
        double dma200;
        double atr14;
        double vol;
        boolean breakout;
        boolean hammer;
        boolean engulf;
        boolean nearSupport;
        boolean dipReclaim;
        boolean macdCross;
        boolean bullStack;
        boolean bearStack;
        boolean priceAboveVwap;
        boolean priceBelowVwap;
        double compressionRange;
        double tightCompression;
        double adxTrend;
        double strongTrendAdx;
        double rsiBull;
        double rsiBear;
        double exhaustionHigh;
        double exhaustionLow;
        double strongVol;
        double range20;

        boolean pullbackToTrend() {
            if (price <= 0.0) return false;
            double pctFrom20 = Math.abs(price - dma20) / price;
            return bullStack && price >= dma20 && pctFrom20 <= 0.04;
        }
    }
}
