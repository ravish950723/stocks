package com.trading.service;

import com.trading.agent.api.DecisionCoordinator;
import com.trading.agent.model.FinalTradeDecision;
import com.trading.agent.model.MarketContext;
import com.trading.agent.service.MarketContextMapper;
import com.trading.alphavantage.AlphaVantageEnrichmentService;
import com.trading.analytics.AnalyticsResult;
import com.trading.analytics.BacktestReportService;
import com.trading.analytics.FundamentalSnapshot;
import com.trading.analytics.MissingAnalyticsLayerService;
import com.trading.analytics.ValidationReportService;
import com.trading.config.AppRuntimeConfig;
import com.trading.config.ColumnDefinition;
import com.trading.config.YamlConfigService;
import com.trading.contracts.MarketSnapshot;
import com.trading.decision.FinalActionEngine;
import com.trading.decision.FinalActionResult;
import com.trading.export.CsvTrainingExportService;
import com.trading.export.ExcelExportService;
import com.trading.indicators.TechnicalIndicatorService;
import com.trading.ingestion.MarketDataAggregationService;
import com.trading.logging.SymbolLoggingContext;
import com.trading.logic.BusinessLogicService;
import com.trading.logic.MlEntryDecisionEngine;
import com.trading.logic.RankingEngine;
import com.trading.logic.RankingEngineV3;
import com.trading.ml.*;
import com.trading.probability.ProbabilityEngine;
import com.trading.sector.SectorRotationEngine;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@RequiredArgsConstructor
@Service
public class PipelineService {

    private static final Logger log = LogManager.getLogger(PipelineService.class);

    private final YamlConfigService yamlConfigService;
    private final MarketDataAggregationService marketDataAggregationService;
    private final TechnicalIndicatorService technicalIndicatorService;
    private final BusinessLogicService businessLogicService;
    private final ExcelExportService excelExportService;
//    private final MlClient mlClient;
    private final MlPredictionClient mlPredictionClient;
    private final MlFeatureMapper mlFeatureMapper;
    private final CsvTrainingExportService csvTrainingExportService;
    private final DecisionCoordinator decisionCoordinator;
    private final MarketContextMapper marketContextMapper;
    private final AlphaVantageEnrichmentService alphaVantageEnrichmentService;
    private final RankingEngine rankingEngine;
    private final MissingAnalyticsLayerService missingAnalyticsLayerService;
    private final FinalActionEngine finalActionEngine;
    private final ParallelPipelineExecutor parallelPipelineExecutor;
    private final RankingEngineV3 rankingEngineV3;
    private final ProbabilityEngine probabilityEngine;
    private final SectorRotationEngine sectorRotationEngine;
    private final MlFusionService mlFusionService;
    private final ValidationReportService validationReportService;
    private final BacktestReportService backtestReportService;
    private final TrainingFeatureExportService trainingFeatureExportService;
    private final HistoricalTrainingFeatureExportService historicalTrainingFeatureExportService;
    private final MlEntryDecisionEngine mlEntryDecisionEngine;
    private final com.trading.logic.ShortSetupEngine shortSetupEngine;
    private final com.trading.logic.EtfProxyGrowthScoreEngine etfProxyGrowthScoreEngine;
    private final com.trading.output.LivePredictionOutputSanitizer livePredictionOutputSanitizer;

