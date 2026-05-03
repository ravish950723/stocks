package com.trading.logic;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ChildSubstageClassificationResult {
    private String parentStage;
    private String parentSubstage;
    private String childSubstage;
    private double confidence;
    private String family;
    private String actionBias;
    private String riskProfile;
    private String description;
}
