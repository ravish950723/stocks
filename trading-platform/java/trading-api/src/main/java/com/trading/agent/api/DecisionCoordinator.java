package com.trading.agent.api;

import com.trading.agent.model.FinalTradeDecision;
import com.trading.agent.model.MarketContext;

public interface DecisionCoordinator {
    FinalTradeDecision evaluate(MarketContext marketContext);
}
