package com.trading.analytics;


import com.trading.entry.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
public class PerformanceAnalyticsService {

    private static final int DEFAULT_LOOKBACK_DAYS = 252;
    private static final int FORWARD_WINDOW_DAYS = 90;
    private static final double HIT_TARGET_PCT = 10.0;
    private static final double STOP_LOSS_PCT = -8.0;

    /**
     * Leakage-safe 90D performance.
     *
     * Old behavior used the most recent 90 candles and then searched all later candles for the peak.
     * For current/live rows that is backward-looking and makes 90D Hit almost always true.
     *
     * New behavior only evaluates historical entry candidates that have a full 90-day forward window.
     * Hit=true only when target is reached before stop inside the forward path.
     */
    public PerformanceResult calculate90DayPerformance(List<Candle> candles, String symbol) {
        if (candles == null || candles.size() < FORWARD_WINDOW_DAYS + 60) {
            return PerformanceResult.empty("INSUFFICIENT_HISTORY_FOR_FORWARD_90D");
        }

        List<Candle> sorted = candles.stream()
                .filter(c -> c.getDate() != null && c.getClose() > 0)
                .sorted(Comparator.comparing(Candle::getDate))
                .toList();

        if (sorted.size() < FORWARD_WINDOW_DAYS + 60) {
            return PerformanceResult.empty("INSUFFICIENT_SORTED_HISTORY_FOR_FORWARD_90D");
        }

        int latestAllowedEntry = sorted.size() - FORWARD_WINDOW_DAYS - 1;
        int earliestEntry = Math.max(30, latestAllowedEntry - DEFAULT_LOOKBACK_DAYS);
        int entryIndex = findBestHistoricalEntryIndex(sorted, earliestEntry, latestAllowedEntry);

        if (entryIndex < 0 || entryIndex + FORWARD_WINDOW_DAYS >= sorted.size()) {
            return PerformanceResult.empty("NO_HISTORICAL_ENTRY_WITH_FULL_FORWARD_WINDOW");
        }

        Candle entry = sorted.get(entryIndex);
        double entryClose = entry.getClose();
        double peakHigh = entryClose;
        int daysToPeak = nullSafePositiveDays(0);
        boolean targetHit = false;
        boolean stopHitFirst = false;
        int targetDay = -1;
        int stopDay = -1;

        for (int i = entryIndex + 1; i <= entryIndex + FORWARD_WINDOW_DAYS && i < sorted.size(); i++) {
            Candle c = sorted.get(i);
            int day = i - entryIndex;

            if (c.getHigh() > peakHigh) {
                peakHigh = c.getHigh();
                daysToPeak = day;
            }

            double highGainPct = ((c.getHigh() - entryClose) / entryClose) * 100.0;
            double lowDrawdownPct = ((c.getLow() - entryClose) / entryClose) * 100.0;

            if (targetDay < 0 && highGainPct >= HIT_TARGET_PCT) {
                targetDay = day;
            }
            if (stopDay < 0 && lowDrawdownPct <= STOP_LOSS_PCT) {
                stopDay = day;
            }
        }

        if (targetDay > 0) {
            targetHit = stopDay < 0 || targetDay <= stopDay;
        }
        stopHitFirst = stopDay > 0 && (targetDay < 0 || stopDay < targetDay);

        double gainPct = ((peakHigh - entryClose) / entryClose) * 100.0;

        log.info("PERF_90D_FORWARD symbol={} entryIndex={} entryDate={} targetHit={} stopHitFirst={} gainPct={} daysToPeak={} targetDay={} stopDay={}",
                symbol, entryIndex, entry.getDate(), targetHit, stopHitFirst, round(gainPct), daysToPeak, targetDay, stopDay);

        return PerformanceResult.builder()
                .ninetyDayHit(targetHit)
                .ninetyDayGainPct(round(gainPct))
                .daysToPeak(daysToPeak)
                .targetPct(HIT_TARGET_PCT)
                .stopLossPct(STOP_LOSS_PCT)
                .entryDate(String.valueOf(entry.getDate()))
                .targetDay(targetDay)
                .stopDay(stopDay)
                .stopHitFirst(stopHitFirst)
                .method("PATH_AWARE_TARGET_FIRST_BEFORE_STOP_FULL_FORWARD_90D")
                .build();
    }

