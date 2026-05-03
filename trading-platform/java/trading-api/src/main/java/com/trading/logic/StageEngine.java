package com.trading.logic;

import com.trading.config.AppRuntimeConfig;
import com.trading.config.YamlConfigService;
import com.trading.entry.Candle;
import com.trading.logic.ChildScoringEngine;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Function;

import static com.trading.logic.ChildSubstageSignalEngine.upper;

/**
 * Production-grade YAML-driven stage engine.
 * <p>
 * Goal:
 * 1. Evaluate every stage from stages/substages YAML.
 * 2. Evaluate every substage under the winning stage.
 * 3. Evaluate every child-substage under the winning substage.
 * 4. Never hardcode stage/substage/child names.
 * 5. Preserve auditability through score maps + selective logging.
 * <p>
 * Expected YAML shapes supported:
 * <p>
 * market_stages:
 * MARKUP:
 * label: ...
 * directional_bias: BULLISH
 * substages:
 * EARLY_TREND:
 * bias: BULLISH
 * directional_bias: BULLISH
 * preferred_trigger: BREAKOUT
 * <p>
 * stage_rules:
 * MARKUP:
 * signals:
 * bullStack_eq: true
 * rsi_between: [55, 72]
 * adx_gte: 18
 * <p>
 * substage_rules:
 * MARKUP:
 * EARLY_TREND:
 * signals:
 * rsi_between: [55, 68]
 * price_above_vwap_eq: true
 * <p>
 * child_substages:
 * EARLY_TREND:
 * EMA_STACK_FORMING:
 * family: TREND
 * action_bias: WATCH_BUY
 * risk_profile: MEDIUM
 * signals:
 * bullStack_eq: true
 * volume_surge_gte: 0.5
 */
@Slf4j
@Component
@RequiredArgsConstructor
@SuppressWarnings("unchecked")
public class StageEngine {

    private final YamlConfigService yamlConfigService;
    private final com.trading.logic.ChildScoringEngine childScoringEngine;

    private static Map<String, Object> asMap(Object raw) {
        if (raw instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        return Map.of();
    }

    private static Map<String, Object> firstNonEmptyMap(Map<String, Object> first, Map<String, Object> second) {
        return !first.isEmpty() ? first : second;
    }

    private static List<Object> list(Object raw) {
        if (raw instanceof List<?> l) return new ArrayList<>(l);
        if (raw == null) return List.of();
        return List.of(raw);
    }

    private static String normalize(String raw) {
        if (raw == null) return "";
        return raw.trim()
                .replace('-', '_')
                .replaceAll("([a-z])([A-Z])", "$1_$2")
                .toLowerCase(Locale.ROOT);
    }

    private static String str(Object raw) {
        return raw == null ? "" : String.valueOf(raw);
    }

    private static double num(Object raw, double def) {
        if (raw == null) return def;
        if (raw instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(raw).trim());
        } catch (Exception ex) {
            return def;
        }
    }

    private static Object rawMetric(Map<String, Object> metrics, String key) {
        if (metrics == null) return null;
        if (metrics.containsKey(key)) return metrics.get(key);

        String normalized = normalize(key);
        for (Map.Entry<String, Object> e : metrics.entrySet()) {
            if (normalize(e.getKey()).equals(normalized)) return e.getValue();
        }
        return null;
    }

    private static double metricNumber(Map<String, Object> metrics, String key) {
        return num(rawMetric(metrics, key), 0.0);
    }

    private static boolean metricBoolean(Map<String, Object> metrics, String key) {
        Object raw = rawMetric(metrics, key);
        if (raw instanceof Boolean b) return b;
        if (raw instanceof Number n) return n.doubleValue() != 0.0;
        if (raw == null) return false;
        String s = String.valueOf(raw).trim();
        return "true".equalsIgnoreCase(s)
                || "yes".equalsIgnoreCase(s)
                || "y".equalsIgnoreCase(s)
                || "1".equals(s);
    }

    private static double normalized(double value, double min, double max) {
        if (max <= min) return value >= min ? 1.0 : 0.0;
        return clamp((value - min) / (max - min), 0.0, 1.0);
    }

