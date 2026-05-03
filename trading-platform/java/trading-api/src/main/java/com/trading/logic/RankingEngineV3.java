package com.trading.logic;

import lombok.Builder;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * RankingEngineV3
 *
 * Additive probability/ranking layer for your trading pipeline.
 *
 * Purpose:
 *  - Adds probability scoring on top of Stage/Substage/Child/Fundamental/Technical/ML signals.
 *  - Produces top buckets:
 *      1) safest buy now
 *      2) aggressive buy / momentum
 *      3) hidden institutional accumulation
 *      4) likely 20%+ upside
 *      5) likely 30%+ upside
 *      6) short candidates
 *      7) avoid / trap setups
 *  - Adds probability columns:
 *      probability_10pct_upside
 *      probability_20pct_upside
 *      probability_30pct_upside
 *      probability_stop_loss_hit
 *      probability_safe_buy
 *      probability_aggressive_buy
 *      probability_hidden_accumulation
 *      probability_short_candidate
 *      probability_avoid_trap
 *      probability_final_edge
 *
 * Integration:
 *  - Best: keep your existing RankingEngine.java unchanged.
 *  - Inject this bean into PipelineService and call rankingEngineV3.applyProbabilityRankings(rows)
 *    after finalActionEngine.decide(row) and before excelExportService.export(config, rows).
 *
 * Example in PipelineService:
 *      private final RankingEngineV3 rankingEngineV3;
 *
 *      ...
 *      RankingEngine.RankingResult rankingResult = rankingEngine.rank(rows);
 *      rankingEngineV3.applyProbabilityRankings(rows);
 *      excelExportService.export(config, rows);
 *
 * Defensive design:
 *  - Reads many possible column names.
 *  - Missing numeric fields default to neutral values where possible.
 *  - Does not remove or rename any existing column.
 */
@Slf4j
@Component
public class RankingEngineV3 {

    public static final int DEFAULT_TOP_N = 10;

    // Probability output columns
    public static final String COL_P10 = "probability_10pct_upside";
    public static final String COL_P20 = "probability_20pct_upside";
    public static final String COL_P30 = "probability_30pct_upside";
    public static final String COL_P_STOP = "probability_stop_loss_hit";
    public static final String COL_P_SAFE_BUY = "probability_safe_buy";
    public static final String COL_P_AGGRESSIVE_BUY = "probability_aggressive_buy";
    public static final String COL_P_HIDDEN_ACC = "probability_hidden_accumulation";
    public static final String COL_P_SHORT = "probability_short_candidate";
    public static final String COL_P_TRAP = "probability_avoid_trap";
    public static final String COL_P_FINAL_EDGE = "probability_final_edge";
    public static final String COL_P_GRADE = "probability_grade";
    public static final String COL_P_DECISION = "probability_decision";
    public static final String COL_P_REASON = "probability_reason";

    // Rank columns
    public static final String COL_SAFE_BUY_RANK = "v3_safest_buy_rank";
    public static final String COL_AGGRESSIVE_BUY_RANK = "v3_aggressive_buy_rank";
    public static final String COL_HIDDEN_ACC_RANK = "v3_hidden_accumulation_rank";
    public static final String COL_UPSIDE20_RANK = "v3_likely_20pct_upside_rank";
    public static final String COL_UPSIDE30_RANK = "v3_likely_30pct_upside_rank";
    public static final String COL_SHORT_RANK = "v3_short_candidate_rank";
    public static final String COL_TRAP_RANK = "v3_avoid_trap_rank";
    public static final String COL_PRIMARY_BUCKET = "v3_primary_bucket";
    public static final String COL_PRIMARY_SCORE = "v3_primary_score";

    private final int topN;

    public RankingEngineV3() {
        this(DEFAULT_TOP_N);
    }

    public RankingEngineV3(int topN) {
        this.topN = topN <= 0 ? DEFAULT_TOP_N : topN;
    }

    public ProbabilityRankingResult applyProbabilityRankings(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            log.warn("RANKING_V3_SKIP rows empty");
            return ProbabilityRankingResult.empty();
        }

        List<RowView> views = rows.stream()
                .filter(Objects::nonNull)
                .map(RowView::new)
                .collect(Collectors.toList());

        for (RowView v : views) {
            ProbabilityScore p = score(v);
            writeProbability(v.row, p);
        }

        List<RankedSymbol> safestBuy = assignRanks(views, COL_P_SAFE_BUY, COL_SAFE_BUY_RANK, this::eligibleSafeBuy);
        List<RankedSymbol> aggressiveBuy = assignRanks(views, COL_P_AGGRESSIVE_BUY, COL_AGGRESSIVE_BUY_RANK, this::eligibleAggressiveBuy);
        List<RankedSymbol> hiddenAccumulation = assignRanks(views, COL_P_HIDDEN_ACC, COL_HIDDEN_ACC_RANK, this::eligibleHiddenAccumulation);
        List<RankedSymbol> upside20 = assignRanks(views, COL_P20, COL_UPSIDE20_RANK, this::eligibleUpside20);
        List<RankedSymbol> upside30 = assignRanks(views, COL_P30, COL_UPSIDE30_RANK, this::eligibleUpside30);
        List<RankedSymbol> shortCandidates = assignRanks(views, COL_P_SHORT, COL_SHORT_RANK, this::eligibleShortCandidate);
        List<RankedSymbol> avoidTraps = assignRanks(views, COL_P_TRAP, COL_TRAP_RANK, this::eligibleAvoidTrap);

