package com.trading.agent.api;

public interface TradingAgent<I, O> {
    O act(I input);
}
