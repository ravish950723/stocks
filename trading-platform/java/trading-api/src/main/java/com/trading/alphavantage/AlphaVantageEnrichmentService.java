package com.trading.alphavantage;

import com.trading.cache.AlphaVantageCacheService;
import com.trading.config.AppRuntimeConfig;
import com.trading.config.YamlConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Slf4j
@Service
@RequiredArgsConstructor
public class AlphaVantageEnrichmentService {

    private final AlphaVantageCacheService alphaVantageCacheService;
    private final YamlConfigService yamlConfigService;

    public Map<String, Object> enrich(AppRuntimeConfig config, String symbol) {
        return enrich(config, symbol, Map.of());
    }

    public Map<String, Object> enrich(AppRuntimeConfig config, String symbol, Map<String, Object> existingRow) {
        Map<String, Object> out = new LinkedHashMap<>();
        String normalizedSymbol = normalizeSymbol(symbol);

        initializeDefaults(out, existingRow);

        log.info("ALPHA_VANTAGE_ENRICH_START symbol={} enabled={} fundamentals={} news={}",
                normalizedSymbol,
                config != null && config.isAlphaVantageEnabled(),
                config != null && config.isAlphaVantageEnrichFundamentals(),
                config != null && config.isAlphaVantageEnrichNewsSentiment());

        if (config == null || !config.isAlphaVantageEnabled()) {
            out.put("alpha_vantage_status", "DISABLED");
            applyRowAwareDerivedFields(out, existingRow);
            return out;
        }

        if (isBlank(config.getAlphaVantageApiKey())) {
            out.put("alpha_vantage_status", "MISSING_API_KEY");
            applyRowAwareDerivedFields(out, existingRow);
            return out;
        }

        boolean etfLike = isEtfLike(normalizedSymbol, existingRow);
        if (etfLike) {
            out.put("is_etf", true);
            log.info("ETF_DETECTED symbol={}", normalizedSymbol);
        }

        try {
            Map<String, Object> avData = alphaVantageCacheService.enrich(config, normalizedSymbol);
            if (avData == null) {
                avData = Map.of();
            }

            log.info("ALPHA_VANTAGE_CACHE_DATA symbol={} empty={} keys={}",
                    normalizedSymbol,
                    avData.isEmpty(),
                    avData.keySet());

            if (config.isAlphaVantageEnrichFundamentals() && !etfLike) {
                applyOverview(out, avData, normalizedSymbol);
                applyEarnings(out, mapToEarnings(avData));
            } else if (etfLike) {
                log.info("ALPHA_VANTAGE_OVERVIEW_SKIPPED symbol={} reason=ETF_OR_FUNDAMENTALS_NOT_APPLICABLE", normalizedSymbol);
                out.put("alpha_vantage_fundamental_note", "ETF_OR_FUNDAMENTALS_NOT_APPLICABLE");
            }

            if (config.isAlphaVantageEnrichNewsSentiment()) {
                applySentiment(out, avData);
            }

            applyEtfFallbacks(out, existingRow, etfLike);
            applyDerivedFundamentalFields(out, existingRow);
            applyRowAwareDerivedFields(out, existingRow);

            out.put("alpha_vantage_status", "OK");
            out.put("alpha_vantage_symbol", normalizedSymbol);

            Object betaObj = out.get("beta");
            String betaLog = betaObj == null ? "NA" : betaObj.toString();

            log.info("ALPHA_VANTAGE_ENRICH_DONE symbol={} status={} sector={} industry={} beta={} betaLog={} epsAvailable={} sentimentLabel={} newsArticles={}",
                    normalizedSymbol,
                    out.get("alpha_vantage_status"),
                    stringOr(out.get("sector"), ""),
                    stringOr(out.get("industry"), ""),
                    d(out.get("beta")),
                    betaLog,
                    out.get("EPS_AVAILABLE"),
                    stringOr(out.get("Sentiment Label"), ""),
                    (int) Math.round(d(out.get("News Article Count"))));

            return out;

        } catch (Exception e) {
            log.warn("Alpha Vantage enrichment failed symbol={} error={}", normalizedSymbol, e.getMessage());

            out.put("alpha_vantage_status", "ERROR");
            out.put("alpha_vantage_error", e.getMessage());

            applyEtfFallbacks(out, existingRow, etfLike);
            applyDerivedFundamentalFields(out, existingRow);
            applyRowAwareDerivedFields(out, existingRow);
            return out;
        }
    }

