package com.trading.sector;

import lombok.Builder;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * SectorRotationEngine
 *
 * Purpose:
 *  - Scores sector / industry leadership across the full pipeline output.
 *  - Adds sector-rotation columns to each row before Excel export.
 *  - Helps answer:
 *      1) Which sectors are attracting strength?
 *      2) Which industries/sub-sectors are strongest?
 *      3) Which stocks are sector leaders vs laggards?
 *      4) Which stocks should get a sector-rotation boost or penalty?
 *
 * Integration point in PipelineService:
 *
 *      RankingEngine.RankingResult rankingResult = rankingEngine.rank(rows);
 *      probabilityEngine.applyProbabilities(rows);
 *      sectorRotationEngine.applySectorRotation(rows);
 *      rankingEngineV3.applyProbabilityRankings(rows);
 *
 * Add dependency:
 *
 *      private final SectorRotationEngine sectorRotationEngine;
 *
 * Suggested package path:
 *
 *      src/main/java/com/trading/sector/SectorRotationEngine.java
 *
 * Notes:
 *  - This class is additive. It does not remove or rename existing columns.
 *  - It defensively reads multiple possible column names.
 *  - Missing sector/industry defaults to UNKNOWN.
 */
@Slf4j
@Service
public class SectorRotationEngine {

    public static final String COL_SECTOR_ROTATION_SCORE = "sector_rotation_score";
    public static final String COL_SECTOR_ROTATION_RANK = "sector_rotation_rank";
    public static final String COL_SECTOR_ROTATION_BUCKET = "sector_rotation_bucket";
    public static final String COL_SECTOR_ROTATION_REASON = "sector_rotation_reason";

    public static final String COL_INDUSTRY_ROTATION_SCORE = "industry_rotation_score";
    public static final String COL_INDUSTRY_ROTATION_RANK = "industry_rotation_rank";
    public static final String COL_INDUSTRY_ROTATION_BUCKET = "industry_rotation_bucket";

    public static final String COL_SYMBOL_SECTOR_LEADERSHIP_SCORE = "symbol_sector_leadership_score";
    public static final String COL_SYMBOL_SECTOR_RANK = "symbol_sector_rank";
    public static final String COL_SYMBOL_INDUSTRY_RANK = "symbol_industry_rank";

    public static final String COL_SECTOR_RELATIVE_STRENGTH_SCORE = "sector_relative_strength_score";
    public static final String COL_SECTOR_BREADTH_SCORE = "sector_breadth_score";
    public static final String COL_SECTOR_PROBABILITY_EDGE = "sector_probability_edge";
    public static final String COL_SECTOR_RISK_PENALTY = "sector_risk_penalty";

    public static final String COL_SECTOR_ROTATION_ACTION = "sector_rotation_action";
    public static final String COL_SECTOR_ROTATION_BOOST = "sector_rotation_boost";
    public static final String COL_SECTOR_ROTATION_PENALTY = "sector_rotation_penalty";

    public void applySectorRotation(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            log.warn("SECTOR_ROTATION_SKIP rows empty");
            return;
        }

        List<RowView> views = rows.stream()
                .filter(Objects::nonNull)
                .map(RowView::new)
                .collect(Collectors.toList());

        Map<String, List<RowView>> bySector = views.stream()
                .collect(Collectors.groupingBy(RowView::sector, LinkedHashMap::new, Collectors.toList()));

        Map<String, List<RowView>> byIndustry = views.stream()
                .collect(Collectors.groupingBy(RowView::industryKey, LinkedHashMap::new, Collectors.toList()));

        List<GroupScore> sectorScores = bySector.entrySet().stream()
                .map(e -> scoreGroup("SECTOR", e.getKey(), e.getValue()))
                .sorted(Comparator.comparingDouble(GroupScore::getScore).reversed()
                        .thenComparing(GroupScore::getName))
                .collect(Collectors.toList());

