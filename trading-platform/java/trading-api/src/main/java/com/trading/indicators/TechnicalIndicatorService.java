package com.trading.indicators;

import com.trading.config.AppRuntimeConfig;
import com.trading.config.YamlConfigService;
import com.trading.entry.Candle;
import lombok.Data;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Data
public class TechnicalIndicatorService {

    private static final Logger log = LogManager.getLogger(TechnicalIndicatorService.class);

    private final YamlConfigService yamlConfigService;

    public TechnicalIndicatorService(YamlConfigService yamlConfigService) {
        this.yamlConfigService = yamlConfigService;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> calculate(List<Candle> candles, AppRuntimeConfig config) {
        log.info("Calculating technical indicators for candles={} historyYears={}", candles == null ? 0 : candles.size(), config.getIbHistoryYears());
        Map<String, Object> result = new LinkedHashMap<>();
        if (candles == null || candles.isEmpty()) {
            return result;
        }

        Candle last = candles.get(candles.size() - 1);
        List<Double> closes = candles.stream().map(Candle::getClose).toList();
        List<Long> volumes = candles.stream().map(Candle::getVolume).toList();

        Map<String, Object> indicators = yamlConfigService.asMap(config.getIndicators().get("indicators"));
        List<Integer> emaPeriods = (List<Integer>) indicators.getOrDefault("ema", List.of(20, 50, 200));
        int rsiPeriod = YamlConfigService.intValue(yamlConfigService.asMap(indicators.get("rsi")).getOrDefault("period", 14));
        Map<String, Object> macdCfg = yamlConfigService.asMap(indicators.get("macd"));
        int fast = YamlConfigService.intValue(macdCfg.getOrDefault("fast", 12));
        int slow = YamlConfigService.intValue(macdCfg.getOrDefault("slow", 26));
        int signal = YamlConfigService.intValue(macdCfg.getOrDefault("signal", 9));
        int adxPeriod = YamlConfigService.intValue(yamlConfigService.asMap(indicators.get("adx")).getOrDefault("period", 14));

        double currentPrice = last.getClose();
        double ema20 = ema(closes, periodOrDefault(emaPeriods, 0, 20));
        double ema50 = ema(closes, periodOrDefault(emaPeriods, 1, 50));
        double ema200 = ema(closes, periodOrDefault(emaPeriods, 2, 200));
        double dma20 = sma(closes, 20);
        double dma50 = sma(closes, 50);
        double dma100 = sma(closes, 100);
        double dma150 = sma(closes, 150);
        double dma200 = sma(closes, 200);
        double rsi = rsi(closes, rsiPeriod);
        MacdValues macd = macd(closes, fast, slow, signal);
        double adx = adx(candles, adxPeriod);
        double vwap = vwap(candles);
        double avgVol20 = averageLong(volumes, 20);
        double avgVol5 = averageLong(volumes, 5);
        double volRatio = avgVol20 == 0.0 ? 0.0 : last.getVolume() / avgVol20;
        boolean breakout = closes.size() > 20 && last.getClose() > max(closes.subList(Math.max(0, closes.size() - 20), closes.size() - 1));
        boolean bullishEngulfing = isBullishEngulfing(candles);
        boolean hammer = isHammer(last);
        double atr14 = atr(candles, 14);
        double atrPct = currentPrice == 0 ? 0 : (atr14 / currentPrice) * 100.0;
        double vwapDistancePct = vwap == 0 ? 0 : ((currentPrice - vwap) / vwap) * 100.0;
        boolean macdCross = macd.macdLine() > macd.signalLine();
        boolean nearSupport = dma20 > 0 && Math.abs((currentPrice - dma20) / dma20) <= 0.02;
        boolean atBbLower = isAtLowerBollinger(closes, 20, 2.0);
        boolean dipReclaim = currentPrice > vwap && last.getLow() < vwap;
        double trendStrength = Math.min(100.0, adx * 2.0);
        double liquidityScore = Math.min(100.0, avgVol20 / 100000.0);
        double volumePressure = Math.min(100.0, volRatio * 25.0);
        double priceReversal = currentPrice > last.getOpen() ? 1.0 : 0.0;
        double gapPct = candles.size() < 2 ? 0.0 : pct(currentPrice - candles.get(candles.size() - 2).getClose(), candles.get(candles.size() - 2).getClose());
        double relStrength20 = dma20 == 0 ? 0.0 : pct(currentPrice - dma20, dma20);
        double dmaStack = (currentPrice > dma20 ? 1 : 0) + (dma20 > dma50 ? 1 : 0) + (dma50 > dma200 ? 1 : 0);
        double darvasBreakout = breakout && dma20 != 0 ? pct(currentPrice - dma20, dma20) : 0.0;
        String darvasSignal = breakout ? "BREAKOUT" : nearSupport ? "NEAR_SUPPORT" : "NONE";
        String obvTrend = obvTrend(candles);
        String rsiState = rsi >= 70 ? "OVERBOUGHT" : rsi <= 30 ? "OVERSOLD" : rsi >= 55 ? "BULLISH" : rsi <= 45 ? "BEARISH" : "NEUTRAL";
        String macdSignal = macdCross ? "BULLISH" : "BEARISH";
        String symVolRegime = volRatio >= 1.5 ? "HIGH" : volRatio >= 1.0 ? "NORMAL" : "LOW";
        String vixVolRegime = "NORMAL";
        String dataSource = "IBKR";
        String asOfDate = last.getDate().toString();
        String assetType = "EQUITY";

        result.put("current_price", round(currentPrice));
        result.put("vwap", round(vwap));
        result.put("vwap_support", round(vwap));
        result.put("vwap_distance_pct", round(vwapDistancePct));
        result.put("adx", round(adx));
        result.put("adx_strength", round(Math.min(100.0, adx * 2.0)));
        result.put("ema_uptrend", ema20 > ema50 && ema50 > ema200 ? 1.0 : 0.0);
        result.put("ema21_slope", round(emaSlope(closes, 21)));
        result.put("macd_cross", macdCross);
        result.put("macd_signal", macdSignal);
        result.put("macd_hist", round(macd.histogram()));
        result.put("rsi", round(rsi));
        result.put("rsi_state", rsiState);
        result.put("obv_trend", obvTrend);
        result.put("atr14", round(atr14));
        result.put("atr14_pct", round(atrPct));
        result.put("volume_surge", round(volRatio));
        result.put("vol_surge_ratio", round(volRatio));
        result.put("vol_today", last.getVolume());
        result.put("avg_vol_20d", Math.round(avgVol20));
        result.put("vol_trend_5d", round(avgVol5));
        result.put("volume_pressure", round(volumePressure));
        result.put("liquidity_score", round(liquidityScore));
        result.put("bid_ask_spread_pct", 0.0);
        result.put("price_reversal", round(priceReversal));
        result.put("bullish_engulfing", bullishEngulfing);
        result.put("hammer", hammer);
        result.put("breakout", breakout);
        result.put("dipreclaim", dipReclaim);
        result.put("smc_breakout", breakout && volRatio >= 1.2);
        result.put("mean_reversion", round(meanReversionScore(currentPrice, dma20, atr14)));
        result.put("trend_strength", round(trendStrength));
        result.put("darvas_breakout", round(darvasBreakout));
        result.put("darvas_signal", darvasSignal);
        result.put("near_support", nearSupport);
        result.put("at_bb_lower", atBbLower);
        result.put("sym_vol_regime", symVolRegime);
        result.put("vix_vol_regime", vixVolRegime);
        result.put("dma20", round(dma20));
        result.put("dma50", round(dma50));
        result.put("dma100", round(dma100));
        result.put("dma150", round(dma150));
        result.put("dma200", round(dma200));
        result.put("pct_from_dma20", round(pct(currentPrice - dma20, dma20)));
        result.put("pct_from_dma50", round(pct(currentPrice - dma50, dma50)));
        result.put("pct_from_dma200", round(pct(currentPrice - dma200, dma200)));
        result.put("dma_stack", round(dmaStack));
        result.put("above_dma20", currentPrice > dma20 ? 1.0 : 0.0);
        result.put("above_dma50", currentPrice > dma50 ? 1.0 : 0.0);
        result.put("gap_pct", round(gapPct));
        result.put("rel_strength_20d_vs_qqq", round(relStrength20));
        result.put("distribution_days_20d", round(distributionDays(candles, 20)));
        result.put("accumulation_days_20d", round(accumulationDays(candles, 20)));
        result.put("sector_correlation", 0.0);
        result.put("whether_the_current_dma_is_greater_than_50_dma", currentPrice > dma50 ? 1.0 : 0.0);
        result.put("whether_the_current_dma_is_greater_than_100_dma", currentPrice > dma100 ? 1.0 : 0.0);
        result.put("whether_the_current_dma_is_greater_than_150_dma", currentPrice > dma150 ? 1.0 : 0.0);
        result.put("whether_the_current_dma_is_greater_than_200", currentPrice > dma200 ? 1.0 : 0.0);
        result.put("whether_weekly_chart_has_got_higher_up_wicks_volume", false);
        result.put("how_much_high_weekly_chart_is_from_previous_lower_volume", 0.0);
        result.put("data_source", dataSource);
        result.put("asof_date", asOfDate);
        result.put("asset_type", assetType);
        log.info("Technical indicator calculation completed. currentPrice={} rsi={} adx={} vwap={}", result.get("current_price"), result.get("rsi"), result.get("adx"), result.get("vwap"));
        return result;
    }

    private int periodOrDefault(List<Integer> periods, int index, int fallback) {
        return periods != null && periods.size() > index ? periods.get(index) : fallback;
    }

    private double averageLong(List<Long> values, int period) {
        if (values.isEmpty()) return 0.0;
        int start = Math.max(0, values.size() - period);
        return values.subList(start, values.size()).stream().mapToLong(Long::longValue).average().orElse(0.0);
    }

    private double max(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
    }

    private double sma(List<Double> values, int period) {
        if (values.isEmpty()) return 0.0;
        int start = Math.max(0, values.size() - period);
        return values.subList(start, values.size()).stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    private double ema(List<Double> values, int period) {
        if (values.isEmpty()) return 0.0;
        double multiplier = 2.0 / (period + 1.0);
        double ema = values.get(0);
        for (int i = 1; i < values.size(); i++) {
            ema = ((values.get(i) - ema) * multiplier) + ema;
        }
        return ema;
    }

    private double rsi(List<Double> closes, int period) {
        if (closes.size() <= period) return 50.0;
        double gain = 0.0;
        double loss = 0.0;
        for (int i = closes.size() - period; i < closes.size(); i++) {
            double delta = closes.get(i) - closes.get(i - 1);
            if (delta >= 0) gain += delta;
            else loss -= delta;
        }
        if (loss == 0.0) return 100.0;
        double rs = (gain / period) / (loss / period);
        return 100.0 - (100.0 / (1.0 + rs));
    }

    private MacdValues macd(List<Double> closes, int fast, int slow, int signal) {
        List<Double> macdSeries = new ArrayList<>();
        List<Double> progressive = new ArrayList<>();
        for (Double close : closes) {
            progressive.add(close);
            macdSeries.add(ema(progressive, fast) - ema(progressive, slow));
        }
        double macdLine = macdSeries.isEmpty() ? 0.0 : macdSeries.get(macdSeries.size() - 1);
        double signalLine = ema(macdSeries, signal);
        return new MacdValues(macdLine, signalLine, macdLine - signalLine);
    }

    private double vwap(List<Candle> candles) {
        double pv = 0.0;
        double volume = 0.0;
        for (Candle candle : candles) {
            double typicalPrice = (candle.getHigh() + candle.getLow() + candle.getClose()) / 3.0;
            pv += typicalPrice * candle.getVolume();
            volume += candle.getVolume();
        }
        return volume == 0 ? 0.0 : pv / volume;
    }

    private double atr(List<Candle> candles, int period) {
        if (candles.size() < 2) return 0.0;
        List<Double> trs = new ArrayList<>();
        for (int i = 1; i < candles.size(); i++) {
            Candle curr = candles.get(i);
            Candle prev = candles.get(i - 1);
            double tr = Math.max(curr.getHigh() - curr.getLow(), Math.max(Math.abs(curr.getHigh() - prev.getClose()), Math.abs(curr.getLow() - prev.getClose())));
            trs.add(tr);
        }
        int start = Math.max(0, trs.size() - period);
        return trs.subList(start, trs.size()).stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    private double adx(List<Candle> candles, int period) {
        if (candles.size() <= period + 1) return 20.0;
        double sumPlusDm = 0.0;
        double sumMinusDm = 0.0;
        double sumTr = 0.0;
        for (int i = candles.size() - period; i < candles.size(); i++) {
            Candle curr = candles.get(i);
            Candle prev = candles.get(i - 1);
            double upMove = curr.getHigh() - prev.getHigh();
            double downMove = prev.getLow() - curr.getLow();
            double plusDm = upMove > downMove && upMove > 0 ? upMove : 0;
            double minusDm = downMove > upMove && downMove > 0 ? downMove : 0;
            double tr = Math.max(curr.getHigh() - curr.getLow(), Math.max(Math.abs(curr.getHigh() - prev.getClose()), Math.abs(curr.getLow() - prev.getClose())));
            sumPlusDm += plusDm;
            sumMinusDm += minusDm;
            sumTr += tr;
        }
        if (sumTr == 0.0) return 0.0;
        double plusDi = 100.0 * (sumPlusDm / sumTr);
        double minusDi = 100.0 * (sumMinusDm / sumTr);
        double diSum = plusDi + minusDi;
        if (diSum == 0.0) return 0.0;
        return Math.abs(plusDi - minusDi) / diSum * 100.0;
    }

    private double emaSlope(List<Double> closes, int period) {
        if (closes.size() < period + 1) return 0.0;
        List<Double> prev = closes.subList(0, closes.size() - 1);
        double current = ema(closes, period);
        double previous = ema(prev, period);
        return current - previous;
    }

    private String obvTrend(List<Candle> candles) {
        if (candles.size() < 3) return "NEUTRAL";
        long obv = 0;
        List<Long> series = new ArrayList<>();
        series.add(obv);
        for (int i = 1; i < candles.size(); i++) {
            Candle curr = candles.get(i);
            Candle prev = candles.get(i - 1);
            if (curr.getClose() > prev.getClose()) obv += curr.getVolume();
            else if (curr.getClose() < prev.getClose()) obv -= curr.getVolume();
            series.add(obv);
        }
        return series.get(series.size() - 1) >= series.get(Math.max(0, series.size() - 3)) ? "UP" : "DOWN";
    }

    private boolean isBullishEngulfing(List<Candle> candles) {
        if (candles.size() < 2) return false;
        Candle prev = candles.get(candles.size() - 2);
        Candle curr = candles.get(candles.size() - 1);
        return prev.getClose() < prev.getOpen() && curr.getClose() > curr.getOpen() && curr.getOpen() < prev.getClose() && curr.getClose() > prev.getOpen();
    }

    private boolean isHammer(Candle candle) {
        double body = Math.abs(candle.getClose() - candle.getOpen());
        double lowerWick = Math.min(candle.getOpen(), candle.getClose()) - candle.getLow();
        double upperWick = candle.getHigh() - Math.max(candle.getOpen(), candle.getClose());
        return lowerWick > body * 2 && upperWick <= body;
    }

    private boolean isAtLowerBollinger(List<Double> closes, int period, double stdMultiplier) {
        if (closes.size() < period) return false;
        List<Double> recent = closes.subList(closes.size() - period, closes.size());
        double mean = recent.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double variance = recent.stream().mapToDouble(v -> Math.pow(v - mean, 2)).average().orElse(0.0);
        double lower = mean - stdMultiplier * Math.sqrt(variance);
        double last = closes.get(closes.size() - 1);
        return last <= lower;
    }

    private double meanReversionScore(double currentPrice, double dma20, double atr14) {
        if (dma20 == 0 || atr14 == 0) return 0.0;
        return Math.max(0.0, Math.min(100.0, (Math.abs(dma20 - currentPrice) / atr14) * 10.0));
    }

    private double distributionDays(List<Candle> candles, int period) {
        if (candles.size() < 2) return 0.0;
        int start = Math.max(1, candles.size() - period);
        int count = 0;
        for (int i = start; i < candles.size(); i++) {
            Candle curr = candles.get(i);
            Candle prev = candles.get(i - 1);
            if (curr.getClose() < prev.getClose() && curr.getVolume() > prev.getVolume()) {
                count++;
            }
        }
        return count;
    }

    private double accumulationDays(List<Candle> candles, int period) {
        if (candles.size() < 2) return 0.0;
        int start = Math.max(1, candles.size() - period);
        int count = 0;
        for (int i = start; i < candles.size(); i++) {
            Candle curr = candles.get(i);
            Candle prev = candles.get(i - 1);
            if (curr.getClose() > prev.getClose() && curr.getVolume() > prev.getVolume()) {
                count++;
            }
        }
        return count;
    }

    private double pct(double numerator, double denominator) {
        return denominator == 0.0 ? 0.0 : (numerator / denominator) * 100.0;
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record MacdValues(double macdLine, double signalLine, double histogram) {
    }
}