    private void initializeDefaults(Map<String, Object> out, Map<String, Object> row) {
        out.put("EPS_AVAILABLE", false);
        out.put("EPS Increase 2Q", 0.0);
        out.put("EPS Increase 3Q", 0.0);
        out.put("EPS Increase 4Q", 0.0);
        out.put("ETF_PROXY_GROWTH_SCORE", 0.0);
        out.put("FUNDAMENTAL_BOOST", 0.0);
        out.put("Sentiment Label", "NEUTRAL");
        out.put("News Sentiment Score", 0.0);
        out.put("News Positive Ratio", 0.0);
        out.put("News Article Count", 0);
        out.put("Earnings Date", "");
        out.put("Sentiment Confidence", 0.0);
        out.put("Rule Recommendation", stringOr(row.get("recommendation"), stringOr(row.get("rule_recommendation"), "WAIT")));
        out.put("Trade Direction", deriveTradeDirection(row));
        out.put("Entry Quality Score", deriveEntryQualityScore(row, 0.0, 0.0));

        out.put("eps_available", false);
        out.put("eps_increase_2q", 0.0);
        out.put("eps_increase_3q", 0.0);
        out.put("eps_increase_4q", 0.0);
        out.put("eps_growth_qoq", 0.0);
        out.put("eps_surprise_pct_last", 0.0);
        out.put("eps_quality_score", 0.0);
        out.put("etf_proxy_growth_score", 0.0);
        out.put("fundamental_boost", 0.0);
        out.put("sentiment_label", "NEUTRAL");
        out.put("news_sentiment_score", 0.0);
        out.put("news_positive_ratio", 0.0);
        out.put("news_article_count", 0);
        out.put("earnings_date", "");
        out.put("sentiment_confidence", 0.0);
        out.put("rule_recommendation", stringOr(row.get("rule_recommendation"), stringOr(row.get("recommendation"), "WAIT")));
        out.put("trade_direction", deriveTradeDirection(row));
        out.put("entry_quality_score", deriveEntryQualityScore(row, 0.0, 0.0));
    }

    private void applyOverview(Map<String, Object> out, Map<String, Object> overview, String symbol) {
        log.info("AV_OVERVIEW_RAW symbol={} empty={} keys={}",
                symbol,
                overview == null || overview.isEmpty(),
                overview == null ? java.util.Set.of() : overview.keySet());

        if (overview == null || overview.isEmpty()) {
            out.put("EPS_AVAILABLE", false);
            out.put("eps_available", false);
            out.put("alpha_vantage_fundamental_note", "OVERVIEW_EMPTY");
            return;
        }

        out.putAll(overview);

        String sector = stringOr(overview.getOrDefault("av_sector", overview.get("Sector")), "");
        String industry = stringOr(overview.getOrDefault("av_industry", overview.get("Industry")), "");
        double beta = d(overview.getOrDefault("av_beta", overview.get("Beta")));
        double eps = d(overview.getOrDefault("av_eps", overview.get("EPS")));

        if (!sector.isBlank()) {
            out.put("sector", sector);
            out.put("Sector", sector);
        }

        if (!industry.isBlank()) {
            out.put("industry", industry);
            out.put("Industry", industry);
        }

        out.put("beta", beta > 0 ? beta : null);
        out.put("Beta", beta > 0 ? beta : null);

        boolean epsAvailable = eps != 0.0;
        out.put("EPS_AVAILABLE", epsAvailable);
        out.put("eps_available", epsAvailable);
        out.put("av_eps_available", epsAvailable);

        log.info("AV_OVERVIEW_MAPPED symbol={} sector={} industry={} beta={} epsAvailable={}",
                symbol, sector, industry, beta, epsAvailable);
    }

    private EarningsData mapToEarnings(Map<String, Object> earnings) {
        if (earnings == null || earnings.isEmpty()) {
            return EarningsData.empty();
        }

        return EarningsData.builder()
                .available(bool(earnings.get("eps_available")) || bool(earnings.get("EPS_AVAILABLE")))
                .eps0(0.0)
                .eps1(0.0)
                .eps2(0.0)
                .eps3(0.0)
                .eps4(0.0)
                .epsIncrease2Q(firstNumber(earnings, "eps_increase_2q", "EPS Increase 2Q"))
                .epsIncrease3Q(firstNumber(earnings, "eps_increase_3q", "EPS Increase 3Q"))
                .epsIncrease4Q(firstNumber(earnings, "eps_increase_4q", "EPS Increase 4Q"))
                .epsGrowthQoq(firstNumber(earnings, "eps_growth_qoq"))
                .epsSurprisePctLast(firstNumber(earnings, "eps_surprise_pct_last"))
                .earningsDate(stringOr(earnings.get("earnings_date"), stringOr(earnings.get("Earnings Date"), "")))
                .build();
    }

