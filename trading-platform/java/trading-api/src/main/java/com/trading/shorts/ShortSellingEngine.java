package com.trading.shorts;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class ShortSellingEngine {

    private final ShortSellingConfig config;

    private static double safe(Double value, double fallback) {
        return value == null || value.isNaN() || value.isInfinite() ? fallback : value;
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static double boolScore(boolean value) {
        return value ? 1.0 : 0.0;
    }

    private static String norm(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value)
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();
    }

    public ShortSellingResult analyze(ShortSellingInput input) {

        if (input == null || input.getPrice() == null || input.getPrice() <= 0) {
            return ShortSellingResult.noShort("INVALID_INPUT");
        }

        double price = input.getPrice();
        double atr = safe(input.getAtr(), price * 0.03);
        double vwap = safe(input.getVwap(), price);
        double swingHigh = safe(input.getRecentSwingHigh(), price + atr);
        double support1 = safe(input.getNearestSupport(), price - atr * 2);
        double support2 = safe(input.getHigherTimeframeSupport(), price - atr * 4);

        double stageScore = stageAlignmentScore(input);
        double substageScore = substageBreakdownScore(input);
        double volumeScore = volumeScore(input);
        double l2Score = level2Score(input);
        double borrowScore = borrowScore(input);

        double shortScore =
                config.weight("stage", 0.30) * stageScore +
                        config.weight("substage", 0.20) * substageScore +
                        config.weight("volume", 0.15) * volumeScore +
                        config.weight("l2", 0.15) * l2Score +
                        config.weight("borrow", 0.10) * borrowScore +
                        config.weight("sentiment", 0.10) * bearishSentimentScore(input);

        shortScore = shortScore * regimePenalty(input) * shortSqueezePenalty(input);
        shortScore = clamp(shortScore);

        double entryLow = round(Math.max(vwap, price));
        double entryHigh = round(Math.max(swingHigh, entryLow + atr * 0.25));
        double entry = (entryLow + entryHigh) / 2.0;

        double invalidation = round(Math.max(
                swingHigh + atr * config.getAtrInvalidationBuffer(),
                entry + atr
        ));

        double target1 = round(Math.min(support1, entry - atr));
        double target2 = round(Math.min(support2, target1 - atr));

        double rrRatio = calculateRR(entry, invalidation, target1);

        double feasibility = clamp(
                0.35 * borrowScore +
                        0.25 * liquidityScore(input) +
                        0.20 * spreadScore(input) +
                        0.20 * volatilityScore(input)
        );

        return ShortSellingResult.builder()
                .shortScore(round(shortScore * 100))
                .shortSetupTag(setupTag(input, stageScore, substageScore, volumeScore, l2Score))
                .shortVerdict(verdict(input, shortScore, rrRatio, feasibility))
                .shortEntryZone(entryLow + " - " + entryHigh)
                .shortEntryZoneLow(entryLow)
                .shortEntryZoneHigh(entryHigh)
                .shortInvalidation(invalidation)
                .shortTarget1(target1)
                .shortTarget2(target2)
                .shortRrRatio(round(rrRatio))
                .shortFeasibility(round(feasibility * 100))
                .shortableFlag(shortableFlag(input))
                .borrowFeePct(round(safe(input.getBorrowFeePct(), 0)))
                .spikeDriver(spikeDriver(input))
                .dropDriver(dropDriver(input))
                .confidenceScore(round(safe(input.getConfidenceScore(), shortScore * 100)))
                .signalScore(round(safe(input.getSignalScore(), shortScore * 100)))
                .institutionalScore(round(institutionalScore(input, l2Score, volumeScore) * 100))
                .volumeWeight(round(volumeScore * 100))
                .confidenceGrade(confidenceGrade(input.getConfidenceScore(), shortScore, feasibility))
                .build();
    }

    private double stageAlignmentScore(ShortSellingInput input) {
        String stage = norm(input.getStage());

        if (stage.contains("DISTRIBUTION")) return 1.00;
        if (stage.contains("MARKDOWN")) return 0.90;
        if (stage.contains("LATE_MARKUP")) return 0.65;
        if (stage.contains("REACCUMULATION")) return 0.35;
        if (stage.contains("ACCUMULATION")) return 0.15;
        if (stage.contains("MARKUP")) return 0.10;

        return 0.40;
    }

    private double substageBreakdownScore(ShortSellingInput input) {
        String s = norm(input.getSubstage());
        String c = norm(input.getChildSubstage());

        if (s.contains("BREAKDOWN") || c.contains("BREAKDOWN")) return 1.00;
        if (s.contains("LOWER_HIGH") || c.contains("LOWER_HIGH")) return 0.90;
        if (s.contains("UPTHRUST") || c.contains("UPTHRUST")) return 0.90;
        if (s.contains("BULL_TRAP") || c.contains("BULL_TRAP")) return 0.85;
        if (s.contains("SUPPLY") || c.contains("SUPPLY")) return 0.80;
        if (s.contains("EXHAUSTION") || c.contains("EXHAUSTION")) return 0.75;
        if (s.contains("BASE") || s.contains("ACCUMULATION")) return 0.20;

        return clamp(safe(input.getSubstageConfidence(), 40) / 100.0);
    }

    private double volumeScore(ShortSellingInput input) {
        double relativeVolume = safe(input.getRelativeVolume(), 1.0);
        double downUpRatio = safe(input.getDownVolumeToUpVolumeRatio(), 1.0);

        return clamp(
                0.60 * clamp(relativeVolume / 2.5) +
                        0.40 * clamp(downUpRatio / 2.0)
        );
    }

    private double level2Score(ShortSellingInput input) {
        if (input.getLevel2() == null) return 0.50;

        Level2Snapshot l2 = input.getLevel2();

        double askBidImbalance = clamp(
                safe(l2.getAskSize(), 0) / Math.max(1.0, safe(l2.getBidSize(), 1))
        );

        return clamp(
                0.35 * clamp(askBidImbalance / 2.0) +
                        0.25 * boolScore(l2.isBidAbsorptionDetected()) +
                        0.20 * boolScore(l2.isOfferStackingDetected()) +
                        0.20 * boolScore(l2.isVwapRejectionDetected())
        );
    }

    private double borrowScore(ShortSellingInput input) {
        if (!Boolean.TRUE.equals(input.getShortable())) return 0.0;

        double fee = safe(input.getBorrowFeePct(), 0);

        if (fee >= config.getMaxBorrowFeePct()) return 0.10;
        if (fee >= config.getHighBorrowFeePct()) return 0.35;
        if (fee >= config.getMediumBorrowFeePct()) return 0.65;

        return 1.00;
    }

    private double bearishSentimentScore(ShortSellingInput input) {
        String label = norm(input.getSentimentLabel());

        if (label.contains("BEARISH")) return 1.00;
        if (label.contains("NEGATIVE")) return 0.85;
        if (label.contains("NEUTRAL")) return 0.50;
        if (label.contains("BULLISH")) return 0.15;

        return clamp(1.0 - safe(input.getNewsSentimentScore(), 0.5));
    }

    private double shortSqueezePenalty(ShortSellingInput input) {
        boolean highShortInterest = safe(input.getShortInterestPct(), 0) >= config.getHighShortInterestPct();
        boolean volumeSpike = safe(input.getRelativeVolume(), 1) >= config.getSqueezeRelativeVolumeThreshold();
        boolean positiveNews = norm(input.getSentimentLabel()).contains("BULLISH");

        if (highShortInterest && volumeSpike && positiveNews) return 0.35;
        if (highShortInterest && volumeSpike) return 0.60;

        return 1.00;
    }

    private double regimePenalty(ShortSellingInput input) {
        String regime = norm(input.getMarketRegime());

        if (regime.contains("STRONG_MARKUP")) return 0.40;
        if (regime.contains("MARKUP")) return 0.60;
        if (regime.contains("DISTRIBUTION")) return 1.00;
        if (regime.contains("MARKDOWN")) return 1.00;

        return 0.85;
    }

    private String setupTag(
            ShortSellingInput input,
            double stageScore,
            double substageScore,
            double volumeScore,
            double l2Score
    ) {
        String combined = norm(input.getSubstage()) + "_" + norm(input.getChildSubstage());

        if (combined.contains("UPTHRUST")) return "UPTHRUST";
        if (combined.contains("BULL_TRAP")) return "BULL_TRAP";
        if (combined.contains("LOWER_HIGH")) return "LOWER_HIGH_REJECTION";
        if (combined.contains("BREAKDOWN")) return "DISTRIBUTION_BREAKDOWN";
        if (l2Score >= 0.75) return "VWAP_REJECTION";
        if (volumeScore >= 0.75 && stageScore >= 0.70) return "PARABOLIC_EXHAUSTION";
        if (substageScore >= 0.75) return "SUPPLY_ZONE_REJECTION";

        return "NO_CLEAR_SHORT_SETUP";
    }

    private String verdict(ShortSellingInput input, double shortScore, double rrRatio, double feasibility) {
        if (!Boolean.TRUE.equals(input.getShortable())) return "NO_SHORT";
        if (safe(input.getBorrowFeePct(), 0) >= config.getMaxBorrowFeePct()) return "NO_SHORT";
        if (rrRatio < config.getMinRrRatio()) return "AVOID_SHORT";
        if (feasibility < config.getMinFeasibility()) return "WEAK_SHORT";

        if (shortScore >= config.getStrongShortThreshold()) return "STRONG_SHORT";
        if (shortScore >= config.getShortThreshold()) return "SHORT";
        if (shortScore >= config.getWeakShortThreshold()) return "WEAK_SHORT";

        return "NO_SHORT";
    }

    private String shortableFlag(ShortSellingInput input) {
        if (!Boolean.TRUE.equals(input.getShortable())) return "NOT_SHORTABLE";

        double fee = safe(input.getBorrowFeePct(), 0);

        if (fee >= config.getHighBorrowFeePct()) return "HARD_TO_SHORT";
        return "EASY_TO_SHORT";
    }

    private String spikeDriver(ShortSellingInput input) {
        String news = norm(input.getNewsEventType());

        if (news.contains("EARNINGS")) return "EARNINGS";
        if (news.contains("GUIDANCE")) return "GUIDANCE";
        if (news.contains("FDA")) return "REGULATORY_NEWS";

        if (safe(input.getShortInterestPct(), 0) >= config.getHighShortInterestPct()
                && safe(input.getRelativeVolume(), 1) >= 2.0) {
            return "SHORT_COVERING";
        }

        if (safe(input.getRelativeVolume(), 1) >= 3.0) {
            return "LOW_FLOAT_OR_MOMENTUM_SPIKE";
        }

        return "UNKNOWN";
    }

    private String dropDriver(ShortSellingInput input) {
        String stage = norm(input.getStage());
        String sentiment = norm(input.getSentimentLabel());

        if (stage.contains("DISTRIBUTION")) return "DISTRIBUTION";
        if (stage.contains("MARKDOWN")) return "MARKDOWN_CONTINUATION";
        if (sentiment.contains("BEARISH") || sentiment.contains("NEGATIVE")) return "NEGATIVE_SENTIMENT";
        if (safe(input.getRelativeStrengthVsSpy(), 0) < -5) return "SECTOR_OR_MARKET_UNDERPERFORMANCE";
        if (safe(input.getDownVolumeToUpVolumeRatio(), 1) > 1.5) return "INSTITUTIONAL_SELLING";

        return "UNKNOWN";
    }

    private double institutionalScore(ShortSellingInput input, double l2Score, double volumeScore) {
        double vwapControl =
                input.getPrice() != null && input.getVwap() != null && input.getPrice() < input.getVwap()
                        ? 1.0
                        : 0.35;

        double relativeWeakness = safe(input.getRelativeStrengthVsSpy(), 0) < 0 ? 1.0 : 0.35;

        return clamp(
                0.35 * l2Score +
                        0.25 * volumeScore +
                        0.20 * vwapControl +
                        0.20 * relativeWeakness
        );
    }

    private double liquidityScore(ShortSellingInput input) {
        double dollarVolume = safe(input.getDollarVolume(), 0);

        if (dollarVolume >= config.getExcellentDollarVolume()) return 1.00;
        if (dollarVolume >= config.getGoodDollarVolume()) return 0.75;
        if (dollarVolume >= config.getMinDollarVolume()) return 0.50;

        return 0.20;
    }

    private double spreadScore(ShortSellingInput input) {
        double spreadPct = safe(input.getSpreadPct(), 1.0);

        if (spreadPct <= 0.10) return 1.00;
        if (spreadPct <= 0.25) return 0.75;
        if (spreadPct <= 0.50) return 0.45;

        return 0.20;
    }

    private double volatilityScore(ShortSellingInput input) {
        double atrPct = safe(input.getAtrPct(), 3.0);

        if (atrPct >= 1.0 && atrPct <= 6.0) return 1.00;
        if (atrPct <= 10.0) return 0.65;

        return 0.35;
    }

    private String confidenceGrade(Double existingConfidence, double shortScore, double feasibility) {
        double score = safe(existingConfidence, shortScore * 100);

        if (score >= 85 && feasibility >= 0.75) return "A+";
        if (score >= 75) return "A";
        if (score >= 65) return "B";
        if (score >= 50) return "C";

        return "D";
    }

    private double calculateRR(double entry, double invalidation, double target) {
        double risk = invalidation - entry;
        double reward = entry - target;

        if (risk <= 0 || reward <= 0) return 0.0;

        return reward / risk;
    }
}