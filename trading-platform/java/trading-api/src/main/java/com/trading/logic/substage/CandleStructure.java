package com.trading.logic.substage;


import com.trading.entry.Candle;

import java.util.List;

public final class CandleStructure {

    private CandleStructure() {
    }

    public static double rangePct(List<Candle> candles, int lookback) {
        if (candles == null || candles.size() < Math.max(2, lookback)) return 1.0;
        List<Candle> sub = candles.subList(candles.size() - lookback, candles.size());
        double high = sub.stream().mapToDouble(Candle::getHigh).max().orElse(0.0);
        double low = sub.stream().mapToDouble(Candle::getLow).min().orElse(0.0);
        double lastClose = sub.get(sub.size() - 1).getClose();
        if (lastClose <= 0.0) return 1.0;
        return Math.max(0.0, (high - low) / lastClose);
    }

    public static boolean breakout(List<Candle> candles, int lookback) {
        if (candles == null || candles.size() <= lookback) return false;
        Candle last = last(candles);
        List<Candle> prior = candles.subList(candles.size() - lookback - 1, candles.size() - 1);
        double resistance = prior.stream().mapToDouble(Candle::getHigh).max().orElse(0.0);
        return resistance > 0.0 && last.getClose() > resistance;
    }

    public static boolean breakdown(List<Candle> candles, int lookback) {
        if (candles == null || candles.size() <= lookback) return false;
        Candle last = last(candles);
        List<Candle> prior = candles.subList(candles.size() - lookback - 1, candles.size() - 1);
        double support = prior.stream().mapToDouble(Candle::getLow).min().orElse(0.0);
        return support > 0.0 && last.getClose() < support;
    }

    public static boolean falseBreakout(List<Candle> candles) {
        if (candles == null || candles.size() < 6) return false;
        Candle last = last(candles);
        Candle prev = candles.get(candles.size() - 2);
        return prev.getHigh() > recentHigh(candles, 6, 2) && last.getClose() < prev.getHigh();
    }

    public static boolean falseBreakdown(List<Candle> candles) {
        if (candles == null || candles.size() < 6) return false;
        Candle last = last(candles);
        Candle prev = candles.get(candles.size() - 2);
        return prev.getLow() < recentLow(candles, 6, 2) && last.getClose() > prev.getLow();
    }

    public static boolean spring(List<Candle> candles) {
        if (candles == null || candles.size() < 20) return false;
        Candle last = last(candles);
        double support = recentLow(candles, 20, 1);
        return support > 0.0 && last.getLow() < support && last.getClose() > support;
    }

    public static boolean liquidityGrabLow(List<Candle> candles) {
        return falseBreakdown(candles) || spring(candles);
    }

    public static boolean liquidityGrabHigh(List<Candle> candles) {
        return falseBreakout(candles);
    }

    public static boolean supportFailure(List<Candle> candles) {
        return breakdown(candles, 20);
    }

    public static boolean breakoutRetest(List<Candle> candles) {
        if (candles == null || candles.size() < 25) return false;
        Candle last = last(candles);
        double resistance = recentHigh(candles, 20, 5);
        if (resistance <= 0.0) return false;
        return last.getLow() <= resistance * 1.015 && last.getClose() >= resistance * 0.985;
    }

    public static boolean higherLow(List<Candle> candles) {
        if (candles == null || candles.size() < 20) return false;
        double first = recentLow(candles.subList(0, candles.size() - 10), Math.min(20, candles.size() - 10), 0);
        double second = recentLow(candles, 10, 0);
        return first > 0.0 && second > first;
    }

    public static boolean higherHigh(List<Candle> candles) {
        if (candles == null || candles.size() < 20) return false;
        double first = recentHigh(candles.subList(0, candles.size() - 10), Math.min(20, candles.size() - 10), 0);
        double second = recentHigh(candles, 10, 0);
        return first > 0.0 && second > first;
    }

    public static boolean lowerHigh(List<Candle> candles) {
        if (candles == null || candles.size() < 20) return false;
        double first = recentHigh(candles.subList(0, candles.size() - 10), Math.min(20, candles.size() - 10), 0);
        double second = recentHigh(candles, 10, 0);
        return first > 0.0 && second < first;
    }

    public static boolean lowerLow(List<Candle> candles) {
        if (candles == null || candles.size() < 20) return false;
        double first = recentLow(candles.subList(0, candles.size() - 10), Math.min(20, candles.size() - 10), 0);
        double second = recentLow(candles, 10, 0);
        return first > 0.0 && second < first;
    }

    public static boolean pullback(List<Candle> candles, double price, double dma20) {
        if (price <= 0.0 || dma20 <= 0.0) return false;
        double distance = Math.abs(price - dma20) / price;
        return price >= dma20 && distance <= 0.04;
    }

    public static boolean rangeExpansion(List<Candle> candles) {
        if (candles == null || candles.size() < 40) return false;
        double shortRange = rangePct(candles, 10);
        double longRange = rangePct(candles, 30);
        return longRange > 0.0 && shortRange > longRange * 1.35;
    }

    public static boolean bottoming(List<Candle> candles) {
        if (candles == null || candles.size() < 20) return false;
        Candle last = last(candles);
        return lowerLow(candles) && last.getClose() > candles.get(candles.size() - 2).getClose();
    }

    public static boolean deadCatBounce(List<Candle> candles) {
        if (candles == null || candles.size() < 8) return false;
        Candle last = last(candles);
        Candle old = candles.get(candles.size() - 8);
        Candle prev = candles.get(candles.size() - 2);
        return last.getClose() > prev.getClose() && last.getClose() < old.getClose();
    }

    public static boolean demandCandle(List<Candle> candles) {
        if (candles == null || candles.isEmpty()) return false;
        Candle c = last(candles);
        double range = Math.max(c.getHigh() - c.getLow(), 0.01);
        return c.getClose() > c.getOpen() && ((c.getClose() - c.getLow()) / range) >= 0.70;
    }

    public static boolean priceReversal(List<Candle> candles) {
        if (candles == null || candles.size() < 3) return false;
        Candle last = last(candles);
        Candle prev = candles.get(candles.size() - 2);
        return last.getClose() > last.getOpen() && prev.getClose() < prev.getOpen() && last.getClose() > prev.getOpen();
    }

    public static double recentHigh(List<Candle> candles, int lookback, int excludeLast) {
        if (candles == null || candles.isEmpty()) return 0.0;
        int end = Math.max(0, candles.size() - excludeLast);
        int start = Math.max(0, end - lookback);
        if (start >= end) return 0.0;
        return candles.subList(start, end).stream().mapToDouble(Candle::getHigh).max().orElse(0.0);
    }

    public static double recentLow(List<Candle> candles, int lookback, int excludeLast) {
        if (candles == null || candles.isEmpty()) return 0.0;
        int end = Math.max(0, candles.size() - excludeLast);
        int start = Math.max(0, end - lookback);
        if (start >= end) return 0.0;
        return candles.subList(start, end).stream().mapToDouble(Candle::getLow).min().orElse(0.0);
    }

    private static Candle last(List<Candle> candles) {
        return candles.get(candles.size() - 1);
    }
}
