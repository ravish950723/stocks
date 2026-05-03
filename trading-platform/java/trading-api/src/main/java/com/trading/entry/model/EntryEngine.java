package com.trading.entry.model;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Production-safe EntryEngine.
 *
 * Fixes:
 * 1. Never returns zero/negative primaryEntry/refinedBuy when current price is valid.
 * 2. Handles missing/short candle history with deterministic fallback.
 * 3. ETF/fund entries do not depend on EPS/fundamental assumptions.
 * 4. Stop-loss math is clamped below entry and above zero.
 * 5. Candle entries use support/recent-low/EMA/VWAP/ATR logic instead of only current price.
 *
 * Expected usage:
 * EntryDecision decision = entryEngine.analyze(EntryRequest.builder()
 *     .symbol(symbol)
 *     .stage(stage)
 *     .substage(substage)
 *     .childSubstage(child)
 *     .currentPrice(price)
 *     .ema9(ema9)
 *     .ema20(ema20)
 *     .ema21(ema21)
 *     .ema50(ema50)
 *     .ema200(ema200)
 *     .vwap(vwap)
 *     .rsi(rsi)
 *     .adx(adx)
 *     .atr(atr)
 *     .isEtf(isEtf)
 *     .candles(candles)
 *     .build());
 */

@Slf4j
@Component
public class EntryEngine {

    private static final double MIN_PRICE = 0.01;
    private static final int MIN_CANDLES_HARD = 20;
    private static final int MIN_CANDLES_GOOD = 50;

