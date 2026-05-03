package com.trading.ml;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
@JsonIgnoreProperties(ignoreUnknown = true)
public class MlPredictionResponse {
    private double probability;
    private double xgbProbability;
    private double dlProbability;
    private double ensembleProbability;

    private String confidenceBand;
    private boolean modelDrivenBuy;
    private boolean modelDrivenStrongBuy;
    private double mlEntryTarget;
    private double techFallbackScore;
    private String decisionReason;
    private boolean fallbackUsed;
    private String modelVersion;
    private String schemaVersion;
    private String status;

    // V7/V8 hedge-safe fields
    private boolean schemaCompatible;
    private boolean hedgeSafeGatingPassed;
    private boolean probabilityCapApplied;
    private String javaActionHint;
    private String promotionStatus;
    private double rankScore;
    private String rankBucket;
    @Builder.Default
    private List<String> missingRequiredFeatures = new ArrayList<>();
    @Builder.Default
    private List<String> missingOptionalFeatures = new ArrayList<>();

    public static MlPredictionResponse fallback(String reason) {
        return MlPredictionResponse.builder()
                .probability(0.0)
                .ensembleProbability(0.0)
                .confidenceBand("ML_ERROR")
                .modelDrivenBuy(false)
                .modelDrivenStrongBuy(false)
                .decisionReason(reason)
                .fallbackUsed(true)
                .modelVersion("unavailable")
                .schemaVersion("v8-java-hedge")
                .status("ERROR")
                .schemaCompatible(false)
                .hedgeSafeGatingPassed(false)
                .probabilityCapApplied(true)
                .javaActionHint("ML_UNAVAILABLE")
                .promotionStatus("SUPPORTING_SIGNAL_ONLY")
                .rankScore(0.0)
                .rankBucket("UNAVAILABLE")
                .build();
    }
}
