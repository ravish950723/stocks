package com.trading.logic.model;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class StageClassificationResult {
    private String stage;
    private String substage;
    private double stageScore;
    private double substageScore;
    private double confidence;

    @Builder.Default
    private Map<String, Double> stageScores = new LinkedHashMap<>();

    @Builder.Default
    private Map<String, Double> substageScores = new LinkedHashMap<>();

    @Builder.Default
    private List<String> matchedSignals = new ArrayList<>();

    @Builder.Default
    private List<String> failedSignals = new ArrayList<>();
}