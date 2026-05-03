package com.trading.ml;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TrainingFeatureExportService {

    @Value("${ml.training-export-file:python/model_service/training/raw_feature_history.csv}")
    private String exportFile;

    @Value("${ml.training-export-symbol-dir:python/model_service/training/by_symbol}")
    private String symbolDir;

    private static final List<String> HARD_REQUIRED_COLUMNS = List.of(
            "symbol",
            "date",
            "current_price"
    );

    private static final List<String> ML_FEATURE_COLUMNS = List.of(
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
            "institutional_score"
    );

    private static final List<String> OPTIONAL_COLUMNS = List.of(
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
            "institutional_flow_score"
    );

    private static final List<String> EXPORT_COLUMNS;

    static {
        List<String> cols = new ArrayList<>();
        cols.addAll(HARD_REQUIRED_COLUMNS);
        cols.addAll(ML_FEATURE_COLUMNS);
        cols.addAll(OPTIONAL_COLUMNS);
        EXPORT_COLUMNS = Collections.unmodifiableList(cols);
    }

    public void exportTrainingFeatures(Collection<Map<String, Object>> enrichedRows) {

        if (enrichedRows == null || enrichedRows.isEmpty()) {
            log.error("TRAINING_EXPORT_SKIPPED reason=NO_ROWS");
            return;
        }

        Path output = outputFile();

        log.info("TRAINING_EXPORT_START rowsReceived={} outputFile={}",
                enrichedRows.size(), output);

        int accepted = 0;
        int rejected = 0;

        List<Map<String, Object>> validRows = new ArrayList<>();

        Map<String, Integer> rejectedBySymbol = new LinkedHashMap<>();
        Map<String, Integer> missingColumnCounts = new LinkedHashMap<>();

        Set<String> duplicateCheck = new HashSet<>();

        try {
            Files.createDirectories(output.getParent());

            try (BufferedWriter writer =
                         Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {

                writer.write(String.join(",", EXPORT_COLUMNS));
                writer.newLine();

                for (Map<String, Object> rawRow : enrichedRows) {

                    Map<String, Object> row = normalizeRow(rawRow);

                    ValidationResult validation =
                            validateHardRequiredColumns(row);

                    if (!validation.isValid()) {

                        rejected++;

                        String symbol =
                                String.valueOf(safe(row, "symbol"));

                        rejectedBySymbol.merge(
                                symbol, 1, Integer::sum);

                        for (String col :
                                validation.getMissingColumns()) {

                            missingColumnCounts.merge(
                                    col, 1, Integer::sum);
                        }

                        log.warn(
                                "TRAINING_EXPORT_ROW_REJECTED symbol={} date={} reason={}",
                                safe(row, "symbol"),
                                safe(row, "date"),
                                validation.getReason()
                        );

                        continue;
                    }

                    String key =
                            safe(row, "symbol") + "|" +
                                    safe(row, "date");

                    if (!duplicateCheck.add(key)) {
                        rejected++;

                        rejectedBySymbol.merge(
                                String.valueOf(
                                        safe(row, "symbol")),
                                1,
                                Integer::sum
                        );

                        log.warn(
                                "TRAINING_EXPORT_DUPLICATE_REJECTED key={}",
                                key
                        );

                        continue;
                    }

                    List<String> softMissing =
                            softMissingColumns(row);

                    for (String col : softMissing) {
                        missingColumnCounts.merge(
                                col, 1, Integer::sum);
                    }

                    writer.write(toCsvLine(row));
                    writer.newLine();

                    accepted++;
                    validRows.add(row);

                    log.info(
                            "TRAINING_EXPORT_ROW_ACCEPTED symbol={} date={} price={}",
                            safe(row, "symbol"),
                            safe(row, "date"),
                            safe(row, "current_price")
                    );
                }
            }

            exportBySymbolFiles(validRows);

            log.info(
                    "TRAINING_EXPORT_DONE rowsReceived={} rowsAccepted={} rowsRejected={} outputFile={}",
                    enrichedRows.size(),
                    accepted,
                    rejected,
                    output
            );

            log.info(
                    "TRAINING_EXPORT_REJECTED_BY_SYMBOL={}",
                    rejectedBySymbol
            );

            log.info(
                    "TRAINING_EXPORT_MISSING_COLUMN_COUNTS={}",
                    missingColumnCounts
            );

        } catch (IOException e) {
            throw new IllegalStateException(
                    "TRAINING_EXPORT_FAILED " + output, e);
        }
    }

    private void exportBySymbolFiles(
            List<Map<String, Object>> rows) throws IOException {

        if (rows == null || rows.isEmpty()) {
            return;
        }

        Path dir =
                Path.of(symbolDir)
                        .toAbsolutePath()
                        .normalize();

        Files.createDirectories(dir);

        Map<String, List<Map<String, Object>>> grouped =
                rows.stream()
                        .collect(Collectors.groupingBy(
                                r -> String.valueOf(
                                        r.get("symbol"))));

        for (String symbol : grouped.keySet()) {

            String safeSymbol =
                    symbol.replaceAll(
                            "[^A-Za-z0-9._-]", "_");

            Path file =
                    dir.resolve(safeSymbol + ".csv");

            List<Map<String, Object>> rowsForSymbol =
                    grouped.get(symbol)
                            .stream()
                            .sorted(Comparator.comparing(
                                    r -> String.valueOf(
                                            r.get("date"))))
                            .toList();

            try (BufferedWriter writer =
                         Files.newBufferedWriter(
                                 file,
                                 StandardCharsets.UTF_8)) {

                writer.write(
                        String.join(",", EXPORT_COLUMNS));
                writer.newLine();

                for (Map<String, Object> row :
                        rowsForSymbol) {

                    writer.write(toCsvLine(row));
                    writer.newLine();
                }
            }

            log.info(
                    "SYMBOL_EXPORT_DONE symbol={} rows={} file={}",
                    symbol,
                    rowsForSymbol.size(),
                    file
            );
        }
    }

    private Map<String, Object> normalizeRow(
            Map<String, Object> input) {

        Map<String, Object> row =
                new LinkedHashMap<>();

        if (input != null) {
            row.putAll(input);
        }

        normalizeAlias(row, "symbol",
                "Symbol", "ticker", "Ticker");

        normalizeAlias(row, "date",
                "Date", "as_of_date");

        normalizeAlias(row, "current_price",
                "Current Price",
                "price",
                "close");

        normalizeAlias(row, "rsi", "RSI");
        normalizeAlias(row, "adx", "ADX");
        normalizeAlias(row, "dma20", "DMA20");
        normalizeAlias(row, "dma50", "DMA50");
        normalizeAlias(row, "dma200", "DMA200");

        if (!hasRealValue(row.get("date"))) {
            row.put("date",
                    LocalDate.now().toString());
        }

        for (String col : ML_FEATURE_COLUMNS) {
            if (!hasRealValue(row.get(col))) {
                row.put(col, defaultValueFor(col));
            }
        }

        for (String col : OPTIONAL_COLUMNS) {
            if (!hasRealValue(row.get(col))) {
                row.put(col, defaultValueFor(col));
            }
        }

        return row;
    }

    private void normalizeAlias(
            Map<String, Object> row,
            String targetKey,
            String... sourceKeys) {

        if (hasRealValue(row.get(targetKey))) {
            return;
        }

        for (String key : sourceKeys) {
            Object v = row.get(key);

            if (hasRealValue(v)) {
                row.put(targetKey, v);
                return;
            }
        }
    }

    private Object defaultValueFor(String col) {

        return switch (col) {
            case "ema_uptrend",
                 "breakout",
                 "bullish_engulfing",
                 "hammer",
                 "near_support",
                 "macd_cross" -> 0;
            default -> 0.0;
        };
    }

    private ValidationResult validateHardRequiredColumns(
            Map<String, Object> row) {

        if (row == null || row.isEmpty()) {
            return ValidationResult.invalid(
                    List.of("ROW"),
                    "row empty"
            );
        }

        List<String> missing =
                HARD_REQUIRED_COLUMNS.stream()
                        .filter(c ->
                                isMissing(row.get(c)))
                        .toList();

        if (!missing.isEmpty()) {
            return ValidationResult.invalid(
                    missing,
                    "missing required columns"
            );
        }

        double px =
                toDouble(row.get("current_price"));

        if (px <= 0) {
            return ValidationResult.invalid(
                    List.of("current_price"),
                    "price <= 0"
            );
        }

        return ValidationResult.ok();
    }

    private List<String> softMissingColumns(
            Map<String, Object> row) {

        List<String> missing =
                new ArrayList<>();

        for (String c : ML_FEATURE_COLUMNS) {
            if (isMissing(row.get(c))) {
                missing.add(c);
            }
        }

        for (String c : OPTIONAL_COLUMNS) {
            if (isMissing(row.get(c))) {
                missing.add(c);
            }
        }

        return missing;
    }

    private String toCsvLine(
            Map<String, Object> row) {

        List<String> values =
                new ArrayList<>();

        for (String col : EXPORT_COLUMNS) {
            values.add(csv(row.get(col)));
        }

        return String.join(",", values);
    }

    private Path outputFile() {
        return Path.of(exportFile)
                .toAbsolutePath()
                .normalize();
    }

    private boolean isMissing(Object value) {
        return !hasRealValue(value);
    }

    private boolean hasRealValue(Object value) {

        if (value == null) {
            return false;
        }

        if (value instanceof String s) {
            String t = s.trim();

            return !t.isEmpty()
                    && !"null".equalsIgnoreCase(t)
                    && !"nan".equalsIgnoreCase(t)
                    && !"n/a".equalsIgnoreCase(t);
        }

        if (value instanceof Double d) {
            return !d.isNaN()
                    && !d.isInfinite();
        }

        if (value instanceof Float f) {
            return !f.isNaN()
                    && !f.isInfinite();
        }

        return true;
    }

    private double toDouble(Object value) {

        if (value instanceof Number n) {
            return n.doubleValue();
        }

        try {
            return Double.parseDouble(
                    String.valueOf(value));
        } catch (Exception e) {
            return 0.0;
        }
    }

    private String csv(Object value) {

        if (value == null) {
            return "";
        }

        String text;

        if (value instanceof BigDecimal bd) {
            text =
                    bd.stripTrailingZeros()
                            .toPlainString();

        } else if (value instanceof Boolean b) {
            text = b ? "1" : "0";

        } else {
            text = String.valueOf(value);
        }

        text = text.trim();

        if (text.contains(",")
                || text.contains("\"")
                || text.contains("\n")
                || text.contains("\r")) {

            return "\"" +
                    text.replace("\"", "\"\"") +
                    "\"";
        }

        return text;
    }

    private Object safe(
            Map<String, Object> row,
            String key) {

        return row == null
                ? null
                : row.get(key);
    }

    private static final class ValidationResult {

        private final boolean valid;
        private final List<String> missingColumns;
        private final String reason;

        private ValidationResult(
                boolean valid,
                List<String> missingColumns,
                String reason) {

            this.valid = valid;
            this.missingColumns = missingColumns;
            this.reason = reason;
        }

        static ValidationResult ok() {
            return new ValidationResult(
                    true,
                    List.of(),
                    "OK"
            );
        }

        static ValidationResult invalid(
                List<String> cols,
                String reason) {

            return new ValidationResult(
                    false,
                    cols,
                    reason
            );
        }

        boolean isValid() {
            return valid;
        }

        List<String> getMissingColumns() {
            return missingColumns;
        }

        String getReason() {
            return reason;
        }
    }
}