from __future__ import annotations

import math
from typing import Any

import joblib
import pandas as pd
from fastapi import FastAPI

from app.ml_config import (
    DEFAULT_THRESHOLD,
    DL_BUNDLE_PATH,
    DL_METRICS_PATH,
    DL_ENSEMBLE_WEIGHT,
    ENSEMBLE_METRICS_PATH,
    ENSEMBLE_MAX_MODEL_GAP_FOR_BUY,
    MAX_SELECTED_THRESHOLD,
    FEATURE_RANGES,
    FEATURES,
    LIVE_BUY_FLOOR,
    LIVE_STRONG_BUY_FLOOR,
    MAX_PROBABILITY_WHEN_REQUIRED_MISSING,
    MAX_PROBABILITY_WHEN_OPTIONAL_COVERAGE_LOW,
    MIN_OPTIONAL_FEATURE_COVERAGE_FOR_BUY,
    OPTIONAL_FEATURES,
    REQUIRED_LIVE_FEATURES,
    SCHEMA_VERSION,
    XGB_ENSEMBLE_WEIGHT,
    XGB_METRICS_PATH,
    XGB_MODEL_PATH,
)
from app.schemas import MlRequest, MlResponse

app = FastAPI(title="Trading ML/DL Service", version="8.0.0")

xgb_artifact: dict[str, Any] = {}
dl_artifact: dict[str, Any] = {}
model_features: list[str] = FEATURES.copy()
model_threshold: float = DEFAULT_THRESHOLD
model_version: str = "unavailable"
promotion_status: str = "SUPPORTING_SIGNAL_ONLY"
model_metrics: dict[str, Any] = {}
ensemble_metrics: dict[str, Any] = {}

FIELD_TO_FEATURE = {
    "currentPrice": "current_price", "rsi": "rsi", "adx": "adx", "atr14Pct": "atr14_pct",
    "vwapDistancePct": "vwap_distance_pct", "volSurgeRatio": "vol_surge_ratio",
    "emaUptrend": "ema_uptrend", "ema21Slope": "ema21_slope", "dma20": "dma20", "dma50": "dma50",
    "dma200": "dma200", "pctFromDma20": "pct_from_dma20", "pctFromDma50": "pct_from_dma50",
    "pctFromDma200": "pct_from_dma200", "breakout": "breakout",
    "bullishEngulfing": "bullish_engulfing", "hammer": "hammer", "nearSupport": "near_support",
    "macdCross": "macd_cross", "regimeQualityScore": "regime_quality_score",
    "signalScore": "signal_score", "confidenceScore": "confidence_score", "institutionalScore": "institutional_score",
    "relativeStrengthVsSpy": "relative_strength_vs_spy", "gapPercent": "gap_percent",
    "volatilityScore": "volatility_score", "entryQualityScore": "entry_quality_score",
    "stageConfidence": "stage_confidence", "substageConfidence": "substage_confidence",
    "childSubstageConfidence": "child_substage_confidence", "bestRiskReward": "best_risk_reward",
    "daysToPeakScore": "days_to_peak_score", "epsQualityScore": "eps_quality_score",
    "fundamentalBoost": "fundamental_boost", "newsSentimentScore": "news_sentiment_score",
    "newsPositiveRatio": "news_positive_ratio", "sectorRotationScore": "sector_rotation_score",
    "institutionalFlowScore": "institutional_flow_score",
}

# Accept snake_case too. This makes Java mapper refactors safer.
FEATURE_TO_FIELD = {v: v for v in FIELD_TO_FEATURE.values()}
BOOLEAN_FEATURES = {"ema_uptrend", "breakout", "bullish_engulfing", "hammer", "near_support", "macd_cross"}


def _safe_float(value: Any, default: float = 0.0) -> float:
    try:
        out = float(value)
        if math.isnan(out) or math.isinf(out):
            return default
        return out
    except Exception:
        return default


def _safe_bool_float(value: Any) -> float:
    if isinstance(value, bool):
        return 1.0 if value else 0.0
    if isinstance(value, str):
        return 1.0 if value.strip().lower() in {"true", "1", "yes", "y"} else 0.0
    return 1.0 if _safe_float(value) > 0 else 0.0


def _clip(value: float | None, low: float, high: float) -> float:
    return max(low, min(high, _safe_float(value)))


def _normalize_feature(feature_name: str, raw: Any) -> float:
    value = _safe_bool_float(raw) if feature_name in BOOLEAN_FEATURES else _safe_float(raw)
    if feature_name in FEATURE_RANGES:
        low, high = FEATURE_RANGES[feature_name]
        return _clip(value, low, high)
    return value


