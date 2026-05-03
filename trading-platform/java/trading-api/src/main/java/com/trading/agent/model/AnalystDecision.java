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
public class AnalystDecision {
    private String symbol;
    private String bias;
    private String setupType;
    private double opportunityScore;
    private double confidence;
    private double candidateEntry;

    @Builder.Default
    private List<String> reasons = new ArrayList<>();

    private String thesis;
}