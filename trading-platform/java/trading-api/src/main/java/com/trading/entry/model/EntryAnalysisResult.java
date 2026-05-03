package com.trading.entry.model;

import com.trading.entry.model.EntrySource;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class EntryAnalysisResult {

    private String symbol;

    private Double candleEntry2w;
    private Double candleEntry4w;
    private Double candleEntry6w;
    private Double candleEntry8w;
    private Double candleEntry12w;
    private Double candleEntry18w;
    private Double candleEntry30w;

    private Double invalidationLevel;
    private Double refinedBuyPrice;
    private Double primaryEntryPrice;
    private EntrySource primaryEntrySource;
    private Double addOnDipPrice;

    // New precision-entry outputs
    private Double aggressiveEntry;
    private Double balancedEntry;
    private Double conservativeEntry;
    private Double stopLossLevel;
    private Double coilStrength;
    private Double entryConfidenceScore;
    private String entryMode;

    private Double entryQualityScore;
    private String decision;
    private String reason;
}