def _load_bundle(path) -> dict[str, Any]:
    if not path.exists():
        return {}
    artifact = joblib.load(path)
    if isinstance(artifact, dict) and "model" in artifact:
        return artifact
    return {
        "model": artifact,
        "features": FEATURES,
        "threshold": DEFAULT_THRESHOLD,
        "model_version": "legacy-bare-model",
        "promotion_status": "SUPPORTING_SIGNAL_ONLY",
        "metrics": {},
    }


def _load_model() -> None:
    global xgb_artifact, dl_artifact, model_features, model_threshold, model_version, promotion_status, model_metrics, ensemble_metrics
    xgb_artifact = _load_bundle(XGB_MODEL_PATH)
    dl_artifact = _load_bundle(DL_BUNDLE_PATH)
    ensemble_metrics = {}
    if ENSEMBLE_METRICS_PATH.exists():
        try:
            import json
            ensemble_metrics = json.loads(ENSEMBLE_METRICS_PATH.read_text(encoding="utf-8"))
        except Exception:
            ensemble_metrics = {}
    primary = xgb_artifact or dl_artifact
    if not primary:
        model_features = FEATURES.copy()
        model_threshold = DEFAULT_THRESHOLD
        model_version = "unavailable"
        promotion_status = "SUPPORTING_SIGNAL_ONLY"
        model_metrics = {}
        return

    all_features: list[str] = []
    versions: list[str] = []
    thresholds: list[float] = []
    promoted: list[bool] = []
    for artifact, name in [(xgb_artifact, "xgb"), (dl_artifact, "dl")]:
        if not artifact:
            continue
        versions.append(str(artifact.get("model_version", name)))
        thresholds.append(float(artifact.get("threshold", DEFAULT_THRESHOLD)))
        promoted.append(str(artifact.get("promotion_status", "SUPPORTING_SIGNAL_ONLY")) == "PROMOTED")
        for f in artifact.get("features", FEATURES):
            if f not in all_features:
                all_features.append(f)
    model_features = all_features or FEATURES.copy()
    # Prefer ensemble threshold/metrics when present; live BUY gating still uses max(threshold, LIVE_BUY_FLOOR).
    if ensemble_metrics.get("threshold") is not None:
        model_threshold = float(ensemble_metrics.get("threshold"))
    else:
        model_threshold = round(float(sum(thresholds) / len(thresholds)), 4) if thresholds else DEFAULT_THRESHOLD
    model_version = (ensemble_metrics.get("model_version") + "+") if ensemble_metrics.get("model_version") else ""
    model_version += "+".join(versions)
    promotion_status = "PROMOTED" if (ensemble_metrics.get("promotion_status") == "PROMOTED" or any(promoted)) else "SUPPORTING_SIGNAL_ONLY"
    model_metrics = ensemble_metrics or dict(primary.get("metrics", {}))


def _request_raw_values(req: MlRequest) -> dict[str, Any]:
    data = req.model_dump(exclude_none=False)
    extra = getattr(req, "model_extra", None) or {}
    # Extra wins only when canonical field was not provided.
    for k, v in extra.items():
        data.setdefault(k, v)
    return data


def request_to_feature_map(req: MlRequest) -> tuple[dict[str, float], list[str], list[str], list[str], float]:
    raw_values = _request_raw_values(req)
    provided_names = {k for k, v in raw_values.items() if v is not None}
    feature_map: dict[str, float] = {}
    missing_features: list[str] = []
    missing_required: list[str] = []
    defaulted: list[str] = []

    for field_name, feature_name in FIELD_TO_FEATURE.items():
        raw = raw_values.get(field_name)
        if raw is None and feature_name in raw_values:  # snake_case compatibility
            raw = raw_values.get(feature_name)
        if raw is None:
            missing_features.append(feature_name)
            defaulted.append(feature_name)
            if feature_name in REQUIRED_LIVE_FEATURES:
                missing_required.append(feature_name)
            feature_map[feature_name] = 0.0
        else:
            feature_map[feature_name] = _normalize_feature(feature_name, raw)

    optional_present = [f for f in OPTIONAL_FEATURES if f not in missing_features]
    optional_coverage_pct = round(100.0 * len(optional_present) / max(1, len(OPTIONAL_FEATURES)), 2)
    return feature_map, sorted(missing_features), sorted(missing_required), sorted(defaulted), optional_coverage_pct


