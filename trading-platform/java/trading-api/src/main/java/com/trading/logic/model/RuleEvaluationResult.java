package com.trading.logic.model;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class RuleEvaluationResult {
    private double score;
    private double maxPossibleScore;
    private double normalizedScore;

    @Builder.Default
    private List<String> matchedSignals = new ArrayList<>();

    @Builder.Default
    private List<String> failedSignals = new ArrayList<>();

    @Builder.Default
    private List<String> penalties = new ArrayList<>();
}