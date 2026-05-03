from __future__ import annotations

import json

import joblib
from sklearn.neural_network import MLPClassifier
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import RobustScaler

from app.metrics_utils import evaluate_split, select_threshold
from app.ml_config import (
    DEFAULT_THRESHOLD,
    DL_BUNDLE_PATH,
    DL_METRICS_PATH,
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
)
from app.splits import chronological_split
from app.train_common import load_training_data, make_base_metrics, walk_forward_diagnostics


def build_model() -> Pipeline:
    # Tabular neural confirmation model. This is not a true sequence LSTM; Java must export candle sequences for that.
    return Pipeline([
        ("scaler", RobustScaler()),
        ("mlp", MLPClassifier(
            hidden_layer_sizes=(128, 64, 24),
            activation="relu",
            solver="adam",
            alpha=0.006,
            batch_size=256,
            learning_rate_init=0.00035,
            max_iter=240,
            early_stopping=True,
            validation_fraction=0.15,
            n_iter_no_change=20,
            random_state=42,
        )),
    ])


def promotion_status(test_metrics: dict, walk_metrics: dict) -> str:
    auc = test_metrics.get("roc_auc") or 0.0
    precision = test_metrics.get("precision") or 0.0
    recall = test_metrics.get("recall") or 0.0
    predicted = test_metrics.get("predicted_positive_count") or 0
    top10_lift = (test_metrics.get("top_bucket_metrics") or {}).get("top_10pct_lift") or 0.0
    wf_auc = ((walk_metrics or {}).get("mean_metrics") or {}).get("roc_auc") or auc
    if (
        auc >= MIN_PROMOTION_ROC_AUC
        and wf_auc >= (MIN_PROMOTION_ROC_AUC - 0.02)
        and precision >= MIN_PROMOTION_PRECISION
        and recall >= MIN_PROMOTION_RECALL
        and top10_lift >= MIN_PROMOTION_TOP10_LIFT
        and predicted > 0
    ):
        return "PROMOTED"
    return "SUPPORTING_SIGNAL_ONLY"


def main() -> None:
    DL_BUNDLE_PATH.parent.mkdir(parents=True, exist_ok=True)
    df = load_training_data("DL")
    train, val, test = chronological_split(df)
    model = build_model()
    model.fit(train[FEATURES], train[TARGET])

    val_probs = model.predict_proba(val[FEATURES])[:, 1]
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

    train_probs = model.predict_proba(train[FEATURES])[:, 1]
    test_probs = model.predict_proba(test[FEATURES])[:, 1]
    train_metrics = evaluate_split("train", train[TARGET], train_probs, threshold)
    val_metrics = evaluate_split("validation", val[TARGET], val_probs, threshold)
    test_metrics = evaluate_split("test", test[TARGET], test_probs, threshold)
    wf_metrics = walk_forward_diagnostics(df, build_model, threshold)
    promotion = promotion_status(test_metrics, wf_metrics)

    metrics = {
        "model_version": "dl-mlp-path-aware-v8-hedge-java-safe",
        "schema_version": SCHEMA_VERSION,
        "threshold": threshold,
        "threshold_selection": threshold_info,
        "promotion_status": promotion,
        "promotion_rules": {
            "min_test_roc_auc": MIN_PROMOTION_ROC_AUC,
            "min_test_precision": MIN_PROMOTION_PRECISION,
            "min_test_recall": MIN_PROMOTION_RECALL,
            "min_top10_lift": MIN_PROMOTION_TOP10_LIFT,
            "requires_walk_forward_stability": True,
        },
        **make_base_metrics(df, train, val, test),
        "train_metrics": train_metrics,
        "validation_metrics": val_metrics,
        "test_metrics": test_metrics,
        "walk_forward_metrics": wf_metrics,
        "features": FEATURES,
    }
    bundle = {
        "model": model,
        "features": FEATURES,
        "threshold": threshold,
        "model_version": metrics["model_version"],
        "schema_version": SCHEMA_VERSION,
        "promotion_status": promotion,
        "metrics": metrics,
    }
    joblib.dump(bundle, DL_BUNDLE_PATH)
    with DL_METRICS_PATH.open("w", encoding="utf-8") as f:
        json.dump(metrics, f, indent=2)
    print(json.dumps({
        "saved_model": str(DL_BUNDLE_PATH),
        "saved_metrics": str(DL_METRICS_PATH),
        "threshold": threshold,
        "threshold_policy": threshold_info.get("selection_policy"),
        "promotion_status": promotion,
        "test_roc_auc": test_metrics["roc_auc"],
        "test_average_precision": test_metrics["average_precision"],
        "test_precision": test_metrics["precision"],
        "test_recall": test_metrics["recall"],
        "test_f1": test_metrics["f1"],
        "test_predicted_positive_count": test_metrics["predicted_positive_count"],
        "top_10pct_precision": test_metrics["top_bucket_metrics"].get("top_10pct_precision"),
        "top_10pct_lift": test_metrics["top_bucket_metrics"].get("top_10pct_lift"),
        "walk_forward_available": wf_metrics.get("available"),
        "walk_forward_mean_auc": (wf_metrics.get("mean_metrics") or {}).get("roc_auc"),
    }, indent=2))


if __name__ == "__main__":
    main()