def tech_fallback(req: MlRequest) -> float:
    feature_map, _, _, _, _ = request_to_feature_map(req)
    rsi = feature_map.get("rsi", 50.0)
    rsi_component = 1.0 - abs(_clip(rsi, 0.0, 100.0) - 55.0) / 55.0
    adx_component = _clip(feature_map.get("adx", 0.0) / 40.0, 0.0, 1.0)
    vol_component = _clip(feature_map.get("vol_surge_ratio", 0.0) / 2.5, 0.0, 1.0)
    trend_component = _clip(feature_map.get("ema_uptrend", 0.0), 0.0, 1.0)
    regime_component = _clip(feature_map.get("regime_quality_score", 0.0) / 100.0, 0.0, 1.0)
    signal_component = _clip(feature_map.get("signal_score", 0.0), 0.0, 1.0)
    institutional_component = _clip(feature_map.get("institutional_score", 0.0) / 100.0, 0.0, 1.0)
    entry_component = _clip(feature_map.get("entry_quality_score", 0.0) / 100.0, 0.0, 1.0)
    rr_component = _clip(feature_map.get("best_risk_reward", 0.0) / 3.0, 0.0, 1.0)
    candle_bonus = sum([
        0.05 if feature_map.get("breakout", 0.0) else 0.0,
        0.04 if feature_map.get("macd_cross", 0.0) else 0.0,
        0.03 if feature_map.get("bullish_engulfing", 0.0) else 0.0,
        0.03 if feature_map.get("hammer", 0.0) else 0.0,
        0.03 if feature_map.get("near_support", 0.0) else 0.0,
    ])
    score = (
        0.14 * trend_component + 0.12 * rsi_component + 0.10 * adx_component + 0.10 * vol_component
        + 0.13 * regime_component + 0.15 * signal_component + 0.10 * institutional_component
        + 0.08 * entry_component + 0.08 * rr_component + candle_bonus
    )
    return round(_clip(score, 0.02, 0.98), 4)


def _rank_bucket(prob: float) -> str:
    rp = (ensemble_metrics.get("ranking_policy") or {}).get("rank_cutoffs", {}) if isinstance(ensemble_metrics, dict) else {}
    try:
        if rp and prob >= float(rp.get("top_1pct", 999)): return "TOP_1PCT"
        if rp and prob >= float(rp.get("top_2pct", 999)): return "TOP_2PCT"
        if rp and prob >= float(rp.get("top_5pct", 999)): return "TOP_5PCT"
        if rp and prob >= float(rp.get("top_10pct", 999)): return "TOP_10PCT"
        if rp and prob >= float(rp.get("top_20pct", 999)): return "TOP_20PCT"
    except Exception:
        pass
    if prob >= 0.60: return "WATCH_HIGH"
    if prob >= 0.50: return "WATCH"
    return "IGNORE"


def _confidence_band(prob: float, live_threshold: float) -> str:
    if prob >= LIVE_STRONG_BUY_FLOOR:
        return "HIGH"
    if prob >= live_threshold:
        return "MEDIUM"
    if prob >= 0.50:
        return "LOW"
    return "AVOID"


def _make_dataframe(feature_map: dict[str, float], features: list[str]) -> pd.DataFrame:
    row = {f: _normalize_feature(f, feature_map.get(f, 0.0)) for f in features}
    return pd.DataFrame([row], columns=features)


def _predict_artifact(artifact: dict[str, Any], feature_map: dict[str, float]) -> float | None:
    if not artifact or "model" not in artifact:
        return None
    features = list(artifact.get("features", FEATURES))
    x = _make_dataframe(feature_map, features)
    return float(artifact["model"].predict_proba(x)[0][1])


def _ensemble_probability(xgb_prob: float | None, dl_prob: float | None, fallback: float) -> tuple[float, bool]:
    probs = [p for p in [xgb_prob, dl_prob] if p is not None]
    if not probs:
        return fallback, True
    if xgb_prob is not None and dl_prob is not None:
        total_weight = XGB_ENSEMBLE_WEIGHT + DL_ENSEMBLE_WEIGHT
        return round((XGB_ENSEMBLE_WEIGHT * xgb_prob + DL_ENSEMBLE_WEIGHT * dl_prob) / total_weight, 4), False
    return round(probs[0], 4), False


def _java_action_hint(prob: float, live_threshold: float, model_buy: bool, strong_buy: bool, missing_required: list[str]) -> str:
    if missing_required:
        return "SCHEMA_FIX_REQUIRED"
    if strong_buy:
        return "ML_STRONG_CONFIRMATION"
    if model_buy:
        return "ML_BUY_CONFIRMATION"
    if prob >= 0.50:
        return "ML_WATCH_CONFIRMATION"
    return "ML_SUPPORT_ONLY"


