package com.trading.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class ParallelPipelineExecutor {

    @Value("${pipeline.parallel.enabled:true}")
    private boolean enabled;

    @Value("${pipeline.parallel.threads:6}")
    private int threads;

    @Value("${pipeline.parallel.timeout-minutes:90}")
    private long timeoutMinutes;

    public <T> List<T> execute(
            List<String> symbols,
            SymbolTask<T> task
    ) {
        if (!enabled || symbols == null || symbols.size() <= 1) {
            return runSequential(symbols, task);
        }

        int poolSize = Math.max(1, Math.min(threads, 8));

        ExecutorService executor = Executors.newFixedThreadPool(
                poolSize,
                r -> {
                    Thread t = new Thread(r);
                    t.setName("pipeline-symbol-worker-" + t.threadId());
                    t.setDaemon(false);
                    return t;
                }
        );

        List<Future<T>> futures = new ArrayList<>();

        log.info("PARALLEL_PIPELINE_START symbols={} threads={}", symbols.size(), poolSize);

        try {
            for (String symbol : symbols) {
                futures.add(executor.submit(() -> {
                    long start = System.currentTimeMillis();

                    try {
                        T result = task.process(symbol);

                        log.info(
                                "PARALLEL_SYMBOL_DONE symbol={} elapsedMs={}",
                                symbol,
                                System.currentTimeMillis() - start
                        );

                        return result;

                    } catch (Exception e) {
                        log.error(
                                "PARALLEL_SYMBOL_FAILED symbol={} error={}",
                                symbol,
                                e.getMessage(),
                                e
                        );

                        return task.onError(symbol, e);
                    }
                }));
            }

            executor.shutdown();

            boolean completed = executor.awaitTermination(timeoutMinutes, TimeUnit.MINUTES);
            if (!completed) {
                log.warn("PARALLEL_PIPELINE_TIMEOUT timeoutMinutes={}", timeoutMinutes);
                executor.shutdownNow();
            }

            List<T> results = new ArrayList<>();

            for (Future<T> future : futures) {
                try {
                    T value = future.get(5, TimeUnit.SECONDS);
                    if (value != null) {
                        results.add(value);
                    }
                } catch (Exception e) {
                    log.warn("PARALLEL_PIPELINE_RESULT_READ_FAILED error={}", e.getMessage());
                }
            }

            log.info("PARALLEL_PIPELINE_DONE inputSymbols={} outputRows={}", symbols.size(), results.size());
            return results;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
            throw new IllegalStateException("Parallel pipeline interrupted", e);
        }
    }

    private <T> List<T> runSequential(List<String> symbols, SymbolTask<T> task) {
        List<T> results = new ArrayList<>();

        if (symbols == null) {
            return results;
        }

        log.info("SEQUENTIAL_PIPELINE_START symbols={}", symbols.size());

        for (String symbol : symbols) {
            try {
                T result = task.process(symbol);
                if (result != null) {
                    results.add(result);
                }
            } catch (Exception e) {
                log.error("SEQUENTIAL_SYMBOL_FAILED symbol={} error={}", symbol, e.getMessage(), e);
                T errorRow = task.onError(symbol, e);
                if (errorRow != null) {
                    results.add(errorRow);
                }
            }
        }

        log.info("SEQUENTIAL_PIPELINE_DONE inputSymbols={} outputRows={}", symbols.size(), results.size());
        return results;
    }

    public interface SymbolTask<T> {
        T process(String symbol) throws Exception;

        T onError(String symbol, Exception e);
    }
}