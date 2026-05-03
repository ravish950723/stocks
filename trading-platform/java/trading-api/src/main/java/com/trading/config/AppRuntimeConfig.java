package com.trading.config;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class AppRuntimeConfig {
    private Map<String, Object> agentConfig = new LinkedHashMap<>();
    private String projectRoot;
    private String outputFile;
    private String cacheDir;
    private String trainingFeatureFile;

    private int ttlMinutes = 240;
    private boolean forceRefresh;

    private String ibHost;
    private int ibPort;
    private int ibClientId;
    private int ibHistoryYears = 10;

    // IBKR cache overlap
    private int ibIncrementalOverlapDays = 10;

    private int minCandlesForHealthyCache = 252;

    private int mlTimeoutSeconds = 10;
    private boolean mlEnabled = true;
    private String mlBaseUrl = "http://127.0.0.1:8000";

    private List<String> symbols = new ArrayList<>();
    private List<ColumnDefinition> columns = new ArrayList<>();

    private Map<String, Object> indicators = new LinkedHashMap<>();
    private Map<String, Object> patterns = new LinkedHashMap<>();
    private Map<String, Object> quant = new LinkedHashMap<>();
    private Map<String, Object> substages = new LinkedHashMap<>();
    private Map<String, Object> childSubstages = new LinkedHashMap<>();
    private Map<String, Object> businessRules = new LinkedHashMap<>();
    private Map<String, Object> config = new LinkedHashMap<>();
    private Map<String, Object> symbolMetadata = new LinkedHashMap<>();

    private boolean rebuildTrainingHistoryOnEveryRun = false;

    private boolean alphaVantageEnabled = false;
    private String alphaVantageApiKey;
    private String alphaVantageBaseUrl = "https://www.alphavantage.co/query";
    private int alphaVantageTimeoutSeconds = 10;
    private boolean alphaVantageEnrichFundamentals = true;
    private boolean alphaVantageEnrichNewsSentiment = true;
    private boolean alphaVantageUseAsCandleFallback = false;

    // NEW
    private int alphaVantageTtlHours = 4;

    // -------- Compatibility getters --------

    public int getIncrementalOverlapDays() {
        return ibIncrementalOverlapDays;
    }

    public int getAlphaVantageTtlHours() {
        return alphaVantageTtlHours;
    }

    public int getIbkrCacheTtlMinutes() {
        return ttlMinutes;
    }
}