package com.trading.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.contracts.CachedSymbolData;
import com.trading.entry.Candle;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class CacheService {

    private static final Logger log = LogManager.getLogger(CacheService.class);
    private static final int DEFAULT_TTL_MINUTES = 240; // 4 hours

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public Optional<CachedSymbolData> readSymbol(Path cacheDir, String symbol) {
        Path path = filePath(cacheDir, symbol);
        try {
            if (!Files.exists(path)) {
                log.debug("Cache file not found for symbol={} at path={}", symbol, path);
                return Optional.empty();
            }
            CachedSymbolData data = mapper.readValue(path.toFile(), CachedSymbolData.class);
            if (data.getCandles() != null) {
                data.setCandles(sanitizeCandles(data.getCandles()));
            }
            log.info("Loaded cache for symbol={} with {} candles from {} lastUpdated={}",
                    symbol,
                    data.getCandles() == null ? 0 : data.getCandles().size(),
                    path,
                    data.getLastUpdated());
            return Optional.of(data);
        } catch (Exception e) {
            log.error("Unable to read cache for symbol={} from path={}", symbol, path, e);
            throw new IllegalStateException("Unable to read cache for " + symbol, e);
        }
    }

    public void writeSymbol(Path cacheDir, CachedSymbolData data) {
        Path path = filePath(cacheDir, data.getSymbol());
        Path tmpPath = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());

            data.setLastUpdated(Instant.now());
            data.setCandles(sanitizeCandles(data.getCandles()));

            mapper.writerWithDefaultPrettyPrinter().writeValue(tmpPath.toFile(), data);
            Files.move(tmpPath, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

            log.info("Wrote cache for symbol={} with {} candles to {} lastUpdated={}",
                    data.getSymbol(),
                    data.getCandles() == null ? 0 : data.getCandles().size(),
                    path,
                    data.getLastUpdated());

        } catch (Exception e) {
            try {
                Files.deleteIfExists(tmpPath);
            } catch (Exception ignored) {
                // best effort cleanup
            }
            log.error("Unable to write cache for symbol={} to path={}", data.getSymbol(), path, e);
            throw new IllegalStateException("Unable to write cache for " + data.getSymbol(), e);
        }
    }

    public boolean shouldRefresh(Optional<CachedSymbolData> cached, int ttlMinutes, boolean forceRefresh) {
        if (forceRefresh || cached.isEmpty()) {
            return true;
        }

        int safeTtlMinutes = ttlMinutes > 0 ? ttlMinutes : DEFAULT_TTL_MINUTES;
        Instant lastUpdated = cached.get().getLastUpdated();
        if (lastUpdated == null) {
            return true;
        }

        long ageMinutes = Duration.between(lastUpdated, Instant.now()).toMinutes();
        boolean refresh = ageMinutes >= safeTtlMinutes;
        log.debug("CACHE_TTL_CHECK symbol={} ageMinutes={} ttlMinutes={} shouldRefresh={}",
                cached.get().getSymbol(), ageMinutes, safeTtlMinutes, refresh);
        return refresh;
    }

    public List<Candle> mergeCandles(List<Candle> existing, List<Candle> incoming) {
        Map<String, Candle> merged = new LinkedHashMap<>();
        for (Candle candle : sanitizeCandles(existing)) {
            merged.put(candle.getDate().toString(), candle);
        }
        for (Candle candle : sanitizeCandles(incoming)) {
            merged.put(candle.getDate().toString(), candle);
        }
        List<Candle> values = new ArrayList<>(merged.values());
        values.sort(Comparator.comparing(Candle::getDate));
        return values;
    }

    public List<Candle> sanitizeCandles(List<Candle> candles) {
        if (candles == null || candles.isEmpty()) {
            return List.of();
        }
        return candles.stream()
                .filter(c -> c != null && c.getDate() != null)
                .sorted(Comparator.comparing(Candle::getDate))
                .toList();
    }

    public boolean isHealthy(Optional<CachedSymbolData> cached, int minCandles) {
        return cached.map(CachedSymbolData::getCandles)
                .map(this::sanitizeCandles)
                .filter(c -> c.size() >= minCandles)
                .isPresent();
    }

    public Optional<LocalDate> latestCandleDate(Optional<CachedSymbolData> cached) {
        return cached.map(CachedSymbolData::getCandles)
                .map(this::sanitizeCandles)
                .filter(c -> !c.isEmpty())
                .map(c -> c.get(c.size() - 1).getDate());
    }

    private Path filePath(Path cacheDir, String symbol) {
        return cacheDir.resolve(symbol + ".json");
    }
}
