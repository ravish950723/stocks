package com.trading.contracts;

import com.trading.entry.Candle;
import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
public class CachedSymbolData {
    private String symbol;
    private Instant lastUpdated;
    private List<Candle> candles = new ArrayList<>();


}
