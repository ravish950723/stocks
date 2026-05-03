package com.trading.analytics;

import com.trading.config.AppRuntimeConfig;
import com.trading.config.ColumnDefinition;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/** Generates a QA report after every Excel export so silent scoring/mapping bugs are visible. */
@Service
public class ValidationReportService {
    private static final Logger log = LogManager.getLogger(ValidationReportService.class);

    public ValidationSummary generate(AppRuntimeConfig config, List<Map<String, Object>> rows) {
        List<CheckResult> checks = new ArrayList<>();
        List<Map<String, Object>> safeRows = rows == null ? List.of() : rows;

        checks.add(check("ROW_COUNT", safeRows.size() > 0, "rows=" + safeRows.size()));
        checks.add(check("DUPLICATE_SYMBOLS", countDuplicates(safeRows, "symbol") == 0, "duplicates=" + countDuplicates(safeRows, "symbol")));
        checks.add(check("MISSING_SYMBOLS", countBlank(safeRows, "symbol") == 0, "blank_symbols=" + countBlank(safeRows, "symbol")));
        checks.add(check("PRIMARY_ENTRY_POSITIVE", countNonPositive(safeRows, "primary_entry_price", "Primary_Entry_Price") == 0,
                "bad_rows=" + countNonPositive(safeRows, "primary_entry_price", "Primary_Entry_Price")));
        checks.add(check("REFINED_BUY_POSITIVE", countNonPositive(safeRows, "refined_buy_price", "Refined Buy Price") == 0,
                "bad_rows=" + countNonPositive(safeRows, "refined_buy_price", "Refined Buy Price")));
        checks.add(check("STOP_BELOW_ENTRY", countStopNotBelowEntry(safeRows) == 0, "bad_rows=" + countStopNotBelowEntry(safeRows)));
        checks.add(check("RISK_REWARD_DYNAMIC", constantRatio(safeRows, "best_risk_reward", "Best_Risk_Reward") < 0.85,
                "top_value_ratio=" + round(constantRatio(safeRows, "best_risk_reward", "Best_Risk_Reward"))));
        checks.add(check("ML_PROBABILITY_PRESENT", countMissingNumeric(safeRows, "model_probability", "ml_probability", "Model Probability") < safeRows.size(),
                "missing_rows=" + countMissingNumeric(safeRows, "model_probability", "ml_probability", "Model Probability")));
        checks.add(check("TARGET_LABEL_PRESENT_FOR_TRAINING", hasAnyColumn(safeRows, "target_hit_before_stop_90d"),
                "target_hit_before_stop_90d_present=" + hasAnyColumn(safeRows, "target_hit_before_stop_90d")));
        checks.add(check("UNKNOWN_STAGE_LOW", countEquals(safeRows, "market_stage", "UNKNOWN") <= Math.max(2, safeRows.size() * 0.05),
                "unknown_stage_rows=" + countEquals(safeRows, "market_stage", "UNKNOWN")));
        checks.add(check("BUY_IN_MARKDOWN", countBuyInBadStage(safeRows) == 0, "bad_rows=" + countBuyInBadStage(safeRows)));
        checks.add(check("BUY_RR_MIN", countBuyWithLowRr(safeRows, 1.30) == 0, "bad_rows=" + countBuyWithLowRr(safeRows, 1.30)));

        if (config != null && config.getColumns() != null && !config.getColumns().isEmpty()) {
            Set<String> presentKeys = safeRows.stream().flatMap(r -> r.keySet().stream()).collect(Collectors.toSet());
            long missingConfigured = config.getColumns().stream().map(ColumnDefinition::getKey).filter(k -> !presentKeys.contains(k)).count();
            checks.add(check("CONFIGURED_COLUMNS_MAPPED", missingConfigured < config.getColumns().size() * 0.20,
                    "configured_keys_without_values=" + missingConfigured));
        }

        ValidationSummary summary = ValidationSummary.from(checks);
        writeReports(config, checks, summary, safeRows);
        log.info("VALIDATION_REPORT status={} pass={} warn={} fail={}", summary.status, summary.passCount, summary.warnCount, summary.failCount);
        return summary;
    }

    private void writeReports(AppRuntimeConfig config, List<CheckResult> checks, ValidationSummary summary, List<Map<String, Object>> rows) {
        try {
            Path base = outputDir(config);
            Files.createDirectories(base);
            Path txt = base.resolve("predictions_summary_validation.txt");
            try (BufferedWriter w = Files.newBufferedWriter(txt, StandardCharsets.UTF_8)) {
                w.write("Validation Report - " + LocalDateTime.now()); w.newLine();
                w.write("Status: " + summary.status + " pass=" + summary.passCount + " warn=" + summary.warnCount + " fail=" + summary.failCount); w.newLine();
                w.write("Rows: " + rows.size()); w.newLine();
                w.newLine();
                for (CheckResult c : checks) {
                    w.write(c.status + " | " + c.name + " | " + c.message); w.newLine();
                }
            }
            Path xlsx = base.resolve("predictions_summary_validation.xlsx");
            try (XSSFWorkbook wb = new XSSFWorkbook(); OutputStream os = Files.newOutputStream(xlsx)) {
                XSSFSheet sheet = wb.createSheet("validation");
                Row h = sheet.createRow(0);
                h.createCell(0).setCellValue("status"); h.createCell(1).setCellValue("check"); h.createCell(2).setCellValue("message");
                for (int i = 0; i < checks.size(); i++) {
                    Row r = sheet.createRow(i + 1);
                    r.createCell(0).setCellValue(checks.get(i).status);
                    r.createCell(1).setCellValue(checks.get(i).name);
                    r.createCell(2).setCellValue(checks.get(i).message);
                }
                sheet.autoSizeColumn(0); sheet.autoSizeColumn(1); sheet.autoSizeColumn(2);
                wb.write(os);
            }
        } catch (Exception e) {
            log.warn("Unable to write validation report: {}", e.getMessage());
        }
    }