@app.on_event("startup")
def startup() -> None:
    _load_model()


@app.get("/")
def root() -> dict[str, Any]:
    return {"status": "ML/DL hedge-safe service running", "docs": "/docs", "health": "/health", "predict": "/predict", "schema": "/schema", "metrics": "/metrics"}


@app.get("/health")
def health() -> dict[str, Any]:
    live_threshold = min(MAX_SELECTED_THRESHOLD, max(model_threshold, LIVE_BUY_FLOOR))
    return {
        "status": "ok",
        "xgbLoaded": bool(xgb_artifact),
        "dlLoaded": bool(dl_artifact),
        "xgbModelPath": str(XGB_MODEL_PATH),
        "dlModelPath": str(DL_BUNDLE_PATH),
        "xgbMetricsPath": str(XGB_METRICS_PATH),
        "dlMetricsPath": str(DL_METRICS_PATH),
        "modelVersion": model_version,
        "schemaVersion": SCHEMA_VERSION,
        "trainingThreshold": model_threshold,
        "liveBuyThreshold": live_threshold,
        "liveStrongBuyThreshold": LIVE_STRONG_BUY_FLOOR,
        "featureCount": len(model_features),
        "promotionStatus": promotion_status,
        "xgbThreshold": None if not xgb_artifact else xgb_artifact.get("threshold"),
        "dlThreshold": None if not dl_artifact else dl_artifact.get("threshold"),
        "ensembleMetricsLoaded": bool(ensemble_metrics),
        "ensembleThreshold": ensemble_metrics.get("threshold"),
    }


@app.get("/schema")
def schema() -> dict[str, Any]:
    return {
        "schemaVersion": SCHEMA_VERSION,
        "javaEndpoint": "POST http://127.0.0.1:8000/predict",
        "requiredCamelCaseFields": [k for k, v in FIELD_TO_FEATURE.items() if v in REQUIRED_LIVE_FEATURES],
        "optionalHighValueCamelCaseFields": [k for k, v in FIELD_TO_FEATURE.items() if v in OPTIONAL_FEATURES],
        "allFeaturesSnakeCase": FEATURES,
        "booleanCompatibleFields": [k for k, v in FIELD_TO_FEATURE.items() if v in BOOLEAN_FEATURES],
        "livePolicy": {
            "modelDrivenBuy requires": "promotionStatus=PROMOTED, probability >= liveBuyThreshold, no missing required fields, optional coverage guard, and XGB/DL agreement guard",
            "javaRecommendation": "Use probability as ranking/confirmation. Do not let ML create final BUY alone unless promoted.",
        },
    }


@app.get("/metrics")
def metrics() -> dict[str, Any]:
    return {
        "modelVersion": model_version,
        "schemaVersion": SCHEMA_VERSION,
        "promotionStatus": promotion_status,
        "trainingThreshold": model_threshold,
        "liveBuyThreshold": min(MAX_SELECTED_THRESHOLD, max(model_threshold, LIVE_BUY_FLOOR)),
        "ensembleMetrics": ensemble_metrics,
        "xgbMetrics": xgb_artifact.get("metrics", {}) if xgb_artifact else {},
        "dlMetrics": dl_artifact.get("metrics", {}) if dl_artifact else {},
    }


@app.post("/reload")
def reload_model() -> dict[str, Any]:
    _load_model()
    return health()


