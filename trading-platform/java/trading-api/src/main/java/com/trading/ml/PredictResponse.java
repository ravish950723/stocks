package com.trading.ml;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class PredictResponse {

    private String symbol;

    private double probability;

    @JsonProperty("rankScore")
    private double rankScore;

    private String status;

    @JsonProperty("promotionStatus")
    private String promotionStatus;

    @JsonProperty("hedgeGate")
    private boolean hedgeGate;

    @JsonProperty("schemaOk")
    private boolean schemaOk;

    @JsonProperty("missingRequired")
    private List<String> missingRequired;

    @JsonProperty("xgbLoaded")
    private boolean xgbLoaded;

    @JsonProperty("strongBuyLoaded")
    private boolean strongBuyLoaded;
}