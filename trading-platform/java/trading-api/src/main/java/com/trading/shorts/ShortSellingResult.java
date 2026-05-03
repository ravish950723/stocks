package com.trading.shorts;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ShortSellingResult {

    private Double shortScore;
    private String shortSetupTag;
    private String shortVerdict;

    private String shortEntryZone;
    private Double shortEntryZoneLow;
    private Double shortEntryZoneHigh;

    private Double shortInvalidation;
    private Double shortTarget1;
    private Double shortTarget2;
    private Double shortRrRatio;

    private Double shortFeasibility;
    private String shortableFlag;
    private Double borrowFeePct;

    private String spikeDriver;
    private String dropDriver;

    private Double confidenceScore;
    private Double signalScore;
    private Double institutionalScore;
    private Double volumeWeight;
    private String confidenceGrade;

    public static ShortSellingResult noShort(String reason) {
        return ShortSellingResult.builder()
                .shortScore(0.0)
                .shortSetupTag(reason)
                .shortVerdict("NO_SHORT")
                .shortFeasibility(0.0)
                .shortableFlag("UNKNOWN")
                .confidenceGrade("D")
                .build();
    }
}
