package com.trading.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.alphavantage.AlphaVantageClient;
import com.trading.config.AppRuntimeConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AlphaVantageCacheService {

    private static final int DEFAULT_ALPHA_VANTAGE_TTL_HOURS = 4;

    private final AlphaVantageClient alphaVantageClient;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public Map<String, Object> enrich(AppRuntimeConfig config, String symbol) {
        Map<String, Object> out = new LinkedHashMap<>();

        Path avDir = Path.of(config.getCacheDir()).resolve("alpha_vantage");
        Duration ttl = Duration.ofHours(resolveAlphaVantageTtlHours(config));

        if (config.isAlphaVantageEnrichFundamentals()) {
            out.putAll(getOrFetch(
                    avDir,
                    symbol,
                    "overview",
                    ttl,
                    config,
                    () -> alphaVantageClient.fetchCompanyOverview(config, symbol)
            ));

            out.putAll(getOrFetch(
                    avDir,
                    symbol,
                    "earnings",
                    ttl,
                    config,
                    () -> alphaVantageClient.fetchEarnings(config, symbol)
            ));
        }

        if (config.isAlphaVantageEnrichNewsSentiment()) {
            out.putAll(getOrFetch(
                    avDir,
                    symbol,
                    "news",
                    ttl,
                    config,
                    () -> alphaVantageClient.fetchNewsSentiment(config, symbol)
            ));
        }

        return out;
    }

    private Map<String, Object> getOrFetch(
            Path avDir,
            String symbol,
            String endpoint,
            Duration ttl,
            AppRuntimeConfig config,
            Fetcher fetcher
    ) {
        Path file = avDir.resolve(symbol + "_" + endpoint + ".json");

        if (!config.isForceRefresh()) {
            Optional<Map<String, Object>> cached = readFresh(file, ttl);
            if (cached.isPresent()) {
                log.info("ALPHA_VANTAGE_CACHE_HIT symbol={} endpoint={} ttlHours={} file={}",
                        symbol, endpoint, ttl.toHours(), file);
                return cached.get();
            }
        }

        log.info("ALPHA_VANTAGE_CACHE_MISS symbol={} endpoint={} ttlHours={} file={}",
                symbol, endpoint, ttl.toHours(), file);

        Map<String, Object> fresh;
        try {
            fresh = fetcher.fetch();
        } catch (Exception e) {
            log.warn("ALPHA_VANTAGE_FETCH_FAILED symbol={} endpoint={} error={}", symbol, endpoint, e.getMessage());
            return readAny(file).orElse(Map.of());
        }

        if (fresh == null) {
            fresh = Map.of();
        }

        if (isUsableResponse(fresh)) {
            write(file, fresh);
            return fresh;
        }

        log.warn("ALPHA_VANTAGE_EMPTY_OR_ERROR_RESPONSE symbol={} endpoint={} keys={} action=KEEP_OLD_CACHE",
                symbol, endpoint, fresh.keySet());
        return readAny(file).orElse(Map.of());
    }

    private Optional<Map<String, Object>> readFresh(Path file, Duration ttl) {
        try {
            if (!Files.exists(file)) {
                return Optional.empty();
            }

            Instant lastModified = Files.getLastModifiedTime(file).toInstant();
            boolean fresh = Duration.between(lastModified, Instant.now()).compareTo(ttl) < 0;

            if (!fresh) {
                return Optional.empty();
            }

            Map<String, Object> data = mapper.readValue(file.toFile(), Map.class);
            return Optional.of(data);

        } catch (Exception e) {
            log.warn("ALPHA_VANTAGE_CACHE_READ_FAILED file={} error={}", file, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<Map<String, Object>> readAny(Path file) {
        try {
            if (!Files.exists(file)) {
                return Optional.empty();
            }
            Map<String, Object> data = mapper.readValue(file.toFile(), Map.class);
            log.info("ALPHA_VANTAGE_STALE_CACHE_FALLBACK file={}", file);
            return Optional.of(data);
        } catch (Exception e) {
            log.warn("ALPHA_VANTAGE_STALE_CACHE_READ_FAILED file={} error={}", file, e.getMessage());
            return Optional.empty();
        }
    }

    private boolean isUsableResponse(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return false;
        }
        if (data.containsKey("Note") || data.containsKey("Error Message") || data.containsKey("Information")) {
            return false;
        }
        return true;
    }

    /**
     * Compile-safe even if AppRuntimeConfig does not yet have getAlphaVantageTtlHours().
     * Preferred: add private int alphaVantageTtlHours = 4 to AppRuntimeConfig.
     * Fallback: use ttlMinutes / 60, then default 4 hours.
     */
    private int resolveAlphaVantageTtlHours(AppRuntimeConfig config) {
        try {
            Method method = config.getClass().getMethod("getAlphaVantageTtlHours");
            Object value = method.invoke(config);
            if (value instanceof Number n && n.intValue() > 0) {
                return n.intValue();
            }
        } catch (Exception ignored) {
            // Backward compatible fallback below.
        }

        int ttlMinutes = config.getTtlMinutes();
        if (ttlMinutes > 0) {
            return Math.max(1, ttlMinutes / 60);
        }
        return DEFAULT_ALPHA_VANTAGE_TTL_HOURS;
    }

    private void write(Path file, Map<String, Object> data) {
        try {
            Files.createDirectories(file.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), data);
            log.info("ALPHA_VANTAGE_CACHE_WRITE file={}", file);
        } catch (Exception e) {
            log.warn("ALPHA_VANTAGE_CACHE_WRITE_FAILED file={} error={}", file, e.getMessage());
        }
    }

    @FunctionalInterface
    private interface Fetcher {
        Map<String, Object> fetch();
    }
}
