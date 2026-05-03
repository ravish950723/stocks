package com.trading.analytics;

import org.springframework.stereotype.Service;

@Service
public class MomentumReasonEngine {

    public String buildReason(
            Double rsi,
            Double adx,
            Double macd,
            Double signal,
            Double price,
            Double vwap,
            Double relativeStrengthVsSpy
    ) {

        double safeRsi = safe(rsi);
        double safeAdx = safe(adx);
        double safeMacd = safe(macd);
        double safeSignal = safe(signal);
        double safePrice = safe(price);
        double safeVwap = safe(vwap);
        double safeRs = safe(relativeStrengthVsSpy);

        if (safeRsi >= 70 && safeAdx >= 25 && safeMacd > safeSignal && safePrice > safeVwap && safeRs > 0) {
            return "Strong bullish momentum: RSI elevated, ADX confirms trend, MACD bullish, price above VWAP, outperforming SPY";
        }

        if (safeRsi >= 60 && safeAdx >= 20 && safePrice > safeVwap) {
            return "Bullish continuation: RSI strong, ADX supportive, price holding above VWAP";
        }

        if (safeRsi < 45 && safeMacd < safeSignal && safePrice < safeVwap && safeRs < 0) {
            return "Bearish momentum: weak RSI, MACD bearish, price below VWAP, underperforming SPY";
        }

        if (safeAdx < 15) {
            return "Weak trend: ADX is low, momentum confirmation is insufficient";
        }

        if (safeRs < 0) {
            return "Relative weakness: symbol is underperforming SPY";
        }

        return "Neutral momentum: mixed technical confirmation";
    }

    private double safe(Double value) {
        return value == null || value.isNaN() || value.isInfinite() ? 0.0 : value;
    }
}