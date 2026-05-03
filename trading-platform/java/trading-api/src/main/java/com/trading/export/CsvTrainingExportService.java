package com.trading.export;

import com.trading.config.AppRuntimeConfig;
import com.trading.entry.Candle;
import com.trading.indicators.TechnicalIndicatorService;
import com.trading.analytics.ForwardOutcomeLabelEngine;
import com.trading.logic.BusinessLogicService;
import lombok.Data;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Data
public class CsvTrainingExportService {

    private static final Logger log = LogManager.getLogger(CsvTrainingExportService.class);
    private static final int MIN_LOOKBACK = 80;

    private static final List<String> FIXED_HEADERS = List.of(
            "symbol",
            "date",
            "close",
            "current_price",
            "rsi",
            "adx",
            "atr14_pct",
            "vwap_distance_pct",
            "vol_surge_ratio",
            "ema_uptrend",
            "ema21_slope",
            "dma20",
            "dma50",
            "dma200",
            "pct_from_dma20",
            "pct_from_dma50",
            "pct_from_dma200",
            "breakout",
            "bullish_engulfing",
            "hammer",
            "near_support",
            "macd_cross",
            "regime_quality_score",
            "signal_score",
            "confidence_score",
            "institutional_score",
            "relative_strength_vs_spy",
            "gap_percent",
            "volatility_score",
            "entry_quality_score",
            "stage_confidence",
            "substage_confidence",
            "child_substage_confidence",
            "best_risk_reward",
            "days_to_peak_score",
            "eps_quality_score",
            "fundamental_boost",
            "news_sentiment_score",
            "news_positive_ratio",
            "sector_rotation_score",
            "institutional_flow_score",
            "target_hit_before_stop_90d",
            "forward_max_gain_pct_90d",
            "forward_min_drawdown_pct_90d",
            "days_to_target_90d",
            "days_to_stop_90d",
            "forward_window_observed_days",
            "first_forward_event",
            "label_quality"
    );

    private final TechnicalIndicatorService technicalIndicatorService;
    private final BusinessLogicService businessLogicService;
    private final ForwardOutcomeLabelEngine forwardOutcomeLabelEngine;

    public CsvTrainingExportService(TechnicalIndicatorService technicalIndicatorService,
                                    BusinessLogicService businessLogicService,
                                    ForwardOutcomeLabelEngine forwardOutcomeLabelEngine) {
        this.technicalIndicatorService = technicalIndicatorService;
        this.businessLogicService = businessLogicService;
        this.forwardOutcomeLabelEngine = forwardOutcomeLabelEngine;
    }

    public List<Map<String, Object>> buildHistoricalTrainingRows(AppRuntimeConfig config,
                                                                 String symbol,
                                                                 List<Candle> candles) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (candles == null || candles.size() < MIN_LOOKBACK) {
            log.warn("Skipping training export for symbol={} because candles are insufficient. candles={}",
                    symbol, candles == null ? 0 : candles.size());
            return rows;
        }

        for (int i = MIN_LOOKBACK; i <= candles.size(); i++) {
            List<Candle> window = new ArrayList<>(candles.subList(0, i));
            Candle last = window.get(window.size() - 1);

            Map<String, Object> metrics = technicalIndicatorService.calculate(window, config);
            Map<String, Object> row = businessLogicService.apply(config, symbol, window, metrics);

            row.put("symbol", symbol);
            row.put("date", last.getDate() == null ? null : last.getDate().toString());
            row.put("close", last.getClose());

            // Leakage-safe ML label: use full candle history but write the label only to historical training rows.
            forwardOutcomeLabelEngine.applyToRow(row, candles, i - 1, ForwardOutcomeLabelEngine.DEFAULT_FORWARD_DAYS);

            rows.add(selectTrainingColumns(row));
        }

        log.info("Built historical training rows for symbol={} rows={}", symbol, rows.size());
        return rows;
    }

    public void export(AppRuntimeConfig config, List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            log.warn("No training rows generated. Skipping raw feature history export.");
            return;
        }

        try {
            Path output = resolveTrainingPath(config);
            Files.createDirectories(output.getParent());

            Map<String, Map<String, Object>> deduped = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                String symbol = stringValue(row.get("symbol"));
                String date = stringValue(row.get("date"));
                if (symbol.isBlank() || date.isBlank()) {
                    continue;
                }
                deduped.put(symbol + "|" + date, row);
            }

            List<Map<String, Object>> finalRows = new ArrayList<>(deduped.values());
            List<String> headers = FIXED_HEADERS;

            try (BufferedWriter writer = Files.newBufferedWriter(
                    output,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            )) {
                writer.write(String.join(",", headers));
                writer.newLine();

                for (Map<String, Object> row : finalRows) {
                    List<String> values = new ArrayList<>(headers.size());
                    for (String header : headers) {
                        Object val = row.get(header);
                        values.add(toCsv(val == null ? "" : val));
                    }
                    writer.write(String.join(",", values));
                    writer.newLine();
                }
            }

            log.info("Training feature history rewritten to {} rows={} symbols={}",
                    output.toAbsolutePath(),
                    finalRows.size(),
                    finalRows.stream().map(r -> stringValue(r.get("symbol"))).distinct().count());

        } catch (IOException e) {
            throw new IllegalStateException("Failed to export training feature history", e);
        }
    }

    private Map<String, Object> selectTrainingColumns(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String header : FIXED_HEADERS) {
            out.put(header, row.get(header));
        }
        return out;
    }

    private Path resolveTrainingPath(AppRuntimeConfig config) {
        String root = config.getProjectRoot() == null || config.getProjectRoot().isBlank()
                ? "."
                : config.getProjectRoot();

        String relative = config.getTrainingFeatureFile() == null || config.getTrainingFeatureFile().isBlank()
                ? "python/model_service/training/raw_feature_history.csv"
                : config.getTrainingFeatureFile();

        return Path.of(root).resolve(relative).normalize();
    }

    private String toCsv(Object value) {
        if (value == null) {
            return "";
        }
        String s = String.valueOf(value).replace("\"", "\"\"");
        if (s.contains(",") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s + "\"";
        }
        return s;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}