    private int findBestHistoricalEntryIndex(List<Candle> candles, int start, int end) {
        int bestIndex = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = Math.max(30, start); i <= end; i++) {
            Candle c = candles.get(i);
            double score = entrySetupScore(candles, i);
            if (score > bestScore) {
                bestScore = score;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    private double entrySetupScore(List<Candle> candles, int i) {
        Candle c = candles.get(i);
        double close = c.getClose();
        if (close <= 0) return Double.NEGATIVE_INFINITY;

        double high20 = highestHigh(candles, i - 20, i - 1);
        double low20 = lowestLow(candles, i - 20, i - 1);
        double avgVol20 = avgVolume(candles, i - 20, i - 1);
        double ema20 = avgClose(candles, i - 20, i - 1);
        double ema50 = avgClose(candles, i - 50, i - 1);

        double rangePct = high20 > 0 ? ((high20 - low20) / high20) * 100.0 : 100.0;
        double volumeRatio = avgVol20 > 0 ? c.getVolume() / avgVol20 : 1.0;
        boolean breakout = high20 > 0 && close > high20;
        boolean pullbackSupport = ema20 > 0 && Math.abs(close - ema20) / close <= 0.035;
        boolean uptrend = ema20 > ema50 && close >= ema20;

        double score = 0.0;
        if (breakout) score += 40.0;
        if (pullbackSupport) score += 25.0;
        if (uptrend) score += 20.0;
        score += Math.min(20.0, Math.max(0.0, volumeRatio - 1.0) * 10.0);
        score += Math.max(0.0, 12.0 - rangePct);
        return score;
    }

    private double highestHigh(List<Candle> candles, int fromInclusive, int toInclusive) {
        int from = Math.max(0, fromInclusive);
        int to = Math.min(candles.size() - 1, toInclusive);
        if (from > to) return 0.0;
        return candles.subList(from, to + 1).stream().mapToDouble(Candle::getHigh).max().orElse(0.0);
    }

    private double lowestLow(List<Candle> candles, int fromInclusive, int toInclusive) {
        int from = Math.max(0, fromInclusive);
        int to = Math.min(candles.size() - 1, toInclusive);
        if (from > to) return 0.0;
        return candles.subList(from, to + 1).stream().mapToDouble(Candle::getLow).min().orElse(0.0);
    }

    private double avgClose(List<Candle> candles, int fromInclusive, int toInclusive) {
        int from = Math.max(0, fromInclusive);
        int to = Math.min(candles.size() - 1, toInclusive);
        if (from > to) return 0.0;
        return candles.subList(from, to + 1).stream().mapToDouble(Candle::getClose).average().orElse(0.0);
    }

    private double avgVolume(List<Candle> candles, int fromInclusive, int toInclusive) {
        int from = Math.max(0, fromInclusive);
        int to = Math.min(candles.size() - 1, toInclusive);
        if (from > to) return 0.0;
        return candles.subList(from, to + 1).stream().mapToDouble(Candle::getVolume).average().orElse(0.0);
    }

    private int nullSafePositiveDays(int value) {
        return Math.max(value, 0);
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    @lombok.Data
    @lombok.Builder
    public static class PerformanceResult {
        private Boolean ninetyDayHit;
        private Double ninetyDayGainPct;
        private Integer daysToPeak;
        private Double targetPct;
        private Double stopLossPct;
        private String entryDate;
        private Integer targetDay;
        private Integer stopDay;
        private Boolean stopHitFirst;
        private String method;

        public static PerformanceResult empty() {
            return empty("EMPTY");
        }

        public static PerformanceResult empty(String reason) {
            return PerformanceResult.builder()
                    .ninetyDayHit(false)
                    .ninetyDayGainPct(0.0)
                    .daysToPeak(null)
                    .targetPct(HIT_TARGET_PCT)
                    .stopLossPct(STOP_LOSS_PCT)
                    .targetDay(-1)
                    .stopDay(-1)
                    .stopHitFirst(false)
                    .method(reason)
                    .build();
        }
    }
}
