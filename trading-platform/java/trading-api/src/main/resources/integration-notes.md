# Integration Notes

## Recommended insertion point
Replace your current recommendation-engine block with the following sequence:

1. Build `MarketContext`
2. Call `DefaultDecisionCoordinator.evaluate()`
3. Map `FinalTradeDecision` into Excel columns / API response

## Suggested output columns
- Symbol
- Market Stage
- Market Sub-Stage
- Opportunity Score
- Analyst Bias
- Setup Type
- Analyst Confidence
- Candidate Entry
- Risk Score
- Approved
- Stop Loss
- Target1
- Target2
- Risk Reward
- Position Size %
- Final Action
- Confidence Band
- Thesis
- Veto Reasons

## Migration mapping
- `computeSignalScore()` -> AnalystAgent
- `computeRecommendation()` -> DecisionCoordinator
- `computeBestRiskReward()` -> RiskAgent
- stage/substage remain upstream inputs to agents
