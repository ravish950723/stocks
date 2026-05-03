package com.trading.ibkr;

import com.ib.client.*;
import com.trading.entry.Candle;
import com.trading.logging.SymbolLoggingContext;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Slf4j
public class InteractiveBrokersHistoricalDataClient {

    private static final int CONTRACT_VALIDATION_REQ_ID_OFFSET = 100_000;
    private static final List<String> PRIMARY_EXCHANGE_FALLBACKS = List.of(
            "",
            "NASDAQ",
            "NYSE",
            "ARCA",
            "BATS",
            "ISLAND"
    );

    private static final List<Integer> INFO_CODES = List.of(2104, 2106, 2107, 2108, 2157, 2158);

    private final Object connectionLock = new Object();
    private final IBWrapper wrapper = new IBWrapper();
    private final EJavaSignal signal = new EJavaSignal();
    private final EClientSocket client = new EClientSocket(wrapper, signal);
    private final AtomicBoolean readerStarted = new AtomicBoolean(false);
    private final AtomicInteger fallbackReqId = new AtomicInteger(1000);

    @Value("${ibkr.host:127.0.0.1}")
    private String configuredHost;

    @Value("${ibkr.port:7497}")
    private int configuredPort;

    @Value("${ibkr.client-id:77}")
    private int configuredClientId;

    public List<Candle> fetchDailyCandles(String host,
                                          int port,
                                          int clientId,
                                          String symbol,
                                          LocalDate startDateInclusive,
                                          int historyYears) {

        log.info("Fetching IBKR daily candles: symbol={}, startDateInclusive={}, historyYears={}",
                symbol, startDateInclusive, historyYears);

        String resolvedHost = isBlank(host) ? configuredHost : host;
        int resolvedPort = port > 0 ? port : configuredPort;
        int resolvedClientId = clientId > 0 ? clientId : configuredClientId;

        ensureConnected(resolvedHost, resolvedPort, resolvedClientId);

        int reqId = wrapper.nextRequestId();
        wrapper.register(reqId, symbol);

        Contract contract = buildResolvedContract(symbol, reqId);

        String duration;
        if (startDateInclusive == null) {
            duration = Math.max(historyYears, 1) + " Y";
        } else {
            long daysSince = ChronoUnit.DAYS.between(startDateInclusive, LocalDate.now());
            long paddedDays = Math.max(5, daysSince + 2);
            duration = paddedDays <= 365 ? paddedDays + " D" : ((paddedDays / 365) + 1) + " Y";
        }

        log.info("Requesting IBKR historical data: symbol={}, reqId={}, host={}, port={}, contract={}/{}, primaryExch={}",
                symbol,
                reqId,
                resolvedHost,
                resolvedPort,
                contract.secType(),
                contract.exchange(),
                contract.primaryExch());

        client.reqMarketDataType(3);

        client.reqHistoricalData(
                reqId,
                contract,
                "",
                duration,
                "1 day",
                "TRADES",
                1,
                1,
                false,
                null
        );

        log.debug("IBKR duration used for symbol={} reqId={}: {}", symbol, reqId, duration);

        List<Candle> raw = wrapper.awaitHistoricalData(reqId, 90);

        Map<LocalDate, Candle> byDate = new TreeMap<>();
        for (Candle candle : raw) {
            byDate.put(candle.getDate(), candle);
        }
        List<Candle> candles = new ArrayList<>(byDate.values());

        if (startDateInclusive != null) {
            List<Candle> filtered = candles.stream()
                    .filter(c -> !c.getDate().isBefore(startDateInclusive))
                    .toList();

            if (!filtered.isEmpty()) {
                candles = filtered;
            } else {
                log.warn("Cutoff removed all candles for symbol={}. Returning unfiltered candles. startDateInclusive={}, rawCount={}",
                        symbol, startDateInclusive, candles.size());
            }
        }

        log.info("Fetched {} candles for symbol={} from IBKR", candles.size(), symbol);
        return candles;
    }