    public EntryDecision analyze(EntryRequest req) {
        Objects.requireNonNull(req, "EntryRequest must not be null");

        String symbol = safeText(req.getSymbol(), "UNKNOWN");
        double price = firstPositive(req.getCurrentPrice(), lastClose(req.getCandles()), req.getEma20(), req.getEma21(), req.getEma50());

        if (!positive(price)) {
            log.warn("ENTRY_ENGINE_NO_VALID_PRICE symbol={} currentPrice={} candles={} decision=NO_ENTRY",
                    symbol, req.getCurrentPrice(), size(req.getCandles()));
            return EntryDecision.noEntry(symbol, "NO_VALID_PRICE_OR_CANDLES");
        }

        List<Candle> candles = validCandles(req.getCandles());
        boolean missingCandles = candles.size() < MIN_CANDLES_HARD;
        boolean weakCandles = candles.size() < MIN_CANDLES_GOOD;
        boolean etf = req.isEtf() || isLikelyEtf(req.getSymbol(), req.getSector(), req.getIndustry());

        CandleStats stats = candles.isEmpty()
                ? CandleStats.fallback(price, req.getAtr())
                : CandleStats.from(candles, price, req.getAtr());

        String stage = norm(req.getStage());
        String substage = norm(req.getSubstage());
        String child = norm(req.getChildSubstage());

        EntryMode mode = selectMode(stage, substage, child, etf, missingCandles);

        double ema9 = positive(req.getEma9()) ? req.getEma9() : price;
        double ema20 = firstPositive(req.getEma20(), req.getEma21(), stats.sma20, price);
        double ema21 = firstPositive(req.getEma21(), req.getEma20(), stats.sma20, price);
        double ema50 = firstPositive(req.getEma50(), stats.sma50, ema20, price);
        double ema200 = firstPositive(req.getEma200(), stats.sma200, ema50, price);
        double vwap = positive(req.getVwap()) ? req.getVwap() : stats.typicalVwap;
        double atr = positive(req.getAtr()) ? req.getAtr() : stats.atr;
        double atrPct = clamp(atr / price, 0.005, etf ? 0.08 : 0.15);

        double support2w = support(candles, 10, price, atr);
        double support4w = support(candles, 20, price, atr);
        double support6w = support(candles, 30, price, atr);
        double support8w = support(candles, 40, price, atr);
        double support12w = support(candles, 60, price, atr);
        double support18w = support(candles, 90, price, atr);
        double support30w = support(candles, 150, price, atr);

        double candle2w = candleEntry(price, support2w, ema9, ema20, vwap, atr, mode, etf, 10);
        double candle4w = candleEntry(price, support4w, ema20, ema21, vwap, atr, mode, etf, 20);
        double candle6w = candleEntry(price, support6w, ema20, ema50, vwap, atr, mode, etf, 30);
        double candle8w = candleEntry(price, support8w, ema21, ema50, vwap, atr, mode, etf, 40);
        double candle12w = candleEntry(price, support12w, ema50, ema200, vwap, atr, mode, etf, 60);
        double candle18w = candleEntry(price, support18w, ema50, ema200, vwap, atr, mode, etf, 90);
        double candle30w = candleEntry(price, support30w, ema200, ema50, vwap, atr, mode, etf, 150);

        double aggressive;
        double balanced;
        double conservative;

        switch (mode) {
            case BREAKOUT -> {
                aggressive = minPositive(price, stats.recentHigh20 + 0.10 * atr);
                balanced = weightedPositive(price, candle2w, ema9, 0.50, 0.30, 0.20);
                conservative = weightedPositive(candle4w, ema20, support4w, 0.45, 0.35, 0.20);
            }
            case RETEST -> {
                aggressive = weightedPositive(price, ema9, candle2w, 0.40, 0.35, 0.25);
                balanced = weightedPositive(candle4w, ema20, vwap, 0.40, 0.35, 0.25);
                conservative = weightedPositive(candle8w, ema50, support8w, 0.40, 0.35, 0.25);
            }
            case EARLY_TREND_PULLBACK -> {
                aggressive = weightedPositive(price, ema9, candle2w, 0.50, 0.25, 0.25);
                balanced = weightedPositive(candle4w, ema20, ema21, 0.45, 0.35, 0.20);
                conservative = weightedPositive(candle8w, ema50, support8w, 0.45, 0.35, 0.20);
            }
            case TREND_CONTINUATION -> {
                aggressive = weightedPositive(price, ema9, candle2w, 0.55, 0.25, 0.20);
                balanced = weightedPositive(candle4w, ema20, vwap, 0.40, 0.35, 0.25);
                conservative = weightedPositive(candle12w, ema50, support12w, 0.45, 0.35, 0.20);
            }
            case ETF_STABLE -> {
                aggressive = weightedPositive(price, ema20, candle4w, 0.50, 0.25, 0.25);
                balanced = weightedPositive(candle8w, ema50, vwap, 0.45, 0.35, 0.20);
                conservative = weightedPositive(candle12w, ema50, support12w, 0.40, 0.35, 0.25);
            }
            case WEAK_OR_MISSING_CANDLES -> {
                aggressive = price;
                balanced = clampPositive(price * (1.0 - (etf ? 0.0075 : 0.015)), price);
                conservative = clampPositive(price * (1.0 - (etf ? 0.015 : 0.030)), price);
            }
            case AVOID_OR_MARKDOWN -> {
                aggressive = weightedPositive(candle8w, ema50, support8w, 0.45, 0.30, 0.25);
                balanced = weightedPositive(candle12w, ema50, support12w, 0.45, 0.30, 0.25);
                conservative = weightedPositive(candle18w, ema200, support18w, 0.45, 0.35, 0.20);
            }
            default -> {
                aggressive = weightedPositive(price, ema9, candle2w, 0.55, 0.25, 0.20);
                balanced = weightedPositive(candle4w, ema20, vwap, 0.45, 0.35, 0.20);
                conservative = weightedPositive(candle8w, ema50, support8w, 0.45, 0.35, 0.20);
            }
        }

        aggressive = sanitizeEntry(aggressive, price, etf, 0.12);
        balanced = sanitizeEntry(balanced, price, etf, 0.18);
        conservative = sanitizeEntry(conservative, price, etf, 0.28);

        // Primary entry: balanced by default, but never zero.
        double primaryEntry = firstPositive(balanced, aggressive, conservative, price);

        // Refined buy: better of balanced/conservative if close enough; otherwise fallback to primary.
        double refinedBuy = chooseRefinedBuy(price, primaryEntry, aggressive, balanced, conservative, mode, etf);

        double stop = computeStop(price, primaryEntry, refinedBuy, conservative, stats, atr, atrPct, stage, substage, child, etf, weakCandles);
        double invalidation = stop;

        double riskPct = ((refinedBuy - stop) / refinedBuy) * 100.0;
        double target1 = refinedBuy + Math.max(1.8 * (refinedBuy - stop), atr * (etf ? 1.8 : 2.2));
        double target2 = refinedBuy + Math.max(2.8 * (refinedBuy - stop), atr * (etf ? 2.8 : 3.5));
        double rr1 = safeDivide(target1 - refinedBuy, refinedBuy - stop);
        double rr2 = safeDivide(target2 - refinedBuy, refinedBuy - stop);

        double confidence = computeConfidence(req, stats, mode, missingCandles, weakCandles, etf);
        double quality = computeQuality(confidence, rr1, atrPct, missingCandles, weakCandles, etf, stage, substage, child);
        String decision = decision(stage, mode, quality, rr1, missingCandles);

        EntryDecision out = EntryDecision.builder()
                .symbol(symbol)
                .mode(mode.name())
                .stage(stage)
                .substage(substage)
                .childSubstage(child)
                .primaryEntry(round2(primaryEntry))
                .refinedBuyPrice(round2(refinedBuy))
                .aggressiveEntry(round2(aggressive))
                .balancedEntry(round2(balanced))
                .conservativeEntry(round2(conservative))
                .stopLoss(round2(stop))
                .invalidationLevel(round2(invalidation))
                .candleEntry2w(round2(candle2w))
                .candleEntry4w(round2(candle4w))
                .candleEntry6w(round2(candle6w))
                .candleEntry8w(round2(candle8w))
                .candleEntry12w(round2(candle12w))
                .candleEntry18w(round2(candle18w))
                .candleEntry30w(round2(candle30w))
                .target1(round2(target1))
                .target2(round2(target2))
                .riskReward1(round2(rr1))
                .riskReward2(round2(rr2))
                .riskPct(round2(riskPct))
                .confidence(round2(confidence))
                .quality(round2(quality))
                .decision(decision)
                .missingCandles(missingCandles)
                .weakCandles(weakCandles)
                .etf(etf)
                .reason(buildReason(mode, missingCandles, weakCandles, etf, primaryEntry, refinedBuy, stop))
                .build();

        validatePositive(out, price);

        log.info("ENTRY_ENGINE symbol={} stage={} substage={} child={} mode={} aggressive={} balanced={} conservative={} primary={} refined={} stop={} candles={} missingCandles={} etf={} confidence={} quality={} decision={}",
                symbol, stage, substage, child, out.getMode(), out.getAggressiveEntry(), out.getBalancedEntry(),
                out.getConservativeEntry(), out.getPrimaryEntry(), out.getRefinedBuyPrice(), out.getStopLoss(),
                candles.size(), missingCandles, etf, out.getConfidence(), out.getQuality(), out.getDecision());

        return out;
    }