    private static double inverseRatio(double value, double threshold) {
        if (threshold <= 0) return 0.0;
        return clamp(1.0 - (value / threshold), 0.0, 1.0);
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double rangePct(List<Candle> candles, int lookback) {
        if (candles == null || candles.isEmpty()) return 0.0;
        int start = Math.max(0, candles.size() - lookback);
        double high = 0.0;
        double low = Double.MAX_VALUE;
        for (int i = start; i < candles.size(); i++) {
            high = Math.max(high, candles.get(i).getHigh());
            low = Math.min(low, candles.get(i).getLow());
        }
        if (low <= 0 || high <= 0) return 0.0;
        return (high - low) / low;
    }

    private static double averageVolume(List<Candle> candles, int count, int offsetFromEnd) {
        if (candles == null || candles.isEmpty()) return 0.0;
        int end = candles.size() - offsetFromEnd;
        int start = Math.max(0, end - count);
        if (start >= end) return 0.0;
        return candles.subList(start, end).stream().mapToLong(Candle::getVolume).average().orElse(0.0);
    }

    private static double volumeDryUpRatio(List<Candle> candles) {
        if (candles == null || candles.size() < 25) return 1.0;
        double recent5 = averageVolume(candles, 5, 0);
        double prior20 = averageVolume(candles, 20, 5);
        if (recent5 <= 0.0) return 1.0;
        return prior20 / recent5;
    }

    private static double coilStrengthScore(double range20, double compressionRange, double tightCompression, double volumeDryUp) {
        double score;
        if (range20 <= tightCompression) {
            score = 90.0;
        } else if (range20 <= compressionRange) {
            score = 70.0;
        } else {
            score = 40.0;
        }
        if (volumeDryUp >= 1.20) score += 5.0;
        if (volumeDryUp >= 1.50) score += 5.0;
        return round(clamp(score, 0.0, 100.0));
    }

    public StageDecision classify(AppRuntimeConfig config,
                                  String symbol,
                                  List<Candle> candles,
                                  Map<String, Object> metrics) {

        MarketContext ctx = MarketContext.from(config, yamlConfigService, candles, metrics);

        Map<String, Object> substagesRoot = asMap(config.getSubstages());
        Map<String, Object> childRoot = asMap(config.getChildSubstages());

        if (log.isDebugEnabled()) {
            log.debug("STAGE_ENGINE_3L_YAML_ROOTS symbol={} substagesRootKeys={} childRootKeys={}",
                    symbol, substagesRoot.keySet(), childRoot.keySet());
        }

        Map<String, Object> marketStages = firstNonEmptyMap(
                asMap(substagesRoot.get("market_stages")),
                asMap(substagesRoot.get("stages"))
        );

        if (marketStages.isEmpty()) {
            log.warn("STAGE_ENGINE_NO_STAGES symbol={} message='market_stages/stages missing in YAML'", symbol);
            return StageDecision.unknown();
        }

        Map<String, Double> stageScores = scoreStages(symbol, ctx, substagesRoot, childRoot, marketStages);
        Map<String, Double> rankedStageScores = ranked(stageScores);
        String winningStage = winner(stageScores, "UNKNOWN");

        Map<String, Object> winningStageMeta = asMap(marketStages.get(winningStage));
        Map<String, Object> substagesForWinningStage = asMap(winningStageMeta.get("substages"));

        log.info( "ALL_SUBSTAGES_CONFIG symbol={} winningStage={} totalSubstages={} names={}",
                symbol,  winningStage,substagesForWinningStage.size(), substagesForWinningStage.keySet());

        Map<String, Double> substageScores = scoreSubstages(symbol, ctx, substagesRoot, childRoot, winningStage, substagesForWinningStage);
        Map<String, Double> rankedSubstageScores = ranked(substageScores);
        String ruleWinningSubstage = winner(substageScores, defaultKey(substagesForWinningStage, "UNKNOWN"));

        Map<String, Double> allChildScores = scoreAllChildren(symbol, ctx, childRoot, winningStage, substagesForWinningStage);

        Map<String, Double> adjustedAllChildScores = childScoringEngine.refineAll(
                allChildScores,
                winningStage,
                ruleWinningSubstage,
                (stage, parent, child) -> {
                    Map<String, Object> meta = resolveChildMeta(childRoot, stage, parent, child);
                    return new com.trading.logic.ChildScoringEngine.ChildMeta(
                            str(meta.get("action_bias")),
                            str(meta.get("risk_profile"))
                    );
                },
                new ChildScoringEngine.MarketInputs(ctx.rsi, ctx.adx, ctx.vol)
        );

        Map<String, Double> rankedAllChildScores = ranked(adjustedAllChildScores);
        ChildConflictProfile childConflictProfile = analyzeChildConflicts(childRoot, adjustedAllChildScores);

        SelectedChild selectedChild = selectBestChildGlobally(
                childRoot,
                winningStage,
                substageScores,
                ruleWinningSubstage,
                rankedAllChildScores
        );

        log.info("CHILD_SCORING_V2_SELECTED symbol={} selectedChildKey={} rawScore={} adjustedScore={} reason={}",
                symbol, selectedChild.getQualifiedKey(), round(allChildScores.getOrDefault(selectedChild.getQualifiedKey(), 0.0)),
                round(adjustedAllChildScores.getOrDefault(selectedChild.getQualifiedKey(), 0.0)),selectedChild.getReason());

        String winningSubstage = selectedChild.getParentSubstage();
        String winningChild = selectedChild.getChildSubstage();

        Map<String, Object> childrenForWinningSubstage = resolveChildrenForSubstage(childRoot, winningStage, winningSubstage);
        log.info("ALL_CHILDREN_CONFIG symbol={} stage={} substage={} totalChildren={} names={}",
                symbol, winningStage, winningSubstage,  childrenForWinningSubstage.size(), childrenForWinningSubstage.keySet()  );

        Map<String, Double> childScores = scoreChildren(symbol, ctx, childRoot, winningStage, winningSubstage);

        Map<String, Object> substageMeta = asMap(substagesForWinningStage.get(winningSubstage));
        Map<String, Object> childMeta = resolveChildMeta(childRoot, winningStage, winningSubstage, winningChild);

        double stageScore = stageScores.getOrDefault(winningStage, 0.0);
        double substageScore = substageScores.getOrDefault(winningSubstage, 0.0);
        double childScore = adjustedAllChildScores.getOrDefault(selectedChild.getQualifiedKey(), selectedChild.getChildScore());
        double contradictionPenalty = childConflictProfile.getContradictionPenalty();

        double stageConfidence = normalizeScore(stageScore);
        double substageConfidence = normalizeScore(substageScore);
        double childConfidence = normalizeScore(Math.max(0.0, childScore - contradictionPenalty));

        log.info(
                "STAGE_ENGINE_3L_DECISION symbol={} stage={} stageScore={} substage={} substageScore={} child={} childScore={} selectedChildKey={} childPromotionReason={} evaluatedStages={} evaluatedSubstages={} evaluatedChildren={} bullishChildScore={} bearishChildScore={} neutralChildScore={} contradictoryChildScore={} conflictPenalty={} bestChildConfidence={}",
                symbol,
                winningStage,
                round(stageScore),
                winningSubstage,
                round(substageScore),
                winningChild,
                round(childScore),
                selectedChild.getQualifiedKey(),
                selectedChild.getReason(),
                stageScores.size(),
                substageScores.size(),
                allChildScores.size(),
                round(childConflictProfile.getBullishScore()),
                round(childConflictProfile.getBearishScore()),
                round(childConflictProfile.getNeutralScore()),
                round(childConflictProfile.getContradictoryScore()),
                round(childConflictProfile.getContradictionPenalty()),
                round(childConflictProfile.getBestChildConfidence())
        );

        if (log.isDebugEnabled()) {
            log.debug("STAGE_ENGINE_3L_CONTEXT symbol={} price={} vwap={} rsi={} adx={} vol={} dma20={} dma50={} dma200={} bullStack={} bearStack={} range20={} volumeDryUp={} coilStrength={}",
                    symbol, ctx.price, ctx.vwap, ctx.rsi, ctx.adx, ctx.vol, ctx.dma20, ctx.dma50, ctx.dma200,
                    ctx.bullStack, ctx.bearStack, round(ctx.range20), round(ctx.volumeDryUp), round(ctx.coilStrength));
            log.debug("STAGE_ENGINE_3L_STAGE_SCORE_MAP symbol={} scores={}", symbol, rankedStageScores);
            log.debug("STAGE_ENGINE_3L_SUBSTAGE_SCORE_MAP symbol={} stage={} scores={}", symbol, winningStage, rankedSubstageScores);
            log.debug("STAGE_ENGINE_3L_CHILD_SCORE_MAP symbol={} selectedSubstage={} scores={}", symbol, winningSubstage, ranked(childScores));
            log.debug("STAGE_ENGINE_3L_ALL_CHILD_SCORE_MAP symbol={} scores={}", symbol, ranked(allChildScores));
            log.debug("CHILD_SCORING_V2_ADJUSTED_MAP symbol={} scores={}", symbol, ranked(adjustedAllChildScores));
            log.debug("STAGE_ENGINE_3L_TOP3 symbol={} topSubstages={} topChildren={}",
                    symbol, topNKeys(rankedSubstageScores, 3), topNKeys(rankedAllChildScores, 3));
            log.debug("STAGE_ENGINE_3L_CONFLICT symbol={} summary={} rankedChildConfidence={}",
                    symbol, childConflictProfile.getConflictSummary(), childConflictProfile.getRankedChildConfidenceMap());
        }

        return StageDecision.builder()
                .marketStage(winningStage)
                .marketSubstage(winningSubstage)
                .childSubstage(winningChild)
                .stageConfidence(stageConfidence)
                .substageConfidence(substageConfidence)
                .childSubstageConfidence(childConfidence)
                .stageScore(round(stageScore))
                .substageScore(round(substageScore))
                .childSubstageScore(round(childScore))
                .stageScoreMap(stageScores)
                .substageScoreMap(substageScores)
                .childSubstageScoreMap(childScores)
                .allChildSubstageScoreMap(allChildScores)
                .adjustedAllChildSubstageScoreMap(adjustedAllChildScores)
                .childScoringVersion("CHILD_SCORING_ENGINE_V2")
                .rankedSubstageScoreMap(rankedSubstageScores)
                .rankedAllChildSubstageScoreMap(rankedAllChildScores)
                .top3Substages(topNKeys(rankedSubstageScores, 3))
                .top3ChildSubstages(topNKeys(rankedAllChildScores, 3))
                .selectedChildQualifiedKey(selectedChild.getQualifiedKey())
                .childPromotionReason(selectedChild.getReason())
                .bullishChildScore(round(childConflictProfile.getBullishScore()))
                .bearishChildScore(round(childConflictProfile.getBearishScore()))
                .neutralChildScore(round(childConflictProfile.getNeutralScore()))
                .contradictoryChildScore(round(childConflictProfile.getContradictoryScore()))
                .childContradictionPenalty(round(childConflictProfile.getContradictionPenalty()))
                .bestChildConfidence(round(childConflictProfile.getBestChildConfidence()))
                .rankedChildConfidenceMap(childConflictProfile.getRankedChildConfidenceMap())
                .childConflictSummary(childConflictProfile.getConflictSummary())
                .evaluatedStageCount(stageScores.size())
                .evaluatedSubstageCount(substageScores.size())
                .evaluatedChildSubstageCount(allChildScores.size())
                .stageLabel(str(winningStageMeta.get("label")))
                .stageDirectionalBias(str(winningStageMeta.get("directional_bias")))
                .stageDescription(str(winningStageMeta.get("description")))
                .substageBias(str(substageMeta.get("bias")))
                .substageDirectionalBias(str(substageMeta.get("directional_bias")))
                .entryStyle(str(substageMeta.get("entry_style")))
                .setupMaturity(str(substageMeta.get("maturity")))
                .riskProfile(str(substageMeta.get("risk_profile")))
                .preferredTrigger(str(substageMeta.get("preferred_trigger")))
                .substageDescription(str(substageMeta.get("description")))
                .childFamily(str(childMeta.get("family")))
                .childActionBias(str(childMeta.get("action_bias")))
                .childRiskProfile(str(childMeta.get("risk_profile")))
                .childDescription(str(childMeta.get("description")))
                .build();
    }

    private SelectedChild selectBestChildGlobally(Map<String, Object> childRoot,
                                                 String winningStage,
                                                 Map<String, Double> substageScores,
                                                 String ruleWinningSubstage,
                                                 Map<String, Double> rankedAllChildScores) {
        String fallbackChild = defaultChild(childRoot, winningStage, ruleWinningSubstage);
        String fallbackQualifiedKey = qualifiedChildKey(winningStage, ruleWinningSubstage, fallbackChild);
        double fallbackScore = rankedAllChildScores.getOrDefault(fallbackQualifiedKey, 0.0);
        SelectedChild fallback = SelectedChild.builder()
                .parentSubstage(ruleWinningSubstage)
                .childSubstage(fallbackChild)
                .qualifiedKey(fallbackQualifiedKey)
                .childScore(fallbackScore)
                .reason("RULE_WINNING_SUBSTAGE_CHILD")
                .build();

        if (rankedAllChildScores == null || rankedAllChildScores.isEmpty()) {
            return fallback;
        }

        double winningSubstageScore = substageScores.getOrDefault(ruleWinningSubstage, 0.0);
        double minParentScore = Math.max(0.0, winningSubstageScore * 0.55);

        for (Map.Entry<String, Double> entry : rankedAllChildScores.entrySet()) {
            String qualifiedKey = entry.getKey();
            String parent = parentSubstageFromQualifiedChild(qualifiedKey, ruleWinningSubstage);
            String child = childFromQualifiedChild(qualifiedKey, defaultChild(childRoot, winningStage, parent));
            double childScore = entry.getValue() == null ? 0.0 : entry.getValue();
            double parentScore = substageScores.getOrDefault(parent, 0.0);

            if (childScore <= 0.0) {
                continue;
            }
            if (!parent.equals(ruleWinningSubstage) && parentScore < minParentScore) {
                continue;
            }

            String reason = parent.equals(ruleWinningSubstage)
                    ? "GLOBAL_CHILD_WITHIN_RULE_WINNING_SUBSTAGE"
                    : "GLOBAL_CHILD_PROMOTED_PARENT_SUBSTAGE parentScore=" + round(parentScore)
                    + " ruleWinningSubstage=" + ruleWinningSubstage
                    + " ruleWinningSubstageScore=" + round(winningSubstageScore);

            return SelectedChild.builder()
                    .parentSubstage(parent)
                    .childSubstage(child)
                    .qualifiedKey(qualifiedKey)
                    .childScore(childScore)
                    .reason(reason)
                    .build();
        }

        return fallback;
    }

    private Map<String, Double> ranked(Map<String, Double> input) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (input == null || input.isEmpty()) {
            return out;
        }
        input.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
                .forEach(e -> out.put(e.getKey(), round(e.getValue() == null ? 0.0 : e.getValue())));
        return out;
    }