    private Contract buildResolvedContract(String symbol, int reqId) {
        Contract base = buildBaseContract(symbol);
        IllegalStateException lastError = null;

        for (String primaryExch : PRIMARY_EXCHANGE_FALLBACKS) {
            Contract candidate = copyContract(base);

            if (primaryExch != null && !primaryExch.isBlank()) {
                candidate.primaryExch(primaryExch);
            }

            try {
                validateContract(candidate, reqId, symbol);
                log.info("Resolved IBKR contract for symbol={} using primaryExch={}",
                        symbol,
                        primaryExch == null || primaryExch.isBlank() ? "<none>" : primaryExch);
                return candidate;
            } catch (IllegalStateException e) {
                lastError = e;
                log.warn("Contract resolution failed for symbol={} primaryExch={}: {}",
                        symbol,
                        primaryExch == null || primaryExch.isBlank() ? "<none>" : primaryExch,
                        e.getMessage());
            }
        }

        throw new IllegalStateException("Unable to resolve contract for symbol=" + symbol, lastError);
    }

    private Contract buildBaseContract(String symbol) {
        Contract contract = new Contract();
        contract.symbol(symbol);
        contract.secType("STK");
        contract.exchange("SMART");
        contract.currency("USD");
        return contract;
    }

    private Contract copyContract(Contract original) {
        Contract copy = new Contract();
        copy.symbol(original.symbol());
        copy.secType(original.secType());
        copy.exchange(original.exchange());
        copy.currency(original.currency());

        if (original.primaryExch() != null && !original.primaryExch().isBlank()) {
            copy.primaryExch(original.primaryExch());
        }

        return copy;
    }

    private void validateContract(Contract contract, int reqId, String symbol) {
        int validationReqId = reqId + CONTRACT_VALIDATION_REQ_ID_OFFSET;
        wrapper.registerContractValidation(validationReqId, symbol);

        client.reqContractDetails(validationReqId, contract);
        wrapper.awaitContractValidation(validationReqId, 15);
    }