    private static void validatePositive(EntryDecision out, double fallbackPrice) {
        if (!positive(out.primaryEntry)) out.primaryEntry = round2(fallbackPrice);
        if (!positive(out.refinedBuyPrice)) out.refinedBuyPrice = out.primaryEntry;
        if (!positive(out.stopLoss) || out.stopLoss >= out.refinedBuyPrice) {
            out.stopLoss = round2(Math.max(MIN_PRICE, out.refinedBuyPrice * 0.92));
            out.invalidationLevel = out.stopLoss;
        }
        if (!positive(out.candleEntry2w)) out.candleEntry2w = out.primaryEntry;
        if (!positive(out.candleEntry4w)) out.candleEntry4w = out.primaryEntry;
        if (!positive(out.candleEntry6w)) out.candleEntry6w = out.primaryEntry;
        if (!positive(out.candleEntry8w)) out.candleEntry8w = out.primaryEntry;
        if (!positive(out.candleEntry12w)) out.candleEntry12w = out.primaryEntry;
        if (!positive(out.candleEntry18w)) out.candleEntry18w = out.primaryEntry;
        if (!positive(out.candleEntry30w)) out.candleEntry30w = out.primaryEntry;
    }

    private static EntryMode selectMode(String stage, String substage, String child, boolean etf, boolean missingCandles) {
        if (missingCandles) return EntryMode.WEAK_OR_MISSING_CANDLES;
        if (contains(stage, "MARKDOWN") || contains(stage, "DISTRIBUTION")) return EntryMode.AVOID_OR_MARKDOWN;
        if (etf) return EntryMode.ETF_STABLE;
        if (contains(substage, "BREAKOUT") || contains(child, "BREAKOUT")) return EntryMode.BREAKOUT;
        if (contains(substage, "RETEST") || contains(child, "RETEST")) return EntryMode.RETEST;
        if (contains(substage, "EARLY_TREND") || contains(child, "EMA_STACK")) return EntryMode.EARLY_TREND_PULLBACK;
        if (contains(substage, "STRONG_TREND") || contains(substage, "TREND_CONTINUATION") || contains(child, "ORDERLY_CONTINUATION")) return EntryMode.TREND_CONTINUATION;
        return EntryMode.STANDARD;
    }

