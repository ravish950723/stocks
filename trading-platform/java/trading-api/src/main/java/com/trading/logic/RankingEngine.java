package com.trading.logic;

import lombok.Builder;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Drop-in RankingEngine replacement.
 *
 * Compatible with existing PipelineService usage:
 *      RankingEngine.RankingResult rankingResult = rankingEngine.rank(rows);
 *      rankingResult.getRanked()
 *      RankedSymbol#getSymbol()
 *      RankedSymbol#getRankScore()
 *      RankedSymbol#getFinalScore()
 *
 * Also adds V2 category columns directly into each row before Excel export:
 *      - ranking_v2_safest_buy_score / rank
 *      - ranking_v2_aggressive_buy_score / rank
 *      - ranking_v2_short_score / rank
 *      - ranking_v2_hidden_accumulation_score / rank
 *      - ranking_v2_likely_30pct_upside_score / rank
 *      - ranking_v2_avoid_trap_score / rank
 *      - ranking_v2_primary_bucket / primary_score / reason
 */
@Slf4j
@Component
public class RankingEngine {

    public static final int DEFAULT_TOP_N = 10;

    public static final String COL_SAFEST_BUY_SCORE = "ranking_v2_safest_buy_score";
    public static final String COL_SAFEST_BUY_RANK = "ranking_v2_safest_buy_rank";
    public static final String COL_AGGRESSIVE_BUY_SCORE = "ranking_v2_aggressive_buy_score";
    public static final String COL_AGGRESSIVE_BUY_RANK = "ranking_v2_aggressive_buy_rank";
    public static final String COL_SHORT_SCORE = "ranking_v2_short_score";
    public static final String COL_SHORT_RANK = "ranking_v2_short_rank";
    public static final String COL_HIDDEN_ACCUMULATION_SCORE = "ranking_v2_hidden_accumulation_score";
    public static final String COL_HIDDEN_ACCUMULATION_RANK = "ranking_v2_hidden_accumulation_rank";
    public static final String COL_UPSIDE_30_SCORE = "ranking_v2_likely_30pct_upside_score";
    public static final String COL_UPSIDE_30_RANK = "ranking_v2_likely_30pct_upside_rank";
    public static final String COL_AVOID_TRAP_SCORE = "ranking_v2_avoid_trap_score";
    public static final String COL_AVOID_TRAP_RANK = "ranking_v2_avoid_trap_rank";
    public static final String COL_PRIMARY_BUCKET = "ranking_v2_primary_bucket";
    public static final String COL_PRIMARY_SCORE = "ranking_v2_primary_score";
    public static final String COL_RANKING_REASON = "ranking_v2_reason";

    private final int topN;

    public RankingEngine() {
        this(DEFAULT_TOP_N);
    }

    public RankingEngine(int topN) {
        this.topN = topN <= 0 ? DEFAULT_TOP_N : topN;
    }

    /**
     * Existing PipelineService-compatible method.
     * Mutates rows with V2 ranking columns, then returns the legacy-style ranking result.
     */
    public RankingResult rank(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            log.warn("RANKING_SKIP rows empty");
            return RankingResult.builder()
                    .ranked(Collections.emptyList())
                    .build();
        }

        applyRankings(rows);

        List<RankedSymbol> ranked = rows.stream()
                .filter(Objects::nonNull)
                .map(row -> {
                    RowView v = new RowView(row);
                    double finalScore = finalScore(v);
                    double rankScore = rankScore(v);

                    row.put("ranking_score", round2(rankScore));
                    row.put("rank_score", round2(rankScore));
                    row.put("final_rank_score", round2(rankScore));

                    return RankedSymbol.builder()
                            .symbol(v.symbol())
                            .rankScore(round2(rankScore))
                            .finalScore(round2(finalScore))
                            .finalAction(v.finalAction())
                            .stage(v.stage())
                            .substage(v.substage())
                            .childSubstage(v.childSubstage())
                            .primaryBucket(String.valueOf(row.getOrDefault(COL_PRIMARY_BUCKET, "UNRANKED")))
                            .build();
                })
                .filter(r -> r.getSymbol() != null && !r.getSymbol().isBlank())
                .sorted(Comparator.comparingDouble(RankedSymbol::getRankScore).reversed()
                        .thenComparing(RankedSymbol::getSymbol))
                .collect(Collectors.toList());

