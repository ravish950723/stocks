package com.trading.analytics;


import com.trading.entry.Candle;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MissingAnalyticsLayerService {

    private final PerformanceAnalyticsService performanceAnalyticsService;
    private final RelativeStrengthService relativeStrengthService;
    private final MomentumReasonEngine momentumReasonEngine;
    private final FinalScoringEngine finalScoringEngine;

    public AnalyticsResult analyze(
            String symbol,
            List<Candle> symbolCandles,
            List<Candle> spyCandles,
            FundamentalSnapshot fundamentalSnapshot,
            TechnicalInputs technicalInputs
    ) {

        PerformanceAnalyticsService.PerformanceResult performance =
                performanceAnalyticsService.calculate90DayPerformance(symbolCandles, symbol);

        double relativeStrengthVsSpy =
                relativeStrengthService.calculateRelativeStrengthVsSpy(symbolCandles, spyCandles, 90);

        String momentumReason =
                momentumReasonEngine.buildReason(
                        technicalInputs.getRsi(),
                        technicalInputs.getAdx(),
                        technicalInputs.getMacd(),
                        technicalInputs.getMacdSignal(),
                        technicalInputs.getPrice(),
                        technicalInputs.getVwap(),
                        relativeStrengthVsSpy
                );

        double finalScore =
                finalScoringEngine.calculateFinalScore(
                        technicalInputs.getStageScore(),
                        technicalInputs.getSubstageScore(),
                        technicalInputs.getSignalScore(),
                        technicalInputs.getConfidenceScore(),
                        technicalInputs.getInstitutionalScore(),
                        relativeStrengthVsSpy,
                        performance.getNinetyDayGainPct()
                );

        return AnalyticsResult.builder()
                .ninetyDayHit(performance.getNinetyDayHit())
                .ninetyDayGainPct(performance.getNinetyDayGainPct())
                .daysToPeak(performance.getDaysToPeak())
                .momentumDecisionReason(momentumReason)
                .finalScore(finalScore)
                .industry(fundamentalSnapshot == null ? null : fundamentalSnapshot.getIndustry())
                .beta(fundamentalSnapshot == null ? null : fundamentalSnapshot.getBeta())
                .relativeStrengthVsSpy(relativeStrengthVsSpy)
                .build();
    }

    @lombok.Data
    @lombok.Builder
    public static class TechnicalInputs {
        private Double price;
        private Double vwap;
        private Double rsi;
        private Double adx;
        private Double macd;
        private Double macdSignal;

        private Double stageScore;
        private Double substageScore;
        private Double signalScore;
        private Double confidenceScore;
        private Double institutionalScore;
    }
}