    private static double candleEntry(double price,
                                      double support,
                                      double fastAvg,
                                      double slowAvg,
                                      double vwap,
                                      double atr,
                                      EntryMode mode,
                                      boolean etf,
                                      int window) {
        double pullbackPct = switch (mode) {
            case BREAKOUT -> etf ? 0.004 : 0.008;
            case RETEST -> etf ? 0.008 : 0.015;
            case EARLY_TREND_PULLBACK -> etf ? 0.010 : 0.020;
            case TREND_CONTINUATION -> etf ? 0.012 : 0.025;
            case ETF_STABLE -> 0.010;
            case AVOID_OR_MARKDOWN -> etf ? 0.025 : 0.060;
            default -> etf ? 0.012 : 0.030;
        };

        double technicalAnchor = medianPositive(support, fastAvg, slowAvg, vwap, price - (atr * (etf ? 0.65 : 0.90)));
        double pullbackAnchor = price * (1.0 - pullbackPct);

        double raw;
        if (window <= 20) {
            raw = weightedPositive(pullbackAnchor, technicalAnchor, fastAvg, 0.45, 0.35, 0.20);
        } else if (window <= 60) {
            raw = weightedPositive(technicalAnchor, support, slowAvg, 0.45, 0.35, 0.20);
        } else {
            raw = weightedPositive(support, technicalAnchor, slowAvg, 0.50, 0.30, 0.20);
        }

        double maxAbovePrice = mode == EntryMode.BREAKOUT ? (etf ? 1.015 : 1.035) : 1.005;
        raw = Math.min(raw, price * maxAbovePrice);
        return clampPositive(raw, price);
    }

    private static double chooseRefinedBuy(double price,
                                           double primary,
                                           double aggressive,
                                           double balanced,
                                           double conservative,
                                           EntryMode mode,
                                           boolean etf) {
        List<Double> candidates = new ArrayList<>();
        candidates.add(primary);
        candidates.add(balanced);
        candidates.add(conservative);
        if (mode == EntryMode.BREAKOUT) candidates.add(aggressive);

        double maxDiscount = etf ? 0.08 : 0.18;
        double minAllowed = price * (1.0 - maxDiscount);
        double maxAllowed = price * (mode == EntryMode.BREAKOUT ? (etf ? 1.015 : 1.035) : 1.005);

        Optional<Double> best = candidates.stream()
                .filter(EntryEngine::positive)
                .map(v -> clamp(v, minAllowed, maxAllowed))
                .min(Comparator.comparingDouble(v -> Math.abs(v - balanced)));

        return clampPositive(best.orElse(primary), price);
    }