        List<GroupScore> industryScores = byIndustry.entrySet().stream()
                .map(e -> scoreGroup("INDUSTRY", e.getKey(), e.getValue()))
                .sorted(Comparator.comparingDouble(GroupScore::getScore).reversed()
                        .thenComparing(GroupScore::getName))
                .collect(Collectors.toList());

        Map<String, GroupScore> sectorScoreMap = assignGroupRanks(sectorScores);
        Map<String, GroupScore> industryScoreMap = assignGroupRanks(industryScores);

        assignSymbolRanksWithinGroups(views, RowView::sector, COL_SYMBOL_SECTOR_RANK);
        assignSymbolRanksWithinGroups(views, RowView::industryKey, COL_SYMBOL_INDUSTRY_RANK);

        for (RowView v : views) {
            GroupScore sector = sectorScoreMap.getOrDefault(v.sector(), GroupScore.empty("SECTOR", v.sector()));
            GroupScore industry = industryScoreMap.getOrDefault(v.industryKey(), GroupScore.empty("INDUSTRY", v.industryKey()));

            double symbolLeadership = scoreSymbolLeadership(v, sector, industry);
            double boost = sectorBoost(sector, industry, symbolLeadership);
            double penalty = sectorPenalty(sector, industry, v);

            v.row.put(COL_SECTOR_ROTATION_SCORE, round2(sector.score));
            v.row.put(COL_SECTOR_ROTATION_RANK, sector.rank);
            v.row.put(COL_SECTOR_ROTATION_BUCKET, bucket(sector.rank, sector.score));

            v.row.put(COL_INDUSTRY_ROTATION_SCORE, round2(industry.score));
            v.row.put(COL_INDUSTRY_ROTATION_RANK, industry.rank);
            v.row.put(COL_INDUSTRY_ROTATION_BUCKET, bucket(industry.rank, industry.score));

            v.row.put(COL_SYMBOL_SECTOR_LEADERSHIP_SCORE, round2(symbolLeadership));

            v.row.put(COL_SECTOR_RELATIVE_STRENGTH_SCORE, round2(sector.relativeStrengthScore));
            v.row.put(COL_SECTOR_BREADTH_SCORE, round2(sector.breadthScore));
            v.row.put(COL_SECTOR_PROBABILITY_EDGE, round2(sector.probabilityEdge));
            v.row.put(COL_SECTOR_RISK_PENALTY, round2(sector.riskPenalty));

            v.row.put(COL_SECTOR_ROTATION_BOOST, round2(boost));
            v.row.put(COL_SECTOR_ROTATION_PENALTY, round2(penalty));
            v.row.put(COL_SECTOR_ROTATION_ACTION, action(sector, industry, symbolLeadership, penalty));
            v.row.put(COL_SECTOR_ROTATION_REASON, reason(v, sector, industry, symbolLeadership, boost, penalty));

            // Display-friendly aliases for Excel users.
            v.row.put("Sector Rotation Score", round2(sector.score));
            v.row.put("Sector Rotation Rank", sector.rank);
            v.row.put("Sector Rotation Bucket", bucket(sector.rank, sector.score));
            v.row.put("Industry Rotation Score", round2(industry.score));
            v.row.put("Industry Rotation Rank", industry.rank);
            v.row.put("Symbol Sector Leadership Score", round2(symbolLeadership));
            v.row.put("Sector Rotation Action", action(sector, industry, symbolLeadership, penalty));
            v.row.put("Sector Rotation Reason", reason(v, sector, industry, symbolLeadership, boost, penalty));
        }

