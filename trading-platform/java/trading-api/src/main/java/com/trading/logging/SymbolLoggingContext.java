package com.trading.logging;

import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SymbolLoggingContext implements AutoCloseable {

    private static final String KEY_SYMBOL = "symbol";
    private static final String KEY_CORR = "corr";

    private final Map<String, String> previousContext;

    public static SymbolLoggingContext open(String symbol) {
        Map<String, String> context = new HashMap<>();
        context.put(KEY_SYMBOL, symbol == null ? "" : symbol);
        context.put(KEY_CORR, UUID.randomUUID().toString().substring(0, 12));
        return new SymbolLoggingContext(context);
    }

    public static SymbolLoggingContext open(Map<String, String> context) {
        return new SymbolLoggingContext(context);
    }

    public static Map<String, String> snapshot() {
        Map<String, String> current = MDC.getCopyOfContextMap();
        if (current == null || current.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(current);
    }

    private SymbolLoggingContext(Map<String, String> context) {
        Map<String, String> current = MDC.getCopyOfContextMap();
        this.previousContext = current == null ? Map.of() : Map.copyOf(current);

        if (context != null) {
            context.forEach((k, v) -> {
                if (v != null) {
                    MDC.put(k, v);
                }
            });
        }
    }

    @Override
    public void close() {
        MDC.clear();
        if (previousContext != null && !previousContext.isEmpty()) {
            MDC.setContextMap(previousContext);
        }
    }
}