    private static double computeStop(double price,
                                      double primary,
                                      double refined,
                                      double conservative,
                                      CandleStats stats,
                                      double atr,
                                      double atrPct,
                                      String stage,
                                      String substage,
                                      String child,
                                      boolean etf,
                                      boolean weakCandles) {
        double baseEntry = firstPositive(refined, primary, price);
        double atrMult = etf ? 1.6 : 2.2;
        if (contains(substage, "BREAKOUT")) atrMult = etf ? 1.8 : 2.6;
        if (contains(substage, "EARLY_TREND")) atrMult = etf ? 1.5 : 2.0;
        if (contains(child, "LOW_RISK") || contains(child, "RETEST")) atrMult *= 0.90;
        if (weakCandles) atrMult *= 1.15;

        double supportStop = minPositive(stats.low20, stats.low50, conservative) - (0.35 * atr);
        double atrStop = baseEntry - (atrMult * atr);
        double pctStop = baseEntry * (1.0 - clamp(atrPct * (etf ? 2.3 : 2.8), etf ? 0.025 : 0.04, etf ? 0.10 : 0.18));

        double stop = maxPositiveBelow(baseEntry, supportStop, atrStop, pctStop);
        if (!positive(stop)) stop = baseEntry * (etf ? 0.94 : 0.91);

        // Hard safety: stop must be positive and below entry.
        double minStop = Math.max(MIN_PRICE, baseEntry * (etf ? 0.88 : 0.78));
        double maxStop = baseEntry * 0.995;
        return round2(clamp(stop, minStop, maxStop));
    }

    private static double computeConfidence(EntryRequest req, CandleStats stats, EntryMode mode, boolean missingCandles, boolean weakCandles, boolean etf) {
        double c = firstPositive(req.getStageConfidence(), req.getBestChildConfidence(), 45.0);
        c += positive(req.getBestChildConfidence()) ? Math.min(10.0, req.getBestChildConfidence()) : 0.0;
        c += mode == EntryMode.RETEST ? 4.0 : 0.0;
        c += mode == EntryMode.BREAKOUT ? 3.0 : 0.0;
        c += mode == EntryMode.EARLY_TREND_PULLBACK ? 5.0 : 0.0;
        c += etf ? 2.0 : 0.0;
        c -= missingCandles ? 20.0 : 0.0;
        c -= weakCandles ? 7.0 : 0.0;
        c += stats.trendUp ? 4.0 : -2.0;
        return clamp(c, 0.0, 100.0);
    }

    private static double computeQuality(double confidence, double rr1, double atrPct, boolean missingCandles, boolean weakCandles, boolean etf, String stage, String substage, String child) {
        double q = confidence;
        q += clamp(rr1, 0, 4) * 5.0;
        q -= atrPct > (etf ? 0.05 : 0.10) ? 5.0 : 0.0;
        q -= missingCandles ? 15.0 : 0.0;
        q -= weakCandles ? 5.0 : 0.0;
        q -= contains(stage, "MARKDOWN") ? 20.0 : 0.0;
        q -= contains(stage, "DISTRIBUTION") ? 12.0 : 0.0;
        q -= contains(substage, "EXHAUSTION") || contains(child, "EXHAUSTION") ? 6.0 : 0.0;
        return clamp(q, 0, 100);
    }

    private static String decision(String stage, EntryMode mode, double quality, double rr1, boolean missingCandles) {
        if (missingCandles) return "WATCH_DATA_WEAK";
        if (contains(stage, "MARKDOWN") || mode == EntryMode.AVOID_OR_MARKDOWN) return quality >= 60 && rr1 >= 2.0 ? "WATCH_REVERSAL" : "AVOID";
        if (quality >= 72 && rr1 >= 1.8) return "BUY";
        if (quality >= 60 && rr1 >= 1.5) return "WATCH_BUY";
        return "WATCH";
    }

    private static String buildReason(EntryMode mode, boolean missingCandles, boolean weakCandles, boolean etf, double primary, double refined, double stop) {
        List<String> r = new ArrayList<>();
        r.add("mode=" + mode);
        if (missingCandles) r.add("missing_candles_fallback");
        else if (weakCandles) r.add("weak_candle_history_penalty");
        if (etf) r.add("etf_entry_logic_no_eps_dependency");
        r.add("primary=" + round2(primary));
        r.add("refined=" + round2(refined));
        r.add("stop=" + round2(stop));
        return String.join(" | ", r);
    }