    private void applyEarnings(Map<String, Object> out, EarningsData e) {
        if (e == null || !e.available) {
            return;
        }

        out.put("EPS_AVAILABLE", true);
        out.put("eps_available", true);

        out.put("EPS Increase 2Q", round(e.epsIncrease2Q));
        out.put("EPS Increase 3Q", round(e.epsIncrease3Q));
        out.put("EPS Increase 4Q", round(e.epsIncrease4Q));

        out.put("eps_increase_2q", round(e.epsIncrease2Q));
        out.put("eps_increase_3q", round(e.epsIncrease3Q));
        out.put("eps_increase_4q", round(e.epsIncrease4Q));

        out.put("eps_growth_qoq", round(e.epsGrowthQoq));
        out.put("eps_surprise_pct_last", round(e.epsSurprisePctLast));
        out.put("Earnings Date", e.earningsDate == null ? "" : e.earningsDate);
        out.put("earnings_date", e.earningsDate == null ? "" : e.earningsDate);
    }

    private void applySentiment(Map<String, Object> out, Map<String, Object> sentiment) {
        if (sentiment == null || sentiment.isEmpty()) {
            out.put("alpha_vantage_sentiment_note", "NEWS_SENTIMENT_EMPTY");
            return;
        }

        out.putAll(sentiment);

        double score = firstNumber(sentiment,
                "av_news_sentiment_score",
                "news_sentiment_score",
                "News Sentiment Score",
                "overall_sentiment_score"
        );

        double positiveRatio = firstNumber(sentiment,
                "av_news_positive_ratio",
                "news_positive_ratio",
                "News Positive Ratio"
        );

        int articleCount = (int) Math.round(firstNumber(sentiment,
                "av_news_article_count",
                "news_article_count",
                "News Article Count",
                "article_count"
        ));

        String label = sentimentLabel(score);
        double confidence = sentimentConfidence(score, articleCount);

        out.put("News Sentiment Score", round(score));
        out.put("News Positive Ratio", round(positiveRatio));
        out.put("News Article Count", articleCount);
        out.put("Sentiment Label", label);
        out.put("Sentiment Confidence", round(confidence));

        out.put("news_sentiment_score", round(score));
        out.put("news_positive_ratio", round(positiveRatio));
        out.put("news_article_count", articleCount);
        out.put("sentiment_label", label);
        out.put("sentiment_confidence", round(confidence));
    }

    private void applyEtfFallbacks(Map<String, Object> out, Map<String, Object> row, boolean etfLike) {
        if (!etfLike) {
            return;
        }

        double signalScore = firstNumber(row, "signal_score", "Signal Score", "long_score");
        double stageScore = firstNumber(row, "stage_score", "stage_alignment_score", "Stage Alignment Score");
        double sentimentScore = d(out.get("news_sentiment_score"));

        double proxy = clamp(
                (0.45 * normalize100(signalScore)) +
                        (0.30 * normalize100(stageScore)) +
                        (0.25 * normalizeSignedSentiment(sentimentScore)),
                0.0,
                1.0
        );

        out.put("ETF_PROXY_GROWTH_SCORE", round(proxy * 100.0));
        out.put("etf_proxy_growth_score", round(proxy * 100.0));

        out.put("EPS_AVAILABLE", false);
        out.put("eps_available", false);

        out.put("sector", "ETF");
        out.put("Sector", "ETF");
        out.put("industry", "ETF");
        out.put("Industry", "ETF");
        out.put("beta", 1.0);
        out.put("Beta", 1.0);
    }

    private void applyDerivedFundamentalFields(Map<String, Object> out, Map<String, Object> row) {
        double epsGrowth = d(out.get("eps_growth_qoq"));
        double eps2q = d(out.get("EPS Increase 2Q"));
        double eps3q = d(out.get("EPS Increase 3Q"));
        double eps4q = d(out.get("EPS Increase 4Q"));
        double surprise = d(out.get("eps_surprise_pct_last"));
        boolean epsAvailable = bool(out.get("EPS_AVAILABLE"));

        double epsTrendScore = clamp(
                (normalizePct(epsGrowth) * 0.35) +
                        (normalizePct(eps2q) * 0.25) +
                        (normalizePct(eps3q) * 0.20) +
                        (normalizePct(eps4q) * 0.10) +
                        (normalizePct(surprise) * 0.10),
                0.0,
                1.0
        );

        double epsQualityScore = epsAvailable ? epsTrendScore * 100.0 : 0.0;
        out.put("eps_quality_score", round(epsQualityScore));

        double etfProxy = d(out.get("ETF_PROXY_GROWTH_SCORE"));
        double sentimentScore = d(out.get("news_sentiment_score"));
        double sentimentBoost = normalizeSignedSentiment(sentimentScore) * 100.0;

        double fundamentalBoost;
        if (epsAvailable) {
            fundamentalBoost = (0.80 * epsQualityScore) + (0.20 * sentimentBoost);
        } else if (etfProxy > 0) {
            fundamentalBoost = (0.70 * etfProxy) + (0.30 * sentimentBoost);
        } else {
            fundamentalBoost = sentimentBoost * 0.30;
        }

        out.put("FUNDAMENTAL_BOOST", round(fundamentalBoost));
        out.put("fundamental_boost", round(fundamentalBoost));
    }

