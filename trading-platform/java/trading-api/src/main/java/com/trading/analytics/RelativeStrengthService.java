package com.trading.analytics;


import com.trading.entry.Candle;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RelativeStrengthService {

    public double calculateRelativeStrengthVsSpy(
            List<Candle> symbolCandles,
            List<Candle> spyCandles,
            int lookbackDays
    ) {

        if (symbolCandles == null || spyCandles == null) {
            return 0.0;
        }

        if (symbolCandles.size() <= lookbackDays || spyCandles.size() <= lookbackDays) {
            return 0.0;
        }

        double symbolReturn = calculateReturn(symbolCandles, lookbackDays);
        double spyReturn = calculateReturn(spyCandles, lookbackDays);

        return round(symbolReturn - spyReturn);
    }

    private double calculateReturn(List<Candle> candles, int lookbackDays) {
        int last = candles.size() - 1;
        int start = candles.size() - 1 - lookbackDays;

        double startClose = candles.get(start).getClose();
        double endClose = candles.get(last).getClose();

        if (startClose <= 0) {
            return 0.0;
        }

        return ((endClose - startClose) / startClose) * 100.0;
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}