    private String topNKeys(Map<String, Double> rankedScores, int n) {
        if (rankedScores == null || rankedScores.isEmpty() || n <= 0) {
            return "";
        }
        return rankedScores.entrySet().stream()
                .limit(n)
                .map(e -> e.getKey() + "=" + round(e.getValue() == null ? 0.0 : e.getValue()))
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }

    private String qualifiedChildKey(String stage, String substage, String child) {
        return str(stage).trim() + "." + str(substage).trim() + "." + str(child).trim();
    }

    private String stageFromQualifiedChild(String qualifiedKey, String fallback) {
        if (qualifiedKey == null || qualifiedKey.isBlank()) return fallback;
        String[] parts = qualifiedKey.split("\\.", 3);
        return parts.length >= 3 ? parts[0] : fallback;
    }

    private String parentSubstageFromQualifiedChild(String qualifiedKey, String fallback) {
        if (qualifiedKey == null || qualifiedKey.isBlank()) return fallback;
        String[] parts = qualifiedKey.split("\\.", 3);
        if (parts.length >= 3) return parts[1];
        if (parts.length == 2) return parts[0]; // backward-compatible old SUBSTAGE.CHILD key
        return fallback;
    }

    private String childFromQualifiedChild(String qualifiedKey, String fallback) {
        if (qualifiedKey == null || qualifiedKey.isBlank()) return fallback;
        String[] parts = qualifiedKey.split("\\.", 3);
        if (parts.length >= 3) return parts[2];
        if (parts.length == 2) return parts[1]; // backward-compatible old SUBSTAGE.CHILD key
        return qualifiedKey;
    }

    private Map<String, Double> scoreStages(String symbol,
                                            MarketContext ctx,
                                            Map<String, Object> root,
                                            Map<String, Object> childRoot,
                                            Map<String, Object> marketStages) {

        Map<String, Object> stageRules = asMap(root.get("stage_rules"));
        Map<String, Object> substageRules = asMap(root.get("substage_rules"));
        Map<String, Object> stageBoosts = asMap(root.get("stage_boosts"));

        Map<String, Double> scores = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : marketStages.entrySet()) {
            String stage = entry.getKey();
            Map<String, Object> stageMeta = asMap(entry.getValue());

            double score = 0.0;

            score += scoreRuleSet(asMap(stageRules.get(stage)), ctx);
            score += scoreSignalMap(asMap(stageBoosts.get(stage)), ctx);

            // Stage also receives credit from the best substage rule under it.
            score += bestNestedRuleScore(asMap(substageRules.get(stage)), ctx) * 0.35;

            // Child-substage evidence is evaluated across every substage under this stage.
            score += bestChildScoreForStage(ctx, childRoot, stage, stageMeta) * 0.20;

            // Metadata is not the primary classifier, but provides weak bias when explicit rules are thin.
            score += scoreMetadata(stageMeta, ctx) * 0.15;

            scores.put(stage, round(score));

            if (log.isDebugEnabled()) {
                log.debug("STAGE_SCORE symbol={} stage={} score={}", symbol, stage, round(score));
            }
        }

