package com.trading.shorts;


import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ShortSellingInput {

    private String symbol;

    private Double price;
    private Double vwap;
    private Double atr;
    private Double atrPct;

    private String stage;
    private String substage;
    private String childSubstage;
    private Double substageConfidence;
    private String marketRegime;

    private Double recentSwingHigh;
    private Double nearestSupport;
    private Double higherTimeframeSupport;

    private Double relativeVolume;
    private Double downVolumeToUpVolumeRatio;
    private Double dollarVolume;
    private Double spreadPct;
    private Double relativeStrengthVsSpy;

    private Boolean shortable;
    private Double borrowFeePct;
    private Double shortInterestPct;

    private String sentimentLabel;
    private Double newsSentimentScore;
    private String newsEventType;

    private Double confidenceScore;
    private Double signalScore;

    private Level2Snapshot level2;
}