    private CheckResult check(String name, boolean pass, String message) {
        return new CheckResult(name, pass ? "PASS" : "FAIL", message);
    }
    private Path outputDir(AppRuntimeConfig config) {
        String out = config == null ? "outputs/predictions_summary_out_nextgen.xlsx" : String.valueOf(config.getOutputFile());
        Path p = Path.of(out);
        return p.getParent() == null ? Path.of("outputs") : p.getParent();
    }
    private boolean hasAnyColumn(List<Map<String, Object>> rows, String key) { return rows.stream().anyMatch(r -> r.containsKey(key)); }
    private int countBlank(List<Map<String, Object>> rows, String key) { int c=0; for (Map<String,Object> r:rows) if (str(r.get(key)).isBlank()) c++; return c; }
    private int countDuplicates(List<Map<String,Object>> rows, String key){ Set<String>s=new HashSet<>();int d=0; for(Map<String,Object>r:rows){String v=str(r.get(key)); if(!v.isBlank()&&!s.add(v)) d++;} return d; }
    private int countNonPositive(List<Map<String,Object>> rows, String... keys){ int c=0; for(Map<String,Object>r:rows){ if(firstDouble(r,keys)<=0) c++; } return c; }
    private int countMissingNumeric(List<Map<String,Object>> rows, String... keys){ int c=0; for(Map<String,Object>r:rows){ if(!Double.isFinite(firstDouble(r,keys))) c++; } return c; }
    private int countStopNotBelowEntry(List<Map<String,Object>> rows){ int c=0; for(Map<String,Object>r:rows){ double e=firstDouble(r,"primary_entry_price","Primary_Entry_Price","refined_buy_price"); double s=firstDouble(r,"stop_loss_level","Stop Loss Level","long_invalidation"); if(e>0&&s>0&&s>=e)c++;} return c; }
    private int countEquals(List<Map<String,Object>> rows, String key, String val){ int c=0; for(Map<String,Object>r:rows) if(str(r.get(key)).equalsIgnoreCase(val)) c++; return c; }
    private int countBuyInBadStage(List<Map<String,Object>> rows){ int c=0; for(Map<String,Object>r:rows){ String a=str(firstObj(r,"final_action","Final Action","recommendation")); String st=str(firstObj(r,"market_stage","Market Stage")); if(a.contains("BUY") && (st.equalsIgnoreCase("MARKDOWN")||st.equalsIgnoreCase("DISTRIBUTION"))) c++; } return c; }
    private int countBuyWithLowRr(List<Map<String,Object>> rows, double minRr){ int c=0; for(Map<String,Object>r:rows){ String a=str(firstObj(r,"final_action","Final Action","recommendation")); double rr=firstDouble(r,"best_risk_reward","Best_Risk_Reward","long_rr_ratio"); if(a.contains("BUY") && rr>0 && rr<minRr)c++;} return c; }
    private double constantRatio(List<Map<String,Object>> rows, String... keys){ if(rows.isEmpty()) return 0.0; Map<String,Integer>m=new HashMap<>(); int n=0; for(Map<String,Object>r:rows){ double d=firstDouble(r,keys); if(Double.isFinite(d)&&d!=0){ String k=String.valueOf(round(d)); m.put(k,m.getOrDefault(k,0)+1); n++; }} return n==0?1.0:m.values().stream().mapToInt(i->i).max().orElse(0)/(double)n; }
    private Object firstObj(Map<String,Object>r,String...keys){ for(String k:keys) if(r.containsKey(k)&&r.get(k)!=null) return r.get(k); return null; }
    private double firstDouble(Map<String,Object>r,String...keys){ for(String k:keys){ Object o=r.get(k); if(o==null)continue; try{return Double.parseDouble(String.valueOf(o));}catch(Exception ignored){}} return Double.NaN; }
    private String str(Object o){ return o==null?"":String.valueOf(o).trim().toUpperCase(); }
    private double round(double d){ return Math.round(d*10000.0)/10000.0; }

    public record CheckResult(String name, String status, String message) {}
    public static class ValidationSummary {
        public final String status; public final long passCount; public final long warnCount; public final long failCount;
        private ValidationSummary(String status,long passCount,long warnCount,long failCount){this.status=status;this.passCount=passCount;this.warnCount=warnCount;this.failCount=failCount;}
        public static ValidationSummary from(List<CheckResult> checks){ long f=checks.stream().filter(c->"FAIL".equals(c.status)).count(); long p=checks.stream().filter(c->"PASS".equals(c.status)).count(); return new ValidationSummary(f==0?"PASS":"FAIL",p,0,f); }
    }
}
