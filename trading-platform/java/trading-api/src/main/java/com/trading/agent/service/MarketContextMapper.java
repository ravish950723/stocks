package com.trading.agent.service;

import com.trading.agent.model.MarketContext;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Component
public class MarketContextMapper {

    public MarketContext map(String symbol, Map<String, Object> row) {

        MarketContext ctx = new MarketContext();

        ctx.setSymbol(symbol);

        // Technical
        ctx.setLastPrice(d(row, "current_price"));
        ctx.setVolume(d(row, "vol_today"));
        ctx.setAvgVolume(d(row, "avg_vol_20d"));
        ctx.setRsi(d(row, "rsi"));
        ctx.setMacd(d(row, "macd_hist"));
        ctx.setAdx(d(row, "adx"));
        ctx.setVwap(d(row, "vwap"));
        ctx.setEma20(d(row, "dma20"));
        ctx.setEma50(d(row, "dma50"));
        ctx.setEma100(d(row, "dma100"));
        ctx.setEma200(d(row, "dma200"));
        ctx.setAtr(d(row, "atr14"));

        // ML
        ctx.setModelProbability(d(row, "model_probability"));

        // Stage
        ctx.setMarketStage(s(row, "market_stage"));
        ctx.setMarketSubstage(s(row, "market_substage"));
        ctx.setSubstageConfidence(normalizePct(row.get("substage_confidence")));

        // Flow
        ctx.setSectorStrength(d(row, "sector_correlation"));
        ctx.setInstitutionalFlowScore(d(row, "institutional_score"));

        // 🔥 Alpha Vantage (FIXED + ADDED)
        ctx.setSentimentScore(d(row, "av_avg_sentiment"));
        ctx.setPeRatio(d(row, "av_pe_ratio"));
        ctx.setEps(d(row, "av_eps"));
        ctx.setMarketCap(d(row, "av_market_cap"));
        ctx.setRevenue(d(row, "av_revenue_ttm"));
        ctx.setAnalystTargetPrice(d(row, "av_analyst_target_price"));
        ctx.setFundamentalsAvailable(b(row, "av_fundamentals_available"));

        ctx.setDetectedPatterns(splitPatterns(s(row, "pattern_detected")));
        ctx.setWarnings(splitPatterns(s(row, "exit_reasons")));

        return ctx;
    }

    private double d(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }

    private boolean b(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v instanceof Boolean b ? b : false;
    }

    private String s(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private double normalizePct(Object value) {
        double d = value instanceof Number n ? n.doubleValue() : 0.0;
        return d > 1.0 ? d / 100.0 : d;
    }

    private List<String> splitPatterns(String text) {
        if (text == null || text.isBlank()) return List.of();
        return Arrays.stream(text.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }
}