        return scores;
    }

    private Map<String, Double> scoreSubstages(String symbol,
                                               MarketContext ctx,
                                               Map<String, Object> root,
                                               Map<String, Object> childRoot,
                                               String winningStage,
                                               Map<String, Object> substagesForStage) {

        Map<String, Object> substageRules = asMap(root.get("substage_rules"));
        Map<String, Object> rulesForStage = asMap(substageRules.get(winningStage));

        log.info("SUBSTAGE_CONFIG symbol={} winningStage={} totalSubstages={} names={}",
                symbol,  winningStage,  rulesForStage.size(), rulesForStage.keySet() );

        Map<String, Double> scores = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : substagesForStage.entrySet()) {
            String substage = entry.getKey();
            Map<String, Object> substageMeta = asMap(entry.getValue());

            double score = 0.0;
            score += scoreRuleSet(asMap(rulesForStage.get(substage)), ctx);
            // Child evidence influences each candidate substage.
            score += bestChildScoreForSubstage(ctx, childRoot, winningStage, substage) * 0.25;
            score += scoreMetadata(substageMeta, ctx) * 0.20;
            score += sequenceRank(substageMeta) * 0.001;

            scores.put(substage, round(score));

            if (log.isDebugEnabled()) {
                log.debug("SUBSTAGE_SCORE symbol={} stage={} substage={} score={}", symbol, winningStage, substage, round(score));
            }
        }

        return scores;
    }

    private Map<String, Double> scoreChildren(String symbol,
                                              MarketContext ctx,
                                              Map<String, Object> childRoot,
                                              String winningStage,
                                              String winningSubstage) {

        Map<String, Object> children = resolveChildrenForSubstage(childRoot, winningStage, winningSubstage);
        Map<String, Double> scores = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : children.entrySet()) {
            String child = entry.getKey();
            Map<String, Object> childMeta = asMap(entry.getValue());

            double score = scoreChildMeta(childMeta, ctx);

            scores.put(child, round(score));

            if (log.isDebugEnabled()) {
                log.debug("CHILD_SUBSTAGE_SCORE_3L symbol={} key={} stage={} parentSubstage={} childSubstage={} score={}",
                        symbol, qualifiedChildKey(winningStage, winningSubstage, child), winningStage, winningSubstage, child, round(score));
            }
        }

        return scores;
    }

    private Map<String, Double> scoreAllChildren(String symbol,
                                                 MarketContext ctx,
                                                 Map<String, Object> childRoot,
                                                 String winningStage,
                                                 Map<String, Object> substagesForStage) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (String substage : substagesForStage.keySet()) {
            Map<String, Double> childScores = scoreChildren(symbol, ctx, childRoot, winningStage, substage);
            for (Map.Entry<String, Double> e : childScores.entrySet()) {
                out.put(qualifiedChildKey(winningStage, substage, e.getKey()), e.getValue());
            }
        }
        return out;
    }

    private double bestChildScoreForStage(MarketContext ctx,
                                          Map<String, Object> childRoot,
                                          String stage,
                                          Map<String, Object> stageMeta) {
        double best = 0.0;
        Map<String, Object> substages = asMap(stageMeta.get("substages"));
        for (String substage : substages.keySet()) {
            best = Math.max(best, bestChildScoreForSubstage(ctx, childRoot, stage, substage));
        }
        return best;
    }

    private double bestChildScoreForSubstage(MarketContext ctx,
                                             Map<String, Object> childRoot,
                                             String stage,
                                             String substage) {
        double best = 0.0;
        for (Object raw : resolveChildrenForSubstage(childRoot, stage, substage).values()) {
            Map<String, Object> childMeta = asMap(raw);
            double score = scoreChildMeta(childMeta, ctx);
            best = Math.max(best, score);
        }
        return best;
    }


    private double scoreChildMeta(Map<String, Object> childMeta, MarketContext ctx) {
        if (childMeta == null || childMeta.isEmpty()) {
            return 0.0;
        }

        double score = 0.0;
        score += scoreRuleSet(childMeta, ctx);
        score += scoreSignalMap(asMap(childMeta.get("signals")), ctx);
        score += scoreMetadata(childMeta, ctx) * 0.15;
        score += sequenceRank(childMeta) * 0.001;

        // Optional YAML knobs. If absent, behavior remains backward compatible.
        score += num(childMeta.get("confidence_bias"), 0.0);
        score += num(childMeta.get("score_bias"), 0.0);
        score -= num(childMeta.get("risk_penalty"), 0.0);

        return round(Math.max(0.0, score));
    }

    private ChildConflictProfile analyzeChildConflicts(Map<String, Object> childRoot,
                                                       Map<String, Double> allChildScores) {
        double bullish = 0.0;
        double bearish = 0.0;
        double neutral = 0.0;
        double contradictory = 0.0;
        Map<String, Double> confidenceMap = new LinkedHashMap<>();

        for (Map.Entry<String, Double> entry : allChildScores.entrySet()) {
            String qualifiedName = entry.getKey();
            double score = entry.getValue() == null ? 0.0 : entry.getValue();
            String stage = stageFromQualifiedChild(qualifiedName, "");
            String substage = parentSubstageFromQualifiedChild(qualifiedName, qualifiedName);
            String child = childFromQualifiedChild(qualifiedName, qualifiedName);

            Map<String, Object> meta = resolveChildMeta(childRoot, stage, substage, child);
            String bias = upper(firstNonBlank(
                    str(meta.get("action_bias")),
                    str(meta.get("bias")),
                    str(meta.get("directional_bias")),
                    str(meta.get("family"))
            ));

            if (score > 0.0) {
                confidenceMap.put(qualifiedName, normalizeScore(score));
            }

            if (isBullishBias(bias)) {
                bullish += score;
            } else if (isBearishBias(bias)) {
                bearish += score;
            } else if (bias.contains("CONFLICT") || bias.contains("CONTRADICT")) {
                contradictory += score;
            } else {
                neutral += score;
            }
        }

        Map<String, Double> ranked = new LinkedHashMap<>();
        confidenceMap.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
                .forEach(e -> ranked.put(e.getKey(), round(e.getValue())));

        double smallerSide = Math.min(bullish, bearish);
        double largerSide = Math.max(bullish, bearish);
        double balancePenalty = largerSide <= 0.0 ? 0.0 : (smallerSide / largerSide) * 20.0;
        double contradictionPenalty = clamp(balancePenalty + contradictory * 0.50, 0.0, 35.0);
        double bestConfidence = ranked.isEmpty() ? 0.0 : ranked.values().iterator().next();

        String summary = "bullish=" + round(bullish)
                + ", bearish=" + round(bearish)
                + ", neutral=" + round(neutral)
                + ", contradictory=" + round(contradictory)
                + ", penalty=" + round(contradictionPenalty);

        return ChildConflictProfile.builder()
                .bullishScore(round(bullish))
                .bearishScore(round(bearish))
                .neutralScore(round(neutral))
                .contradictoryScore(round(contradictory))
                .contradictionPenalty(round(contradictionPenalty))
                .bestChildConfidence(round(bestConfidence))
                .rankedChildConfidenceMap(ranked)
                .conflictSummary(summary)
                .build();
    }

    private boolean isBullishBias(String bias) {
        return bias.contains("BUY")
                || bias.contains("BULL")
                || bias.contains("LONG")
                || bias.contains("ACCUMULATION")
                || bias.contains("MARKUP")
                || bias.contains("BREAKOUT");
    }

    private boolean isBearishBias(String bias) {
        return bias.contains("SELL")
                || bias.contains("BEAR")
                || bias.contains("SHORT")
                || bias.contains("AVOID")
                || bias.contains("DISTRIBUTION")
                || bias.contains("MARKDOWN")
                || bias.contains("EXHAUSTION")
                || bias.contains("OVEREXTENSION");
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private double scoreRuleSet(Map<String, Object> rule, MarketContext ctx) {
        if (rule == null || rule.isEmpty()) {
            return 0.0;
        }

        Map<String, Object> gates = asMap(rule.get("gates"));
        if (!passesGates(gates, ctx)) {
            return -1_000_000.0;
        }

        double score = 0.0;
        score += scoreSignalMap(asMap(rule.get("signals")), ctx);

        // Also support compact rules directly under the rule:
        // rsi_between: [50, 65]
        // bullStack_eq: true
        for (Map.Entry<String, Object> entry : rule.entrySet()) {
            String key = entry.getKey();
            if (isReservedRuleKey(key)) {
                continue;
            }
            if (looksLikeSignalKey(key)) {
                score += scoreSignal(key, entry.getValue(), ctx);
            }
        }

        List<Object> penalties = list(rule.get("penalties"));
        for (Object penalty : penalties) {
            score -= penaltyValue(String.valueOf(penalty), ctx);
        }

        Object weight = rule.get("weight");
        if (weight != null) {
            score *= num(weight, 1.0);
        }

        return score;
    }

    private double bestNestedRuleScore(Map<String, Object> nestedRules, MarketContext ctx) {
        double best = 0.0;
        for (Object raw : nestedRules.values()) {
            best = Math.max(best, scoreRuleSet(asMap(raw), ctx));
        }
        return best;
    }

    private double scoreSignalMap(Map<String, Object> signals, MarketContext ctx) {
        double score = 0.0;
        for (Map.Entry<String, Object> entry : signals.entrySet()) {
            score += scoreSignal(entry.getKey(), entry.getValue(), ctx);
        }
        return score;
    }

    private double scoreSignal(String rawKey, Object descriptor, MarketContext ctx) {
        String key = normalize(rawKey);
        if (key.isBlank()) return 0.0;

        String operator = operator(key);
        String feature = feature(key);
        double weight = signalWeight(descriptor, operator);

        if (weight <= 0) return 0.0;

        double match = switch (operator) {
            case "between" -> between(numericFeature(feature, ctx), descriptor) ? 1.0 : 0.0;
            case "gte", "min" -> numericFeature(feature, ctx) >= num(descriptor, 0.0) ? 1.0 : 0.0;
            case "lte", "max" -> numericFeature(feature, ctx) <= num(descriptor, 0.0) ? 1.0 : 0.0;
            case "gt" -> numericFeature(feature, ctx) > num(descriptor, 0.0) ? 1.0 : 0.0;
            case "lt" -> numericFeature(feature, ctx) < num(descriptor, 0.0) ? 1.0 : 0.0;
            case "eq" -> eqMatch(feature, descriptor, ctx);
            default -> descriptorMatch(feature, descriptor, ctx);
        };

        return weight * clamp(match, 0.0, 1.0);
    }

    private boolean passesGates(Map<String, Object> gates, MarketContext ctx) {
        for (Map.Entry<String, Object> gate : gates.entrySet()) {
            if (scoreSignal(gate.getKey(), gate.getValue(), ctx) <= 0.0) {
                return false;
            }
        }
        return true;
    }

    private double descriptorMatch(String feature, Object descriptor, MarketContext ctx) {
        if (descriptor == null) {
            return signalStrength(feature, ctx);
        }
        if (descriptor instanceof Boolean b) {
            return boolFeature(feature, ctx) == b ? 1.0 : 0.0;
        }
        if (descriptor instanceof Number) {
            return signalStrength(feature, ctx) > 0 ? 1.0 : 0.0;
        }
        if (descriptor instanceof List<?> l) {
            return between(numericFeature(feature, ctx), l) ? 1.0 : 0.0;
        }
        Map<String, Object> map = asMap(descriptor);
        if (!map.isEmpty()) {
            Object expected = map.containsKey("value") ? map.get("value") : map.get("expected");
            if (expected != null) return descriptorMatch(feature, expected, ctx);

            Object min = map.get("min");
            Object max = map.get("max");
            if (min != null || max != null) {
                double v = numericFeature(feature, ctx);
                return v >= num(min, Double.NEGATIVE_INFINITY) && v <= num(max, Double.POSITIVE_INFINITY) ? 1.0 : 0.0;
            }

            return signalStrength(feature, ctx);
        }

        String raw = String.valueOf(descriptor).trim();
        if ("true".equalsIgnoreCase(raw) || "false".equalsIgnoreCase(raw)) {
            return boolFeature(feature, ctx) == Boolean.parseBoolean(raw) ? 1.0 : 0.0;
        }
        if (raw.startsWith(">=")) return numericFeature(feature, ctx) >= num(raw.substring(2), 0) ? 1.0 : 0.0;
        if (raw.startsWith("<=")) return numericFeature(feature, ctx) <= num(raw.substring(2), 0) ? 1.0 : 0.0;
        if (raw.startsWith(">")) return numericFeature(feature, ctx) > num(raw.substring(1), 0) ? 1.0 : 0.0;
        if (raw.startsWith("<")) return numericFeature(feature, ctx) < num(raw.substring(1), 0) ? 1.0 : 0.0;

        return 0.0;
    }

    private double eqMatch(String feature, Object descriptor, MarketContext ctx) {
        if (descriptor instanceof Boolean b) {
            return boolFeature(feature, ctx) == b ? 1.0 : 0.0;
        }
        return Objects.equals(String.valueOf(descriptor), String.valueOf(rawMetric(ctx.metrics, feature))) ? 1.0 : 0.0;
    }

    private double signalStrength(String feature, MarketContext ctx) {
        String key = normalize(feature);

        Function<MarketContext, Double> numeric = numericSignalRegistry().get(key);
        if (numeric != null) return clamp(numeric.apply(ctx), 0.0, 1.0);

        Function<MarketContext, Boolean> bool = booleanSignalRegistry().get(key);
        if (bool != null) return Boolean.TRUE.equals(bool.apply(ctx)) ? 1.0 : 0.0;

        if (metricBoolean(ctx.metrics, key)) return 1.0;

        double metric = metricNumber(ctx.metrics, key);
        return clamp(metric, 0.0, 1.0);
    }

    private double numericFeature(String feature, MarketContext ctx) {
        String key = normalize(feature);

        Function<MarketContext, Double> fn = rawNumericRegistry().get(key);
        if (fn != null) return fn.apply(ctx);

        return metricNumber(ctx.metrics, key);
    }

    private boolean boolFeature(String feature, MarketContext ctx) {
        String key = normalize(feature);

        Function<MarketContext, Boolean> fn = booleanSignalRegistry().get(key);
        if (fn != null) return Boolean.TRUE.equals(fn.apply(ctx));

        return metricBoolean(ctx.metrics, key);
    }

    private Map<String, Function<MarketContext, Double>> rawNumericRegistry() {
        Map<String, Function<MarketContext, Double>> map = new LinkedHashMap<>();
        registerNumeric(map, c -> c.price, "price", "current_price", "current");
        registerNumeric(map, c -> c.rsi, "rsi");
        registerNumeric(map, c -> c.adx, "adx");
        registerNumeric(map, c -> c.vwap, "vwap");
        registerNumeric(map, c -> c.dma20, "dma20");
        registerNumeric(map, c -> c.dma50, "dma50");
        registerNumeric(map, c -> c.dma200, "dma200");
        registerNumeric(map, c -> c.atr14, "atr14");
        registerNumeric(map, c -> c.vol, "vol", "volume_ratio", "vol_surge_ratio");
        registerNumeric(map, c -> c.range20, "range20", "range_20");
        registerNumeric(map, c -> c.range20 * 100.0, "range_compression_pct", "range_compression");
        registerNumeric(map, c -> c.coilStrength, "coil_strength", "coilstrength");
        registerNumeric(map, c -> c.volumeDryUp, "volume_dry_up", "volumedryup");
        registerNumeric(map, c -> metricNumber(c.metrics, "pct_from_dma20"), "pct_from_dma20");
        return map;
    }

    private Map<String, Function<MarketContext, Double>> numericSignalRegistry() {
        Map<String, Function<MarketContext, Double>> map = new LinkedHashMap<>(rawNumericRegistry());
        registerNumeric(map, c -> inverseRatio(c.range20, c.compressionRange), "compression");
        registerNumeric(map, c -> inverseRatio(c.range20, c.tightCompression), "tight_compression");
        registerNumeric(map, c -> c.coilStrength / 100.0, "coil", "coil_quality");
        registerNumeric(map, c -> normalized(c.adx, c.adxTrend, c.strongTrendAdx), "adx_trend");
        registerNumeric(map, c -> normalized(c.adx, c.strongTrendAdx, c.strongTrendAdx * 1.5), "adx_strong");
        registerNumeric(map, c -> normalized(c.rsi, c.rsiBull, c.exhaustionHigh), "rsi_bull");
        registerNumeric(map, c -> normalized(50.0 - c.rsi, 50.0 - c.rsiBear, 50.0 - c.exhaustionLow), "rsi_bear");
        registerNumeric(map, c -> inverseRatio(c.rsi, c.exhaustionLow), "rsi_exhaustion_low");
        registerNumeric(map, c -> normalized(c.rsi, c.exhaustionHigh, c.exhaustionHigh + 10), "rsi_exhaustion_high");
        registerNumeric(map, c -> normalized(c.vol, 1.0, c.strongVol), "volume_surge");
        registerNumeric(map, c -> normalized(c.vol, c.strongVol, c.strongVol * 1.5), "strong_volume");
        return map;
    }

    private Map<String, Function<MarketContext, Boolean>> booleanSignalRegistry() {
        Map<String, Function<MarketContext, Boolean>> map = new LinkedHashMap<>();
        registerBoolean(map, c -> c.bullStack, "bull_stack", "bullstack");
        registerBoolean(map, c -> c.bearStack, "bear_stack", "bearstack");
        registerBoolean(map, c -> c.priceAboveVwap, "price_above_vwap", "priceabovevwap");
        registerBoolean(map, c -> c.priceBelowVwap, "price_below_vwap", "pricebelowvwap");
        registerBoolean(map, c -> c.breakout, "breakout");
        registerBoolean(map, c -> c.nearSupport, "near_support", "nearsupport");
        registerBoolean(map, c -> c.dipReclaim, "dip_reclaim", "dipreclaim");
        registerBoolean(map, c -> c.hammer, "hammer");
        registerBoolean(map, c -> c.engulf, "bullish_engulfing", "bullishengulfing", "engulf");
        registerBoolean(map, c -> c.macdCross, "macd_cross", "macdcross");
        registerBoolean(map, c -> c.tightCoil, "tight_coil", "tightcoil");
        registerBoolean(map, c -> c.preBreakoutCompression, "pre_breakout_compression", "prebreakoutcompression");
        registerBoolean(map, c -> c.coiledBreakoutSetup, "coiled_breakout_setup", "coiledbreakoutsetup");

        // Structural predicates. Add new advanced candle structures here only when the metric cannot be precomputed upstream.
        registerBoolean(map, c -> higherLowStructure(c.candles, 30), "higher_low", "higherlow", "higher_low_structure", "higherlowstructure");
        registerBoolean(map, c -> lowerHighStructure(c.candles, 30), "lower_high", "lowerhigh", "lower_high_structure", "lowerhighstructure");
        registerBoolean(map, c -> lowerLowAcceleration(c.candles, 20), "lower_low_acceleration", "lowerlowacceleration");
        registerBoolean(map, c -> failedBreakout(c.candles), "failed_breakout", "false_breakout", "failedbreakout", "falsebreakout");
        registerBoolean(map, c -> isFailedBreakdown(c.candles), "failed_breakdown", "false_breakdown", "failedbreakdown", "falsebreakdown");
        registerBoolean(map, c -> isSpring(c.candles, c.metrics), "spring");
        registerBoolean(map, c -> isFailedSpring(c.candles, c.metrics), "failed_spring", "failedspring");
        registerBoolean(map, c -> isFailedBreakdown(c.candles) || isSpring(c.candles, c.metrics), "liquidity_grab_low", "liquiditygrablow");
        registerBoolean(map, c -> failedBreakout(c.candles), "liquidity_grab_high", "liquiditygrabhigh");
        registerBoolean(map, c -> true, "always_true", "alwaystrue");
        return map;
    }

    private void registerNumeric(Map<String, Function<MarketContext, Double>> map,
                                 Function<MarketContext, Double> fn,
                                 String... aliases) {
        for (String alias : aliases) {
            map.put(normalize(alias), fn);
        }
    }

    private void registerBoolean(Map<String, Function<MarketContext, Boolean>> map,
                                 Function<MarketContext, Boolean> fn,
                                 String... aliases) {
        for (String alias : aliases) {
            map.put(normalize(alias), fn);
        }
    }

    private double scoreMetadata(Map<String, Object> meta, MarketContext ctx) {
        double score = 0.0;

        String bias = str(meta.get("bias")).toUpperCase(Locale.ROOT);
        String directionalBias = str(meta.get("directional_bias")).toUpperCase(Locale.ROOT);
        String preferredTrigger = str(meta.get("preferred_trigger")).toUpperCase(Locale.ROOT);
        String entryStyle = str(meta.get("entry_style")).toUpperCase(Locale.ROOT);
        String riskProfile = str(meta.get("risk_profile")).toUpperCase(Locale.ROOT);

        if (bias.contains("BULLISH") || directionalBias.contains("BULLISH")) {
            score += ctx.bullStack ? 8.0 : 0.0;
            score += ctx.priceAboveVwap ? 4.0 : 0.0;
            score += normalized(ctx.rsi, 45.0, ctx.exhaustionHigh) * 4.0;
        }

        if (bias.contains("BEARISH") || directionalBias.contains("BEARISH")) {
            score += ctx.bearStack ? 8.0 : 0.0;
            score += ctx.priceBelowVwap ? 4.0 : 0.0;
            score += normalized(50.0 - ctx.rsi, 50.0 - ctx.rsiBear, 50.0 - ctx.exhaustionLow) * 4.0;
        }

        if (bias.contains("NEUTRAL") || directionalBias.contains("NEUTRAL")) {
            score += inverseRatio(ctx.range20, ctx.compressionRange) * 5.0;
        }

        if (preferredTrigger.contains("BREAKOUT") || preferredTrigger.contains("RANGE_EXPANSION")) {
            score += ctx.breakout ? 6.0 : 0.0;
            score += normalized(ctx.vol, 1.0, ctx.strongVol) * 3.0;
        }

        if (preferredTrigger.contains("SUPPORT") || preferredTrigger.contains("RECLAIM") || preferredTrigger.contains("SPRING")) {
            score += ctx.nearSupport ? 5.0 : 0.0;
            score += (ctx.hammer || ctx.engulf || ctx.dipReclaim) ? 3.0 : 0.0;
        }

        if (preferredTrigger.contains("VOLUME")) {
            score += normalized(ctx.vol, 1.0, ctx.strongVol) * 5.0;
        }

        if (entryStyle.contains("WAIT")) score += 0.5;
        if (riskProfile.contains("VERY_HIGH") && (ctx.rsi >= ctx.exhaustionHigh || ctx.rsi <= ctx.exhaustionLow))
            score += 2.0;

        return score;
    }

    private double penaltyValue(String penalty, MarketContext ctx) {
        String key = normalize(penalty);
        if ("failed_spring".equals(key)) return isFailedSpring(ctx.candles, ctx.metrics) ? 10.0 : 0.0;
        if ("failed_breakout".equals(key)) return failedBreakout(ctx.candles) ? 8.0 : 0.0;
        if ("failed_breakdown".equals(key)) return isFailedBreakdown(ctx.candles) ? 8.0 : 0.0;
        if ("late_exhaustion".equals(key)) return ctx.rsi >= ctx.exhaustionHigh ? 6.0 : 0.0;
        if ("panic".equals(key)) return ctx.rsi <= ctx.exhaustionLow && ctx.vol >= ctx.strongVol ? 6.0 : 0.0;
        return 0.0;
    }

    private Map<String, Object> resolveChildrenForSubstage(Map<String, Object> childRoot, String stage, String substage) {
        // Preferred 3-level architecture:
        // child_substages:
        //   MARKUP:
        //     STRONG_TREND:
        //       TREND_ACCELERATION: {...}
        Map<String, Object> byStage = asMap(childRoot.get(stage));
        Map<String, Object> direct = asMap(byStage.get(substage));
        if (!direct.isEmpty()) return direct;

        // Also support flattened 3-level key: MARKUP.STRONG_TREND
        direct = asMap(childRoot.get(stage + "." + substage));
        if (!direct.isEmpty()) return direct;

        // Optional container shapes for future config organization.
        Map<String, Object> byStageContainer = asMap(childRoot.get("by_stage"));
        direct = asMap(asMap(byStageContainer.get(stage)).get(substage));
        if (!direct.isEmpty()) return direct;

        Map<String, Object> childrenContainer = asMap(childRoot.get("children"));
        direct = asMap(asMap(childrenContainer.get(stage)).get(substage));
        if (!direct.isEmpty()) return direct;

        // Backward-compatible legacy fallback: top-level parent substage.
        direct = asMap(childRoot.get(substage));
        if (!direct.isEmpty()) return direct;

        Map<String, Object> bySubstage = asMap(childRoot.get("by_substage"));
        direct = asMap(bySubstage.get(substage));
        if (!direct.isEmpty()) return direct;

        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("DEFAULT", Map.of(
                "family", "DEFAULT",
                "action_bias", "WAIT",
                "risk_profile", "UNKNOWN",
                "description", "No child_substage YAML mapping found for stage.substage=" + stage + "." + substage,
                "parent_stage", stage,
                "parent_substage", substage
        ));
        return fallback;
    }

    private Map<String, Object> resolveChildMeta(Map<String, Object> childRoot, String stage, String substage, String child) {
        return asMap(resolveChildrenForSubstage(childRoot, stage, substage).get(child));
    }

    private String defaultChild(Map<String, Object> childRoot, String stage, String substage) {
        Map<String, Object> children = resolveChildrenForSubstage(childRoot, stage, substage);
        if (children.containsKey("DEFAULT")) return "DEFAULT";
        return defaultKey(children, "DEFAULT");
    }

    private String winner(Map<String, Double> scores, String fallback) {
        return scores.entrySet()
                .stream()
                .max(Comparator.comparingDouble(Map.Entry::getValue))
                .map(Map.Entry::getKey)
                .orElse(fallback);
    }

    private String defaultKey(Map<String, Object> map, String fallback) {
        return map.keySet().stream().findFirst().orElse(fallback);
    }

    private boolean isReservedRuleKey(String key) {
        String k = normalize(key);
        return "signals".equals(k)
                || "gates".equals(k)
                || "penalties".equals(k)
                || "weight".equals(k)
                || "description".equals(k)
                || "family".equals(k)
                || "action_bias".equals(k)
                || "risk_profile".equals(k)
                || "bias".equals(k)
                || "directional_bias".equals(k)
                || "preferred_trigger".equals(k)
                || "entry_style".equals(k)
                || "maturity".equals(k)
                || "granular_classification".equals(k);
    }

    private boolean looksLikeSignalKey(String key) {
        String k = normalize(key);
        return k.endsWith("_eq")
                || k.endsWith("_gte")
                || k.endsWith("_lte")
                || k.endsWith("_gt")
                || k.endsWith("_lt")
                || k.endsWith("_between")
                || k.endsWith("_min")
                || k.endsWith("_max")
                || numericSignalRegistry().containsKey(k)
                || booleanSignalRegistry().containsKey(k);
    }

    private String feature(String key) {
        String k = normalize(key);
        for (String suffix : List.of("_between", "_gte", "_lte", "_gt", "_lt", "_eq", "_min", "_max")) {
            if (k.endsWith(suffix)) return k.substring(0, k.length() - suffix.length());
        }
        return k;
    }

    private String operator(String key) {
        String k = normalize(key);
        for (String suffix : List.of("_between", "_gte", "_lte", "_gt", "_lt", "_eq", "_min", "_max")) {
            if (k.endsWith(suffix)) return suffix.substring(1);
        }
        return "";
    }

    private double signalWeight(Object descriptor, String operator) {
        Map<String, Object> map = asMap(descriptor);
        if (!map.isEmpty() && map.containsKey("weight")) {
            return num(map.get("weight"), 1.0);
        }
        if (descriptor instanceof Number n && operator.isBlank()) {
            return Math.max(0.0, n.doubleValue());
        }
        return 1.0;
    }

    private boolean between(double value, Object descriptor) {
        if (descriptor instanceof List<?> list) return between(value, list);
        Map<String, Object> map = asMap(descriptor);
        if (!map.isEmpty()) {
            Object range = map.get("range");
            if (range instanceof List<?> list) return between(value, list);
            double min = num(map.get("min"), Double.NEGATIVE_INFINITY);
            double max = num(map.get("max"), Double.POSITIVE_INFINITY);
            return value >= min && value <= max;
        }
        return false;
    }

    private boolean between(double value, List<?> list) {
        if (list.size() < 2) return false;
        double min = num(list.get(0), Double.NEGATIVE_INFINITY);
        double max = num(list.get(1), Double.POSITIVE_INFINITY);
        return value >= min && value <= max;
    }

    private double sequenceRank(Map<String, Object> meta) {
        Map<String, Object> granular = asMap(meta.get("granular_classification"));
        return num(granular.get("sequence_rank"), 0.0);
    }

    // ---- Structural candle predicates ----
    // These are generic market-structure helpers. YAML still decides whether/when to use them.

    private double normalizeScore(double score) {
        // Keeps confidence bounded while allowing high rule weights.
        return round(clamp(score, 0.0, 100.0));
    }

    private boolean higherLowStructure(List<Candle> candles, int lookback) {
        if (candles == null || candles.size() < lookback + 2) return false;
        int start = Math.max(1, candles.size() - lookback);
        int up = 0;
        int total = 0;
        for (int i = start; i < candles.size(); i++) {
            if (candles.get(i).getLow() > candles.get(i - 1).getLow()) up++;
            total++;
        }
        return total > 0 && ((double) up / total) >= 0.60;
    }

    private boolean lowerHighStructure(List<Candle> candles, int lookback) {
        if (candles == null || candles.size() < lookback + 2) return false;
        int start = Math.max(1, candles.size() - lookback);
        int down = 0;
        int total = 0;
        for (int i = start; i < candles.size(); i++) {
            if (candles.get(i).getHigh() < candles.get(i - 1).getHigh()) down++;
            total++;
        }
        return total > 0 && ((double) down / total) >= 0.60;
    }

    private boolean lowerLowAcceleration(List<Candle> candles, int lookback) {
        if (candles == null || candles.size() < lookback + 2) return false;
        int start = Math.max(1, candles.size() - lookback);
        int lower = 0;
        for (int i = start; i < candles.size(); i++) {
            if (candles.get(i).getLow() < candles.get(i - 1).getLow()) lower++;
        }
        return ((double) lower / lookback) >= 0.65;
    }

    private boolean failedBreakout(List<Candle> candles) {
        if (candles == null || candles.size() < 8) return false;
        Candle last = candles.get(candles.size() - 1);
        double priorHigh = 0.0;
        for (int i = Math.max(0, candles.size() - 8); i < candles.size() - 1; i++) {
            priorHigh = Math.max(priorHigh, candles.get(i).getHigh());
        }
        return last.getHigh() > priorHigh && last.getClose() < priorHigh;
    }

    private boolean isFailedBreakdown(List<Candle> candles) {
        if (candles == null || candles.size() < 8) return false;
        Candle last = candles.get(candles.size() - 1);
        double priorLow = Double.MAX_VALUE;
        for (int i = Math.max(0, candles.size() - 8); i < candles.size() - 1; i++) {
            priorLow = Math.min(priorLow, candles.get(i).getLow());
        }
        return last.getLow() < priorLow && last.getClose() > priorLow;
    }

    private boolean isSpring(List<Candle> candles, Map<String, Object> metrics) {
        return isFailedBreakdown(candles)
                || metricBoolean(metrics, "spring")
                || metricBoolean(metrics, "liquidity_grab_low");
    }

    private boolean isFailedSpring(List<Candle> candles, Map<String, Object> metrics) {
        if (!isSpring(candles, metrics) || candles == null || candles.size() < 2) return false;
        Candle last = candles.get(candles.size() - 1);
        Candle prev = candles.get(candles.size() - 2);
        return last.getClose() < prev.getLow();
    }

    @Data
    private static class MarketContext {
        private List<Candle> candles;
        private Map<String, Object> metrics;

        private double price;
        private double vwap;
        private double adx;
        private double rsi;
        private double dma20;
        private double dma50;
        private double dma200;
        private double atr14;
        private double vol;
        private double range20;
        private double coilStrength;
        private double volumeDryUp;

        private boolean breakout;
        private boolean hammer;
        private boolean engulf;
        private boolean nearSupport;
        private boolean dipReclaim;
        private boolean macdCross;
        private boolean bullStack;
        private boolean bearStack;
        private boolean priceAboveVwap;
        private boolean priceBelowVwap;
        private boolean tightCoil;
        private boolean preBreakoutCompression;
        private boolean coiledBreakoutSetup;

        private double compressionRange;
        private double tightCompression;
        private double adxTrend;
        private double strongTrendAdx;
        private double rsiBull;
        private double rsiBear;
        private double exhaustionHigh;
        private double exhaustionLow;
        private double strongVol;

        static MarketContext from(AppRuntimeConfig config,
                                  YamlConfigService yamlConfigService,
                                  List<Candle> candles,
                                  Map<String, Object> metrics) {

            Map<String, Object> root = asMap(config.getSubstages());
            Map<String, Object> thresholds = asMap(root.get("stage_thresholds"));

            MarketContext c = new MarketContext();
            c.candles = candles == null ? List.of() : candles;
            c.metrics = metrics == null ? Map.of() : metrics;

            c.price = metricNumber(c.metrics, "current_price");
            c.vwap = metricNumber(c.metrics, "vwap");
            c.adx = metricNumber(c.metrics, "adx");
            c.rsi = metricNumber(c.metrics, "rsi");
            c.dma20 = metricNumber(c.metrics, "dma20");
            c.dma50 = metricNumber(c.metrics, "dma50");
            c.dma200 = metricNumber(c.metrics, "dma200");
            c.atr14 = metricNumber(c.metrics, "atr14");
            c.vol = metricNumber(c.metrics, "vol_surge_ratio");

            c.breakout = metricBoolean(c.metrics, "breakout");
            c.hammer = metricBoolean(c.metrics, "hammer");
            c.engulf = metricBoolean(c.metrics, "bullish_engulfing");
            c.nearSupport = metricBoolean(c.metrics, "near_support");
            c.dipReclaim = metricBoolean(c.metrics, "dipreclaim") || metricBoolean(c.metrics, "dip_reclaim");
            c.macdCross = metricBoolean(c.metrics, "macd_cross");

            c.compressionRange = num(thresholds.get("compression_range_max_pct"), 0.08);
            c.tightCompression = num(thresholds.get("tight_compression_range_max_pct"), 0.04);
            c.adxTrend = num(thresholds.get("adx_trend_min"), 18.0);
            c.strongTrendAdx = num(thresholds.get("adx_strong_trend_min"), 25.0);
            c.rsiBull = num(thresholds.get("rsi_bull_min"), 55.0);
            c.rsiBear = num(thresholds.get("rsi_bear_max"), 45.0);
            c.exhaustionHigh = num(thresholds.get("rsi_exhaustion_high"), 72.0);
            c.exhaustionLow = num(thresholds.get("rsi_exhaustion_low"), 28.0);
            c.strongVol = num(thresholds.get("strong_volume_surge_min"), 1.60);

            c.range20 = rangePct(c.candles, 20);
            c.volumeDryUp = volumeDryUpRatio(c.candles);
            c.coilStrength = coilStrengthScore(c.range20, c.compressionRange, c.tightCompression, c.volumeDryUp);
            c.tightCoil = c.range20 > 0.0 && c.range20 <= c.tightCompression;
            c.preBreakoutCompression = c.range20 > 0.0 && c.range20 <= c.compressionRange;
            c.coiledBreakoutSetup = c.preBreakoutCompression && c.volumeDryUp >= 1.20;

            c.bullStack = c.price > c.dma20 && c.dma20 > c.dma50 && c.dma50 > c.dma200;
            c.bearStack = c.price < c.dma20 && c.dma20 < c.dma50 && c.dma50 < c.dma200;
            c.priceAboveVwap = c.vwap > 0 && c.price >= c.vwap;
            c.priceBelowVwap = c.vwap > 0 && c.price <= c.vwap;

            return c;
        }
    }

    @Value
    @Builder
    private static class SelectedChild {
        String parentSubstage;
        String childSubstage;
        String qualifiedKey;
        double childScore;
        String reason;
    }

    @Value
    @Builder
    private static class ChildConflictProfile {
        double bullishScore;
        double bearishScore;
        double neutralScore;
        double contradictoryScore;
        double contradictionPenalty;
        double bestChildConfidence;
        Map<String, Double> rankedChildConfidenceMap;
        String conflictSummary;
    }

    @Value
    @Builder
    public static class StageDecision {
        String marketStage;
        String marketSubstage;
        String childSubstage;

        double stageConfidence;
        double substageConfidence;
        double childSubstageConfidence;

        double stageScore;
        double substageScore;
        double childSubstageScore;

        Map<String, Double> stageScoreMap;
        Map<String, Double> substageScoreMap;
        Map<String, Double> childSubstageScoreMap;
        Map<String, Double> allChildSubstageScoreMap;
        Map<String, Double> adjustedAllChildSubstageScoreMap;
        String childScoringVersion;
        Map<String, Double> rankedSubstageScoreMap;
        Map<String, Double> rankedAllChildSubstageScoreMap;
        String top3Substages;
        String top3ChildSubstages;
        String selectedChildQualifiedKey;
        String childPromotionReason;
        double bullishChildScore;
        double bearishChildScore;
        double neutralChildScore;
        double contradictoryChildScore;
        double childContradictionPenalty;
        double bestChildConfidence;
        Map<String, Double> rankedChildConfidenceMap;
        String childConflictSummary;

        int evaluatedStageCount;
        int evaluatedSubstageCount;
        int evaluatedChildSubstageCount;

        String stageLabel;
        String stageDirectionalBias;
        String stageDescription;

        String substageBias;
        String substageDirectionalBias;
        String entryStyle;
        String setupMaturity;
        String riskProfile;
        String preferredTrigger;
        String substageDescription;

        String childFamily;
        String childActionBias;
        String childRiskProfile;
        String childDescription;


        public static StageDecision unknown() {
            return StageDecision.builder()
                    .marketStage("UNKNOWN")
                    .marketSubstage("UNKNOWN")
                    .childSubstage("DEFAULT")
                    .stageConfidence(0.0)
                    .substageConfidence(0.0)
                    .childSubstageConfidence(0.0)
                    .stageScore(0.0)
                    .substageScore(0.0)
                    .childSubstageScore(0.0)
                    .stageScoreMap(Map.of())
                    .substageScoreMap(Map.of())
                    .childSubstageScoreMap(Map.of())
                    .allChildSubstageScoreMap(Map.of())
                    .adjustedAllChildSubstageScoreMap(Map.of())
                    .childScoringVersion("CHILD_SCORING_ENGINE_V2")
                    .rankedSubstageScoreMap(Map.of())
                    .rankedAllChildSubstageScoreMap(Map.of())
                    .top3Substages("")
                    .top3ChildSubstages("")
                    .selectedChildQualifiedKey("UNKNOWN.DEFAULT")
                    .childPromotionReason("UNKNOWN")
                    .bullishChildScore(0.0)
                    .bearishChildScore(0.0)
                    .neutralChildScore(0.0)
                    .contradictoryChildScore(0.0)
                    .childContradictionPenalty(0.0)
                    .bestChildConfidence(0.0)
                    .rankedChildConfidenceMap(Map.of())
                    .childConflictSummary("UNKNOWN")
                    .evaluatedStageCount(0)
                    .evaluatedSubstageCount(0)
                    .evaluatedChildSubstageCount(0)
                    .stageLabel("")
                    .stageDirectionalBias("")
                    .stageDescription("")
                    .substageBias("")
                    .substageDirectionalBias("")
                    .entryStyle("")
                    .setupMaturity("")
                    .riskProfile("")
                    .preferredTrigger("")
                    .substageDescription("")
                    .childFamily("DEFAULT")
                    .childActionBias("WAIT")
                    .childRiskProfile("UNKNOWN")
                    .childDescription("No stage decision available")
                    .build();
        }

    }
}