    private void applyRowAwareDerivedFields(Map<String, Object> out, Map<String, Object> row) {
        String ruleRecommendation = stringOr(row.get("rule_recommendation"), stringOr(row.get("recommendation"), "WAIT"));
        String tradeDirection = deriveTradeDirection(row);
        double fundamentalBoost = d(out.get("FUNDAMENTAL_BOOST"));
        double sentimentConfidence = d(out.get("Sentiment Confidence"));
        double entryQuality = deriveEntryQualityScore(row, fundamentalBoost, sentimentConfidence);

        out.put("Rule Recommendation", ruleRecommendation);
        out.put("rule_recommendation", ruleRecommendation);

        out.put("Trade Direction", tradeDirection);
        out.put("trade_direction", tradeDirection);

        out.put("Entry Quality Score", round(entryQuality));
        out.put("entry_quality_score", round(entryQuality));
    }

    private double deriveEntryQualityScore(Map<String, Object> row, double fundamentalBoost, double sentimentConfidence) {
        double signal = firstNumber(row, "signal_score", "Signal Score", "long_score");
        double confidence = firstNumber(row, "confidence_score", "Confidence Score");
        double regime = firstNumber(row, "regime_quality_score", "Regime Quality Score");
        double stage = firstNumber(row, "stage_score", "stage_alignment_score", "Stage Alignment Score");
        double substage = firstNumber(row, "substage_score", "Substage Score");
        double child = firstNumber(row, "child_substage_score", "Child Substage Score");

        double base =
                (0.25 * normalize100(signal)) +
                        (0.20 * normalize100(confidence)) +
                        (0.20 * normalize100(regime)) +
                        (0.15 * normalize100(stage)) +
                        (0.10 * normalize100(substage)) +
                        (0.05 * normalize100(child)) +
                        (0.15 * normalize100(fundamentalBoost));

        double rsi = firstNumber(row, "rsi", "RSI");
        double adx = firstNumber(row, "adx", "ADX");
        double close = firstNumber(row, "close", "Close", "current_price", "Current Price");
        double darvasTop = firstNumber(row, "darvas_top", "Darvas Top", "darvasBoxTop", "DarvasBoxTop");

        if (rsi > 80) base -= 0.15;
        if (adx < 15) base -= 0.10;

        if (darvasTop > 0 && close > darvasTop * 1.05) {
            base -= 0.12;
        }

        double confidenceBoost = normalize100(sentimentConfidence) * 5.0;

        return clamp((base * 100.0) + confidenceBoost, 0.0, 100.0);
    }

    private String deriveTradeDirection(Map<String, Object> row) {
        String finalAction = upper(row.get("final_action"));
        String recommendation = upper(row.get("recommendation"));
        String ruleRecommendation = upper(row.get("rule_recommendation"));
        String stage = upper(row.get("market_stage"));

        String decision = !finalAction.isBlank() ? finalAction :
                !recommendation.isBlank() ? recommendation :
                        !ruleRecommendation.isBlank() ? ruleRecommendation : "";

        if (decision.contains("SHORT") || decision.contains("SELL") || decision.contains("EXIT")) {
            return "SHORT_OR_EXIT";
        }

        if ("MARKDOWN".equals(stage)) {
            return "SHORT_OR_AVOID_LONG";
        }

        if ("DISTRIBUTION".equals(stage)) {
            return "REDUCE_OR_WAIT";
        }

        double entryScore = firstNumber(row, "entry_quality_score", "Entry Quality Score");
        double sentiment = firstNumber(row, "news_sentiment_score", "News Sentiment Score");

        if (entryScore >= 75 && sentiment > 0) {
            return "STRONG_LONG";
        }

        if (entryScore >= 60) {
            return "LONG";
        }

        if (entryScore < 40) {
            return "AVOID";
        }

        if (decision.contains("BUY") || decision.contains("ADD") || decision.contains("SCALE")) {
            return "LONG";
        }

        return "WAIT";
    }