        log.info("RANKING_DONE total={} topSymbol={}",
                ranked.size(),
                ranked.isEmpty() ? "NONE" : ranked.get(0).getSymbol());

        return RankingResult.builder()
                .ranked(ranked)
                .build();
    }

    /**
     * V2 category ranking. Safe to call independently, but rank(rows) already calls it.
     */
    public void applyRankings(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            log.warn("RANKING_V2_SKIP rows empty");
            return;
        }

        List<RowView> views = rows.stream()
                .filter(Objects::nonNull)
                .map(RowView::new)
                .collect(Collectors.toList());

        for (RowView v : views) {
            v.row.put(COL_SAFEST_BUY_SCORE, round2(scoreSafestBuy(v)));
            v.row.put(COL_AGGRESSIVE_BUY_SCORE, round2(scoreAggressiveBuy(v)));
            v.row.put(COL_SHORT_SCORE, round2(scoreShortCandidate(v)));
            v.row.put(COL_HIDDEN_ACCUMULATION_SCORE, round2(scoreHiddenAccumulation(v)));
            v.row.put(COL_UPSIDE_30_SCORE, round2(scoreLikely30PctUpside(v)));
            v.row.put(COL_AVOID_TRAP_SCORE, round2(scoreAvoidTrap(v)));
        }

        assignRanks(views, COL_SAFEST_BUY_SCORE, COL_SAFEST_BUY_RANK, this::eligibleSafestBuy);
        assignRanks(views, COL_AGGRESSIVE_BUY_SCORE, COL_AGGRESSIVE_BUY_RANK, this::eligibleAggressiveBuy);
        assignRanks(views, COL_SHORT_SCORE, COL_SHORT_RANK, this::eligibleShortCandidate);
        assignRanks(views, COL_HIDDEN_ACCUMULATION_SCORE, COL_HIDDEN_ACCUMULATION_RANK, this::eligibleHiddenAccumulation);
        assignRanks(views, COL_UPSIDE_30_SCORE, COL_UPSIDE_30_RANK, this::eligibleLikely30PctUpside);
        assignRanks(views, COL_AVOID_TRAP_SCORE, COL_AVOID_TRAP_RANK, this::eligibleAvoidTrap);

        for (RowView v : views) {
            PrimaryBucket primary = primaryBucket(v);
            v.row.put(COL_PRIMARY_BUCKET, primary.bucket);
            v.row.put(COL_PRIMARY_SCORE, round2(primary.score));
            v.row.put(COL_RANKING_REASON, buildReason(v, primary));
        }

        logTop(views, "SAFEST_BUY", COL_SAFEST_BUY_SCORE, COL_SAFEST_BUY_RANK);
        logTop(views, "AGGRESSIVE_BUY", COL_AGGRESSIVE_BUY_SCORE, COL_AGGRESSIVE_BUY_RANK);
        logTop(views, "SHORT_CANDIDATE", COL_SHORT_SCORE, COL_SHORT_RANK);
        logTop(views, "HIDDEN_ACCUMULATION", COL_HIDDEN_ACCUMULATION_SCORE, COL_HIDDEN_ACCUMULATION_RANK);
        logTop(views, "LIKELY_30PCT_UPSIDE", COL_UPSIDE_30_SCORE, COL_UPSIDE_30_RANK);
        logTop(views, "AVOID_TRAP", COL_AVOID_TRAP_SCORE, COL_AVOID_TRAP_RANK);
    }

    private double rankScore(RowView v) {
        double buyBest = Math.max(v.getDouble(COL_SAFEST_BUY_SCORE),
                Math.max(v.getDouble(COL_AGGRESSIVE_BUY_SCORE), v.getDouble(COL_UPSIDE_30_SCORE)));
        double hidden = v.getDouble(COL_HIDDEN_ACCUMULATION_SCORE);
        double trap = v.getDouble(COL_AVOID_TRAP_SCORE);
        double shortScore = v.getDouble(COL_SHORT_SCORE);

        double score = 0;
        score += 0.42 * buyBest;
        score += 0.18 * hidden;
        score += 0.16 * normalizeFinalProbability(v) * 100.0;
        score += 0.10 * clamp01(v.riskRewardScore()) * 100.0;
        score += 0.08 * clamp01(v.relativeStrengthScore()) * 100.0;
        score += 0.06 * clamp01(v.epsGrowthScore()) * 100.0;
        score -= 0.30 * trap;
        score -= 0.15 * shortScore;

        if (isBadLongContext(v)) {
            score -= 35.0;
        }
        if (containsAny(v.finalAction(), "STRONG_BUY", "STRONG BUY")) {
            score += 8.0;
        } else if (containsAny(v.finalAction(), "BUY")) {
            score += 5.0;
        } else if (containsAny(v.finalAction(), "WATCH")) {
            score += 1.5;
        } else if (containsAny(v.finalAction(), "AVOID", "SELL", "SHORT")) {
            score -= 18.0;
        }

        return clamp100(score);
    }

    private double finalScore(RowView v) {
        double explicit = v.firstDouble("Final Score", "final_score", "agent_analyst_score", "signal_score", "Signal Score");
        if (explicit > 0) return normalizeScore100(explicit);
        return rankScore(v);
    }

    private double normalizeFinalProbability(RowView v) {
        double p = v.firstDouble("final_probability", "Final Probability", "model_probability", "rule_probability");
        if (p <= 0) return v.finalActionBuyStrength();
        if (p > 1.0) return clamp01(p / 100.0);
        return clamp01(p);
    }

    private double scoreSafestBuy(RowView v) {
        double score = 0;
        score += 22 * bullishStageQuality(v);
        score += 16 * bullishSubstageQuality(v);
        score += 12 * bullishChildQuality(v);
        score += 12 * clamp01(v.finalActionBuyStrength());
        score += 10 * clamp01(v.epsGrowthScore());
        score += 10 * clamp01(v.relativeStrengthScore());
        score += 7 * clamp01(v.sentimentScore01());
        score += 8 * clamp01(v.riskRewardScore());
        score += 5 * lowBetaSafety(v.beta());
        score += 6 * healthyVolume(v);
        score -= 18 * trapRisk(v);
        score -= 12 * bearishContext(v);
        return clamp100(score);
    }

    private double scoreAggressiveBuy(RowView v) {
        double score = 0;
        score += 18 * bullishStageQuality(v);
        score += 14 * momentumSubstageQuality(v);
        score += 12 * bullishChildQuality(v);
        score += 12 * clamp01(v.finalActionBuyStrength());
        score += 10 * clamp01(v.epsGrowthScore());
        score += 12 * clamp01(v.relativeStrengthScore());
        score += 7 * clamp01(v.sentimentScore01());
        score += 7 * clamp01(v.riskRewardScore());
        score += 10 * clamp01(v.volumeSurgeScore());
        score += 6 * betaAggression(v.beta());
        score -= 16 * trapRisk(v);
        score -= 12 * markdownDistributionPenalty(v);
        return clamp100(score);
    }

    private double scoreShortCandidate(RowView v) {
        double score = 0;
        score += 22 * bearishStageQuality(v);
        score += 16 * bearishSubstageQuality(v);
        score += 14 * bearishChildQuality(v);
        score += 10 * v.finalActionSellShortStrength();
        score += 8 * weakRelativeStrength(v);
        score += 7 * negativeSentiment(v);
        score += 8 * weakEps(v);
        score += 8 * bearishVolume(v);
        score += 5 * betaAggression(v.beta());
        score += 8 * technicalWeakness(v);
        score -= 15 * bullishStageQuality(v);
        score -= 8 * clamp01(v.epsGrowthScore());
        return clamp100(score);
    }

    private double scoreHiddenAccumulation(RowView v) {
        double score = 0;
        score += 24 * accumulationStageQuality(v);
        score += 16 * accumulationSubstageQuality(v);
        score += 14 * accumulationChildQuality(v);
        score += 9 * clamp01(v.epsGrowthScore());
        score += 8 * neutralToPositiveSentiment(v);
        score += 10 * clamp01(v.volumeSurgeScore());
        score += 7 * clamp01(v.relativeStrengthScore());
        score += 7 * lowBetaSafety(v.beta());
        score += 5 * clamp01(v.riskRewardScore());
        score -= 16 * trapRisk(v);
        score -= 12 * markdownDistributionPenalty(v);
        return clamp100(score);
    }

    private double scoreLikely30PctUpside(RowView v) {
        double explicitUpside = explicitUpside30Score(v);
        double score = 0;
        score += 18 * bullishStageQuality(v);
        score += 12 * bullishSubstageQuality(v);
        score += 10 * bullishChildQuality(v);
        score += 10 * clamp01(v.finalActionBuyStrength());
        score += 12 * clamp01(v.epsGrowthScore());
        score += 10 * clamp01(v.relativeStrengthScore());
        score += 8 * clamp01(v.sentimentScore01());
        score += 10 * clamp01(v.riskRewardScore());
        score += 8 * clamp01(v.volumeSurgeScore());
        score += 7 * explicitUpside;
        score -= 16 * trapRisk(v);
        score -= 10 * bearishContext(v);
        return clamp100(score);
    }

    private double scoreAvoidTrap(RowView v) {
        double score = 0;
        score += 22 * trapStageQuality(v);
        score += 18 * trapSubstageQuality(v);
        score += 16 * trapChildQuality(v);
        score += 10 * bearishContext(v);
        score += 8 * weakRelativeStrength(v);
        score += 8 * negativeSentiment(v);
        score += 7 * bearishVolume(v);
        score += 6 * weakEps(v);
        score += 5 * betaAggression(v.beta());
        score += 8 * technicalWeakness(v);
        score -= 12 * accumulationStageQuality(v);
        return clamp100(score);
    }

    private boolean eligibleSafestBuy(RowView v) {
        return !isBadLongContext(v)
                && v.getDouble(COL_SAFEST_BUY_SCORE) >= 45
                && v.finalActionBuyStrength() >= 0.25;
    }

    private boolean eligibleAggressiveBuy(RowView v) {
        return !isBadLongContext(v)
                && v.getDouble(COL_AGGRESSIVE_BUY_SCORE) >= 45
                && (v.finalActionBuyStrength() >= 0.25 || bullishStageQuality(v) > 0.65);
    }

    private boolean eligibleShortCandidate(RowView v) {
        return v.getDouble(COL_SHORT_SCORE) >= 45
                && (bearishStageQuality(v) > 0.5 || v.finalActionSellShortStrength() > 0.25);
    }

    private boolean eligibleHiddenAccumulation(RowView v) {
        return !isBadLongContext(v)
                && v.getDouble(COL_HIDDEN_ACCUMULATION_SCORE) >= 40
                && (isStage(v, "ACCUMULATION") || containsAny(v.substage(), "BASE", "ABSORPTION", "PRE_BREAKOUT", "SPRING"));
    }

    private boolean eligibleLikely30PctUpside(RowView v) {
        return !isBadLongContext(v)
                && v.getDouble(COL_UPSIDE_30_SCORE) >= 45
                && (v.finalActionBuyStrength() >= 0.25 || explicitUpside30Score(v) > 0.5);
    }

    private boolean eligibleAvoidTrap(RowView v) {
        return v.getDouble(COL_AVOID_TRAP_SCORE) >= 45
                || isBadLongContext(v)
                || containsAny(v.finalAction(), "AVOID", "SELL", "SHORT");
    }

    private boolean isBadLongContext(RowView v) {
        return isStage(v, "DISTRIBUTION", "MARKDOWN")
                || trapRisk(v) >= 0.70
                || containsAny(v.finalAction(), "SELL", "SHORT", "AVOID");
    }

    private void assignRanks(List<RowView> views,
                             String scoreColumn,
                             String rankColumn,
                             Function<RowView, Boolean> eligibility) {
        for (RowView v : views) {
            v.row.put(rankColumn, "");
        }

        List<RowView> ranked = views.stream()
                .filter(v -> Boolean.TRUE.equals(eligibility.apply(v)))
                .sorted(Comparator
                        .comparingDouble((RowView v) -> v.getDouble(scoreColumn)).reversed()
                        .thenComparing(RowView::symbol))
                .limit(topN)
                .collect(Collectors.toList());

        int rank = 1;
        for (RowView v : ranked) {
            v.row.put(rankColumn, rank++);
        }
    }

    private PrimaryBucket primaryBucket(RowView v) {
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put("SAFEST_BUY", eligibleSafestBuy(v) ? v.getDouble(COL_SAFEST_BUY_SCORE) : 0);
        scores.put("AGGRESSIVE_BUY", eligibleAggressiveBuy(v) ? v.getDouble(COL_AGGRESSIVE_BUY_SCORE) : 0);
        scores.put("SHORT_CANDIDATE", eligibleShortCandidate(v) ? v.getDouble(COL_SHORT_SCORE) : 0);
        scores.put("HIDDEN_ACCUMULATION", eligibleHiddenAccumulation(v) ? v.getDouble(COL_HIDDEN_ACCUMULATION_SCORE) : 0);
        scores.put("LIKELY_30PCT_UPSIDE", eligibleLikely30PctUpside(v) ? v.getDouble(COL_UPSIDE_30_SCORE) : 0);
        scores.put("AVOID_TRAP", eligibleAvoidTrap(v) ? v.getDouble(COL_AVOID_TRAP_SCORE) : 0);

        return scores.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(e -> new PrimaryBucket(e.getKey(), e.getValue()))
                .orElse(new PrimaryBucket("UNRANKED", 0));
    }

    private String buildReason(RowView v, PrimaryBucket primary) {
        return String.format(Locale.US,
                "%s score=%.2f stage=%s substage=%s child=%s finalAction=%s eps=%.2f rs=%.2f sent=%.2f rr=%.2f beta=%.2f vol=%.2f trap=%.2f",
                primary.bucket,
                primary.score,
                v.stage(),
                v.substage(),
                v.childSubstage(),
                v.finalAction(),
                v.epsGrowthScore(),
                v.relativeStrengthScore(),
                v.sentimentScore01(),
                v.riskRewardScore(),
                v.beta(),
                v.volumeSurgeScore(),
                trapRisk(v));
    }

    private void logTop(List<RowView> views, String bucket, String scoreColumn, String rankColumn) {
        List<RowView> top = views.stream()
                .filter(v -> v.row.get(rankColumn) instanceof Number)
                .sorted(Comparator.comparingInt(v -> ((Number) v.row.get(rankColumn)).intValue()))
                .collect(Collectors.toList());

        if (top.isEmpty()) {
            log.info("RANKING_V2_TOP bucket={} empty", bucket);
            return;
        }

        log.info("RANKING_V2_TOP bucket={} count={}", bucket, top.size());
        for (RowView v : top) {
            log.info("RANKING_V2 bucket={} rank={} symbol={} score={} stage={} substage={} child={} finalAction={}",
                    bucket,
                    v.row.get(rankColumn),
                    v.symbol(),
                    v.row.get(scoreColumn),
                    v.stage(),
                    v.substage(),
                    v.childSubstage(),
                    v.finalAction());
        }
    }

    private double bullishStageQuality(RowView v) {
        if (isStage(v, "MARKUP")) return scoreFromRaw(v.stageScore(), 30, 8, 0.85);
        if (isStage(v, "ACCUMULATION")) return scoreFromRaw(v.stageScore(), 22, 5, 0.75);
        return 0;
    }

    private double accumulationStageQuality(RowView v) {
        if (isStage(v, "ACCUMULATION")) return scoreFromRaw(v.stageScore(), 25, 5, 0.9);
        if (isStage(v, "MARKUP") && containsAny(v.substage(), "PRE_BREAKOUT", "RETEST", "PULLBACK")) return 0.35;
        return 0;
    }

    private double bearishStageQuality(RowView v) {
        if (isStage(v, "MARKDOWN")) return scoreFromRaw(v.stageScore(), 25, 5, 0.95);
        if (isStage(v, "DISTRIBUTION")) return scoreFromRaw(v.stageScore(), 22, 5, 0.90);
        return 0;
    }

    private double trapStageQuality(RowView v) {
        if (isStage(v, "DISTRIBUTION")) return scoreFromRaw(v.stageScore(), 22, 5, 0.95);
        if (isStage(v, "MARKDOWN")) return scoreFromRaw(v.stageScore(), 25, 5, 0.90);
        if (isStage(v, "MARKUP") && containsAny(v.substage(), "EXHAUSTION", "CLIMAX", "OVEREXTENSION")) return 0.65;
        return 0;
    }

    private double bullishSubstageQuality(RowView v) {
        String s = v.substage();
        if (containsAny(s, "BREAKOUT", "RETEST", "EARLY_TREND", "PULLBACK", "HIGHER_LOW", "STRONG_TREND", "TREND_CONTINUATION", "BASE", "ABSORPTION", "PRE_BREAKOUT")) {
            return scoreFromRaw(v.substageScore(), 30, 4, 0.85);
        }
        return 0;
    }

    private double momentumSubstageQuality(RowView v) {
        String s = v.substage();
        if (containsAny(s, "BREAKOUT", "EARLY_TREND", "STRONG_TREND", "MOMENTUM_SURGE", "ACCELERATION", "RANGE_EXPANSION", "HIGHER_HIGH")) {
            return scoreFromRaw(v.substageScore(), 30, 4, 0.9);
        }
        if (containsAny(s, "PULLBACK", "RETEST", "HIGHER_LOW")) return 0.55;
        return 0;
    }

    private double accumulationSubstageQuality(RowView v) {
        String s = v.substage();
        if (containsAny(s, "BASE", "ABSORPTION", "PRE_BREAKOUT", "SPRING", "TIGHT", "LOW_VOL", "RANGE", "CONSOLIDATION")) {
            return scoreFromRaw(v.substageScore(), 25, 3, 0.9);
        }
        return 0;
    }

    private double bearishSubstageQuality(RowView v) {
        String s = v.substage();
        if (containsAny(s, "BREAKDOWN", "SUPPORT_FAILURE", "WEAK_BOUNCE", "LOWER_LOW", "LOWER_HIGH", "STRONG_DOWNTREND", "MOMENTUM_DROP", "DEAD_CAT", "FAILED_BREAKOUT")) {
            return scoreFromRaw(v.substageScore(), 25, 3, 0.9);
        }
        if (containsAny(s, "DISTRIBUTION", "TOP", "FALSE_BREAKOUT", "MOMENTUM_LOSS", "EXHAUSTION")) return 0.75;
        return 0;
    }

    private double trapSubstageQuality(RowView v) {
        String s = v.substage();
        if (containsAny(s, "EXHAUSTION", "OVEREXTENSION", "CLIMAX", "FALSE_BREAKOUT", "FAILED_BREAKOUT", "TOP", "MOMENTUM_LOSS", "DEAD_CAT", "WEAK_BOUNCE", "SUPPORT_FAILURE")) {
            return scoreFromRaw(v.substageScore(), 25, 3, 0.95);
        }
        return 0;
    }

    private double bullishChildQuality(RowView v) {
        String c = v.childSubstage();
        if (containsAny(c, "CLEAN_BREAKOUT", "SUCCESSFUL_RETEST", "EMA_STACK", "RSI_50_HOLD", "ORDERLY_CONTINUATION",
                "PULLBACK_TO_TREND", "SHALLOW_PULLBACK", "CONFIRMED_HIGHER_LOW", "HL_LOW_RISK_ENTRY",
                "TREND_ACCELERATION", "VOLUME_CONFIRMED_TREND", "SUPPORT_DEFENSE", "TIGHT_BASE", "ABSORPTION")) {
            return scoreFromRaw(v.childScore(), 12, 2, 0.9);
        }
        return 0;
    }

    private double accumulationChildQuality(RowView v) {
        String c = v.childSubstage();
        if (containsAny(c, "TIGHT_BASE", "SUPPORT_DEFENSE", "ABSORPTION", "SPRING", "LOW_VOLUME_RETEST", "PRE_BREAKOUT", "VOLUME_ACCUMULATION")) {
            return scoreFromRaw(v.childScore(), 12, 2, 0.9);
        }
        return 0;
    }

    private double bearishChildQuality(RowView v) {
        String c = v.childSubstage();
        if (containsAny(c, "FAILED", "LOST_VWAP", "BREAKDOWN", "SUPPORT_FAILURE", "LOWER_LOW", "LOWER_HIGH",
                "DISTRIBUTION", "EXHAUSTION_REVERSAL", "MOMENTUM_EXHAUSTION", "DEAD_CAT", "WEAK_BOUNCE", "SELLING")) {
            return scoreFromRaw(v.childScore(), 12, 2, 0.9);
        }
        return 0;
    }

    private double trapChildQuality(RowView v) {
        String c = v.childSubstage();
        if (containsAny(c, "OVEREXTENDED", "EXHAUSTION", "DISTRIBUTION", "FAILED", "LOST_VWAP", "BLOWOFF", "FINAL_PUSH",
                "RSI_OVEREXTENSION", "ADX_OVEREXTENSION", "DEAD_CAT", "WEAK_BOUNCE")) {
            return scoreFromRaw(v.childScore(), 12, 2, 0.95);
        }
        return 0;
    }

    private double markdownDistributionPenalty(RowView v) {
        return isStage(v, "DISTRIBUTION", "MARKDOWN") ? 1.0 : 0.0;
    }

    private double bearishContext(RowView v) {
        double x = 0;
        x += markdownDistributionPenalty(v) * 0.45;
        x += bearishSubstageQuality(v) * 0.25;
        x += bearishChildQuality(v) * 0.20;
        x += containsAny(v.finalAction(), "SELL", "SHORT", "AVOID") ? 0.25 : 0;
        return clamp01(x);
    }

    private double trapRisk(RowView v) {
        double x = 0;
        x += trapStageQuality(v) * 0.25;
        x += trapSubstageQuality(v) * 0.25;
        x += trapChildQuality(v) * 0.25;
        x += technicalWeakness(v) * 0.15;
        x += v.rsi() >= 78 ? 0.15 : 0;
        x += v.beta() >= 2.2 ? 0.05 : 0;
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

    private double healthyVolume(RowView v) {
        double surge = clamp01(v.volumeSurgeScore());
        if (surge <= 0) return 0.2;
        if (surge > 0.9 && trapRisk(v) > 0.6) return 0.3;
        return surge;
    }

    private double bearishVolume(RowView v) {
        double surge = clamp01(v.volumeSurgeScore());
        if (surge <= 0) return 0;
        return (bearishStageQuality(v) > 0.4 || trapRisk(v) > 0.55) ? surge : surge * 0.35;
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

    private double weakRelativeStrength(RowView v) {
        return 1.0 - clamp01(v.relativeStrengthScore());
    }

    private double negativeSentiment(RowView v) {
        String label = v.sentimentLabel();
        if (containsAny(label, "NEGATIVE", "BEARISH")) return 1.0;
        if (containsAny(label, "NEUTRAL")) return 0.35;
        return 1.0 - clamp01(v.sentimentScore01());
    }

    private double neutralToPositiveSentiment(RowView v) {
        String label = v.sentimentLabel();
        if (containsAny(label, "POSITIVE", "BULLISH")) return 1.0;
        if (containsAny(label, "NEUTRAL")) return 0.65;
        return clamp01(v.sentimentScore01());
    }

    private double weakEps(RowView v) {
        return 1.0 - clamp01(v.epsGrowthScore());
    }

    private double explicitUpside30Score(RowView v) {
        double gain90 = v.firstDouble(
                "90D Gain (%)", "90D Gain", "90d_gain_pct", "ninety_day_gain_pct",
                "expected_return_pct", "Expected Return %", "upside_pct", "Upside %", "analyst_upside_pct");
        if (gain90 <= 0) return 0;
        return clamp01(gain90 / 30.0);
    }

    private double scoreFromRaw(double raw, double maxRaw, double minUseful, double fallbackWhenPresent) {
        if (raw <= 0) return 0;
        if (raw < minUseful) return Math.min(fallbackWhenPresent, raw / minUseful * fallbackWhenPresent);
        return clamp01(raw / maxRaw);
    }

    @Value
    @Builder
    public static class RankingResult {
        List<RankedSymbol> ranked;
    }

    @Value
    @Builder
    public static class RankedSymbol {
        String symbol;
        double rankScore;
        double finalScore;
        String finalAction;
        String stage;
        String substage;
        String childSubstage;
        String primaryBucket;
    }

    @Value
    @Builder
    public static class RankingSummary {
        String bucket;
        List<String> symbols;
    }

    @Value
    private static class PrimaryBucket {
        String bucket;
        double score;
    }

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
            return firstString("Child Substage", "child_substage", "childSubstage", "winning_child_substage", "child");
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
            return firstDouble("winning_child_score", "child_substage_score", "Child Score", "child_score");
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

        private double getDouble(String key) {
            return toDouble(row.get(key));
        }

        private Object first(String... keys) {
            for (String key : keys) {
                if (row.containsKey(key) && row.get(key) != null) {
                    return row.get(key);
                }
            }
            return null;
        }

        private String firstString(String... keys) {
            Object v = first(keys);
            return v == null ? "" : String.valueOf(v).trim();
        }

        private double firstDouble(String... keys) {
            Object v = first(keys);
            return toDouble(v);
        }
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

    private static double normalizeScore100(double x) {
        if (Double.isNaN(x) || Double.isInfinite(x)) return 0;
        if (x <= 1.0 && x >= 0.0) return x * 100.0;
        return clamp100(x);
    }

    private static double asBoolish(Object v) {
        if (v == null) return 0;
        if (v instanceof Boolean) return ((Boolean) v) ? 1 : 0;
        if (v instanceof Number) return ((Number) v).doubleValue() > 0 ? 1 : 0;
        String s = normalize(String.valueOf(v));
        if (s.equals("TRUE") || s.equals("YES") || s.equals("Y") || s.equals("1")) return 1;
        return 0;
    }

    private static double toDouble(Object v) {
        if (v == null) return 0;
        if (v instanceof Number) return ((Number) v).doubleValue();
        String s = String.valueOf(v).trim();
        if (s.isEmpty() || s.equalsIgnoreCase("null") || s.equalsIgnoreCase("nan")) return 0;
        try {
            s = s.replace("%", "").replace(",", "");
            return Double.parseDouble(s);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static double clamp01(double x) {
        if (Double.isNaN(x) || Double.isInfinite(x)) return 0;
        return Math.max(0, Math.min(1, x));
    }

    private static double clamp100(double x) {
        if (Double.isNaN(x) || Double.isInfinite(x)) return 0;
        return Math.max(0, Math.min(100, x));
    }

    private static double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