    public String runPipeline() {
        AppRuntimeConfig config = yamlConfigService.load();
        List<Map<String, Object>> historicalTrainingRows = Collections.synchronizedList(new ArrayList<>());

        log.info("Starting pipeline for symbols={}", config.getSymbols());

        MarketSnapshot spySnapshot = null;
        try {
            spySnapshot = marketDataAggregationService.loadDailySnapshot("SPY", config);
            log.info("SPY snapshot loaded for Relative Strength calculation. candles={}",
                    spySnapshot == null || spySnapshot.getCandles() == null ? 0 : spySnapshot.getCandles().size());
        } catch (Exception e) {
            log.warn("Unable to load SPY snapshot. Relative Strength vs SPY unavailable. error={}", e.getMessage());
        }

        final MarketSnapshot finalSpySnapshot = spySnapshot;

        List<Map<String, Object>> rows = parallelPipelineExecutor.execute(
                config.getSymbols(),
                new ParallelPipelineExecutor.SymbolTask<Map<String, Object>>() {

                    @Override
                    public Map<String, Object> process(String symbol) throws Exception {
                        try (SymbolLoggingContext ignored = SymbolLoggingContext.open(symbol)) {

                            MarketSnapshot snapshot = marketDataAggregationService.loadDailySnapshot(symbol, config);

                            Map<String, Object> metrics =
                                    technicalIndicatorService.calculate(snapshot.getCandles(), config);

                            Map<String, Object> row =
                                    businessLogicService.apply(config, symbol, snapshot.getCandles(), metrics);

                            /* CRITICAL: merge technical indicators into final export row */
                            if (metrics != null && !metrics.isEmpty()) {
                                row.putAll(metrics);
                                log.info("TECH_METRICS_MERGED symbol={} metricCount={}",  symbol, metrics.size());
                            }

                            Map<String, Object> avData =
                                    alphaVantageEnrichmentService.enrich(config, symbol, row);

                            row.putAll(avData);

                            log.info("ALPHA_VANTAGE_OUTPUT symbol={} status={} sector={} industry={} beta={} epsAvailable={} sentimentLabel={}",
                                    symbol,
                                    row.get("alpha_vantage_status"),
                                    firstString(row, "Sector", "sector", "av_sector"),
                                    firstString(row, "Industry", "industry", "av_industry"),
                                    firstDouble(row, "Beta", "beta", "av_beta"),
                                    row.get("EPS_AVAILABLE"),
                                    firstString(row, "Sentiment Label", "sentiment_label"));

                            enrichWithMl(symbol, row);

                            MarketContext ctx = marketContextMapper.map(symbol, row);
                            FinalTradeDecision decision = decisionCoordinator.evaluate(ctx);
                            mergeAgentDecision(row, decision);

                            enrichWithAnalytics(symbol, snapshot, finalSpySnapshot, row);

                            applyFinalAction(row);
                            applyMlFusion(row);
                            row = enrichLiveOnlyColumns(row);

                            if (config.isRebuildTrainingHistoryOnEveryRun()) {
                                List<Map<String, Object>> symbolHistory =
                                        csvTrainingExportService.buildHistoricalTrainingRows(
                                                config, symbol, snapshot.getCandles());

                                if (symbolHistory != null && !symbolHistory.isEmpty()) {
                                    historicalTrainingRows.addAll(symbolHistory);
                                }
                            }

                            Map<String,Object> finalRow = fillSchemaDefaults(config, row);
                            return livePredictionOutputSanitizer.sanitizeLivePredictionRow(finalRow);
                        }
                    }

                    @Override
                    public Map<String, Object> onError(String symbol, Exception e) {
                        log.error("Pipeline failed for symbol={} error={}", symbol, e.getMessage(), e);
                        return errorRow(config, symbol, e);
                    }
                }
        );

        log.info("HISTORICAL_TRAINING_EXPORT_START source=PipelineService");
        historicalTrainingFeatureExportService.exportHistoricalTrainingFeatures();
        log.info("HISTORICAL_TRAINING_EXPORT_DONE source=PipelineService");

        log.info("Running ranking engine on rows={}", rows.size());

        RankingEngine.RankingResult rankingResult = rankingEngine.rank(rows);

        probabilityEngine.applyProbabilities(rows);
        sectorRotationEngine.applySectorRotation(rows);
        rankingEngineV3.applyProbabilityRankings(rows);

        Map<String, Integer> rankMap = new LinkedHashMap<>();
        int rank = 1;
        for (RankingEngine.RankedSymbol r : rankingResult.getRanked()) {
            rankMap.put(r.getSymbol(), rank++);
        }

        for (Map<String, Object> row : rows) {
            String symbol = String.valueOf(row.get("symbol"));
            Integer symbolRank = rankMap.get(symbol);

            row.put("rank", symbolRank == null ? 999 : symbolRank);
            row.put("rank_bucket", bucket(symbolRank));
        }

        log.info("Ranking completed. Top symbol={}",
                rankingResult.getRanked().isEmpty()
                        ? "NONE"
                        : rankingResult.getRanked().get(0).getSymbol());

        log.info("Exporting excel output rows={}", rows.size());
        log.info("Top 7 ranked symbols:");
        rankingResult.getRanked().stream()
                .limit(7)
                .forEach(r -> log.info("Rank {} -> {} score={}",
                        r.getSymbol(), r.getRankScore(), r.getFinalScore()));

        excelExportService.export(config, rows);

        validationReportService.generate(config, rows);
        backtestReportService.generate(config, historicalTrainingRows.isEmpty() ? rows : historicalTrainingRows);

        return "Pipeline completed. Output written to " + config.getOutputFile();
    }