        for (RowView v : views) {
            PrimaryBucket primary = primaryBucket(v);
            v.row.put(COL_PRIMARY_BUCKET, primary.bucket);
            v.row.put(COL_PRIMARY_SCORE, round2(primary.score));
        }

        logBucket("SAFEST_BUY", safestBuy);
        logBucket("AGGRESSIVE_BUY", aggressiveBuy);
        logBucket("HIDDEN_ACCUMULATION", hiddenAccumulation);
        logBucket("LIKELY_20PCT_UPSIDE", upside20);
        logBucket("LIKELY_30PCT_UPSIDE", upside30);
        logBucket("SHORT_CANDIDATE", shortCandidates);
        logBucket("AVOID_TRAP", avoidTraps);

        return ProbabilityRankingResult.builder()
                .safestBuy(safestBuy)
                .aggressiveBuy(aggressiveBuy)
                .hiddenAccumulation(hiddenAccumulation)
                .likely20PctUpside(upside20)
                .likely30PctUpside(upside30)
                .shortCandidates(shortCandidates)
                .avoidTrap(avoidTraps)
                .build();
    }

    private ProbabilityScore score(RowView v) {
        double bullish = bullishComposite(v);
        double bearish = bearishComposite(v);
        double trap = trapRisk(v);
        double quality = qualityComposite(v);
        double upside = upsideComposite(v);
        double downside = downsideComposite(v);
        double ml = v.modelProbability();

        double p10 = sigmoid(2.20 * bullish + 1.10 * quality + 0.80 * upside + 0.60 * ml - 1.30 * trap - 0.80 * bearish - 1.35);
        double p20 = sigmoid(1.85 * bullish + 1.00 * quality + 1.15 * upside + 0.55 * ml - 1.40 * trap - 0.90 * bearish - 1.75);
        double p30 = sigmoid(1.55 * bullish + 0.85 * quality + 1.45 * upside + 0.50 * ml - 1.55 * trap - 1.00 * bearish - 2.10);
        double pStop = sigmoid(1.80 * bearish + 1.75 * trap + 0.95 * downside - 1.15 * quality - 0.65 * bullish - 0.25);

        double pSafeBuy = clamp01(0.42 * p10 + 0.28 * p20 + 0.20 * quality + 0.10 * (1.0 - pStop));
        double pAggressiveBuy = clamp01(0.35 * p20 + 0.30 * p30 + 0.20 * momentumComposite(v) + 0.15 * volumeStrength(v));
        double pHiddenAccumulation = clamp01(0.45 * accumulationComposite(v) + 0.22 * quality + 0.18 * volumeStrength(v) + 0.15 * (1.0 - trap));
        double pShort = clamp01(0.45 * bearish + 0.25 * trap + 0.20 * pStop + 0.10 * weakRelativeStrength(v));
        double pAvoidTrap = clamp01(0.45 * trap + 0.22 * pStop + 0.18 * bearish + 0.15 * overextensionRisk(v));

        double finalEdge = clamp01((0.36 * p20 + 0.28 * p30 + 0.22 * quality + 0.14 * bullish) - (0.55 * pStop + 0.30 * trap));

        String decision = decision(v, pSafeBuy, pAggressiveBuy, pHiddenAccumulation, pShort, pAvoidTrap, finalEdge);
        String grade = grade(finalEdge, pStop, pAvoidTrap);
        String reason = reason(v, p10, p20, p30, pStop, pSafeBuy, pAggressiveBuy, pHiddenAccumulation, pShort, pAvoidTrap, finalEdge);

        return ProbabilityScore.builder()
                .probability10PctUpside(p10)
                .probability20PctUpside(p20)
                .probability30PctUpside(p30)
                .probabilityStopLossHit(pStop)
                .probabilitySafeBuy(pSafeBuy)
                .probabilityAggressiveBuy(pAggressiveBuy)
                .probabilityHiddenAccumulation(pHiddenAccumulation)
                .probabilityShortCandidate(pShort)
                .probabilityAvoidTrap(pAvoidTrap)
                .probabilityFinalEdge(finalEdge)
                .grade(grade)
                .decision(decision)
                .reason(reason)
                .build();
    }

    private void writeProbability(Map<String, Object> row, ProbabilityScore p) {
        row.put(COL_P10, pct(p.probability10PctUpside));
        row.put(COL_P20, pct(p.probability20PctUpside));
        row.put(COL_P30, pct(p.probability30PctUpside));
        row.put(COL_P_STOP, pct(p.probabilityStopLossHit));
        row.put(COL_P_SAFE_BUY, pct(p.probabilitySafeBuy));
        row.put(COL_P_AGGRESSIVE_BUY, pct(p.probabilityAggressiveBuy));
        row.put(COL_P_HIDDEN_ACC, pct(p.probabilityHiddenAccumulation));
        row.put(COL_P_SHORT, pct(p.probabilityShortCandidate));
        row.put(COL_P_TRAP, pct(p.probabilityAvoidTrap));
        row.put(COL_P_FINAL_EDGE, pct(p.probabilityFinalEdge));
        row.put(COL_P_GRADE, p.grade);
        row.put(COL_P_DECISION, p.decision);
        row.put(COL_P_REASON, p.reason);
    }

    private boolean eligibleSafeBuy(RowView v) {
        return v.getPct(COL_P_SAFE_BUY) >= 55
                && v.getPct(COL_P_STOP) <= 45
                && !isBearishStage(v)
                && !containsAny(v.finalAction(), "SELL", "SHORT", "AVOID");
    }

    private boolean eligibleAggressiveBuy(RowView v) {
        return v.getPct(COL_P_AGGRESSIVE_BUY) >= 55
                && v.getPct(COL_P_STOP) <= 55
                && !isBearishStage(v)
                && !containsAny(v.finalAction(), "SELL", "SHORT", "AVOID");
    }

    private boolean eligibleHiddenAccumulation(RowView v) {
        return v.getPct(COL_P_HIDDEN_ACC) >= 50
                && !isStage(v, "MARKDOWN")
                && (isStage(v, "ACCUMULATION") || containsAny(v.substage(), "BASE", "ABSORPTION", "PRE_BREAKOUT", "SPRING", "RANGE", "BOTTOMING"));
    }

    private boolean eligibleUpside20(RowView v) {
        return v.getPct(COL_P20) >= 50
                && v.getPct(COL_P_STOP) <= 55
                && !isBearishStage(v);
    }

    private boolean eligibleUpside30(RowView v) {
        return v.getPct(COL_P30) >= 45
                && v.getPct(COL_P_STOP) <= 60
                && !isBearishStage(v);
    }

    private boolean eligibleShortCandidate(RowView v) {
        return v.getPct(COL_P_SHORT) >= 50
                && (isBearishStage(v) || containsAny(v.finalAction(), "SELL", "SHORT", "AVOID"));
    }

    private boolean eligibleAvoidTrap(RowView v) {
        return v.getPct(COL_P_TRAP) >= 55
                || containsAny(v.finalAction(), "AVOID", "SELL", "SHORT")
                || isBearishStage(v);
    }

    private List<RankedSymbol> assignRanks(List<RowView> views,
                                           String scoreColumn,
                                           String rankColumn,
                                           Function<RowView, Boolean> eligibility) {
        for (RowView v : views) {
            v.row.put(rankColumn, "");
        }

        List<RowView> ranked = views.stream()
                .filter(v -> Boolean.TRUE.equals(eligibility.apply(v)))
                .sorted(Comparator
                        .comparingDouble((RowView v) -> v.getPct(scoreColumn)).reversed()
                        .thenComparing(RowView::symbol))
                .limit(topN)
                .collect(Collectors.toList());

        List<RankedSymbol> out = new ArrayList<>();
        int rank = 1;
        for (RowView v : ranked) {
            double score = v.getPct(scoreColumn);
            v.row.put(rankColumn, rank);
            out.add(RankedSymbol.builder()
                    .rank(rank)
                    .symbol(v.symbol())
                    .score(round2(score))
                    .stage(v.stage())
                    .substage(v.substage())
                    .childSubstage(v.childSubstage())
                    .finalAction(v.finalAction())
                    .reason(v.firstString(COL_P_REASON))
                    .build());
            rank++;
        }
        return out;
    }

    private PrimaryBucket primaryBucket(RowView v) {
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put("SAFEST_BUY", eligibleSafeBuy(v) ? v.getPct(COL_P_SAFE_BUY) : 0);
        scores.put("AGGRESSIVE_BUY", eligibleAggressiveBuy(v) ? v.getPct(COL_P_AGGRESSIVE_BUY) : 0);
        scores.put("HIDDEN_ACCUMULATION", eligibleHiddenAccumulation(v) ? v.getPct(COL_P_HIDDEN_ACC) : 0);
        scores.put("LIKELY_20PCT_UPSIDE", eligibleUpside20(v) ? v.getPct(COL_P20) : 0);
        scores.put("LIKELY_30PCT_UPSIDE", eligibleUpside30(v) ? v.getPct(COL_P30) : 0);
        scores.put("SHORT_CANDIDATE", eligibleShortCandidate(v) ? v.getPct(COL_P_SHORT) : 0);
        scores.put("AVOID_TRAP", eligibleAvoidTrap(v) ? v.getPct(COL_P_TRAP) : 0);

        return scores.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(e -> new PrimaryBucket(e.getKey(), e.getValue()))
                .orElse(new PrimaryBucket("UNRANKED", 0));
    }

    private void logBucket(String bucket, List<RankedSymbol> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            log.info("RANKING_V3_TOP bucket={} empty", bucket);
            return;
        }
        for (RankedSymbol r : symbols) {
            log.info("RANKING_V3_TOP bucket={} rank={} symbol={} score={} stage={} substage={} child={} finalAction={}",
                    bucket, r.rank, r.symbol, r.score, r.stage, r.substage, r.childSubstage, r.finalAction);
        }
    }

    // ----------------------------- Composite scoring -----------------------------

    private double bullishComposite(RowView v) {
        double x = 0;
        x += 0.25 * bullishStageQuality(v);
        x += 0.18 * bullishSubstageQuality(v);
        x += 0.16 * bullishChildQuality(v);
        x += 0.12 * v.finalActionBuyStrength();
        x += 0.10 * v.relativeStrengthScore();
        x += 0.08 * volumeStrength(v);
        x += 0.06 * v.riskRewardScore();
        x += 0.05 * v.epsGrowthScore();
        return clamp01(x);
    }

    private double bearishComposite(RowView v) {
        double x = 0;
        x += 0.28 * bearishStageQuality(v);
        x += 0.18 * bearishSubstageQuality(v);
        x += 0.18 * bearishChildQuality(v);
        x += 0.12 * v.finalActionSellShortStrength();
        x += 0.09 * weakRelativeStrength(v);
        x += 0.08 * technicalWeakness(v);
        x += 0.07 * negativeSentiment(v);
        return clamp01(x);
    }

    private double qualityComposite(RowView v) {
        double x = 0;
        x += 0.20 * v.epsGrowthScore();
        x += 0.18 * v.relativeStrengthScore();
        x += 0.15 * v.sentimentScore01();
        x += 0.15 * v.riskRewardScore();
        x += 0.10 * lowBetaSafety(v.beta());
        x += 0.10 * v.finalActionBuyStrength();
        x += 0.12 * (1.0 - trapRisk(v));
        return clamp01(x);
    }

    private double momentumComposite(RowView v) {
        double x = 0;
        x += 0.23 * momentumSubstageQuality(v);
        x += 0.18 * bullishChildQuality(v);
        x += 0.18 * v.relativeStrengthScore();
        x += 0.15 * volumeStrength(v);
        x += 0.12 * betaAggression(v.beta());
        x += 0.09 * v.finalActionBuyStrength();
        x += 0.05 * v.sentimentScore01();
        return clamp01(x);
    }

    private double accumulationComposite(RowView v) {
        double x = 0;
        x += 0.30 * accumulationStageQuality(v);
        x += 0.22 * accumulationSubstageQuality(v);
        x += 0.18 * accumulationChildQuality(v);
        x += 0.12 * volumeStrength(v);
        x += 0.08 * v.epsGrowthScore();
        x += 0.06 * v.sentimentScore01();
        x += 0.04 * lowBetaSafety(v.beta());
        return clamp01(x);
    }

    private double upsideComposite(RowView v) {
        double explicit = explicitUpsideScore(v);
        double rr = v.riskRewardScore();
        double gain = historicalGainScore(v);
        return clamp01(0.38 * explicit + 0.28 * rr + 0.20 * gain + 0.14 * momentumComposite(v));
    }

    private double downsideComposite(RowView v) {
        double risk = 0;
        risk += 0.35 * technicalWeakness(v);
        risk += 0.25 * trapRisk(v);
        risk += 0.15 * bearishComposite(v);
        risk += 0.10 * highBetaRisk(v.beta());
        risk += 0.10 * (1.0 - v.riskRewardScore());
        risk += 0.05 * negativeSentiment(v);
        return clamp01(risk);
    }

    // ----------------------------- Signal helpers -----------------------------

    private double bullishStageQuality(RowView v) {
        if (isStage(v, "MARKUP")) return scoreFromRaw(v.stageScore(), 50, 6, 0.85);
        if (isStage(v, "ACCUMULATION")) return scoreFromRaw(v.stageScore(), 30, 4, 0.75);
        return 0;
    }

    private double accumulationStageQuality(RowView v) {
        if (isStage(v, "ACCUMULATION")) return scoreFromRaw(v.stageScore(), 30, 4, 0.9);
        if (isStage(v, "MARKUP") && containsAny(v.substage(), "PRE_BREAKOUT", "RETEST", "PULLBACK", "EARLY_TREND")) return 0.35;
        return 0;
    }

    private double bearishStageQuality(RowView v) {
        if (isStage(v, "MARKDOWN")) return scoreFromRaw(v.stageScore(), 30, 4, 0.95);
        if (isStage(v, "DISTRIBUTION")) return scoreFromRaw(v.stageScore(), 30, 4, 0.90);
        return 0;
    }

    private double bullishSubstageQuality(RowView v) {
        String s = v.substage();
        if (containsAny(s, "BREAKOUT", "RETEST", "EARLY_TREND", "PULLBACK", "HIGHER_LOW", "STRONG_TREND", "TREND_CONTINUATION", "BASE", "ABSORPTION", "PRE_BREAKOUT", "BOTTOMING")) {
            return scoreFromRaw(v.substageScore(), 35, 3, 0.85);
        }
        return 0;
    }

    private double momentumSubstageQuality(RowView v) {
        String s = v.substage();
        if (containsAny(s, "BREAKOUT", "EARLY_TREND", "STRONG_TREND", "MOMENTUM_SURGE", "ACCELERATION", "RANGE_EXPANSION", "HIGHER_HIGH")) {
            return scoreFromRaw(v.substageScore(), 35, 3, 0.9);
        }
        if (containsAny(s, "PULLBACK", "RETEST", "HIGHER_LOW")) return 0.55;
        return 0;
    }

    private double accumulationSubstageQuality(RowView v) {
        String s = v.substage();
        if (containsAny(s, "BASE", "ABSORPTION", "PRE_BREAKOUT", "SPRING", "TIGHT", "LOW_VOL", "RANGE", "CONSOLIDATION", "BOTTOMING")) {
            return scoreFromRaw(v.substageScore(), 30, 3, 0.9);
        }
        return 0;
    }

    private double bearishSubstageQuality(RowView v) {
        String s = v.substage();
        if (containsAny(s, "BREAKDOWN", "SUPPORT_FAILURE", "WEAK_BOUNCE", "LOWER_LOW", "LOWER_HIGH", "STRONG_DOWNTREND", "MOMENTUM_DROP", "DEAD_CAT", "FAILED_BREAKOUT")) {
            return scoreFromRaw(v.substageScore(), 30, 3, 0.9);
        }
        if (containsAny(s, "DISTRIBUTION", "TOP", "FALSE_BREAKOUT", "MOMENTUM_LOSS", "EXHAUSTION", "OVEREXTENSION", "CLIMAX")) return 0.75;
        return 0;
    }

    private double bullishChildQuality(RowView v) {
        String c = v.childSubstage();
        if (containsAny(c, "CLEAN_BREAKOUT", "SUCCESSFUL_RETEST", "EMA_STACK", "RSI_50_HOLD", "ORDERLY_CONTINUATION",
                "PULLBACK_TO_TREND", "SHALLOW_PULLBACK", "CONFIRMED_HIGHER_LOW", "HL_LOW_RISK_ENTRY",
                "TREND_ACCELERATION", "VOLUME_CONFIRMED_TREND", "SUPPORT_DEFENSE", "TIGHT_BASE", "ABSORPTION",
                "RANGE_COMPRESSION", "VWAP_RECLAIM", "SUPPORT_HOLD")) {
            return scoreFromRaw(v.childScore(), 12, 2, 0.9);
        }
        return 0;
    }

    private double accumulationChildQuality(RowView v) {
        String c = v.childSubstage();
        if (containsAny(c, "TIGHT_BASE", "SUPPORT_DEFENSE", "ABSORPTION", "SPRING", "LOW_VOLUME_RETEST", "PRE_BREAKOUT", "VOLUME_ACCUMULATION", "RANGE_COMPRESSION")) {
            return scoreFromRaw(v.childScore(), 12, 2, 0.9);
        }
        return 0;
    }

    private double bearishChildQuality(RowView v) {
        String c = v.childSubstage();
        if (containsAny(c, "FAILED", "LOST_VWAP", "BREAKDOWN", "SUPPORT_FAILURE", "SUPPORT_LOST", "LOWER_LOW", "LOWER_HIGH",
                "DISTRIBUTION", "EXHAUSTION_REVERSAL", "MOMENTUM_EXHAUSTION", "DEAD_CAT", "WEAK_BOUNCE", "SELLING", "REJECTION")) {
            return scoreFromRaw(v.childScore(), 12, 2, 0.9);
        }
        return 0;
    }

    private double trapRisk(RowView v) {
        double x = 0;
        x += isStage(v, "DISTRIBUTION") ? 0.35 : 0;
        x += isStage(v, "MARKDOWN") ? 0.30 : 0;
        x += containsAny(v.substage(), "EXHAUSTION", "OVEREXTENSION", "CLIMAX", "FALSE_BREAKOUT", "FAILED_BREAKOUT", "TOP", "MOMENTUM_LOSS", "DEAD_CAT", "WEAK_BOUNCE", "SUPPORT_FAILURE") ? 0.25 : 0;
        x += containsAny(v.childSubstage(), "OVEREXTENDED", "EXHAUSTION", "DISTRIBUTION", "FAILED", "LOST_VWAP", "BLOWOFF", "FINAL_PUSH", "RSI_OVEREXTENSION", "ADX_OVEREXTENSION", "DEAD_CAT", "WEAK_BOUNCE", "REJECTION") ? 0.25 : 0;
        x += technicalWeakness(v) * 0.15;
        x += v.rsi() >= 78 ? 0.15 : 0;
        x += v.beta() >= 2.2 ? 0.05 : 0;
        return clamp01(x);
    }

    private double overextensionRisk(RowView v) {
        double x = 0;
        x += v.rsi() >= 80 ? 0.35 : v.rsi() >= 72 ? 0.18 : 0;
        x += containsAny(v.substage(), "OVEREXTENSION", "EXHAUSTION", "CLIMAX", "ACCELERATION") ? 0.30 : 0;
        x += containsAny(v.childSubstage(), "OVEREXTENDED", "BLOWOFF", "FINAL_PUSH", "RSI_OVEREXTENSION", "ADX_OVEREXTENSION", "EXHAUSTION") ? 0.30 : 0;
        x += v.adx() >= 65 ? 0.12 : 0;
        return clamp01(x);
    }

    private double technicalWeakness(RowView v) {
        double x = 0;
        double price = v.currentPrice();
        if (price > 0 && v.vwap() > 0 && price < v.vwap()) x += 0.35;
        if (price > 0 && v.ema21() > 0 && price < v.ema21()) x += 0.25;
        if (price > 0 && v.ema50() > 0 && price < v.ema50()) x += 0.25;
        if (v.rsi() > 0 && v.rsi() < 42) x += 0.15;
        return clamp01(x);
    }

    private double volumeStrength(RowView v) {
        double surge = v.volumeSurgeScore();
        if (surge <= 0) return 0.25;
        if (surge > 0.90 && trapRisk(v) > 0.65) return 0.35;
        return clamp01(surge);
    }

    private double lowBetaSafety(double beta) {
        if (beta <= 0) return 0.5;
        if (beta <= 0.8) return 0.85;
        if (beta <= 1.25) return 1.0;
        if (beta <= 1.60) return 0.75;
        if (beta <= 2.00) return 0.45;
        return 0.20;
    }

    private double betaAggression(double beta) {
        if (beta <= 0) return 0.25;
        if (beta < 1.0) return 0.20;
        if (beta <= 1.6) return 0.65;
        if (beta <= 2.3) return 1.0;
        return 0.75;
    }

    private double highBetaRisk(double beta) {
        if (beta <= 0) return 0.3;
        if (beta <= 1.25) return 0.2;
        if (beta <= 1.75) return 0.45;
        if (beta <= 2.25) return 0.75;
        return 1.0;
    }

    private double weakRelativeStrength(RowView v) {
        return 1.0 - clamp01(v.relativeStrengthScore());
    }

    private double negativeSentiment(RowView v) {
        String label = v.sentimentLabel();
        if (containsAny(label, "NEGATIVE", "BEARISH")) return 1.0;
        if (containsAny(label, "NEUTRAL")) return 0.35;
        return 1.0 - clamp01(v.sentimentScore01());
    }

    private double explicitUpsideScore(RowView v) {
        double gain = v.firstDouble("expected_return_pct", "Expected Return %", "upside_pct", "Upside %", "analyst_upside_pct", "90D Gain (%)", "90d_gain");
        if (gain <= 0) return 0;
        return clamp01(gain / 30.0);
    }

    private double historicalGainScore(RowView v) {
        double gain = v.firstDouble("90D Gain (%)", "90D Gain", "90d_gain", "ninety_day_gain_pct");
        if (gain <= 0) return 0;
        return clamp01(gain / 25.0);
    }

    private String decision(RowView v, double safe, double aggressive, double hidden, double shortP, double trap, double edge) {
        if (trap >= 0.72 || containsAny(v.finalAction(), "AVOID")) return "AVOID_TRAP";
        if (shortP >= 0.62 || containsAny(v.finalAction(), "SHORT")) return "SHORT_CANDIDATE";
        if (safe >= 0.70 && edge >= 0.55) return "SAFEST_BUY";
        if (hidden >= 0.65) return "HIDDEN_ACCUMULATION";
        if (aggressive >= 0.68 && edge >= 0.45) return "AGGRESSIVE_BUY";
        if (edge >= 0.50) return "WATCH_BUY";
        return "WATCH";
    }

    private String grade(double edge, double pStop, double pTrap) {
        if (pTrap >= 0.72 || pStop >= 0.70) return "D";
        if (edge >= 0.72 && pStop <= 0.35) return "A+";
        if (edge >= 0.62 && pStop <= 0.45) return "A";
        if (edge >= 0.52) return "B";
        if (edge >= 0.40) return "C";
        return "D";
    }

    private String reason(RowView v,
                          double p10, double p20, double p30, double pStop,
                          double pSafe, double pAggressive, double pHidden, double pShort, double pTrap, double edge) {
        return String.format(Locale.US,
                "stage=%s substage=%s child=%s finalAction=%s p10=%.1f p20=%.1f p30=%.1f stop=%.1f safe=%.1f aggressive=%.1f hidden=%.1f short=%.1f trap=%.1f edge=%.1f eps=%.2f rs=%.2f sent=%.2f rr=%.2f beta=%.2f vol=%.2f",
                v.stage(), v.substage(), v.childSubstage(), v.finalAction(),
                pct(p10), pct(p20), pct(p30), pct(pStop), pct(pSafe), pct(pAggressive), pct(pHidden), pct(pShort), pct(pTrap), pct(edge),
                v.epsGrowthScore(), v.relativeStrengthScore(), v.sentimentScore01(), v.riskRewardScore(), v.beta(), v.volumeSurgeScore());
    }

    private double scoreFromRaw(double raw, double maxRaw, double minUseful, double fallbackWhenPresent) {
        if (raw <= 0) return 0;
        if (raw < minUseful) return Math.min(fallbackWhenPresent, raw / minUseful * fallbackWhenPresent);
        return clamp01(raw / maxRaw);
    }

    // ----------------------------- DTOs -----------------------------

    @Value
    @Builder
    public static class ProbabilityRankingResult {
        List<RankedSymbol> safestBuy;
        List<RankedSymbol> aggressiveBuy;
        List<RankedSymbol> hiddenAccumulation;
        List<RankedSymbol> likely20PctUpside;
        List<RankedSymbol> likely30PctUpside;
        List<RankedSymbol> shortCandidates;
        List<RankedSymbol> avoidTrap;

        public static ProbabilityRankingResult empty() {
            return ProbabilityRankingResult.builder()
                    .safestBuy(List.of())
                    .aggressiveBuy(List.of())
                    .hiddenAccumulation(List.of())
                    .likely20PctUpside(List.of())
                    .likely30PctUpside(List.of())
                    .shortCandidates(List.of())
                    .avoidTrap(List.of())
                    .build();
        }
    }

    @Value
    @Builder
    public static class RankedSymbol {
        int rank;
        String symbol;
        double score;
        String stage;
        String substage;
        String childSubstage;
        String finalAction;
        String reason;
    }

    @Value
    @Builder
    private static class ProbabilityScore {
        double probability10PctUpside;
        double probability20PctUpside;
        double probability30PctUpside;
        double probabilityStopLossHit;
        double probabilitySafeBuy;
        double probabilityAggressiveBuy;
        double probabilityHiddenAccumulation;
        double probabilityShortCandidate;
        double probabilityAvoidTrap;
        double probabilityFinalEdge;
        String grade;
        String decision;
        String reason;
    }

    @Value
    private static class PrimaryBucket {
        String bucket;
        double score;
    }

    // ----------------------------- Row adapter -----------------------------

    private static class RowView {
        private final Map<String, Object> row;

        private RowView(Map<String, Object> row) {
            this.row = row;
        }

        private String symbol() {
            return firstString("symbol", "Symbol", "ticker", "Ticker");
        }

        private String stage() {
            return firstString("Market Stage", "market_stage", "stage", "Stage", "winning_stage");
        }

        private String substage() {
            return firstString("Market Substage", "market_substage", "substage", "Substage", "winning_substage");
        }

        private String childSubstage() {
            return firstString("Child Substage", "child_substage", "childSubstage", "winning_child_substage", "child", "best_child_substage");
        }

        private String finalAction() {
            return firstString("Final Action", "final_action", "FinalAction", "Rule Recommendation", "rule_recommendation", "recommendation");
        }

        private String sentimentLabel() {
            return firstString("Sentiment Label", "sentiment_label", "news_sentiment_label", "News Sentiment Label");
        }

        private double stageScore() {
            return firstDouble("winning_stage_score", "stage_score", "Stage Score", "market_stage_score");
        }

        private double substageScore() {
            return firstDouble("winning_substage_score", "substage_score", "Substage Score", "market_substage_score");
        }

        private double childScore() {
            return firstDouble("winning_child_score", "child_substage_score", "Child Score", "child_score", "best_child_confidence");
        }

        private double modelProbability() {
            double p = firstDouble("model_probability", "final_probability", "ml_probability");
            if (p > 1.0) p = p / 100.0;
            return clamp01(p);
        }

        private double currentPrice() {
            return firstDouble("currentPrice", "current_price", "Current Price", "price", "Price", "Close", "close");
        }

        private double ema21() {
            return firstDouble("ema21", "EMA21", "EMA 21", "ema_21");
        }

        private double ema50() {
            return firstDouble("ema50", "EMA50", "EMA 50", "ema_50");
        }

        private double vwap() {
            return firstDouble("vwap", "VWAP");
        }

        private double rsi() {
            return firstDouble("rsi", "RSI");
        }

        private double adx() {
            return firstDouble("adx", "ADX");
        }

        private double beta() {
            return firstDouble("Beta", "beta", "av_beta");
        }

        private double epsGrowthScore() {
            double direct = firstDouble("eps_quality_score", "EPS Quality Score", "FUNDAMENTAL_BOOST", "fundamental_boost");
            if (direct > 0) return normalizeMaybePct(direct);

            double qoq = firstDouble("eps_growth_qoq", "EPS Growth QoQ", "eps_growth_qoq_pct");
            double inc2 = asBoolish(first("eps_increase_2q", "EPS Increase 2Q"));
            double inc3 = asBoolish(first("eps_increase_3q", "EPS Increase 3Q"));
            double inc4 = asBoolish(first("eps_increase_4q", "EPS Increase 4Q"));
            double surprise = firstDouble("eps_surprise_pct_last", "EPS Surprise % Last", "eps_surprise_pct");

            double score = 0;
            score += clamp01(qoq / 25.0) * 0.35;
            score += ((inc2 + inc3 + inc4) / 3.0) * 0.35;
            score += clamp01(Math.max(0, surprise) / 20.0) * 0.20;
            score += asBoolish(first("eps_available", "EPS_AVAILABLE", "epsAvailable")) * 0.10;
            return clamp01(score);
        }

        private double relativeStrengthScore() {
            double direct = firstDouble("Relative Strength vs SPY", "relative_strength_vs_spy", "rs_vs_spy", "relativeStrengthVsSpy");
            if (direct == 0) return 0.5;
            if (direct > 3) return clamp01(direct / 100.0);
            return clamp01((direct - 0.75) / 0.65);
        }

        private double sentimentScore01() {
            double direct = firstDouble("News Sentiment Score", "news_sentiment_score", "sentiment_score", "Sentiment Score");
            if (direct != 0) {
                if (direct >= -1.0 && direct <= 1.0) return clamp01((direct + 1.0) / 2.0);
                return normalizeMaybePct(direct);
            }
            String label = sentimentLabel();
            if (containsAny(label, "POSITIVE", "BULLISH")) return 0.75;
            if (containsAny(label, "NEGATIVE", "BEARISH")) return 0.20;
            return 0.50;
        }

        private double riskRewardScore() {
            double direct = firstDouble("Best_Risk_Reward", "best_risk_reward", "risk_reward", "Risk Reward", "rr_ratio", "min_rr_ratio", "agent_risk_reward");
            if (direct <= 0) return 0.35;
            return clamp01(direct / 3.0);
        }

        private double volumeSurgeScore() {
            double direct = firstDouble("volume_surge_ratio", "Volume Surge Ratio", "volume_surge", "Volume Surge", "relative_volume", "Relative Volume");
            if (direct <= 0) return 0.25;
            if (direct <= 5.0) return clamp01(direct / 2.5);
            return normalizeMaybePct(direct);
        }

        private double finalActionBuyStrength() {
            String a = finalAction();
            if (containsAny(a, "STRONG_BUY", "STRONG BUY")) return 1.0;
            if (containsAny(a, "BUY")) return 0.75;
            if (containsAny(a, "WATCH")) return 0.35;
            return 0.0;
        }

        private double finalActionSellShortStrength() {
            String a = finalAction();
            if (containsAny(a, "STRONG_SHORT", "STRONG SHORT", "SHORT")) return 1.0;
            if (containsAny(a, "SELL")) return 0.8;
            if (containsAny(a, "AVOID")) return 0.55;
            return 0.0;
        }

        private double getPct(String key) {
            return toDouble(row.get(key));
        }

        private Object first(String... keys) {
            for (String key : keys) {
                if (row.containsKey(key) && row.get(key) != null) return row.get(key);
            }
            return null;
        }

        private String firstString(String... keys) {
            Object v = first(keys);
            return v == null ? "" : String.valueOf(v).trim();
        }

        private double firstDouble(String... keys) {
            return toDouble(first(keys));
        }
    }

    // ----------------------------- Static helpers -----------------------------

    private static boolean isBearishStage(RowView v) {
        return isStage(v, "DISTRIBUTION", "MARKDOWN");
    }

    private static boolean isStage(RowView v, String... stages) {
        String actual = normalize(v.stage());
        for (String s : stages) {
            if (actual.equals(normalize(s))) return true;
        }
        return false;
    }

    private static boolean containsAny(String value, String... tokens) {
        String v = normalize(value);
        for (String t : tokens) {
            if (v.contains(normalize(t))) return true;
        }
        return false;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.US).replace('-', '_').replace(' ', '_');
    }

    private static double normalizeMaybePct(double x) {
        if (Double.isNaN(x) || Double.isInfinite(x)) return 0;
        if (x <= 1.0 && x >= -1.0) return clamp01(x);
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
        if (s.isEmpty() || s.equalsIgnoreCase("null") || s.equalsIgnoreCase("nan")) return 0;
        try {
            s = s.replace("%", "").replace(",", "");
            return Double.parseDouble(s);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }

    private static double clamp01(double x) {
        if (Double.isNaN(x) || Double.isInfinite(x)) return 0;
        return Math.max(0, Math.min(1, x));
    }

    private static double pct(double x) {
        return round2(clamp01(x) * 100.0);
    }

    private static double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