    private void ensureConnected(String host, int port, int clientId) {
        synchronized (connectionLock) {
            if (client.isConnected()) {
                log.debug("IBKR client already connected to host={} port={} clientId={}", host, port, clientId);
                return;
            }

            wrapper.prepareForFreshConnection();
            log.info("Connecting to IBKR host={} port={} clientId={}", host, port, clientId);
            client.eConnect(host, port, clientId);

            if (!client.isConnected()) {
                log.error("Could not connect to TWS/IB Gateway at {}:{}", host, port);
                throw new IllegalStateException("Could not connect to TWS/IB Gateway at %s:%s".formatted(host, port));
            }

            if (readerStarted.compareAndSet(false, true)) {
                EReader reader = new EReader(client, signal);
                reader.start();

                Thread readerThread = new Thread(() -> {
                    while (true) {
                        try {
                            if (!client.isConnected()) {
                                Thread.sleep(250);
                                continue;
                            }
                            signal.waitForSignal();
                            reader.processMsgs();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        } catch (Exception e) {
                            log.error("IBKR reader thread failed", e);
                            wrapper.failAllOpenRequests("IBKR reader thread failed: " + e.getMessage());
                            wrapper.failAllContractValidations("IBKR reader thread failed: " + e.getMessage());
                        }
                    }
                }, "ibkr-reader-shared");
                readerThread.setDaemon(true);
                readerThread.start();
            }

            wrapper.awaitReady();
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @PreDestroy
    public void shutdown() {
        synchronized (connectionLock) {
            try {
                if (client.isConnected()) {
                    log.info("Disconnecting IBKR client");
                    client.eDisconnect();
                }
            } catch (Exception ignored) {
                // no-op during shutdown
            }
        }
    }

    private static final class RequestState {
        final String symbol;
        final Map<String, String> logContext;
        final CountDownLatch done = new CountDownLatch(1);
        final List<Candle> candles = new ArrayList<>();
        volatile String error;

        RequestState(String symbol, Map<String, String> logContext) {
            this.symbol = symbol;
            this.logContext = logContext == null ? Map.of() : Map.copyOf(logContext);
        }
    }

    private static final class ContractValidationState {
        final String symbol;
        final CountDownLatch done = new CountDownLatch(1);
        volatile String error;
        volatile boolean resolved;

        ContractValidationState(String symbol) {
            this.symbol = symbol;
        }
    }

    private final class IBWrapper extends DefaultEWrapper {
        private final AtomicInteger nextValidId = new AtomicInteger(0);
        private final Map<Integer, RequestState> requests = new ConcurrentHashMap<>();
        private final Map<Integer, ContractValidationState> contractValidations = new ConcurrentHashMap<>();
        private volatile CountDownLatch readyLatch = new CountDownLatch(1);

        void prepareForFreshConnection() {
            readyLatch = new CountDownLatch(1);
        }

        int nextRequestId() {
            int id = nextValidId.incrementAndGet();
            return id > 0 ? id : fallbackReqId.incrementAndGet();
        }

        void register(int reqId, String symbol) {
            requests.put(reqId, new RequestState(symbol, SymbolLoggingContext.snapshot()));
            log.debug("Registered IBKR request reqId={} symbol={}", reqId, symbol);
        }

        void registerContractValidation(int reqId, String symbol) {
            contractValidations.put(reqId, new ContractValidationState(symbol));
            log.debug("Registered IBKR contract validation reqId={} symbol={}", reqId, symbol);
        }

        void awaitReady() {
            try {
                if (nextValidId.get() > 0) {
                    return;
                }
                if (!readyLatch.await(20, TimeUnit.SECONDS)) {
                    log.error("Timed out waiting for IBKR nextValidId handshake");
                    throw new IllegalStateException("Timed out waiting for IBKR nextValidId handshake");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for IBKR readiness", e);
            }
        }

        List<Candle> awaitHistoricalData(int reqId, int timeoutSeconds) {
            RequestState state = requests.get(reqId);
            if (state == null) {
                throw new IllegalStateException("Unknown IBKR request id " + reqId);
            }

            try {
                if (!state.done.await(timeoutSeconds, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting for historical data for request "
                            + reqId + " symbol=" + state.symbol);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for historical data", e);
            }

            requests.remove(reqId);

            if (state.error != null) {
                throw new IllegalStateException(state.error);
            }

            state.candles.sort(Comparator.comparing(Candle::getDate));
            return new ArrayList<>(state.candles);
        }

        void awaitContractValidation(int reqId, int timeoutSeconds) {
            ContractValidationState state = contractValidations.get(reqId);
            if (state == null) {
                throw new IllegalStateException("Unknown contract validation request id " + reqId);
            }

            try {
                if (!state.done.await(timeoutSeconds, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out validating contract for request "
                            + reqId + " symbol=" + state.symbol);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while validating contract", e);
            } finally {
                contractValidations.remove(reqId);
            }

            if (state.error != null) {
                throw new IllegalStateException(state.error);
            }

            if (!state.resolved) {
                throw new IllegalStateException("No contract details returned for symbol=" + state.symbol);
            }
        }

        void failAllOpenRequests(String message) {
            for (RequestState state : requests.values()) {
                if (state.error == null) {
                    state.error = message;
                }
                state.done.countDown();
            }
        }

        void failAllContractValidations(String message) {
            for (ContractValidationState state : contractValidations.values()) {
                if (state.error == null) {
                    state.error = message;
                }
                state.done.countDown();
            }
        }

        @Override
        public void nextValidId(int orderId) {
            nextValidId.set(orderId);
            log.info("IBKR nextValidId received={}", orderId);
            readyLatch.countDown();
        }

        @Override
        public void contractDetails(int reqId, ContractDetails contractDetails) {
            ContractValidationState state = contractValidations.get(reqId);
            if (state == null) {
                return;
            }
            state.resolved = true;
        }

        @Override
        public void contractDetailsEnd(int reqId) {
            ContractValidationState state = contractValidations.get(reqId);
            if (state == null) {
                return;
            }
            state.done.countDown();
        }

        @Override
        public void historicalData(int reqId, Bar bar) {
            RequestState state = requests.get(reqId);
            if (state == null) {
                return;
            }

            String rawDate = bar.time();
            if (rawDate == null || rawDate.isBlank() || rawDate.startsWith("finished")) {
                return;
            }

            LocalDate date;
            try {
                String cleaned = rawDate.trim();
                if (cleaned.matches("\\d{8}")) {
                    date = LocalDate.of(
                            Integer.parseInt(cleaned.substring(0, 4)),
                            Integer.parseInt(cleaned.substring(4, 6)),
                            Integer.parseInt(cleaned.substring(6, 8))
                    );
                } else {
                    long epoch = Long.parseLong(cleaned);
                    date = java.time.Instant.ofEpochSecond(epoch)
                            .atZone(ZoneId.systemDefault())
                            .toLocalDate();
                }
            } catch (Exception e) {
                state.error = "Failed parsing IBKR bar date '" + rawDate + "' for reqId=" + reqId + ": " + e.getMessage();
                try (SymbolLoggingContext ignored = SymbolLoggingContext.open(state.logContext)) {
                    log.error(state.error, e);
                }
                state.done.countDown();
                return;
            }

            state.candles.add(new Candle(
                    date,
                    bar.open(),
                    bar.high(),
                    bar.low(),
                    bar.close(),
                    bar.volume().longValue()
            ));

            try (SymbolLoggingContext ignored = SymbolLoggingContext.open(state.logContext)) {
                log.debug("IBKR bar received: reqId={}, symbol={}, date={}, close={}",
                        reqId, state.symbol, date, bar.close());
            }
        }

        @Override
        public void historicalDataEnd(int reqId, String startDateStr, String endDateStr) {
            RequestState state = requests.get(reqId);
            if (state == null) {
                return;
            }
            try (SymbolLoggingContext ignored = SymbolLoggingContext.open(state.logContext)) {
                log.info("IBKR historical data end: reqId={}, symbol={}, start={}, end={}, bars={}",
                        reqId, state.symbol, startDateStr, endDateStr, state.candles.size());
            }
            state.done.countDown();
        }

        @Override
        public void error(int id, long errorTime, int errorCode, String errorMsg, String advancedOrderRejectJson) {
            if (INFO_CODES.contains(errorCode)) {
                log.info("IBKR info message: code={}, id={}, message={}", errorCode, id, errorMsg);
                return;
            }

            ContractValidationState validationState = contractValidations.get(id);
            if (validationState != null) {
                validationState.error = "IBKR contract validation error " + errorCode
                        + " for request " + id + " symbol=" + validationState.symbol + ": " + errorMsg;
                validationState.done.countDown();
                return;
            }

            RequestState state = requests.get(id);
            if (state != null) {
                state.error = "IBKR error " + errorCode + " for request " + id + " symbol=" + state.symbol + ": " + errorMsg;
                try (SymbolLoggingContext ignored = SymbolLoggingContext.open(state.logContext)) {
                    log.error(state.error);
                }
                state.done.countDown();
                return;
            }

            log.warn("IBKR warning: code={}, id={}, message={}", errorCode, id, errorMsg);
        }

        @Override
        public void error(Exception e) {
            failAllOpenRequests("IBKR exception: " + e.getMessage());
            failAllContractValidations("IBKR exception: " + e.getMessage());
        }

        @Override
        public void connectionClosed() {
            failAllOpenRequests("Connection to IBKR closed unexpectedly");
            failAllContractValidations("Connection to IBKR closed unexpectedly");
        }
    }
}