    private void exportJavaMlTrainingRows(List<Map<String, Object>> rows) {

        List<Map<String, Object>> mlRows = rows.stream()
                .map(this::toMlTrainingRow)
                .peek(row -> {
                    Object symbol = row.get("symbol");
                    List<String> missing = missingRequiredMlColumns(row);

                    if (!missing.isEmpty()) {
                        log.warn("ML_EXPORT_SCHEMA_FAIL symbol={} missing={} keys={}",
                                symbol, missing, row.keySet());
                    } else {
                        log.info("ML_EXPORT_SCHEMA_OK symbol={} current_price={} rsi={} adx={} dma20={} dma50={} dma200={}",
                                symbol,
                                row.get("current_price"),
                                row.get("rsi"),
                                row.get("adx"),
                                row.get("dma20"),
                                row.get("dma50"),
                                row.get("dma200"));
                    }
                })
                .filter(row -> missingRequiredMlColumns(row).isEmpty())
                .toList();

        log.info("JAVA_ML_TRAINING_EXPORT_START totalRows={} eligibleRows={}",
                rows.size(), mlRows.size());

        if (mlRows.isEmpty()) {
            log.error("JAVA_ML_TRAINING_EXPORT_SKIPPED reason=ZERO_ELIGIBLE_ROWS");
            return;
        }

        trainingFeatureExportService.exportTrainingFeatures(mlRows);

        log.info("JAVA_ML_TRAINING_EXPORT_DONE exportedRows={}", mlRows.size());
    }




    private boolean isTrainingEligibleRow(Map<String, Object> row) {
        return missingRequiredMlColumns(row).isEmpty();
    }

    private List<String> missingRequiredMlColumns(Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return List.of("ROW_EMPTY");
        }


        List<String> required = List.of(
                "symbol",
                "date",
                "current_price",
                "rsi",
                "adx",
                "atr14_pct",
                "vwap_distance_pct",
                "vol_surge_ratio",
                "ema_uptrend",
                "ema21_slope",
                "dma20",
                "dma50",
                "dma200",
                "pct_from_dma20",
                "pct_from_dma50",
                "pct_from_dma200",
                "breakout",
                "bullish_engulfing",
                "hammer",
                "near_support",
                "macd_cross",
                "regime_quality_score",
                "signal_score",
                "confidence_score",
                "institutional_score"
        );

