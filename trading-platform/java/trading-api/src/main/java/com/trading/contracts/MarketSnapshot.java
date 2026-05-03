package com.trading.contracts;

import com.trading.entry.Candle;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MarketSnapshot {

    private String symbol;
    private double close;
    private double ema21;
    private double ema9;
    private double ema50;
    private double vwap;
    private double adx;
    private double rsi;
    private double atr;
    private double darvasBoxTop;
    private double darvasBoxBottom;
    private double recentSwingLow;
    private double volumeSurgeRatio;
    private double substageConfidence;
    private String marketStage;
    private String marketSubStage;
    private String childSubstage;

    private List<Candle> candles;
    private String timeframe;
    private double currentPrice;
    private Instant asOf;


}