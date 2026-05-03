package com.trading.output;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Cleans live prediction Excel rows so unknown / future-known values are not exported as fake zeros.
 *
 * IMPORTANT:
 *  - 90D forward label columns are training/backtest-only columns.
 *  - Live prediction rows must leave those fields blank because future outcome is not known yet.
 *  - ML / short / ETF fields should be populated only by real engines, never by default zero placeholders.
 */
@Slf4j
@Component
public class LivePredictionOutputSanitizer {

    public static final List<String> FUTURE_KNOWN_90D_COLUMNS = List.of(
            "Target Hit Before Stop 90D",
            "Forward Max Gain Pct 90D",
            "Forward Min Drawdown Pct 90D",
            "Days To Target 90D",
            "Days To Stop 90D",
            "Label Quality"
    );

    public static final List<String> ML_ENTRY_COLUMNS = List.of(
            "ML Entry Target",
            "ML Entry Mode",
            "ML Entry Bias ATR"
    );

    public static final List<String> SHORT_COLUMNS = List.of(
            "SHORT_ENTRY_ZONE",
            "SHORT_ENTRY_ZONE_LOW",
            "SHORT_ENTRY_ZONE_HIGH",
            "SHORT_INVALIDATION",
            "SHORT_TARGET_1",
            "SHORT_TARGET_2",
            "SHORT_RR_RATIO",
            "BORROW_FEE_PCT"
    );

    public static final String ETF_PROXY_GROWTH_SCORE = "ETF_PROXY_GROWTH_SCORE";

    /**
     * Apply this only to live prediction summary rows, not historical training/backtest rows.
     */
    public Map<String, Object> sanitizeLivePredictionRow(Map<String, Object> inputRow) {
        Map<String, Object> row = new LinkedHashMap<>(Objects.requireNonNull(inputRow, "inputRow cannot be null"));

        blankFutureKnown90dColumns(row);
        blankFakeZeroMlEntryColumns(row);
        blankFakeZeroShortColumns(row);
        blankFakeZeroEtfProxyScore(row);

        return row;
    }

    public void blankFutureKnown90dColumns(Map<String, Object> row) {
        FUTURE_KNOWN_90D_COLUMNS.forEach(col -> row.put(col, ""));
    }

    /**
     * ML entry fields should be blank unless MlEntryDecisionEngine produced a real decision.
     */
    public void blankFakeZeroMlEntryColumns(Map<String, Object> row) {
        for (String col : ML_ENTRY_COLUMNS) {
            Object value = row.get(col);
            if (isZeroLike(value) || isMissing(value)) {
                row.put(col, "");
            }
        }
    }

    /**
     * Short setup fields should be blank unless ShortSetupEngine produced a real short setup.
     */
    public void blankFakeZeroShortColumns(Map<String, Object> row) {
        for (String col : SHORT_COLUMNS) {
            Object value = row.get(col);
            if (isZeroLike(value) || isMissing(value)) {
                row.put(col, "");
            }
        }
    }

    /**
     * ETF proxy should not show 0 as a fake score. Blank means not calculated / not applicable.
     */
    public void blankFakeZeroEtfProxyScore(Map<String, Object> row) {
        Object assetType = row.getOrDefault("ASSET_TYPE", row.get("Asset Type"));
        Object score = row.get(ETF_PROXY_GROWTH_SCORE);

        boolean isEtf = assetType != null && "ETF".equalsIgnoreCase(String.valueOf(assetType).trim());
        if (!isEtf && (isZeroLike(score) || isMissing(score))) {
            row.put(ETF_PROXY_GROWTH_SCORE, "");
        }
    }

    private boolean isMissing(Object value) {
        if (value == null) return true;
        String s = String.valueOf(value).trim();
        return s.isEmpty() || "null".equalsIgnoreCase(s) || "N/A".equalsIgnoreCase(s) || "NA".equalsIgnoreCase(s);
    }

    private boolean isZeroLike(Object value) {
        if (value == null) return false;
        if (value instanceof Number n) {
            return Math.abs(n.doubleValue()) < 0.0000001;
        }
        String s = String.valueOf(value).trim();
        return "0".equals(s) || "0.0".equals(s) || "0.00".equals(s);
    }
}
