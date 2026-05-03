package com.trading.alphavantage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.alphavantage.AlphaVantageRateLimiter;
import com.trading.config.AppRuntimeConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class AlphaVantageClient {

    private final ObjectMapper objectMapper;
    private final AlphaVantageRateLimiter rateLimiter;
    private final RestClient restClient;

    public AlphaVantageClient(AlphaVantageRateLimiter rateLimiter) {
        this.objectMapper = new ObjectMapper();
        this.rateLimiter = rateLimiter;
        this.restClient = RestClient.create();
    }

    public Map<String, Object> fetchCompanyOverview(AppRuntimeConfig config, String symbol) {
        if (!enabled(config)) {
            return Map.of();
        }

        String url = buildUrl(config, Map.of(
                "function", "OVERVIEW",
                "symbol", symbol
        ));

        try {
            rateLimiter.acquire(symbol, "OVERVIEW");

            String json = restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(json);

            if (isApiProblem(root)) {
                log.warn("Alpha Vantage overview unavailable for symbol={} response={}", symbol, root);
                return Map.of();
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("av_company_name", text(root, "Name"));
            out.put("av_sector", text(root, "Sector"));
            out.put("av_industry", text(root, "Industry"));
            out.put("av_market_cap", number(root, "MarketCapitalization"));
            out.put("av_pe_ratio", number(root, "PERatio"));
            out.put("av_peg_ratio", number(root, "PEGRatio"));
            out.put("av_eps", number(root, "EPS"));
            out.put("av_beta", number(root, "Beta"));
            out.put("av_profit_margin", number(root, "ProfitMargin"));
            out.put("av_operating_margin_ttm", number(root, "OperatingMarginTTM"));
            out.put("av_revenue_ttm", number(root, "RevenueTTM"));
            out.put("av_analyst_target_price", number(root, "AnalystTargetPrice"));
            return out;

        } catch (Exception e) {
            log.warn("Alpha Vantage overview fetch failed for symbol={}: {}", symbol, e.getMessage());
            return Map.of();
        }
    }

    public Map<String, Object> fetchEarnings(AppRuntimeConfig config, String symbol) {
        if (!enabled(config)) {
            return Map.of();
        }

        String url = buildUrl(config, Map.of(
                "function", "EARNINGS",
                "symbol", symbol
        ));

        try {
            rateLimiter.acquire(symbol, "EARNINGS");

            String json = restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(json);

            if (isApiProblem(root)) {
                log.warn("Alpha Vantage earnings unavailable symbol={} response={}", symbol, root);
                return Map.of();
            }

            JsonNode quarterly = root.path("quarterlyEarnings");
            if (!quarterly.isArray() || quarterly.isEmpty()) {
                return Map.of();
            }

            List<JsonNode> quarters = new ArrayList<>();
            quarterly.forEach(quarters::add);
            quarters.sort(Comparator.comparing((JsonNode n) -> text(n, "fiscalDateEnding")).reversed());

            double eps0 = actualEps(quarters, 0);
            double eps1 = actualEps(quarters, 1);
            double eps2 = actualEps(quarters, 2);
            double eps3 = actualEps(quarters, 3);
            double eps4 = actualEps(quarters, 4);

            double surprisePctLast = number(quarters.get(0), "surprisePercentage");
            String earningsDate = text(quarters.get(0), "reportedDate");
            if (earningsDate.isBlank()) {
                earningsDate = text(quarters.get(0), "fiscalDateEnding");
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("eps_available", true);
            out.put("eps_increase_2q", round(percentChange(eps0, eps2)));
            out.put("eps_increase_3q", round(percentChange(eps0, eps3)));
            out.put("eps_increase_4q", round(percentChange(eps0, eps4)));
            out.put("eps_growth_qoq", round(percentChange(eps0, eps1)));
            out.put("eps_surprise_pct_last", round(surprisePctLast));
            out.put("earnings_date", earningsDate == null ? "" : earningsDate);
            return out;

        } catch (Exception e) {
            log.warn("Alpha Vantage earnings fetch failed symbol={} error={}", symbol, e.getMessage());
            return Map.of();
        }
    }

    public Map<String, Object> fetchNewsSentiment(AppRuntimeConfig config, String symbol) {
        if (!enabled(config)) {
            return Map.of();
        }

        String url = buildUrl(config, Map.of(
                "function", "NEWS_SENTIMENT",
                "tickers", symbol,
                "limit", "50"
        ));

        try {
            rateLimiter.acquire(symbol, "NEWS_SENTIMENT");

            String json = restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(json);

            if (isApiProblem(root)) {
                log.warn("Alpha Vantage news sentiment unavailable for symbol={} response={}", symbol, root);
                return Map.of();
            }

            JsonNode feed = root.path("feed");
            int count = feed.isArray() ? feed.size() : 0;

            double sentimentSum = 0.0;
            int sentimentCount = 0;
            int positive = 0;

            if (feed.isArray()) {
                for (JsonNode article : feed) {
                    double score = article.path("overall_sentiment_score").asDouble(Double.NaN);
                    if (!Double.isNaN(score)) {
                        sentimentSum += score;
                        sentimentCount++;
                        if (score > 0) {
                            positive++;
                        }
                    }
                }
            }

            double avgSentiment = sentimentCount == 0 ? 0.0 : sentimentSum / sentimentCount;
            double positiveRatio = sentimentCount == 0 ? 0.0 : (double) positive / sentimentCount;

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("news_article_count", count);
            out.put("news_sentiment_score", round4(avgSentiment));
            out.put("news_positive_ratio", round4(positiveRatio));
            out.put("av_news_source", "ALPHA_VANTAGE");
            return out;

        } catch (Exception e) {
            log.warn("Alpha Vantage news sentiment fetch failed for symbol={}: {}", symbol, e.getMessage());
            return Map.of();
        }
    }

    private boolean enabled(AppRuntimeConfig config) {
        return config != null
                && config.isAlphaVantageEnabled()
                && config.getAlphaVantageApiKey() != null
                && !config.getAlphaVantageApiKey().isBlank();
    }

    private String buildUrl(AppRuntimeConfig config, Map<String, String> params) {
        StringBuilder sb = new StringBuilder(config.getAlphaVantageBaseUrl());
        sb.append("?apikey=").append(encode(config.getAlphaVantageApiKey()));

        for (Map.Entry<String, String> entry : params.entrySet()) {
            sb.append("&")
                    .append(encode(entry.getKey()))
                    .append("=")
                    .append(encode(entry.getValue()));
        }

        return sb.toString();
    }

    private boolean isApiProblem(JsonNode root) {
        return root == null
                || root.isMissingNode()
                || root.isNull()
                || root.has("Error Message")
                || root.has("Note")
                || root.has("Information")
                || root.has("message")
                || (root.isObject() && root.isEmpty());
    }

    private double actualEps(List<JsonNode> quarters, int index) {
        if (quarters == null || quarters.size() <= index) {
            return 0.0;
        }
        return number(quarters.get(index), "reportedEPS");
    }

    private double percentChange(double current, double previous) {
        if (previous == 0.0) {
            return 0.0;
        }
        return ((current - previous) / Math.abs(previous)) * 100.0;
    }

    private String text(JsonNode node, String field) {
        String value = node == null ? "" : node.path(field).asText("");
        return "None".equalsIgnoreCase(value) ? "" : value;
    }

    private double number(JsonNode node, String field) {
        String value = text(node, field);
        if (value.isBlank()) {
            return 0.0;
        }

        try {
            return Double.parseDouble(value.replace(",", ""));
        } catch (Exception e) {
            return 0.0;
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private double round4(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }
}
