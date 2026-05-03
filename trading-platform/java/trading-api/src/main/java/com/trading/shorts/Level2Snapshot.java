package com.trading.shorts;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class Level2Snapshot {

    private Double bidSize;
    private Double askSize;

    private boolean bidAbsorptionDetected;
    private boolean offerStackingDetected;
    private boolean vwapRejectionDetected;
}