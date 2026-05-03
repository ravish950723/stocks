def predict_probability(features: dict) -> float:
    score = float(features.get("signal_score", 0.0)) * 0.45
    score += min(1.0, float(features.get("regime_quality_score", 0.0)) / 100.0) * 0.35
    score += min(1.0, float(features.get("institutional_score", 0.0)) / 100.0) * 0.20
    return round(max(0.05, min(0.95, score)), 4)
