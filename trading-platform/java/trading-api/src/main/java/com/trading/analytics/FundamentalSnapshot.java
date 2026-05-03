package com.trading.analytics;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class FundamentalSnapshot {

    private String symbol;
    private String industry;
    private Double beta;
}