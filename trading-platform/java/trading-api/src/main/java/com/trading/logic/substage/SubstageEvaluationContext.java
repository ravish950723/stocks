package com.trading.logic.substage;


import com.trading.entry.Candle;
import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Map;

@Getter
@Builder
public class SubstageEvaluationContext {

    private final String stage;
    private final String substage;
    private final List<Candle> candles;
    private final Map<String, Object> metrics;

    private final double price;
    private final double vwap;
    private final double adx;
    private final double rsi;
    private final double dma20;
    private final double dma50;
    private final double dma200;
    private final double atr14;
    private final double volSurgeRatio;

    private final double compressionRangeMaxPct;
    private final double tightCompressionRangeMaxPct;
    private final double adxTrendMin;
    private final double adxStrongTrendMin;
    private final double rsiBullMin;
    private final double rsiBearMax;
    private final double rsiExhaustionHigh;
    private final double rsiExhaustionLow;
    private final double strongVolumeSurgeMin;

    public boolean bullStack() {
        return price > 0 && dma20 > 0 && dma50 > 0 && dma200 > 0
                && price > dma20 && dma20 > dma50 && dma50 > dma200;
    }

    public boolean bearStack() {
        return price > 0 && dma20 > 0 && dma50 > 0 && dma200 > 0
                && price < dma20 && dma20 < dma50 && dma50 < dma200;
    }

    public boolean priceAboveVwap() {
        return vwap > 0 && price >= vwap;
    }

    public boolean priceBelowVwap() {
        return vwap > 0 && price <= vwap;
    }

    public double range20Pct() {
        return CandleStructure.rangePct(candles, 20);
    }
}