    private boolean isEtfLike(String symbol, Map<String, Object> row) {
        String assetType = upper(firstNonBlank(row.get("asset_type"), row.get("security_type"), row.get("instrument_type")));
        if (assetType.contains("ETF") || assetType.contains("FUND") || assetType.contains("ETN")) {
            return true;
        }

        return switch (upper(symbol)) {
            case "QQQ", "SPY", "IWM", "DIA", "BUG", "SLV", "GLD", "CPER", "USO", "UNG", "TLT", "HYG", "LQD",
                 "XLK", "XLF", "XLE", "XLV", "XLY", "XLI", "XLP", "XLB", "XLU", "SMH", "ARKK" -> true;
            default -> false;
        };
    }

    private double percentChange(double current, double previous) {
        if (previous == 0.0) return 0.0;
        return ((current - previous) / Math.abs(previous)) * 100.0;
    }

    private String sentimentLabel(double score) {
        if (score >= 0.30) return "POSITIVE";
        if (score <= -0.30) return "NEGATIVE";
        if (score >= 0.10) return "SLIGHTLY_POSITIVE";
        if (score <= -0.10) return "SLIGHTLY_NEGATIVE";
        return "NEUTRAL";
    }

    private double sentimentConfidence(double score, int articleCount) {
        double articleComponent = clamp(articleCount / 50.0, 0.0, 1.0);
        double scoreComponent = clamp(Math.abs(score) / 0.50, 0.0, 1.0);
        return ((0.65 * articleComponent) + (0.35 * scoreComponent)) * 100.0;
    }

    private double normalizePct(double pct) {
        return clamp((pct + 50.0) / 100.0, 0.0, 1.0);
    }

    private double normalize100(double value) {
        return clamp(value / 100.0, 0.0, 1.0);
    }

    private double normalizeSignedSentiment(double sentiment) {
        return clamp((sentiment + 1.0) / 2.0, 0.0, 1.0);
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private String upper(Object raw) {
        return raw == null ? "" : String.valueOf(raw).trim().toUpperCase(Locale.ROOT);
    }

    private String stringOr(Object raw, String def) {
        if (raw == null) return def;
        String s = String.valueOf(raw).trim();
        return s.isBlank() ? def : s;
    }

    private String firstNonBlank(Object... values) {
        for (Object value : values) {
            if (value != null && !String.valueOf(value).trim().isBlank()) {
                return String.valueOf(value).trim();
            }
        }
        return "";
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isBlank();
    }

    private String encode(String raw) {
        return URLEncoder.encode(raw == null ? "" : raw, StandardCharsets.UTF_8);
    }

    private double firstNumber(Map<String, Object> map, String... keys) {
        if (map == null || map.isEmpty()) return 0.0;

        for (String key : keys) {
            if (map.containsKey(key)) {
                return d(map.get(key));
            }

            String normalized = normalizeKey(key);
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                if (normalizeKey(entry.getKey()).equals(normalized)) {
                    return d(entry.getValue());
                }
            }
        }

        return 0.0;
    }

    private String normalizeKey(String key) {
        return key == null ? "" : key.trim().replace(" ", "_").replace("-", "_").toLowerCase(Locale.ROOT);
    }

    private double d(Object raw) {
        if (raw == null) return 0.0;
        if (raw instanceof Number n) return n.doubleValue();

        try {
            String s = String.valueOf(raw).trim();
            if (s.isBlank()
                    || "None".equalsIgnoreCase(s)
                    || "null".equalsIgnoreCase(s)
                    || "N/A".equalsIgnoreCase(s)) {
                return 0.0;
            }
            return Double.parseDouble(s);
        } catch (Exception e) {
            return 0.0;
        }
    }

    private boolean bool(Object raw) {
        if (raw instanceof Boolean b) return b;
        if (raw instanceof Number n) return n.doubleValue() != 0.0;
        if (raw == null) return false;

        String s = String.valueOf(raw).trim();
        return "true".equalsIgnoreCase(s)
                || "yes".equalsIgnoreCase(s)
                || "y".equalsIgnoreCase(s)
                || "1".equals(s);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    private static class EarningsData {
        boolean available;
        double eps0;
        double eps1;
        double eps2;
        double eps3;
        double eps4;
        double epsIncrease2Q;
        double epsIncrease3Q;
        double epsIncrease4Q;
        double epsGrowthQoq;
        double epsSurprisePctLast;
        String earningsDate;

        static EarningsData empty() {
            EarningsData e = new EarningsData();
            e.available = false;
            e.earningsDate = "";
            return e;
        }
    }
}