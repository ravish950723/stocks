package com.trading.logic;

import lombok.Builder;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Generates short-side setup columns only when a real bearish setup exists.
 * Otherwise columns are intentionally blank, not zero.
 */
@Slf4j
@Component
public class ShortSetupEngine {

    public ShortSetup evaluate(Map<String, Object> row) {
        String symbol = text(row, "Symbol", "symbol");
        String finalAction = text(row, "Final Action", "final_action", "FINAL_ACTION");
        String stage = text(row, "Market Stage", "stage", "MARKET_STAGE");
        String child = text(row, "Child Sub-Stage", "child_substage", "CHILD_SUBSTAGE");

        double currentPrice = num(row, "Current Price", "current_price", "currentPrice");
        double atr = num(row, "ATR", "atr", "atr14");
        double rsi = num(row, "RSI", "rsi");
        double adx = num(row, "ADX", "adx");
        double borrowFeePct = num(row, "BORROW_FEE_PCT", "borrow_fee_pct", "Borrow Fee Pct");

        boolean bearishAction = containsAny(finalAction, "SELL", "SHORT", "AVOID");
        boolean bearishStage = containsAny(stage, "MARKDOWN", "DISTRIBUTION");
        boolean bearishChild = containsAny(child, "BREAKDOWN", "DOWNTREND", "MARKDOWN", "LOWER_HIGH", "FAILED_BREAKOUT");
        boolean momentumConfirms = adx >= 18 && rsi <= 48;

        if (currentPrice <= 0 || atr <= 0 || !(bearishAction && (bearishStage || bearishChild) && momentumConfirms)) {
            log.debug("SHORT_SETUP_NONE symbol={} finalAction={} stage={} child={} price={} atr={} rsi={} adx={}",
                    symbol, finalAction, stage, child, currentPrice, atr, rsi, adx);
            return ShortSetup.blank();
        }

        double zoneHigh = round2(currentPrice + (0.25 * atr));
        double zoneLow = round2(currentPrice - (0.15 * atr));
        double invalidation = round2(zoneHigh + (1.20 * atr));
        double target1 = round2(currentPrice - (1.50 * atr));
        double target2 = round2(currentPrice - (2.50 * atr));
        double risk = invalidation - zoneHigh;
        double reward = zoneHigh - target1;
        double rr = risk > 0 ? round2(reward / risk) : 0.0;

        if (target1 <= 0 || rr < 1.25) {
            log.debug("SHORT_SETUP_REJECTED symbol={} reason=target_or_rr target1={} rr={}", symbol, target1, rr);
            return ShortSetup.blank();
        }

        return ShortSetup.builder()
                .zone("SHORT_ON_WEAK_BOUNCE")
                .zoneLow(zoneLow)
                .zoneHigh(zoneHigh)
                .invalidation(invalidation)
                .target1(target1)
                .target2(Math.max(0.01, target2))
                .riskRewardRatio(rr)
                .borrowFeePct(borrowFeePct > 0 ? round2(borrowFeePct) : null)
                .build();
    }

    public void applyToRow(Map<String, Object> row) {
        ShortSetup s = evaluate(row);
        if (s.isPresent()) {
            row.put("SHORT_ENTRY_ZONE", s.getZone());
            row.put("SHORT_ENTRY_ZONE_LOW", s.getZoneLow());
            row.put("SHORT_ENTRY_ZONE_HIGH", s.getZoneHigh());
            row.put("SHORT_INVALIDATION", s.getInvalidation());
            row.put("SHORT_TARGET_1", s.getTarget1());
            row.put("SHORT_TARGET_2", s.getTarget2());
            row.put("SHORT_RR_RATIO", s.getRiskRewardRatio());
            row.put("BORROW_FEE_PCT", s.getBorrowFeePct() == null ? "" : s.getBorrowFeePct());
        } else {
            row.put("SHORT_ENTRY_ZONE", "");
            row.put("SHORT_ENTRY_ZONE_LOW", "");
            row.put("SHORT_ENTRY_ZONE_HIGH", "");
            row.put("SHORT_INVALIDATION", "");
            row.put("SHORT_TARGET_1", "");
            row.put("SHORT_TARGET_2", "");
            row.put("SHORT_RR_RATIO", "");
            row.put("BORROW_FEE_PCT", "");
        }
    }

    @Value
    @Builder
    public static class ShortSetup {
        String zone;
        Double zoneLow;
        Double zoneHigh;
        Double invalidation;
        Double target1;
        Double target2;
        Double riskRewardRatio;
        Double borrowFeePct;

        public static ShortSetup blank() {
            return ShortSetup.builder().zone("").build();
        }

        public boolean isPresent() {
            return zone != null && !zone.isBlank() && zoneLow != null && zoneHigh != null && invalidation != null;
        }
    }

    private boolean containsAny(String value, String... needles) {
        String v = value == null ? "" : value.toUpperCase();
        for (String n : needles) if (v.contains(n)) return true;
        return false;
    }

    private String text(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            Object v = row.get(key);
            if (v != null && !String.valueOf(v).isBlank()) return String.valueOf(v).trim();
        }
        return "";
    }

    private double num(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            Object v = row.get(key);
            if (v instanceof Number n) return n.doubleValue();
            if (v != null) {
                try {
                    String s = String.valueOf(v).replace("%", "").trim();
                    if (!s.isBlank()) return Double.parseDouble(s);
                } catch (Exception ignored) {
                    // try next key
                }
            }
        }
        return 0.0;
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
