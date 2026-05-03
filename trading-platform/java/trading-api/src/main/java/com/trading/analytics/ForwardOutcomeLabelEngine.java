package com.trading.analytics;

import com.trading.entry.Candle;
import lombok.Builder;
import lombok.Value;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Leakage-safe forward-outcome label generator for ML training/backtests.
 *
 * IMPORTANT:
 * - This must be used only for historical rows, never for today's live decision.
 * - The label looks forward from the row's candle index and checks whether the
 *   target is hit before the stop within a fixed forward window.
 * - Python must train on target_hit_before_stop_90d, not the display analytics
 *   column 90D Hit.
 */
@Service
public class ForwardOutcomeLabelEngine {

    private static final Logger log = LogManager.getLogger(ForwardOutcomeLabelEngine.class);
    public static final int DEFAULT_FORWARD_DAYS = 90;
    public static final double DEFAULT_TARGET_GAIN_PCT = 10.0;
    public static final double DEFAULT_STOP_LOSS_PCT = -8.0;

    public ForwardOutcomeResult evaluate(List<Candle> fullCandles,
                                         int asOfIndex,
                                         double entryPrice,
                                         double stopPrice,
                                         double targetPrice,
                                         int forwardDays) {
        if (fullCandles == null || fullCandles.isEmpty() || asOfIndex < 0 || asOfIndex >= fullCandles.size()) {
            return ForwardOutcomeResult.empty("INVALID_INPUT");
        }

        Candle asOf = fullCandles.get(asOfIndex);
        double entry = positiveOr(entryPrice, asOf.getClose());
        double stop = positiveOr(stopPrice, entry * (1.0 + DEFAULT_STOP_LOSS_PCT / 100.0));
        double target = positiveOr(targetPrice, entry * (1.0 + DEFAULT_TARGET_GAIN_PCT / 100.0));

        if (entry <= 0.0 || stop <= 0.0 || target <= 0.0 || target <= entry || stop >= entry) {
            return ForwardOutcomeResult.empty("INVALID_ENTRY_STOP_TARGET");
        }

        int start = asOfIndex + 1;
        int endExclusive = Math.min(fullCandles.size(), start + Math.max(1, forwardDays));
        if (start >= endExclusive) {
            return ForwardOutcomeResult.empty("INSUFFICIENT_FORWARD_WINDOW");
        }

        double maxGainPct = Double.NEGATIVE_INFINITY;
        double minDrawdownPct = Double.POSITIVE_INFINITY;
        int daysToTarget = 0;
        int daysToStop = 0;
        boolean targetHit = false;
        boolean stopHit = false;
        String firstEvent = "NONE";

        for (int i = start; i < endExclusive; i++) {
            Candle c = fullCandles.get(i);
            int day = i - asOfIndex;
            double highGainPct = pct(c.getHigh(), entry);
            double lowDrawdownPct = pct(c.getLow(), entry);
            maxGainPct = Math.max(maxGainPct, highGainPct);
            minDrawdownPct = Math.min(minDrawdownPct, lowDrawdownPct);

            boolean stopToday = c.getLow() <= stop;
            boolean targetToday = c.getHigh() >= target;

            // Conservative same-day handling: if both hit inside the same daily candle,
            // assume stop first because intraday ordering is unknown.
            if (stopToday) {
                stopHit = true;
                daysToStop = day;
                firstEvent = "STOP_FIRST";
                break;
            }
            if (targetToday) {
                targetHit = true;
                daysToTarget = day;
                firstEvent = "TARGET_FIRST";
                break;
            }
        }

        int label = targetHit && !stopHit ? 1 : 0;
        int observedDays = endExclusive - start;
        return ForwardOutcomeResult.builder()
                .targetHitBeforeStop90d(label)
                .futureMaxGainPct90d(finite(maxGainPct) ? maxGainPct : 0.0)
                .futureMinDrawdownPct90d(finite(minDrawdownPct) ? minDrawdownPct : 0.0)
                .daysToTarget90d(daysToTarget)
                .daysToStop90d(daysToStop)
                .forwardWindowObservedDays(observedDays)
                .firstForwardEvent(firstEvent)
                .labelQuality(observedDays >= forwardDays ? "COMPLETE" : "PARTIAL")
                .build();
    }

    public void applyToRow(Map<String, Object> row,
                           List<Candle> fullCandles,
                           int asOfIndex,
                           int forwardDays) {
        if (row == null) return;
        double entry = firstDouble(row, "primary_entry_price", "Primary_Entry_Price", "refined_buy_price", "Refined Buy Price", "close", "current_price");
        double stop = firstDouble(row, "stop_loss_level", "Stop Loss Level", "invalidation_level_entry", "long_invalidation");
        double target = firstDouble(row, "dynamic_target_1", "long_target_1", "target_1", "Target 1");
        ForwardOutcomeResult r = evaluate(fullCandles, asOfIndex, entry, stop, target, forwardDays);
        row.put("target_hit_before_stop_90d", r.getTargetHitBeforeStop90d());
        row.put("forward_max_gain_pct_90d", round(r.getFutureMaxGainPct90d()));
        row.put("forward_min_drawdown_pct_90d", round(r.getFutureMinDrawdownPct90d()));
        row.put("days_to_target_90d", r.getDaysToTarget90d());
        row.put("days_to_stop_90d", r.getDaysToStop90d());
        row.put("forward_window_observed_days", r.getForwardWindowObservedDays());
        row.put("first_forward_event", r.getFirstForwardEvent());
        row.put("label_quality", r.getLabelQuality());
        log.debug("FORWARD_LABEL symbol={} date={} label={} firstEvent={} maxGain={} minDrawdown={}",
                row.get("symbol"), row.get("date"), r.getTargetHitBeforeStop90d(), r.getFirstForwardEvent(),
                r.getFutureMaxGainPct90d(), r.getFutureMinDrawdownPct90d());
    }

    private double firstDouble(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            Object value = row.get(key);
            if (value == null) continue;
            try {
                double d = Double.parseDouble(String.valueOf(value));
                if (Double.isFinite(d) && d != 0.0) return d;
            } catch (Exception ignored) {}
        }
        return 0.0;
    }

    private double positiveOr(double value, double fallback) {
        return Double.isFinite(value) && value > 0.0 ? value : fallback;
    }

    private double pct(double price, double entry) {
        return entry <= 0.0 ? 0.0 : ((price - entry) / entry) * 100.0;
    }

    private boolean finite(double v) {
        return Double.isFinite(v) && v != Double.NEGATIVE_INFINITY && v != Double.POSITIVE_INFINITY;
    }

    private double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    @Value
    @Builder
    public static class ForwardOutcomeResult {
        int targetHitBeforeStop90d;
        double futureMaxGainPct90d;
        double futureMinDrawdownPct90d;
        int daysToTarget90d;
        int daysToStop90d;
        int forwardWindowObservedDays;
        String firstForwardEvent;
        String labelQuality;

        public static ForwardOutcomeResult empty(String reason) {
            return ForwardOutcomeResult.builder()
                    .targetHitBeforeStop90d(0)
                    .futureMaxGainPct90d(0.0)
                    .futureMinDrawdownPct90d(0.0)
                    .daysToTarget90d(0)
                    .daysToStop90d(0)
                    .forwardWindowObservedDays(0)
                    .firstForwardEvent(reason)
                    .labelQuality("UNUSABLE")
                    .build();
        }
    }
}
