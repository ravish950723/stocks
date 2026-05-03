package com.trading.ml;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MlPredictionClient {

    @Value("${ml.base-url:http://127.0.0.1:8000}")
    private String baseUrl;



    private final RestTemplate rest ;//= new RestTemplate();
    private final ObjectMapper mapper;// = new ObjectMapper();

    public PredictResponse predict(String symbol, Map<String, Double> features) {

        if (features == null || features.isEmpty()) {
            log.warn("ML_PREDICT_SKIPPED symbol={} reason=EMPTY_FEATURES", symbol);
            return null;
        }

        Map<String, Double> cleanFeatures = new LinkedHashMap<>();

        features.forEach((k, v) -> {
            if (k != null && !k.isBlank() && v != null && Double.isFinite(v)) {
                cleanFeatures.put(k, v);
            }
        });

        if (cleanFeatures.isEmpty()) {
            log.warn("ML_PREDICT_SKIPPED symbol={} reason=NO_VALID_NUMERIC_FEATURES", symbol);
            return null;
        }

        PredictRequest req = new PredictRequest(
                symbol == null || symbol.isBlank() ? "UNKNOWN" : symbol.trim().toUpperCase(),
                cleanFeatures
        );

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<PredictRequest> entity = new HttpEntity<>(req, headers);

            ResponseEntity<PredictResponse> response =
                    rest.postForEntity(predictUrl(), entity, PredictResponse.class);

            PredictResponse body = response.getBody();

            log.info("ML_PREDICT_OK symbol={} probability={} status={} rankScore={}",
                    req.getSymbol(),
                    body == null ? null : body.getProbability(),
                    body == null ? null : body.getStatus(),
                    body == null ? null : body.getRankScore());

            return body;

        } catch (Exception ex) {
            try {
                log.error("ML_PREDICT_FAILED url={} payload={}",
                        predictUrl(),
                        mapper.writeValueAsString(req),
                        ex);
            } catch (Exception ignored) {
                log.error("ML_PREDICT_FAILED url={}", predictUrl(), ex);
            }
            return null;
        }
    }


    private String predictUrl() {
        return baseUrl + "/predict";
    }

}