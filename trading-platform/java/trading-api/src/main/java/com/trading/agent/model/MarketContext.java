package com.trading.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketContext {

    private String symbol;

    // Price / Technical
    private double lastPrice;
    private double volume;
    private double avgVolume;
    private double rsi;
    private double macd;
    private double adx;
    private double vwap;
    private double ema20;
    private double ema50;
    private double ema100;
    private double ema200;
    private double atr;

    // ML
    private double modelProbability;

    // Stage
    private String marketStage;
    private String marketSubstage;
    private double substageConfidence;

    // Flow / Strength
    private double sectorStrength;
    private double institutionalFlowScore;

    // 🔥 Alpha Vantage (NEW)
    private double sentimentScore;
    private double peRatio;
    private double eps;
    private double marketCap;
    private double revenue;
    private double analystTargetPrice;
    private boolean fundamentalsAvailable;

    @Builder.Default
    private List<String> detectedPatterns = new ArrayList<>();

    @Builder.Default
    private List<String> warnings = new ArrayList<>();
}