    private static double support(List<Candle> candles, int window, double fallbackPrice, double atr) {
        if (candles == null || candles.isEmpty()) {
            return clampPositive(fallbackPrice - Math.max(atr, fallbackPrice * 0.02), fallbackPrice);
        }
        List<Candle> tail = tail(candles, Math.min(window, candles.size()));
        double low = tail.stream().mapToDouble(c -> firstPositive(c.getLow(), c.getClose())).filter(EntryEngine::positive).min().orElse(0.0);
        double closeMedian = median(tail.stream().map(c -> c.getClose()).filter(EntryEngine::positive).collect(Collectors.toList()));
        double raw = weightedPositive(low, closeMedian, fallbackPrice - Math.max(atr, fallbackPrice * 0.015), 0.50, 0.30, 0.20);
        return clampPositive(raw, fallbackPrice);
    }

    private static List<Candle> validCandles(List<Candle> candles) {
        if (candles == null) return List.of();
        return candles.stream()
                .filter(Objects::nonNull)
                .filter(c -> positive(c.getClose()) || positive(c.getOpen()) || positive(c.getHigh()) || positive(c.getLow()))
                .collect(Collectors.toList());
    }

    private static double lastClose(List<Candle> candles) {
        List<Candle> valid = validCandles(candles);
        if (valid.isEmpty()) return 0.0;
        for (int i = valid.size() - 1; i >= 0; i--) {
            double c = valid.get(i).getClose();
            if (positive(c)) return c;
        }
        return 0.0;
    }

    private static List<Candle> tail(List<Candle> candles, int n) {
        if (candles == null || candles.isEmpty()) return List.of();
        int from = Math.max(0, candles.size() - n);
        return candles.subList(from, candles.size());
    }

    private static double sanitizeEntry(double entry, double price, boolean etf, double maxDiscount) {
        if (!positive(entry)) return round2(price);
        double min = price * (1.0 - (etf ? Math.min(maxDiscount, 0.12) : maxDiscount));
        double max = price * (etf ? 1.02 : 1.05);
        return round2(clamp(entry, min, max));
    }

    private static boolean isLikelyEtf(String symbol, String sector, String industry) {
        String s = norm(symbol);
        String sec = norm(sector);
        String ind = norm(industry);
        if (contains(sec, "ETF") || contains(ind, "ETF") || contains(ind, "FUND")) return true;
        return List.of("SPY", "QQQ", "IWM", "DIA", "VTI", "VOO", "VEA", "VWO", "VGK", "SLV", "GLD", "PPLT", "TLT", "HYG", "LQD", "XLF", "XLK", "XLE", "XLV", "XLI", "XLY", "XLP", "XLC", "XLU", "XLB", "ARKK", "BOTZ", "ROBO", "QTUM")
                .contains(s);
    }

    private static boolean contains(String v, String token) {
        return norm(v).contains(norm(token));
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
    }

    private static String safeText(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s.trim();
    }

    private static int size(List<?> list) {
        return list == null ? 0 : list.size();
    }

    private static boolean positive(double v) {
        return Double.isFinite(v) && v > 0.0;
    }

    private static double firstPositive(double... values) {
        for (double v : values) if (positive(v)) return v;
        return 0.0;
    }

    private static double minPositive(double... values) {
        double out = Double.POSITIVE_INFINITY;
        for (double v : values) if (positive(v)) out = Math.min(out, v);
        return out == Double.POSITIVE_INFINITY ? 0.0 : out;
    }

    private static double maxPositiveBelow(double below, double... values) {
        double out = 0.0;
        for (double v : values) {
            if (positive(v) && v < below) out = Math.max(out, v);
        }
        return out;
    }