        return required.stream()
                .filter(col -> !hasRealValue(row.get(col)))
                .toList();
    }




    private Map<String, Object> toMlTrainingRow(Map<String, Object> source) {
        Map<String, Object> out = new LinkedHashMap<>(source);

        putAlias(out, "symbol", "symbol");
        if (!hasRealValue(out.get("date"))) {
            out.put("date", java.time.LocalDate.now().toString());
        }
        putAlias(out, "date", "date", "as_of_date", "snapshot_date");

        putAlias(out, "current_price", "current_price", "Current Price", "price", "last_price");
        putAlias(out, "rsi", "rsi", "RSI");
        putAlias(out, "adx", "adx", "ADX");

        putAlias(out, "vwap_distance_pct", "vwap_distance_pct", "VWAP Distance %");
        putAlias(out, "vol_surge_ratio", "vol_surge_ratio", "Volume Surge Ratio");

        putAlias(out, "atr14_pct", "atr14_pct", "atr14Pct", "ATR14 Percent", "ATR14 %", "atr_pct");
        putAlias(out, "vwap_distance_pct", "vwap_distance_pct", "vwapDistancePct", "VWAP Distance %");
        putAlias(out, "vol_surge_ratio", "vol_surge_ratio", "volSurgeRatio", "Volume Surge Ratio");

        putAlias(out, "breakout", "breakout", "Breakout", "is_breakout");
        putAlias(out, "bullish_engulfing", "bullish_engulfing", "bullishEngulfing", "Bullish Engulfing");
        putAlias(out, "hammer", "hammer", "Hammer");
        putAlias(out, "near_support", "near_support", "nearSupport", "Near Support");
        putAlias(out, "macd_cross", "macd_cross", "macdCross", "MACD Cross");

        putAlias(out, "regime_quality_score", "regime_quality_score", "regimeQualityScore", "Regime Quality Score");

        putAlias(out, "ema_uptrend", "ema_uptrend", "EMA Uptrend");
        putAlias(out, "ema21_slope", "ema21_slope", "EMA21 Slope");

        putAlias(out, "dma20", "dma20", "DMA20", "SMA20");
        putAlias(out, "dma50", "dma50", "DMA50", "SMA50");
        putAlias(out, "dma200", "dma200", "DMA200", "SMA200");

        putAlias(out, "pct_from_dma20", "pct_from_dma20", "Pct From DMA20");
        putAlias(out, "pct_from_dma50", "pct_from_dma50", "Pct From DMA50");
        putAlias(out, "pct_from_dma200", "pct_from_dma200", "Pct From DMA200");

        putAlias(out, "breakout", "breakout", "Breakout");
        putAlias(out, "bullish_engulfing", "bullish_engulfing", "Bullish Engulfing");
        putAlias(out, "hammer", "hammer", "Hammer");
        putAlias(out, "near_support", "near_support", "Near Support");
        putAlias(out, "macd_cross", "macd_cross", "MACD Cross");

        putAlias(out, "regime_quality_score", "regime_quality_score", "Regime Quality Score");
        putAlias(out, "signal_score", "signal_score", "Signal Score");
        putAlias(out, "confidence_score", "confidence_score", "Confidence Score");
        putAlias(out, "institutional_score", "institutional_score", "Institutional Score");

        putAlias(out, "relative_strength_vs_spy", "relative_strength_vs_spy", "Relative Strength vs SPY");
        putAlias(out, "best_risk_reward", "best_risk_reward", "Best_Risk_Reward", "risk_reward_ratio");
        putAlias(out, "news_sentiment_score", "news_sentiment_score", "News Sentiment Score");
        putAlias(out, "news_positive_ratio", "news_positive_ratio", "News Positive Ratio");


        /* force normalized ML keys */
        putAlias(out, "adx", "adx", "ADX");
        putAlias(out, "rsi", "rsi", "RSI");

        putAlias(out, "dma20", "dma20", "DMA20", "SMA20");
        putAlias(out, "dma50", "dma50", "DMA50", "SMA50");
        putAlias(out, "dma200", "dma200", "DMA200", "SMA200");

        putAlias(out, "current_price", "current_price", "Current Price", "price", "last_price");

        putAlias(out, "confidence_score", "confidence_score", "Confidence Score");
        putAlias(out, "signal_score", "signal_score", "Signal Score");
        putAlias(out, "institutional_score", "institutional_score", "Institutional Score");

        putAlias(out, "ema21_slope", "ema21_slope", "EMA21 Slope");
        putAlias(out, "ema_uptrend", "ema_uptrend", "EMA Uptrend");

        return out;
    }

    private void putAlias(Map<String, Object> row, String targetKey, String... sourceKeys) {
        if (hasRealValue(row.get(targetKey))) {
            return;
        }

        for (String sourceKey : sourceKeys) {
            Object value = row.get(sourceKey);
            if (hasRealValue(value)) {
                row.put(targetKey, value);
                return;
            }
        }
    }

    private boolean hasAny(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            if (hasRealValue(row.get(key))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasRealValue(Object value) {
        if (value == null) return false;

        if (value instanceof String s) {
            String t = s.trim();
            return !t.isEmpty()
                    && !"null".equalsIgnoreCase(t)
                    && !"nan".equalsIgnoreCase(t)
                    && !"n/a".equalsIgnoreCase(t)
                    && !"na".equalsIgnoreCase(t)
                    && !"ERROR".equalsIgnoreCase(t);
        }

        if (value instanceof Double d) return !d.isNaN() && !d.isInfinite();
        if (value instanceof Float f) return !f.isNaN() && !f.isInfinite();

        return true;
    }

    private void enrichWithAnalytics(
            String symbol,
            MarketSnapshot snapshot,
            MarketSnapshot spySnapshot,
            Map<String, Object> row
    ) {
        try {
            AnalyticsResult analytics = missingAnalyticsLayerService.analyze(
                    symbol,
                    snapshot.getCandles(),
                    spySnapshot == null ? null : spySnapshot.getCandles(),
                    FundamentalSnapshot.builder()
                            .symbol(symbol)
                            .industry(firstString(row, "Industry", "industry"))
                            .beta(firstDouble(row, "Beta", "beta"))
                            .build(),
                    MissingAnalyticsLayerService.TechnicalInputs.builder()
                            .price(firstDouble(row, "current_price", "Current Price", "price"))
                            .vwap(firstDouble(row, "VWAP", "vwap"))
                            .rsi(firstDouble(row, "RSI", "rsi"))
                            .adx(firstDouble(row, "ADX", "adx"))
                            .macd(firstDouble(row, "MACD", "macd"))
                            .macdSignal(firstDouble(row, "MACD_SIGNAL", "macd_signal", "macdSignal"))
                            .stageScore(firstDouble(row, "stage_score", "Stage Score"))
                            .substageScore(firstDouble(row, "substage_score", "Substage Score"))
                            .signalScore(firstDouble(row, "signal_score", "Signal Score"))
                            .confidenceScore(firstDouble(row, "confidence_score", "Confidence Score"))
                            .institutionalScore(firstDouble(row, "institutional_score", "Institutional Score"))
                            .build()
            );

            putBoth(row, "90d_hit", "90D Hit", analytics.getNinetyDayHit());
            putBoth(row, "90d_gain", "90D Gain (%)", analytics.getNinetyDayGainPct());
            putBoth(row, "days_to_peak", "Days to Peak", analytics.getDaysToPeak());
            putBoth(row, "momentum_decision_reason", "Momentum Decision Reason", analytics.getMomentumDecisionReason());
            putBoth(row, "final_score", "Final Score", analytics.getFinalScore());
            putBoth(row, "industry", "Industry", analytics.getIndustry());
            putBoth(row, "beta", "Beta", analytics.getBeta());
            putBoth(row, "relative_strength_vs_spy", "Relative Strength vs SPY", analytics.getRelativeStrengthVsSpy());

            log.info("ANALYTICS_OUTPUT symbol={} daysToPeak={}", symbol, analytics.getDaysToPeak());

        } catch (Exception e) {
            log.warn("Analytics enrichment failed for symbol={} error={}", symbol, e.getMessage());

            putBothIfAbsent(row, "momentum_decision_reason", "Momentum Decision Reason",
                    "Analytics unavailable: " + e.getMessage());
            putBothIfAbsent(row, "industry", "Industry", firstString(row, "Industry", "industry", "av_industry"));
            putBothIfAbsent(row, "beta", "Beta", firstDouble(row, "Beta", "beta", "av_beta"));
        }
    }

    private void applyFinalAction(Map<String, Object> row) {
        FinalActionResult finalAction = finalActionEngine.decide(row);

        row.put("final_action", finalAction.getFinalAction());
        row.put("Final Action", finalAction.getFinalAction());

        row.put("trade_direction", finalAction.getTradeDirection());
        row.put("Trade Direction", finalAction.getTradeDirection());

        row.put("rule_recommendation", finalAction.getRuleRecommendation());
        row.put("Rule Recommendation", finalAction.getRuleRecommendation());

        row.put("execution_action", finalAction.getExecutionAction());
        row.put("Execution Action", finalAction.getExecutionAction());

        row.put("final_probability", finalAction.getFinalProbability());

        row.put("Confidence Grade", finalAction.getConfidenceGrade());
        row.put("confidence_band", finalAction.getConfidenceBand());

        row.put("decision_reason", finalAction.getDecisionReason());
        row.put("Decision Reason", finalAction.getDecisionReason());
    }

    private String bucket(Integer rank) {
        if (rank == null) return "UNRANKED";
        if (rank <= 10) return "TOP_10";
        if (rank <= 20) return "TOP_20";
        return "OTHER";
    }


    private Map<String, Object> enrichLiveOnlyColumns(Map<String, Object> row) {
        // Real live-calculated values, where enough current features exist.
        mlEntryDecisionEngine.applyToRow(row);
        shortSetupEngine.applyToRow(row);
        etfProxyGrowthScoreEngine.applyToRow(row);

        // Final safety pass: never export future-known 90D labels in live prediction Excel.
        return livePredictionOutputSanitizer.sanitizeLivePredictionRow(row);
    }

    private void mergeAgentDecision(Map<String, Object> row, FinalTradeDecision d) {
        row.put("agent_final_action", d.getFinalAction());
        row.put("agent_confidence_band", d.getConfidenceBand());
        row.put("agent_summary", d.getSummary());
        row.put("agent_analyst_score", d.getAnalystScore());
        row.put("agent_risk_score", d.getRiskScore());
        row.put("agent_entry_price", d.getEntryPrice());
        row.put("agent_stop_loss", d.getStopLoss());
        row.put("agent_target_1", d.getTarget1());
        row.put("agent_target_2", d.getTarget2());
        row.put("agent_risk_reward", d.getRiskReward());
    }

    private void enrichWithMl(String symbol, Map<String, Object> row) {
        double ruleProbability = normalizeRuleProbabilityWithoutMl(row);
        row.put("rule_probability", ruleProbability);

        try {
            MlFeaturesRequest mlRequest = mlFeatureMapper.toRequest(symbol, row);
            PredictResponse resp =
                    mlPredictionClient.predict(symbol, mlRequest.getFeatures());

            MlPredictionResponse ml = mapPredictResponse(resp);
            double mlProbability = clamp01(firstNonZero(
                    ml.getEnsembleProbability(),
                    ml.getProbability(),
                    ml.getXgbProbability(),
                    ml.getDlProbability()
            ));
            double finalProbability = Math.max(ruleProbability, mlProbability);

            mlFusionService.writeMlColumns(row, ml);

            row.put("model_probability", mlProbability);
            row.put("model_driven_buy", ml.isModelDrivenBuy());
            row.put("model_driven_strong_buy", ml.isModelDrivenStrongBuy());
            row.put("ml_entry_target", ml.getMlEntryTarget());
            row.put("confidence_band", stringValue(row.getOrDefault("confidence_band", ml.getConfidenceBand())));
            row.put("tech_fallback_score", ml.getTechFallbackScore());
            row.put("final_probability", finalProbability);

            String existingReason = stringValue(row.get("decision_reason"));
            String mlReason = stringValue(ml.getDecisionReason());
            if (!mlReason.isBlank()) {
                row.put("decision_reason", existingReason.isBlank() ? mlReason : existingReason + " | ML: " + mlReason);
            }

            log.info("ML_ENRICHED symbol={} probability={} ensemble={} rankScore={} status={} schemaOk={} hedgeGate={} capApplied={} missingRequired={}",
                    symbol,
                    round4(ml.getProbability()),
                    round4(ml.getEnsembleProbability()),
                    round4(ml.getRankScore()),
                    ml.getStatus(),
                    ml.isSchemaCompatible(),
                    ml.isHedgeSafeGatingPassed(),
                    ml.isProbabilityCapApplied(),
                    ml.getMissingRequiredFeatures());

        } catch (Exception e) {
            MlPredictionResponse fallback = MlPredictionResponse.fallback("ML unavailable: " + e.getMessage());
            mlFusionService.writeMlColumns(row, fallback);

            String existingReason = stringValue(row.get("decision_reason"));
            String mlError = "ML unavailable: " + e.getMessage();
            row.put("decision_reason", existingReason.isBlank() ? mlError : existingReason + " | " + mlError);

            log.warn("ML_ENRICH_FAILED symbol={} error={}", symbol, e.getMessage());
        }
    }

    private MlPredictionResponse mapPredictResponse(PredictResponse r) {

        if (r == null) {
            return MlPredictionResponse.fallback("ML response null");
        }

        return MlPredictionResponse.builder()
                .probability(r.getProbability())
                .ensembleProbability(r.getProbability())
                .rankScore(r.getRankScore())
                .status(r.getStatus())
                .promotionStatus(r.getPromotionStatus())
                .hedgeSafeGatingPassed(r.isHedgeGate())
                .schemaCompatible(r.isSchemaOk())
                .missingRequiredFeatures(
                        r.getMissingRequired() == null
                                ? java.util.List.of()
                                : r.getMissingRequired())
                .modelDrivenBuy(r.getProbability() >= 0.55)
                .modelDrivenStrongBuy(r.getProbability() >= 0.72)
                .confidenceBand(
                        r.getProbability() >= 0.72 ? "HIGH" :
                                r.getProbability() >= 0.55 ? "MEDIUM" : "LOW")
                .probabilityCapApplied(false)
                .fallbackUsed(false)
                .decisionReason("")
                .techFallbackScore(0.0)
                .mlEntryTarget(0.0)
                .xgbProbability(0.0)
                .dlProbability(0.0)
                .modelVersion("")
                .schemaVersion("")
                .build();
    }

    private void applyMlFusion(Map<String, Object> row) {
        try {
            MlPredictionResponse ml = MlPredictionResponse.builder()
                    .probability(getDouble(row, "ml_probability"))
                    .ensembleProbability(getDouble(row, "ml_ensemble_probability"))
                    .rankScore(getDouble(row, "ml_rank_score"))
                    .modelDrivenBuy(booleanValue(row.get("ml_model_driven_buy")))
                    .modelDrivenStrongBuy(booleanValue(row.get("ml_model_driven_strong_buy")))
                    .schemaCompatible(booleanValue(row.get("ml_schema_compatible")))
                    .hedgeSafeGatingPassed(booleanValue(row.get("ml_hedge_safe_gating_passed")))
                    .probabilityCapApplied(booleanValue(row.get("ml_probability_cap_applied")))
                    .promotionStatus(stringValue(row.get("ml_promotion_status")))
                    .fallbackUsed("ERROR".equalsIgnoreCase(stringValue(row.get("ml_status"))))
                    .status(stringValue(row.get("ml_status")))
                    .decisionReason(stringValue(row.get("ml_decision_reason")))
                    .confidenceBand(stringValue(row.get("ml_confidence_band")))
                    .techFallbackScore(getDouble(row, "ml_tech_fallback_score"))
                    .mlEntryTarget(getDouble(row, "ml_entry_target"))
                    .xgbProbability(getDouble(row, "ml_xgb_probability"))
                    .dlProbability(getDouble(row, "ml_dl_probability"))
                    .missingRequiredFeatures(Collections.singletonList(stringValue(row.get("ml_missing_required_features"))))
                    .modelVersion(stringValue(row.get("ml_model_version")))
                    .schemaVersion(stringValue(row.get("ml_schema_version")))
                    .build();

            String currentAction = firstString(row, "final_action", "Final Action", "recommendation");
            String fusedAction = mlFusionService.fuseFinalAction(
                    currentAction,
                    ml,
                    isBullishStage(row),
                    firstDouble(row, "entry_quality_score", "entry_confidence_score", "Entry Quality Score", "Entry Confidence Score"),
                    firstDouble(row, "best_risk_reward", "long_rr_ratio", "risk_reward_ratio"),
                    firstDouble(row, "final_recommendation_score", "Final Score", "signal_score", "confidence_score")
            );

            row.put("ml_fused_action", fusedAction);

            if (!stringValue(fusedAction).equalsIgnoreCase(currentAction)) {
                String reason = stringValue(row.get("Decision Reason"));
                String updatedReason = reason.isBlank()
                        ? "ML fusion adjusted action from " + currentAction + " to " + fusedAction
                        : reason + " | ML fusion adjusted action from " + currentAction + " to " + fusedAction;
                row.put("decision_reason", updatedReason);
                row.put("Decision Reason", updatedReason);
            }

            row.put("final_action", fusedAction);
            row.put("Final Action", fusedAction);

        } catch (Exception e) {
            log.warn("ML_FUSION_SKIPPED symbol={} error={}", row.get("symbol"), e.getMessage());
        }
    }

    private double firstNonZero(double... values) {
        if (values == null) return 0.0;
        for (double v : values) {
            if (v > 0.0) return v;
        }
        return 0.0;
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.doubleValue() != 0.0;
        if (value == null) return false;
        String s = String.valueOf(value).trim();
        return "true".equalsIgnoreCase(s) || "yes".equalsIgnoreCase(s) || "y".equalsIgnoreCase(s) || "1".equals(s);
    }

    private boolean isBullishStage(Map<String, Object> row) {
        String stage = firstString(row, "market_stage", "stage_key", "Market Stage").toUpperCase();
        String substage = firstString(row, "market_substage", "substage_key", "Market Substage").toUpperCase();
        String child = firstString(row, "child_substage", "child_substage_key").toUpperCase();

        return stage.contains("MARKUP")
                || stage.contains("ACCUMULATION")
                || substage.contains("BREAKOUT")
                || substage.contains("EARLY_TREND")
                || child.contains("BREAKOUT")
                || child.contains("ACCUMULATION");
    }

    private double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private double normalizeRuleProbabilityWithoutMl(Map<String, Object> row) {
        double regimeQualityScore = getDouble(row, "regime_quality_score");
        regimeQualityScore = regimeQualityScore > 1.0 ? clamp01(regimeQualityScore / 100.0) : clamp01(regimeQualityScore);

        double confidenceScore = getDouble(row, "confidence_score");
        confidenceScore = confidenceScore > 1.0 ? clamp01(confidenceScore / 100.0) : clamp01(confidenceScore);

        double institutionalScore = getDouble(row, "institutional_score");
        institutionalScore = institutionalScore > 1.0 ? clamp01(institutionalScore / 100.0) : clamp01(institutionalScore);

        return clamp01(
                0.45 * confidenceScore +
                        0.35 * regimeQualityScore +
                        0.20 * institutionalScore
        );
    }

    private Map<String, Object> fillSchemaDefaults(AppRuntimeConfig config, Map<String, Object> row) {
        Map<String, Object> merged = new LinkedHashMap<>(row);
        for (ColumnDefinition column : config.getColumns()) {
            merged.putIfAbsent(column.getKey(), column.getDefaultValue());
        }
        return merged;
    }

    private Map<String, Object> errorRow(AppRuntimeConfig config, String symbol, Exception e) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("symbol", symbol);
        row.put("market_stage", "ERROR");
        row.put("market_substage", "ERROR");
        row.put("trend", "ERROR");
        row.put("pattern_detected", e.getMessage());
        row.put("recommendation", "ERROR");
        row.put("rule_recommendation", "ERROR");
        row.put("signal", "ERROR");
        row.put("execution_action", "ERROR");
        row.put("final_action", "ERROR");
        row.put("decision_reason", e.getMessage());
        row.put("Final Action", "ERROR");
        row.put("Trade Direction", "NEUTRAL");
        row.put("Rule Recommendation", "ERROR");
        row.put("Execution Action", "ERROR");
        row.put("Decision Reason", e.getMessage());
        row.put("Confidence Grade", "D");
        return fillSchemaDefaults(config, row);
    }

    private void putBoth(Map<String, Object> row, String schemaKey, String displayKey, Object value) {
        row.put(schemaKey, value);
        row.put(displayKey, value);
    }

    private void putBothIfAbsent(Map<String, Object> row, String schemaKey, String displayKey, Object value) {
        row.putIfAbsent(schemaKey, value);
        row.putIfAbsent(displayKey, value);
    }

    private double firstDouble(Map<String, Object> row, String... keys) {
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
            String value = stringValue(row.get(key));
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

    private double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}