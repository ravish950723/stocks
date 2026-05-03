package com.trading.ml;

import lombok.*;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PredictRequest {
    private String symbol;
    private Map<String, Double> features;
}