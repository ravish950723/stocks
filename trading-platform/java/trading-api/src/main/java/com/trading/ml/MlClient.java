package com.trading.ml;

import com.trading.ml.MlFeaturesRequest;
import com.trading.ml.MlPredictionResponse;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Getter
@Component
public class MlClient {

    private final boolean mlEnabled;
    private final String mlBaseUrl;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final RestTemplate restTemplate;

    public MlClient(
            @Value("${ml.enabled:true}") boolean mlEnabled,
            @Value("${ml.base-url:http://127.0.0.1:5000}") String mlBaseUrl,
            @Value("${ml.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${ml.read-timeout-ms:5000}") int readTimeoutMs
    ) {
        this.mlEnabled = mlEnabled;
        this.mlBaseUrl = trimRightSlash(mlBaseUrl);
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.restTemplate = buildRestTemplate(connectTimeoutMs, readTimeoutMs);
    }

    public void verifyAvailableOnce() {
        if (!mlEnabled) {
            log.info("ML disabled via application.yml");
            return;
        }
        try {
            restTemplate.exchange(mlBaseUrl + "/health", HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);
            log.info("ML_HEALTH_OK url={}", mlBaseUrl);
        } catch (RestClientException e) {
            log.warn("ML_HEALTH_FAIL url={} error={}", mlBaseUrl, e.getMessage());
        }
    }

    public com.trading.ml.MlPredictionResponse predict(MlFeaturesRequest request) {
        if (!mlEnabled) {
            return com.trading.ml.MlPredictionResponse.fallback("ML disabled in application.yml");
        }
        if (request == null || request.getSymbol() == null || request.getSymbol().isBlank()) {
            return com.trading.ml.MlPredictionResponse.fallback("ML request missing symbol");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);

        try {
            ResponseEntity<com.trading.ml.MlPredictionResponse> response = restTemplate.postForEntity(
                    mlBaseUrl + "/predict",
                    new HttpEntity<>(request, headers),
                    com.trading.ml.MlPredictionResponse.class
            );

            com.trading.ml.MlPredictionResponse body = response.getBody();
            if (body == null) {
                return com.trading.ml.MlPredictionResponse.fallback("ML response body is null");
            }

            log.debug("ML_PREDICT_OK symbol={} probability={} rankScore={} status={} promotion={} hedgeGate={} schemaOk={}",
                    request.getSymbol(), body.getProbability(), body.getRankScore(), body.getStatus(),
                    body.getPromotionStatus(), body.isHedgeSafeGatingPassed(), body.isSchemaCompatible());
            return body;
        } catch (RestClientException e) {
            log.warn("ML_PREDICT_FAIL symbol={} error={}", request.getSymbol(), e.getMessage());
            return MlPredictionResponse.fallback("ML predict failed: " + e.getMessage());
        }
    }

    private RestTemplate buildRestTemplate(int connectTimeoutMs, int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return new RestTemplate(factory);
    }

    private static String trimRightSlash(String value) {
        if (value == null || value.isBlank()) return "http://127.0.0.1:5000";
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