    private static double weightedPositive(double a, double b, double c, double wa, double wb, double wc) {
        double sum = 0.0;
        double w = 0.0;
        if (positive(a)) { sum += a * wa; w += wa; }
        if (positive(b)) { sum += b * wb; w += wb; }
        if (positive(c)) { sum += c * wc; w += wc; }
        return w <= 0 ? 0.0 : sum / w;
    }

    private static double medianPositive(double... values) {
        List<Double> list = new ArrayList<>();
        for (double v : values) if (positive(v)) list.add(v);
        return median(list);
    }

    private static double median(List<Double> list) {
        List<Double> vals = list == null ? List.of() : list.stream().filter(EntryEngine::positive).sorted().collect(Collectors.toList());
        if (vals.isEmpty()) return 0.0;
        int mid = vals.size() / 2;
        if (vals.size() % 2 == 1) return vals.get(mid);
        return (vals.get(mid - 1) + vals.get(mid)) / 2.0;
    }

    private static double clampPositive(double value, double fallbackPrice) {
        return positive(value) ? value : Math.max(MIN_PRICE, fallbackPrice);
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) return min;
        return Math.max(min, Math.min(max, value));
    }

    private static double safeDivide(double n, double d) {
        if (!Double.isFinite(n) || !Double.isFinite(d) || Math.abs(d) < 1e-9) return 0.0;
        return n / d;
    }

    private static double round2(double v) {
        if (!Double.isFinite(v)) return 0.0;
        return Math.round(v * 100.0) / 100.0;
    }

    private enum EntryMode {
        STANDARD,
        BREAKOUT,
        RETEST,
        EARLY_TREND_PULLBACK,
        TREND_CONTINUATION,
        ETF_STABLE,
        WEAK_OR_MISSING_CANDLES,
        AVOID_OR_MARKDOWN
    }

    @Data
    @Builder
    public static class EntryRequest {
        private String symbol;
        private String stage;
        private String substage;
        private String childSubstage;
        private String sector;
        private String industry;
        private boolean etf;
        private double currentPrice;
        private double ema9;
        private double ema20;
        private double ema21;
        private double ema50;
        private double ema200;
        private double vwap;
        private double rsi;
        private double adx;
        private double atr;
        private double stageConfidence;
        private double bestChildConfidence;
        private List<Candle> candles;
    }

    @Data
    @Builder
    public static class EntryDecision {
        private String symbol;
        private String mode;
        private String stage;
        private String substage;
        private String childSubstage;
        private double primaryEntry;
        private double refinedBuyPrice;
        private double aggressiveEntry;
        private double balancedEntry;
        private double conservativeEntry;
        private double stopLoss;
        private double invalidationLevel;
        private double candleEntry2w;
        private double candleEntry4w;
        private double candleEntry6w;
        private double candleEntry8w;
        private double candleEntry12w;
        private double candleEntry18w;
        private double candleEntry30w;
        private double target1;
        private double target2;
        private double riskReward1;
        private double riskReward2;
        private double riskPct;
        private double confidence;
        private double quality;
        private String decision;
        private boolean missingCandles;
        private boolean weakCandles;
        private boolean etf;
        private String reason;

        public static EntryDecision noEntry(String symbol, String reason) {
            return EntryDecision.builder()
                    .symbol(symbol)
                    .mode("NO_ENTRY")
                    .stage("")
                    .substage("")
                    .childSubstage("")
                    .primaryEntry(0.0)
                    .refinedBuyPrice(0.0)
                    .aggressiveEntry(0.0)
                    .balancedEntry(0.0)
                    .conservativeEntry(0.0)
                    .stopLoss(0.0)
                    .invalidationLevel(0.0)
                    .candleEntry2w(0.0)
                    .candleEntry4w(0.0)
                    .candleEntry6w(0.0)
                    .candleEntry8w(0.0)
                    .candleEntry12w(0.0)
                    .candleEntry18w(0.0)
                    .candleEntry30w(0.0)
                    .target1(0.0)
                    .target2(0.0)
                    .riskReward1(0.0)
                    .riskReward2(0.0)
                    .riskPct(0.0)
                    .confidence(0.0)
                    .quality(0.0)
                    .decision("NO_ENTRY")
                    .missingCandles(false)
                    .weakCandles(false)
                    .etf(false)
                    .reason(reason)
                    .build();
        }
    }

    @Data
    @Builder
    public static class Candle {
        private double open;
        private double high;
        private double low;
        private double close;
        private double volume;
    }

    private static class CandleStats {
        double low20;
        double low50;
        double recentHigh20;
        double sma20;
        double sma50;
        double sma200;
        double typicalVwap;
        double atr;
        boolean trendUp;

        static CandleStats fallback(double price, double atrIn) {
            CandleStats s = new CandleStats();
            s.low20 = price * 0.97;
            s.low50 = price * 0.94;
            s.recentHigh20 = price;
            s.sma20 = price;
            s.sma50 = price;
            s.sma200 = price;
            s.typicalVwap = price;
            s.atr = positive(atrIn) ? atrIn : Math.max(price * 0.025, MIN_PRICE);
            s.trendUp = false;
            return s;
        }

        static CandleStats from(List<Candle> candles, double price, double atrIn) {
            CandleStats s = fallback(price, atrIn);
            List<Candle> v = validCandles(candles);
            if (v.isEmpty()) return s;

            s.low20 = tail(v, Math.min(20, v.size())).stream().mapToDouble(c -> firstPositive(c.getLow(), c.getClose())).filter(EntryEngine::positive).min().orElse(price * 0.97);
            s.low50 = tail(v, Math.min(50, v.size())).stream().mapToDouble(c -> firstPositive(c.getLow(), c.getClose())).filter(EntryEngine::positive).min().orElse(price * 0.94);
            s.recentHigh20 = tail(v, Math.min(20, v.size())).stream().mapToDouble(c -> firstPositive(c.getHigh(), c.getClose())).filter(EntryEngine::positive).max().orElse(price);
            s.sma20 = sma(v, 20, price);
            s.sma50 = sma(v, 50, s.sma20);
            s.sma200 = sma(v, 200, s.sma50);
            s.typicalVwap = typicalVwap(v, price);
            s.atr = positive(atrIn) ? atrIn : atr(v, price);
            s.trendUp = s.sma20 >= s.sma50 && price >= s.sma20;
            return s;
        }

        private static double sma(List<Candle> candles, int window, double fallback) {
            List<Candle> t = tail(candles, Math.min(window, candles.size()));
            double avg = t.stream().mapToDouble(Candle::getClose).filter(EntryEngine::positive).average().orElse(0.0);
            return positive(avg) ? avg : fallback;
        }

        private static double typicalVwap(List<Candle> candles, double fallback) {
            List<Candle> t = tail(candles, Math.min(30, candles.size()));
            double pv = 0.0;
            double vol = 0.0;
            for (Candle c : t) {
                double typical = (firstPositive(c.getHigh(), c.getClose()) + firstPositive(c.getLow(), c.getClose()) + firstPositive(c.getClose(), fallback)) / 3.0;
                double volume = positive(c.getVolume()) ? c.getVolume() : 1.0;
                pv += typical * volume;
                vol += volume;
            }
            return vol > 0 ? pv / vol : fallback;
        }

        private static double atr(List<Candle> candles, double price) {
            List<Candle> t = tail(candles, Math.min(14, candles.size()));
            if (t.size() < 2) return Math.max(price * 0.025, MIN_PRICE);
            double sum = 0.0;
            int count = 0;
            double prevClose = t.get(0).getClose();
            for (int i = 1; i < t.size(); i++) {
                Candle c = t.get(i);
                double high = firstPositive(c.getHigh(), c.getClose(), prevClose);
                double low = firstPositive(c.getLow(), c.getClose(), prevClose);
                double tr = Math.max(high - low, Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));
                if (positive(tr)) {
                    sum += tr;
                    count++;
                }
                if (positive(c.getClose())) prevClose = c.getClose();
            }
            double out = count > 0 ? sum / count : price * 0.025;
            return Math.max(out, price * 0.005);
        }
    }
}
