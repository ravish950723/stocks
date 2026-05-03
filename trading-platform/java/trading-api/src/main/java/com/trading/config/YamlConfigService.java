package com.trading.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.Data;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Service
@SuppressWarnings("unchecked")
@Data
public class YamlConfigService {

    private static final Logger log = LogManager.getLogger(YamlConfigService.class);
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory()).findAndRegisterModules();

    public static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public static int intValue(Object value) {
        return value instanceof Number n ? n.intValue() : Integer.parseInt(stringValue(value).isBlank() ? "0" : stringValue(value));
    }

    public static boolean boolValue(Object value) {
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.intValue() != 0;
        return Boolean.parseBoolean(stringValue(value));
    }

    public static double doubleValue(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        String s = stringValue(value);
        return s.isBlank() ? 0.0 : Double.parseDouble(s);
    }

    public AppRuntimeConfig load() {
        Map<String, Object> config = readYaml(resolvePath("config/config.yml"));
        Map<String, Object> indicators = readYaml(resolvePath("config/indicators.yml"));
        Map<String, Object> patterns = readYaml(resolvePath("config/patterns.yml"));
        Map<String, Object> quant = readYaml(resolvePath("config/quant.yml"));
        Map<String, Object> substages = readYaml(resolvePath("config/substages.yml"));
        Map<String, Object> businessRules = readYaml(resolvePath("config/business-rules.yml"));
        Map<String, Object> columnsYaml = readYaml(resolvePath("config/col.yml"));
        Map<String, Object> agentConfig = readYaml(resolvePath("config/agent-config.yml"));
        Map<String, Object> childSubstages = readYaml(resolvePath("config/child_substages.yml"));


        AppRuntimeConfig runtimeConfig = new AppRuntimeConfig();
        runtimeConfig.setConfig(config);
        runtimeConfig.setIndicators(indicators);
        runtimeConfig.setPatterns(patterns);
        runtimeConfig.setQuant(quant);
        runtimeConfig.setSubstages(substages);
        runtimeConfig.setChildSubstages(childSubstages);
        runtimeConfig.setBusinessRules(businessRules);
        runtimeConfig.setSymbolMetadata(asMap(config.get("symbol_metadata")));

        Map<String, Object> project = asMap(config.get("project"));
        Map<String, Object> output = asMap(config.get("output"));
        Map<String, Object> runtime = asMap(config.get("runtime"));
        Map<String, Object> ibkr = asMap(config.get("ibkr"));
        Map<String, Object> ml = asMap(config.get("ml"));
        Map<String, Object> alphaVantage = asMap(config.get("alpha_vantage"));
        runtimeConfig.setAlphaVantageEnabled(boolValue(alphaVantage.getOrDefault("enabled", false)));
        runtimeConfig.setAlphaVantageApiKey(stringValue(alphaVantage.getOrDefault("api_key", "")));
        runtimeConfig.setAlphaVantageBaseUrl(stringValue(alphaVantage.getOrDefault("base_url", "https://www.alphavantage.co/query")));
        runtimeConfig.setAlphaVantageTimeoutSeconds(intValue(alphaVantage.getOrDefault("timeout_seconds", 10)));
        runtimeConfig.setAlphaVantageEnrichFundamentals(boolValue(alphaVantage.getOrDefault("enrich_fundamentals", true)));
        runtimeConfig.setAlphaVantageEnrichNewsSentiment(boolValue(alphaVantage.getOrDefault("enrich_news_sentiment", true)));
        runtimeConfig.setAlphaVantageUseAsCandleFallback(boolValue(alphaVantage.getOrDefault("use_as_candle_fallback", false)));

        List<String> symbols = (List<String>) config.getOrDefault("symbols", Collections.emptyList());

        runtimeConfig.setProjectRoot(stringValue(project.getOrDefault("root", ".")));
        runtimeConfig.setOutputFile(stringValue(output.getOrDefault("file", "outputs/predictions_summary_out_nextgen.xlsx")));
        runtimeConfig.setCacheDir(stringValue(ibkr.getOrDefault("cache_dir", "cache")));
        runtimeConfig.setTrainingFeatureFile(stringValue(runtime.getOrDefault("training_feature_file", "python/model_service/training/raw_feature_history.csv")));
        runtimeConfig.setTtlMinutes(intValue(runtime.getOrDefault("ttl_minutes", 240)));
        runtimeConfig.setForceRefresh(boolValue(runtime.getOrDefault("force_refresh", false)));
        runtimeConfig.setIbHost(stringValue(ibkr.getOrDefault("host", "127.0.0.1")));
        runtimeConfig.setIbPort(intValue(ibkr.getOrDefault("port", 7496)));
        runtimeConfig.setIbClientId(intValue(ibkr.getOrDefault("client_id", 103)));
        runtimeConfig.setIbHistoryYears(intValue(ibkr.getOrDefault("history_years", 10)));
        runtimeConfig.setIbIncrementalOverlapDays(intValue(ibkr.getOrDefault("incremental_overlap_days", 10)));
        runtimeConfig.setMinCandlesForHealthyCache(intValue(ibkr.getOrDefault("min_candles_for_healthy_cache", 252)));

        runtimeConfig.setRebuildTrainingHistoryOnEveryRun(asBoolean(config.get("rebuild_training_history_on_every_run"), false));

        runtimeConfig.setSymbols(symbols == null ? List.of() : symbols);
        runtimeConfig.setColumns(parseColumns(columnsYaml));
        runtimeConfig.setAgentConfig(agentConfig);

        return runtimeConfig;
    }

    public Path resolvePath(String relativePath) {
        Path fsPath = Path.of("src/main/resources").resolve(relativePath);
        if (Files.exists(fsPath)) return fsPath;
        return Path.of(relativePath);
    }

    private double asDouble(Object value, double defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(value.toString());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private boolean asBoolean(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }

        if (value instanceof Boolean b) {
            return b;
        }

        if (value instanceof String s) {
            return Boolean.parseBoolean(s.trim());
        }

        return defaultValue;
    }

    public Map<String, Object> readYaml(Path path) {
        if (Files.exists(path)) {
            try (InputStream input = Files.newInputStream(path)) {
                return yamlMapper.readValue(input, new TypeReference<>() {
                });
            } catch (IOException e) {
                throw new IllegalStateException("Failed to read YAML from path: " + path, e);
            }
        }
        try (InputStream input = new ClassPathResource(path.toString()).getInputStream()) {
            return yamlMapper.readValue(input, new TypeReference<>() {
            });
        } catch (IOException ignored) {
            try (InputStream input = new ClassPathResource(path.toString().replace("src/main/resources/", "")).getInputStream()) {
                return yamlMapper.readValue(input, new TypeReference<>() {
                });
            } catch (IOException e) {
                throw new IllegalStateException("Unable to resolve YAML: " + path, e);
            }
        }
    }

    private List<ColumnDefinition> parseColumns(Map<String, Object> columnsYaml) {
        List<ColumnDefinition> columns = new ArrayList<>();

        Object raw = columnsYaml.get("columns");

        List<Map<String, Object>> items;

        if (raw instanceof List<?>) {
            items = (List<Map<String, Object>>) raw;

        } else if (raw instanceof Map<?, ?> map) {
            // Convert map → list
            items = new ArrayList<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("key", entry.getKey().toString());

                if (entry.getValue() instanceof Map<?, ?> inner) {
                    inner.forEach((k, v) -> value.put(String.valueOf(k), v));
                }

                items.add(value);
            }

        } else {
            return List.of();
        }

        for (Map<String, Object> item : items) {
            ColumnDefinition column = new ColumnDefinition();
            column.setName(stringValue(item.get("name")));
            column.setKey(stringValue(item.get("key")));
            column.setType(stringValue(item.getOrDefault("type", "string")));
            column.setDefaultValue(item.get("default"));
            column.setRequired(boolValue(item.getOrDefault("required", false)));
            column.setLayer(stringValue(item.getOrDefault("layer", "default")));
            columns.add(column);
        }

        return columns;
    }

    public Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return new LinkedHashMap<>();
    }
}
