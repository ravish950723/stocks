package com.trading.ml;

import com.trading.config.AppRuntimeConfig;
import com.trading.config.YamlConfigService;
import com.trading.contracts.MarketSnapshot;
import com.trading.indicators.TechnicalIndicatorService;
import com.trading.ingestion.MarketDataAggregationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class HistoricalTrainingFeatureExportService {

    private final YamlConfigService yamlConfigService;
    private final MarketDataAggregationService marketDataAggregationService;
    private final TechnicalIndicatorService technicalIndicatorService;
    private final TrainingFeatureExportService trainingFeatureExportService;

    @Value("${ml.training-history.min-warmup-days:260}")
    private int minWarmupDays;

    @Value("${ml.training-history.max-rows-per-symbol:750}")
    private int maxRowsPerSymbol;

    public void exportHistoricalTrainingFeatures() {
        AppRuntimeConfig config = yamlConfigService.load();

        List<Map<String, Object>> allRows = new ArrayList<>();

        log.info("HIST_TRAINING_EXPORT_START symbols={} minWarmupDays={} maxRowsPerSymbol={}",
                config.getSymbols().size(), minWarmupDays, maxRowsPerSymbol);

        for (String symbol : config.getSymbols()) {
            try {
                List<Map<String, Object>> rows = buildRowsForSymbol(config, symbol);

                log.info("HIST_TRAINING_SYMBOL_DONE symbol={} rows={}", symbol, rows.size());

                allRows.addAll(rows);

            } catch (Exception e) {
                log.warn("HIST_TRAINING_SYMBOL_FAILED symbol={} error={}", symbol, e.getMessage(), e);
            }
        }

        log.info("HIST_TRAINING_EXPORT_ROWS_READY totalRows={}", allRows.size());

        trainingFeatureExportService.exportTrainingFeatures(allRows);

        log.info("HIST_TRAINING_EXPORT_DONE totalRows={}", allRows.size());
    }

    private List<Map<String, Object>> buildRowsForSymbol(AppRuntimeConfig config, String symbol) {
        MarketSnapshot snapshot = marketDataAggregationService.loadDailySnapshot(symbol, config);

        if (snapshot == null || snapshot.getCandles() == null || snapshot.getCandles().isEmpty()) {
            log.warn("HIST_TRAINING_NO_CANDLES symbol={}", symbol);
            return List.of();
        }

        var candles = snapshot.getCandles();

        if (candles.size() <= minWarmupDays) {
            log.warn("HIST_TRAINING_INSUFFICIENT_CANDLES symbol={} candles={} minWarmupDays={}",
                    symbol, candles.size(), minWarmupDays);
            return List.of();
        }

        int start = Math.max(minWarmupDays, candles.size() - maxRowsPerSymbol);
        List<Map<String, Object>> rows = new ArrayList<>();

        for (int i = start; i < candles.size(); i++) {
            var historicalSlice = candles.subList(0, i + 1);
            Object candle = candles.get(i);

            Map<String, Object> metrics = technicalIndicatorService.calculate(historicalSlice, config);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("symbol", symbol);
            row.put("date", extractDate(candle));
            row.put("current_price", extractClose(candle));

            if (metrics != null) {
                row.putAll(metrics);
            }

            normalizeAliases(row);

            if (isValidTrainingRow(row)) {
                rows.add(row);
            } else {
                log.warn("HIST_TRAINING_ROW_SKIPPED symbol={} date={} price={} keys={}",
                        symbol, row.get("date"), row.get("current_price"), row.keySet());
            }
        }

        return rows;
    }

    private void normalizeAliases(Map<String, Object> row) {
        alias(row, "current_price", "Current Price", "price", "last_price", "close", "Close");
        alias(row, "rsi", "RSI");
        alias(row, "adx", "ADX");
        alias(row, "atr14_pct", "atr14Pct", "ATR14 Percent", "ATR14 %", "atr_pct");
        alias(row, "vwap_distance_pct", "vwapDistancePct", "VWAP Distance %");
        alias(row, "vol_surge_ratio", "volSurgeRatio", "Volume Surge Ratio");
        alias(row, "ema_uptrend", "emaUptrend", "EMA Uptrend");
        alias(row, "ema21_slope", "ema21Slope", "EMA21 Slope");
        alias(row, "dma20", "DMA20", "SMA20");
        alias(row, "dma50", "DMA50", "SMA50");
        alias(row, "dma200", "DMA200", "SMA200");
        alias(row, "pct_from_dma20", "pctFromDma20", "Pct From DMA20");
        alias(row, "pct_from_dma50", "pctFromDma50", "Pct From DMA50");
        alias(row, "pct_from_dma200", "pctFromDma200", "Pct From DMA200");
        alias(row, "breakout", "Breakout", "is_breakout");
        alias(row, "bullish_engulfing", "bullishEngulfing", "Bullish Engulfing");
        alias(row, "hammer", "Hammer");
        alias(row, "near_support", "nearSupport", "Near Support");
        alias(row, "macd_cross", "macdCross", "MACD Cross");
        alias(row, "regime_quality_score", "regimeQualityScore", "Regime Quality Score");
        alias(row, "signal_score", "signalScore", "Signal Score");
        alias(row, "confidence_score", "confidenceScore", "Confidence Score");
        alias(row, "institutional_score", "institutionalScore", "Institutional Score");
    }

    private void alias(Map<String, Object> row, String target, String... sources) {
        if (hasValue(row.get(target))) return;

        for (String source : sources) {
            Object value = row.get(source);
            if (hasValue(value)) {
                row.put(target, value);
                return;
            }
        }
    }

    private boolean isValidTrainingRow(Map<String, Object> row) {
        return hasValue(row.get("symbol"))
                && hasValue(row.get("date"))
                && toDouble(row.get("current_price")) > 0.0;
    }

    private Object extractDate(Object candle) {
        Object value = invokeGetter(candle,
                "getDate",
                "getLocalDate",
                "getTimestamp",
                "getTime",
                "date"
        );

        if (value == null) {
            return LocalDate.now().toString();
        }

        String text = String.valueOf(value);

        if (text.length() >= 10) {
            return text.substring(0, 10);
        }

        return text;
    }

    private Object extractClose(Object candle) {
        Object value = invokeGetter(candle,
                "getClose",
                "getClosePrice",
                "close",
                "closePrice"
        );

        return value == null ? 0.0 : value;
    }

    private Object invokeGetter(Object target, String... methods) {
        if (target == null) return null;

        for (String methodName : methods) {
            try {
                Method method = target.getClass().getMethod(methodName);
                method.setAccessible(true);
                return method.invoke(target);
            } catch (Exception ignored) {
                // Try next method name.
            }
        }

        return null;
    }

    private boolean hasValue(Object value) {
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

    private double toDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }

        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (Exception ignored) {
                return 0.0;
            }
        }

        return 0.0;
    }
}