@app.post("/predict", response_model=MlResponse)
def predict(req: MlRequest) -> MlResponse:
    feature_map, missing_features, missing_required, defaulted, optional_coverage_pct = request_to_feature_map(req)
    fallback = tech_fallback(req)

    xgb_prob = _predict_artifact(xgb_artifact, feature_map)
    dl_prob = _predict_artifact(dl_artifact, feature_map)
    prob, fallback_used = _ensemble_probability(xgb_prob, dl_prob, fallback)

    probability_cap_applied = False
    agreement_gap = None
    if xgb_prob is not None and dl_prob is not None:
        agreement_gap = round(abs(xgb_prob - dl_prob), 4)
    if missing_required and prob > MAX_PROBABILITY_WHEN_REQUIRED_MISSING:
        prob = MAX_PROBABILITY_WHEN_REQUIRED_MISSING
        probability_cap_applied = True
    if optional_coverage_pct < (MIN_OPTIONAL_FEATURE_COVERAGE_FOR_BUY * 100.0) and prob > MAX_PROBABILITY_WHEN_OPTIONAL_COVERAGE_LOW:
        prob = MAX_PROBABILITY_WHEN_OPTIONAL_COVERAGE_LOW
        probability_cap_applied = True
    if agreement_gap is not None and agreement_gap > ENSEMBLE_MAX_MODEL_GAP_FOR_BUY and prob > MAX_PROBABILITY_WHEN_OPTIONAL_COVERAGE_LOW:
        prob = MAX_PROBABILITY_WHEN_OPTIONAL_COVERAGE_LOW
        probability_cap_applied = True

    live_threshold = min(MAX_SELECTED_THRESHOLD, max(model_threshold, LIVE_BUY_FLOOR))
    promoted = promotion_status == "PROMOTED"
    optional_ok = optional_coverage_pct >= (MIN_OPTIONAL_FEATURE_COVERAGE_FOR_BUY * 100.0)
    agreement_ok = agreement_gap is None or agreement_gap <= ENSEMBLE_MAX_MODEL_GAP_FOR_BUY
    model_buy = bool(promoted and not missing_required and optional_ok and agreement_ok and prob >= live_threshold)
    strong_buy = bool(promoted and not missing_required and optional_ok and agreement_ok and prob >= LIVE_STRONG_BUY_FLOOR)
    band = _confidence_band(prob, live_threshold)
    rank_bucket = _rank_bucket(prob)
    java_hint = _java_action_hint(prob, live_threshold, model_buy, strong_buy, missing_required)

    current_price = _safe_float(req.currentPrice)
    ml_entry_target = round(current_price * (1.0 + max(prob - 0.50, 0.0) * 0.06), 4) if current_price > 0 else 0.0
    metrics = model_metrics or (xgb_artifact.get("metrics", {}) if xgb_artifact else {})
    test_metrics = metrics.get("test_metrics", {}) if isinstance(metrics, dict) else {}

    if missing_required:
        reason = f"Required Java features missing: {missing_required}. Probability capped; Java mapper should be fixed."
        status = "SCHEMA_DEGRADED"
    elif optional_coverage_pct < (MIN_OPTIONAL_FEATURE_COVERAGE_FOR_BUY * 100.0):
        reason = "High-value Java optional features are missing/low coverage; probability capped for hedge-safe gating."
        status = "OPTIONAL_FEATURES_DEGRADED"
    elif agreement_gap is not None and agreement_gap > ENSEMBLE_MAX_MODEL_GAP_FOR_BUY:
        reason = "XGB and DL disagree materially; probability capped for hedge-safe gating."
        status = "MODEL_DISAGREEMENT"
    elif fallback_used:
        reason = "No trained model loaded; using deterministic technical fallback score."
        status = "FALLBACK"
    elif not promoted:
        reason = "Model loaded but not promoted by strict test metrics; use as ranking/confirmation only."
        status = "SUPPORTING_SIGNAL_ONLY"
    else:
        reason = "Promoted ML/DL ensemble available; still combine with Java stage, substage, risk/reward, and entry engines."
        status = "OK"

    return MlResponse(
        probability=round(prob, 4),
        xgbProbability=None if xgb_prob is None else round(xgb_prob, 4),
        dlProbability=None if dl_prob is None else round(dl_prob, 4),
        confidenceBand=band,
        modelDrivenBuy=model_buy,
        modelDrivenStrongBuy=strong_buy,
        mlEntryTarget=ml_entry_target,
        techFallbackScore=fallback,
        decisionReason=reason,
        fallbackUsed=fallback_used,
        modelVersion=model_version,
        schemaVersion=SCHEMA_VERSION,
        status=status,
        thresholdUsed=live_threshold,
        modelAuc=test_metrics.get("roc_auc"),
        precisionAtThreshold=test_metrics.get("precision"),
        missingFeatures=missing_features,
        missingRequiredFeatures=missing_required,
        defaultedFeatures=defaulted,
        featureCount=len(FEATURES),
        promotionStatus=promotion_status,
        javaActionHint=java_hint,
        probabilityCapApplied=probability_cap_applied,
        liveBuyFloor=LIVE_BUY_FLOOR,
        liveStrongBuyFloor=LIVE_STRONG_BUY_FLOOR,
        schemaCompatible=len(missing_required) == 0,
        optionalFeatureCoveragePct=optional_coverage_pct,
        modelAgreementGap=agreement_gap,
        hedgeSafeGatingPassed=bool(not missing_required and optional_ok and agreement_ok),
        rankBucket=rank_bucket,
        rankScore=round(prob * 100.0, 2),
    )
