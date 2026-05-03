from __future__ import annotations

import json
from datetime import datetime, timezone

import joblib
import numpy as np

from app.metrics_utils import evaluate_split, select_threshold, select_live_threshold_from_validation
from app.ml_config import (
    DEFAULT_THRESHOLD,
    DL_BUNDLE_PATH,
    DL_ENSEMBLE_WEIGHT,
    ENSEMBLE_METRICS_PATH,
    FEATURES,
    MIN_PRECISION_FOR_BUY,
    MIN_PROMOTION_PRECISION,
    MIN_PROMOTION_RECALL,
    MIN_PROMOTION_ROC_AUC,
    MIN_PROMOTION_TOP10_LIFT,
    MIN_PREDICTED_POSITIVE_RATE,
    MAX_PREDICTED_POSITIVE_RATE,
    MIN_RECALL_FOR_THRESHOLD,
    MAX_SELECTED_THRESHOLD,
    SCHEMA_VERSION,
    TARGET,
    XGB_ENSEMBLE_WEIGHT,
    XGB_MODEL_PATH,
)
from app.splits import chronological_split
from app.train_common import load_training_data, make_base_metrics


def _load(path):
    if not path.exists():
        return None
    artifact = joblib.load(path)
    return artifact if isinstance(artifact, dict) else {"model": artifact, "features": FEATURES}


def _predict(artifact, df):
    features = artifact.get("features", FEATURES)
    return artifact["model"].predict_proba(df[features])[:, 1]


def _promotion(metrics: dict) -> str:
    tm = metrics["test_metrics"]
    auc = tm.get("roc_auc") or 0.0
    precision = tm.get("precision") or 0.0
    recall = tm.get("recall") or 0.0
    top10_lift = (tm.get("top_bucket_metrics") or {}).get("top_10pct_lift") or 0.0
    if auc >= MIN_PROMOTION_ROC_AUC and precision >= MIN_PROMOTION_PRECISION and recall >= MIN_PROMOTION_RECALL and top10_lift >= MIN_PROMOTION_TOP10_LIFT:
        return "PROMOTED"
    return "SUPPORTING_SIGNAL_ONLY"


def main() -> None:
    xgb = _load(XGB_MODEL_PATH)
    dl = _load(DL_BUNDLE_PATH)
    if not xgb and not dl:
        raise FileNotFoundError("No XGB or DL model found. Train at least one model before ensemble.")
    df = load_training_data("ENSEMBLE")
    train, val, test = chronological_split(df)

    weights = []
    names = []
    if xgb:
        weights.append(XGB_ENSEMBLE_WEIGHT); names.append("xgb")
    if dl:
        weights.append(DL_ENSEMBLE_WEIGHT); names.append("dl")
    weights = np.asarray(weights, dtype=float)
    weights = weights / weights.sum()

    def ensemble_probs(part):
        parts = []
        if xgb:
            parts.append(_predict(xgb, part))
        if dl:
            parts.append(_predict(dl, part))
        stacked = np.vstack(parts).T
        return (stacked * weights).sum(axis=1)

    val_probs = ensemble_probs(val)
    threshold_info = select_threshold(
        val[TARGET], val_probs,
        min_precision=MIN_PRECISION_FOR_BUY,
        min_recall=MIN_RECALL_FOR_THRESHOLD,
        min_predicted_positive_rate=MIN_PREDICTED_POSITIVE_RATE,
        max_predicted_positive_rate=MAX_PREDICTED_POSITIVE_RATE,
        default_threshold=DEFAULT_THRESHOLD,
    )
    threshold = float(min(MAX_SELECTED_THRESHOLD, threshold_info["threshold"]))
    threshold_info["threshold_after_stability_cap"] = threshold
    threshold_info["stability_cap"] = MAX_SELECTED_THRESHOLD
    ranking_policy = select_live_threshold_from_validation(val[TARGET], val_probs, default_threshold=threshold)

    train_metrics = evaluate_split("train", train[TARGET], ensemble_probs(train), threshold)
    val_metrics = evaluate_split("validation", val[TARGET], val_probs, threshold)
    test_metrics = evaluate_split("test", test[TARGET], ensemble_probs(test), threshold)
    metrics = {
        "model_version": "ensemble-xgb-dl-v8-hedge-java-safe",
        "schema_version": SCHEMA_VERSION,
        "trained_at_utc": datetime.now(timezone.utc).isoformat(),
        "models": names,
        "weights": {name: float(w) for name, w in zip(names, weights)},
        "threshold": threshold,
        "threshold_selection": threshold_info,
        "ranking_policy": ranking_policy,
        **make_base_metrics(df, train, val, test),
        "train_metrics": train_metrics,
        "validation_metrics": val_metrics,
        "test_metrics": test_metrics,
    }
    metrics["promotion_status"] = _promotion(metrics)
    ENSEMBLE_METRICS_PATH.parent.mkdir(parents=True, exist_ok=True)
    with ENSEMBLE_METRICS_PATH.open("w", encoding="utf-8") as f:
        json.dump(metrics, f, indent=2)
    print(json.dumps({
        "saved_metrics": str(ENSEMBLE_METRICS_PATH),
        "threshold": threshold,
        "threshold_policy": threshold_info.get("selection_policy"),
        "promotion_status": metrics["promotion_status"],
        "test_roc_auc": test_metrics["roc_auc"],
        "test_average_precision": test_metrics["average_precision"],
        "test_precision": test_metrics["precision"],
        "test_recall": test_metrics["recall"],
        "test_f1": test_metrics["f1"],
        "top_10pct_precision": test_metrics["top_bucket_metrics"].get("top_10pct_precision"),
        "top_10pct_lift": test_metrics["top_bucket_metrics"].get("top_10pct_lift"),
    }, indent=2))


if __name__ == "__main__":
    main()
