package com.trading.logic;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Calculates ETF_PROXY_GROWTH_SCORE only from real relative-strength inputs already present in the row.
 * It does not default to 0 when inputs are missing.
 */
@Slf4j
@Component
public class EtfProxyGrowthScoreEngine {

    public void applyToRow(Map<String, Object> row) {
        String symbol = text(row, "Symbol", "symbol");
        Double score = calculate(row);
        if (score == null) {
            row.put("ETF_PROXY_GROWTH_SCORE", "");
            log.debug("ETF_PROXY_GROWTH_SCORE_SKIPPED symbol={} reason=missing_relative_strength_inputs", symbol);
        } else {
            row.put("ETF_PROXY_GROWTH_SCORE", score);
            log.debug("ETF_PROXY_GROWTH_SCORE_DONE symbol={} score={}", symbol, score);
        }
    }

    public Double calculate(Map<String, Object> row) {
        double rsVsSpy = num(row, "Relative Strength vs SPY", "relative_strength_vs_spy", "RS_VS_SPY");
        double sectorRs = num(row, "Sector Relative Strength", "sector_relative_strength", "SECTOR_RS");
        double etfTrend = num(row, "Sector ETF Trend Score", "sector_etf_trend_score", "ETF_TREND_SCORE");
        double momentum = num(row, "Momentum Score", "momentum_score", "MOMENTUM_SCORE");

        boolean hasInput = nonZero(rsVsSpy) || nonZero(sectorRs) || nonZero(etfTrend) || nonZero(momentum);
        if (!hasInput) return null;

        double score = 0.0;
        score += clamp(rsVsSpy, -20, 20) * 1.5;
        score += clamp(sectorRs, -20, 20) * 1.2;
        score += clamp(etfTrend, -20, 20) * 1.0;
        score += clamp(momentum, -20, 20) * 0.8;

        // Normalize to 0..100.
        double normalized = 50.0 + score;
        return round2(clamp(normalized, 0, 100));
    }

    private boolean nonZero(double v) {
        return Math.abs(v) > 0.000001;
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
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
