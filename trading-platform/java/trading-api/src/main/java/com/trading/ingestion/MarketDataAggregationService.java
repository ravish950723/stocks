package com.trading.ingestion;

import com.trading.cache.CacheService;
import com.trading.config.AppRuntimeConfig;
import com.trading.contracts.CachedSymbolData;
import com.trading.contracts.MarketSnapshot;
import com.trading.entry.Candle;
import com.trading.ibkr.InteractiveBrokersHistoricalDataClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketDataAggregationService {

    private static final int DEFAULT_IBKR_TTL_MINUTES = 240; // 4 hours

    private final CacheService cacheService;
    private final InteractiveBrokersHistoricalDataClient ibClient;

    public MarketSnapshot loadDailySnapshot(String symbol, AppRuntimeConfig config) {
        Path cacheDir = resolveCacheDir(config);
        Optional<CachedSymbolData> cached = cacheService.readSymbol(cacheDir, symbol);

        List<Candle> candles = cached.map(CachedSymbolData::getCandles)
                .map(cacheService::sanitizeCandles)
                .orElse(List.of());

        int ttlMinutes = resolveIbkrTtlMinutes(config);
        boolean healthyCache = cacheService.isHealthy(cached, config.getMinCandlesForHealthyCache());
        boolean ttlExpired = cacheService.shouldRefresh(cached, ttlMinutes, config.isForceRefresh());

        LocalDate latestCachedDate = cacheService.latestCandleDate(cached).orElse(null);
        LocalDate today = LocalDate.now();
        LocalDate lastMarketDay = resolveLastMarketDay(today);

        boolean fullBackfillNeeded = candles.isEmpty();

        boolean missingLastMarketDay =
                latestCachedDate == null || latestCachedDate.isBefore(lastMarketDay);

        boolean latestCacheAlreadyHasLastMarketDay =
                latestCachedDate != null && latestCachedDate.isEqual(lastMarketDay);

        boolean shouldRefresh =
                config.isForceRefresh()
                        || fullBackfillNeeded
                        || ttlExpired
                        || missingLastMarketDay;

        String refreshReason = resolveRefreshReason(
                config.isForceRefresh(),
                fullBackfillNeeded,
                ttlExpired,
                missingLastMarketDay,
                latestCacheAlreadyHasLastMarketDay
        );

        log.info(
                "CACHE_DECISION symbol={} cacheDir={} cachedBars={} healthyCache={} ttlMinutes={} " +
                        "forceRefresh={} ttlExpired={} fullBackfillNeeded={} latestCachedDate={} " +
                        "lastMarketDay={} missingLastMarketDay={} latestCacheAlreadyHasLastMarketDay={} " +
                        "shouldRefresh={} reason={}",
                symbol,
                cacheDir.toAbsolutePath(),
                candles.size(),
                healthyCache,
                ttlMinutes,
                config.isForceRefresh(),
                ttlExpired,
                fullBackfillNeeded,
                latestCachedDate,
                lastMarketDay,
                missingLastMarketDay,
                latestCacheAlreadyHasLastMarketDay,
                shouldRefresh,
                refreshReason
        );

        if (shouldRefresh) {
            LocalDate startDateInclusive = null;

            if (!fullBackfillNeeded && latestCachedDate != null) {
                int overlapDays = Math.max(0, config.getIncrementalOverlapDays());
                startDateInclusive = latestCachedDate.minusDays(overlapDays);
            }

            log.info(
                    "IBKR_FETCH_START symbol={} mode={} reason={} startDateInclusive={} " +
                            "oldLatestCachedDate={} lastMarketDay={} ttlMinutes={} historyYears={} " +
                            "host={} port={} clientId={}",
                    symbol,
                    fullBackfillNeeded ? "FULL" : "INCREMENTAL",
                    refreshReason,
                    startDateInclusive,
                    latestCachedDate,
                    lastMarketDay,
                    ttlMinutes,
                    config.getIbHistoryYears(),
                    config.getIbHost(),
                    config.getIbPort(),
                    config.getIbClientId()
            );

            List<Candle> fetched = ibClient.fetchDailyCandles(
                    config.getIbHost(),
                    config.getIbPort(),
                    config.getIbClientId(),
                    symbol,
                    startDateInclusive,
                    config.getIbHistoryYears()
            );

            List<Candle> sanitizedFetched = cacheService.sanitizeCandles(fetched);

            LocalDate fetchedLatestDate = sanitizedFetched.isEmpty()
                    ? null
                    : sanitizedFetched.get(sanitizedFetched.size() - 1).getDate();

            log.info(
                    "IBKR_FETCH_DONE symbol={} fetchedRaw={} fetchedSanitized={} fetchedLatestDate={} " +
                            "oldCachedBars={} oldLatestCachedDate={}",
                    symbol,
                    fetched == null ? 0 : fetched.size(),
                    sanitizedFetched.size(),
                    fetchedLatestDate,
                    candles.size(),
                    latestCachedDate
            );

            candles = fullBackfillNeeded
                    ? sanitizedFetched
                    : cacheService.mergeCandles(candles, sanitizedFetched);

            if (candles.isEmpty() && cached.isPresent()) {
                candles = cacheService.sanitizeCandles(cached.get().getCandles());
                log.warn(
                        "IBKR_FETCH_EMPTY_REUSED_OLD_CACHE symbol={} cachedBars={} oldLatestCachedDate={} reason={}",
                        symbol,
                        candles.size(),
                        latestCachedDate,
                        refreshReason
                );
            }

            CachedSymbolData data = new CachedSymbolData();
            data.setSymbol(symbol);
            data.setLastUpdated(Instant.now());
            data.setCandles(candles);
            cacheService.writeSymbol(cacheDir, data);

            LocalDate newLatestCachedDate = cacheService.latestCandleDate(Optional.of(data)).orElse(null);

            boolean stillMissingLastMarketDay =
                    newLatestCachedDate == null || newLatestCachedDate.isBefore(lastMarketDay);

            log.info(
                    "IBKR_CACHE_UPDATED symbol={} mode={} reason={} fetched={} totalBars={} " +
                            "oldLatestCachedDate={} newLatestCachedDate={} lastMarketDay={} " +
                            "stillMissingLastMarketDay={} startDateInclusive={}",
                    symbol,
                    fullBackfillNeeded ? "FULL" : "INCREMENTAL",
                    refreshReason,
                    sanitizedFetched.size(),
                    candles.size(),
                    latestCachedDate,
                    newLatestCachedDate,
                    lastMarketDay,
                    stillMissingLastMarketDay,
                    startDateInclusive
            );

            if (stillMissingLastMarketDay) {
                log.warn(
                        "IBKR_CACHE_STILL_BEHIND symbol={} newLatestCachedDate={} lastMarketDay={} " +
                                "message='IBKR did not return latest market day candle yet. This can happen before daily bar is finalized or if IBKR data is delayed.'",
                        symbol,
                        newLatestCachedDate,
                        lastMarketDay
                );
            }

        } else {
            log.info(
                    "CACHE_REUSED symbol={} totalBars={} latestCachedDate={} lastMarketDay={} ttlMinutes={} reason={}",
                    symbol,
                    candles.size(),
                    latestCachedDate,
                    lastMarketDay,
                    ttlMinutes,
                    refreshReason
            );
        }

        if (candles.isEmpty()) {
            throw new IllegalStateException("No candles available for " + symbol);
        }

        MarketSnapshot snapshot = new MarketSnapshot();
        snapshot.setSymbol(symbol);
        snapshot.setTimeframe("DAILY");
        snapshot.setCandles(candles);
        snapshot.setCurrentPrice(candles.get(candles.size() - 1).getClose());
        snapshot.setAsOf(Instant.now());

        log.info(
                "SNAPSHOT_READY symbol={} timeframe={} candles={} currentPrice={} latestCandleDate={}",
                symbol,
                snapshot.getTimeframe(),
                candles.size(),
                snapshot.getCurrentPrice(),
                candles.get(candles.size() - 1).getDate()
        );

        return snapshot;
    }

    private String resolveRefreshReason(
            boolean forceRefresh,
            boolean fullBackfillNeeded,
            boolean ttlExpired,
            boolean missingLastMarketDay,
            boolean latestCacheAlreadyHasLastMarketDay
    ) {
        if (forceRefresh) {
            return "FORCE_REFRESH";
        }
        if (fullBackfillNeeded) {
            return "FULL_BACKFILL_EMPTY_CACHE";
        }
        if (missingLastMarketDay) {
            return "MISSING_LAST_MARKET_DAY";
        }
        if (ttlExpired) {
            return "TTL_EXPIRED_INTRADAY_REFRESH";
        }
        if (latestCacheAlreadyHasLastMarketDay) {
            return "CACHE_HAS_LAST_MARKET_DAY";
        }
        return "CACHE_REUSE";
    }

    private int resolveIbkrTtlMinutes(AppRuntimeConfig config) {
        int ttl = config.getTtlMinutes();
        return ttl > 0 ? ttl : DEFAULT_IBKR_TTL_MINUTES;
    }

    private LocalDate resolveLastMarketDay(LocalDate today) {
        return switch (today.getDayOfWeek()) {
            case SATURDAY -> today.minusDays(1);
            case SUNDAY -> today.minusDays(2);
            default -> today;
        };
    }

    private Path resolveCacheDir(AppRuntimeConfig config) {
        Path direct = Path.of(config.getCacheDir());
        if (direct.isAbsolute()) {
            return direct;
        }

        return Path.of(System.getProperty("user.dir"))
                .resolve(config.getCacheDir())
                .normalize();
    }
}