package com.trading.analytics;

import com.trading.config.AppRuntimeConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;

/** Aggregates leakage-safe target labels into a simple backtest quality report. */
@Service
public class BacktestReportService {
    private static final Logger log = LogManager.getLogger(BacktestReportService.class);

    public void generate(AppRuntimeConfig config, List<Map<String, Object>> rows) {
        List<Map<String,Object>> safeRows = rows == null ? List.of() : rows;
        try {
            Path out = outputDir(config).resolve("backtest_report.xlsx");
            Files.createDirectories(out.getParent());
            try (XSSFWorkbook wb = new XSSFWorkbook(); OutputStream os = Files.newOutputStream(out)) {
                writeSummary(wb.createSheet("summary"), safeRows);
                writeGroup(wb.createSheet("by_stage"), safeRows, r -> text(r, "market_stage", "Market Stage"));
                writeGroup(wb.createSheet("by_substage"), safeRows, r -> text(r, "market_substage", "Market Sub-Stage"));
                writeGroup(wb.createSheet("by_child"), safeRows, r -> text(r, "child_substage", "Child Substage", "child_substage_key"));
                writeGroup(wb.createSheet("by_sector"), safeRows, r -> text(r, "sector", "Sector"));
                writeGroup(wb.createSheet("by_final_action"), safeRows, r -> text(r, "final_action", "Final Action"));
                wb.write(os);
            }
            log.info("Backtest report written to {}", out.toAbsolutePath());
        } catch (Exception e) {
            log.warn("Unable to write backtest report: {}", e.getMessage());
        }
    }

    private void writeSummary(XSSFSheet s, List<Map<String,Object>> rows) { writeGroup(s, rows, r -> "ALL"); }

    private void writeGroup(XSSFSheet s, List<Map<String,Object>> rows, Function<Map<String,Object>, String> groupFn) {
        String[] h = {"group", "rows", "target_hit_rate", "avg_future_gain_pct", "avg_future_drawdown_pct", "avg_days_to_target", "avg_rr", "avg_ml_probability"};
        var header = s.createRow(0); for(int i=0;i<h.length;i++) header.createCell(i).setCellValue(h[i]);
        Map<String, List<Map<String,Object>>> grouped = new TreeMap<>();
        for (Map<String,Object> r: rows) grouped.computeIfAbsent(blank(groupFn.apply(r), "UNKNOWN"), k -> new ArrayList<>()).add(r);
        int rowIdx = 1;
        for (var e: grouped.entrySet()) {
            List<Map<String,Object>> g = e.getValue();
            var row = s.createRow(rowIdx++);
            row.createCell(0).setCellValue(e.getKey());
            row.createCell(1).setCellValue(g.size());
            row.createCell(2).setCellValue(avg(g, "target_hit_before_stop_90d"));
            row.createCell(3).setCellValue(avg(g, "forward_max_gain_pct_90d"));
            row.createCell(4).setCellValue(avg(g, "forward_min_drawdown_pct_90d"));
            row.createCell(5).setCellValue(avg(g, "days_to_target_90d"));
            row.createCell(6).setCellValue(avg(g, "best_risk_reward", "Best_Risk_Reward"));
            row.createCell(7).setCellValue(avg(g, "model_probability", "ml_probability", "Model Probability"));
        }
        for(int i=0;i<h.length;i++) s.autoSizeColumn(i);
    }

    private double avg(List<Map<String,Object>> rows, String... keys) {
        double sum = 0; int n = 0;
        for (Map<String,Object> r: rows) {
            double d = firstDouble(r, keys);
            if (Double.isFinite(d)) { sum += d; n++; }
        }
        return n == 0 ? 0.0 : Math.round((sum / n) * 10000.0) / 10000.0;
    }
    private String text(Map<String,Object> r, String... keys) { for(String k:keys) if(r.get(k)!=null && !String.valueOf(r.get(k)).isBlank()) return String.valueOf(r.get(k)); return "UNKNOWN"; }
    private double firstDouble(Map<String,Object>r,String...keys){ for(String k:keys){ Object o=r.get(k); if(o==null)continue; try{return Double.parseDouble(String.valueOf(o));}catch(Exception ignored){}} return Double.NaN; }
    private String blank(String s, String fallback){ return s==null||s.isBlank()?fallback:s; }
    private Path outputDir(AppRuntimeConfig config){ String out=config==null?"outputs/predictions_summary_out_nextgen.xlsx":String.valueOf(config.getOutputFile()); Path p=Path.of(out); return p.getParent()==null?Path.of("outputs"):p.getParent(); }
}