        logTopGroups("SECTOR", sectorScores);
        logTopGroups("INDUSTRY", industryScores);
    }

    private GroupScore scoreGroup(String type, String name, List<RowView> members) {
        if (members == null || members.isEmpty()) {
            return GroupScore.empty(type, name);
        }

        int n = members.size();

        double avgRelativeStrength = avg(members, RowView::relativeStrengthScore);
        double avgProbEdge = avg(members, RowView::probabilityFinalEdge);
        double avgSafeBuy = avg(members, RowView::probabilitySafeBuy);
        double avgUpside20 = avg(members, RowView::probability20PctUpside);
        double avgUpside30 = avg(members, RowView::probability30PctUpside);
        double avgStopRisk = avg(members, RowView::probabilityStopLossHit);
        double avgEps = avg(members, RowView::epsGrowthScore);
        double avgSentiment = avg(members, RowView::sentimentScore01);
        double avgVolume = avg(members, RowView::volumeSurgeScore);
        double avgRiskReward = avg(members, RowView::riskRewardScore);

        double buyBreadth = pct(members, RowView::isBuyOrWatchBuy);
        double markupBreadth = pct(members, RowView::isMarkupOrAccumulation);
        double avoidTrapBreadth = pct(members, RowView::isAvoidTrap);
        double shortBreadth = pct(members, RowView::isShortOrSell);
        double exhaustionBreadth = pct(members, RowView::isExhaustionOrOverextension);

        double breadthScore =
                0.45 * buyBreadth +
                0.35 * markupBreadth +
                0.20 * (1.0 - avoidTrapBreadth);

        double riskPenalty =
                0.35 * avgStopRisk +
                0.25 * avoidTrapBreadth +
                0.20 * shortBreadth +
                0.20 * exhaustionBreadth;

        double probabilityEdge =
                0.30 * avgProbEdge +
                0.25 * avgSafeBuy +
                0.20 * avgUpside20 +
                0.15 * avgUpside30 +
                0.10 * (1.0 - avgStopRisk);

        double rawScore =
                24 * avgRelativeStrength +
                22 * probabilityEdge +
                16 * breadthScore +
                10 * avgEps +
                8 * avgSentiment +
                8 * avgVolume +
                7 * avgRiskReward -
                18 * riskPenalty;

        // Small sample penalty. Sector with one symbol can still rank, but less confidently.
        double sampleQuality = sampleQuality(n);
        double score = clamp100(rawScore * sampleQuality);

        return GroupScore.builder()
                .type(type)
                .name(name)
                .memberCount(n)
                .score(score)
                .relativeStrengthScore(clamp100(avgRelativeStrength * 100.0))
                .breadthScore(clamp100(breadthScore * 100.0))
                .probabilityEdge(clamp100(probabilityEdge * 100.0))
                .riskPenalty(clamp100(riskPenalty * 100.0))
                .buyBreadth(clamp100(buyBreadth * 100.0))
                .markupBreadth(clamp100(markupBreadth * 100.0))
                .avoidTrapBreadth(clamp100(avoidTrapBreadth * 100.0))
                .shortBreadth(clamp100(shortBreadth * 100.0))
                .build();
    }

    private Map<String, GroupScore> assignGroupRanks(List<GroupScore> scores) {
        Map<String, GroupScore> result = new LinkedHashMap<>();
        int rank = 1;
        for (GroupScore s : scores) {
            GroupScore ranked = s.toBuilder().rank(rank++).build();
            result.put(ranked.name, ranked);
        }
        return result;
    }

    private void assignSymbolRanksWithinGroups(List<RowView> views,
                                               java.util.function.Function<RowView, String> groupFn,
                                               String outputColumn) {
        Map<String, List<RowView>> grouped = views.stream()
                .collect(Collectors.groupingBy(groupFn, LinkedHashMap::new, Collectors.toList()));

        for (List<RowView> groupRows : grouped.values()) {
            List<RowView> ranked = groupRows.stream()
                    .sorted(Comparator.comparingDouble(this::scoreSymbolRaw).reversed()
                            .thenComparing(RowView::symbol))
                    .collect(Collectors.toList());

            int rank = 1;
            for (RowView v : ranked) {
                v.row.put(outputColumn, rank++);
            }
        }
    }

    private double scoreSymbolLeadership(RowView v, GroupScore sector, GroupScore industry) {
        double score =
                20 * v.relativeStrengthScore() +
                16 * v.probabilityFinalEdge() +
                14 * v.probabilitySafeBuy() +
                12 * v.probability20PctUpside() +
                8 * v.epsGrowthScore() +
                7 * v.sentimentScore01() +
                7 * v.volumeSurgeScore() +
                7 * v.riskRewardScore() +
                5 * v.finalActionBuyStrength() +
                4 * v.stageTrendQuality() -
                12 * v.probabilityStopLossHit() -
                10 * v.avoidTrapScore();

        // Reward being in a strong sector/industry, but don't let it dominate symbol quality.
        score += 0.08 * sector.score;
        score += 0.05 * industry.score;

        return clamp100(score);
    }

    private double scoreSymbolRaw(RowView v) {
        return scoreSymbolLeadership(v, GroupScore.empty("SECTOR", v.sector()), GroupScore.empty("INDUSTRY", v.industryKey()));
    }

    private double sectorBoost(GroupScore sector, GroupScore industry, double symbolLeadership) {
        double score = 0;
        if (sector.rank > 0 && sector.rank <= 3) score += 4.0;
        if (industry.rank > 0 && industry.rank <= 5) score += 3.0;
        if (sector.score >= 65) score += 3.0;
        if (industry.score >= 65) score += 2.0;
        if (symbolLeadership >= 70) score += 2.0;
        return Math.min(15.0, score);
    }

    private double sectorPenalty(GroupScore sector, GroupScore industry, RowView v) {
        double penalty = 0;
        if (sector.score < 35) penalty += 4.0;
        if (industry.score < 35) penalty += 3.0;
        if (sector.riskPenalty >= 55) penalty += 3.0;
        if (v.probabilityStopLossHit() >= 0.55) penalty += 3.0;
        if (v.isAvoidTrap()) penalty += 4.0;
        return Math.min(15.0, penalty);
    }

    private String action(GroupScore sector, GroupScore industry, double symbolLeadership, double penalty) {
        if (penalty >= 8.0) return "SECTOR_RISK_AVOID";
        if (sector.rank > 0 && sector.rank <= 3 && symbolLeadership >= 70) return "LEADER_BUY_CANDIDATE";
        if (sector.rank > 0 && sector.rank <= 5 && industry.rank > 0 && industry.rank <= 8 && symbolLeadership >= 60) {
            return "SECTOR_ROTATION_BUY";
        }
        if (sector.score >= 55 && symbolLeadership >= 55) return "WATCH_SECTOR_LEADER";
        if (sector.score < 35) return "WEAK_SECTOR";
        return "NEUTRAL";
    }

    private String reason(RowView v, GroupScore sector, GroupScore industry, double symbolLeadership, double boost, double penalty) {
        return String.format(Locale.US,
                "sector=%s sectorRank=%d sectorScore=%.2f industry=%s industryRank=%d industryScore=%.2f symbolLeadership=%.2f rs=%.2f probEdge=%.2f safeBuy=%.2f upside20=%.2f stopRisk=%.2f buyBreadth=%.2f riskPenalty=%.2f boost=%.2f penalty=%.2f",
                v.sector(),
                sector.rank,
                sector.score,
                v.industry(),
                industry.rank,
                industry.score,
                symbolLeadership,
                v.relativeStrengthScore(),
                v.probabilityFinalEdge(),
                v.probabilitySafeBuy(),
                v.probability20PctUpside(),
                v.probabilityStopLossHit(),
                sector.buyBreadth,
                sector.riskPenalty,
                boost,
                penalty);
    }

    private String bucket(int rank, double score) {
        if (rank <= 0) return "UNRANKED";
        if (rank <= 3 && score >= 60) return "HOT_ROTATION";
        if (rank <= 7 && score >= 50) return "LEADING";
        if (score >= 40) return "NEUTRAL";
        return "LAGGING";
    }

    private void logTopGroups(String type, List<GroupScore> scores) {
        log.info("SECTOR_ROTATION_TOP type={} count={}", type, scores.size());
        scores.stream().limit(10).forEach(s ->
                log.info("SECTOR_ROTATION type={} rank={} name={} score={} members={} breadth={} probEdge={} riskPenalty={}",
                        type,
                        s.rank,
                        s.name,
                        round2(s.score),
                        s.memberCount,
                        round2(s.breadthScore),
                        round2(s.probabilityEdge),
                        round2(s.riskPenalty))
        );
    }

    private double avg(List<RowView> rows, java.util.function.ToDoubleFunction<RowView> fn) {
        if (rows == null || rows.isEmpty()) return 0;
        return rows.stream().mapToDouble(fn).average().orElse(0);
    }

    private double pct(List<RowView> rows, java.util.function.Predicate<RowView> predicate) {
        if (rows == null || rows.isEmpty()) return 0;
        long count = rows.stream().filter(predicate).count();
        return (double) count / (double) rows.size();
    }

    private double sampleQuality(int n) {
        if (n >= 8) return 1.0;
        if (n >= 5) return 0.92;
        if (n >= 3) return 0.84;
        if (n == 2) return 0.75;
        return 0.62;
    }

    @Value
    @Builder(toBuilder = true)
    public static class GroupScore {
        String type;
        String name;
        int rank;
        int memberCount;
        double score;
        double relativeStrengthScore;
        double breadthScore;
        double probabilityEdge;
        double riskPenalty;
        double buyBreadth;
        double markupBreadth;
        double avoidTrapBreadth;
        double shortBreadth;

        public static GroupScore empty(String type, String name) {
            return GroupScore.builder()
                    .type(type)
                    .name(name == null || name.isBlank() ? "UNKNOWN" : name)
                    .rank(999)
                    .memberCount(0)
                    .score(0)
                    .relativeStrengthScore(0)
                    .breadthScore(0)
                    .probabilityEdge(0)
                    .riskPenalty(100)
                    .buyBreadth(0)
                    .markupBreadth(0)
                    .avoidTrapBreadth(0)
                    .shortBreadth(0)
                    .build();
        }
    }

    private static class RowView {
        private final Map<String, Object> row;

        private RowView(Map<String, Object> row) {
            this.row = row;
        }

        private String symbol() {
            return firstString("symbol", "Symbol", "ticker", "Ticker");
        }

        private String sector() {
            String v = firstString("Sector", "sector", "av_sector", "Sector Name");
            return normalizeGroup(v);
        }

        private String industry() {
            String v = firstString("Industry", "industry", "av_industry", "Sub Sector", "sub_sector", "Sub-Sector");
            return v == null || v.isBlank() ? "UNKNOWN" : v.trim().toUpperCase(Locale.US);
        }

        private String industryKey() {
            return sector() + " / " + industry();
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
            return firstString("Final Action", "final_action", "Rule Recommendation", "rule_recommendation", "recommendation");
        }

        private double relativeStrengthScore() {
            double direct = firstDouble("Relative Strength vs SPY", "relative_strength_vs_spy", "rs_vs_spy", "relativeStrengthVsSpy");
            if (direct == 0) return 0.50;
            if (direct > 3) return clamp01(direct / 100.0);
            return clamp01((direct - 0.75) / 0.65);
        }

        private double probabilityFinalEdge() {
            return normalizeMaybePct(firstDouble("probability_final_edge", "probability_edge_score", "edge_score", "Probability Final Edge"));
        }

        private double probabilitySafeBuy() {
            return normalizeMaybePct(firstDouble("probability_safe_buy", "probability_safe_buy_pct", "Probability Safe Buy"));
        }

        private double probability20PctUpside() {
            return normalizeMaybePct(firstDouble("probability_20pct_upside", "probability_20_pct_upside", "Probability 20pct Upside"));
        }

        private double probability30PctUpside() {
            return normalizeMaybePct(firstDouble("probability_30pct_upside", "probability_30_pct_upside", "Probability 30pct Upside"));
        }

        private double probabilityStopLossHit() {
            return normalizeMaybePct(firstDouble("probability_stop_loss_hit", "probability_stop_loss_hit_first", "Probability Stop Loss Hit"));
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

        private double sentimentScore01() {
            double direct = firstDouble("News Sentiment Score", "news_sentiment_score", "sentiment_score", "Sentiment Score");
            if (direct != 0) {
                if (direct >= -1.0 && direct <= 1.0) return clamp01((direct + 1.0) / 2.0);
                return normalizeMaybePct(direct);
            }

            String label = firstString("Sentiment Label", "sentiment_label", "news_sentiment_label", "News Sentiment Label");
            if (containsAny(label, "POSITIVE", "BULLISH")) return 0.75;
            if (containsAny(label, "NEGATIVE", "BEARISH")) return 0.20;
            return 0.50;
        }

        private double volumeSurgeScore() {
            double direct = firstDouble("volume_surge_ratio", "Volume Surge Ratio", "volume_surge", "Volume Surge", "relative_volume", "Relative Volume");
            if (direct <= 0) return 0.25;
            if (direct <= 5.0) return clamp01(direct / 2.5);
            return normalizeMaybePct(direct);
        }

        private double riskRewardScore() {
            double direct = firstDouble("Best_Risk_Reward", "best_risk_reward", "risk_reward", "Risk Reward", "rr_ratio", "agent_risk_reward");
            if (direct <= 0) return 0.35;
            return clamp01(direct / 3.0);
        }

        private double finalActionBuyStrength() {
            String a = finalAction();
            if (containsAny(a, "STRONG_BUY", "STRONG BUY")) return 1.0;
            if (containsAny(a, "WATCH_BUY", "BUY")) return 0.75;
            if (containsAny(a, "WATCH")) return 0.35;
            return 0.0;
        }

        private double stageTrendQuality() {
            String stg = normalize(stage());
            String sub = normalize(substage());
            String child = normalize(childSubstage());

            double score = 0;
            if (stg.contains("MARKUP")) score += 0.45;
            if (stg.contains("ACCUMULATION")) score += 0.35;
            if (sub.contains("EARLY_TREND") || sub.contains("BREAKOUT") || sub.contains("PULLBACK") || sub.contains("HIGHER_LOW")) score += 0.30;
            if (child.contains("TREND_ACCELERATION") || child.contains("EMA_STACK") || child.contains("CLEAN_BREAKOUT") || child.contains("HL_LOW_RISK_ENTRY")) score += 0.25;
            return clamp01(score);
        }

        private double avoidTrapScore() {
            double direct = normalizeMaybePct(firstDouble("probability_avoid_trap", "ranking_v2_avoid_trap_score", "avoid_trap_score"));
            if (direct > 0) return direct;
            if (containsAny(finalAction(), "AVOID", "SELL", "SHORT")) return 0.85;
            if (containsAny(stage(), "DISTRIBUTION", "MARKDOWN")) return 0.75;
            if (containsAny(substage(), "EXHAUSTION", "OVEREXTENSION", "CLIMAX", "FALSE_BREAKOUT", "FAILED_BREAKOUT")) return 0.60;
            return 0.0;
        }

        private boolean isBuyOrWatchBuy() {
            return containsAny(finalAction(), "STRONG_BUY", "BUY", "WATCH_BUY")
                    || probabilitySafeBuy() >= 0.55;
        }

        private boolean isMarkupOrAccumulation() {
            return containsAny(stage(), "MARKUP", "ACCUMULATION");
        }

        private boolean isAvoidTrap() {
            return avoidTrapScore() >= 0.55;
        }

        private boolean isShortOrSell() {
            return containsAny(finalAction(), "SHORT", "SELL")
                    || normalizeMaybePct(firstDouble("probability_short_candidate")) >= 0.55;
        }

        private boolean isExhaustionOrOverextension() {
            return containsAny(substage(), "EXHAUSTION", "OVEREXTENSION", "CLIMAX_RUN")
                    || containsAny(childSubstage(), "EXHAUSTION", "OVEREXTENDED", "BLOWOFF", "FINAL_PUSH");
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

    private static String normalizeGroup(String s) {
        if (s == null || s.isBlank()) return "UNKNOWN";
        return s.trim().toUpperCase(Locale.US);
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
