from __future__ import annotations

from typing import Iterable

import numpy as np
from sklearn.metrics import classification_report, precision_recall_fscore_support, roc_auc_score, average_precision_score

try:
    from app.ml_config import MAX_SELECTED_THRESHOLD, MIN_TEST_BUY_CALLS_FOR_METRIC_SANITY
except Exception:
    MAX_SELECTED_THRESHOLD = 0.60
    MIN_TEST_BUY_CALLS_FOR_METRIC_SANITY = 25


def _safe_auc(y_true: Iterable[int], probs: np.ndarray) -> float | None:
    y = np.asarray(list(y_true), dtype=int)
    if len(np.unique(y)) < 2:
        return None
    return float(roc_auc_score(y, probs))


def _safe_ap(y_true: Iterable[int], probs: np.ndarray) -> float | None:
    y = np.asarray(list(y_true), dtype=int)
    if len(np.unique(y)) < 2:
        return None
    return float(average_precision_score(y, probs))


def probability_diagnostics(probs: np.ndarray) -> dict:
    p = np.asarray(probs, dtype=float)
    if p.size == 0:
        return {}
    quantiles = [0.01, 0.05, 0.10, 0.25, 0.50, 0.75, 0.90, 0.95, 0.99]
    return {
        "min": float(np.min(p)),
        "max": float(np.max(p)),
        "mean": float(np.mean(p)),
        "std": float(np.std(p)),
        "quantiles": {str(q): float(np.quantile(p, q)) for q in quantiles},
    }


def top_bucket_metrics(y_true: Iterable[int], probs: np.ndarray) -> dict:
    y = np.asarray(list(y_true), dtype=int)
    p = np.asarray(probs, dtype=float)
    base_rate = float(y.mean()) if len(y) else 0.0
    order = np.argsort(-p)
    result = {"base_positive_rate": base_rate}
    for pct in [0.01, 0.02, 0.05, 0.10, 0.20]:
        n = max(1, int(round(len(y) * pct))) if len(y) else 0
        if n == 0:
            continue
        top_y = y[order[:n]]
        precision = float(top_y.mean())
        result[f"top_{int(pct*100)}pct_precision"] = precision
        result[f"top_{int(pct*100)}pct_lift"] = float(precision / base_rate) if base_rate > 0 else None
        result[f"top_{int(pct*100)}pct_count"] = int(n)
    return result


def threshold_table(y_true: Iterable[int], probs: np.ndarray) -> list[dict]:
    rows: list[dict] = []
    y = np.asarray(list(y_true), dtype=int)
    p = np.asarray(probs, dtype=float)
    max_threshold = min(0.91, float(MAX_SELECTED_THRESHOLD) + 0.001)
    for threshold in np.arange(0.25, max_threshold, 0.01):
        preds = (p >= threshold).astype(int)
        precision, recall, f1, _ = precision_recall_fscore_support(y, preds, average="binary", zero_division=0)
        rows.append({
            "threshold": float(round(threshold, 2)),
            "precision": float(precision),
            "recall": float(recall),
            "f1": float(f1),
            "predicted_positive_rate": float(preds.mean()),
            "predicted_positive_count": int(preds.sum()),
        })
    return rows


def select_threshold(
    y_true: Iterable[int],
    probs: np.ndarray,
    *,
    min_precision: float,
    min_recall: float,
    min_predicted_positive_rate: float,
    max_predicted_positive_rate: float,
    default_threshold: float,
) -> dict:
    rows = threshold_table(y_true, probs)
    eligible = [
        r for r in rows
        if r["precision"] >= min_precision
        and r["recall"] >= min_recall
        and min_predicted_positive_rate <= r["predicted_positive_rate"] <= max_predicted_positive_rate
    ]
    if eligible:
        best = max(eligible, key=lambda r: (r["precision"], r["f1"], r["recall"], -r["predicted_positive_rate"]))
        best = dict(best)
        best["selection_policy"] = "PRECISION_FIRST_WITH_RECALL_AND_COVERAGE_GUARD"
        return best

    conservative = [
        r for r in rows
        if r["predicted_positive_count"] > 0
        and r["recall"] > 0
        and r["predicted_positive_rate"] <= max_predicted_positive_rate
    ]
    if conservative:
        best = max(conservative, key=lambda r: (r["precision"], r["f1"], r["recall"], -r["predicted_positive_rate"]))
        best = dict(best)
        best["selection_policy"] = "FALLBACK_MAX_PRECISION_CONTROLLED_BUY_CALLS"
        best["warning"] = "No threshold reached target precision/recall. Using conservative max-precision fallback; keep model as supporting signal."
        return best

    fallback_threshold = min(float(default_threshold), float(MAX_SELECTED_THRESHOLD))
    preds = (np.asarray(probs) >= fallback_threshold).astype(int)
    precision, recall, f1, _ = precision_recall_fscore_support(list(y_true), preds, average="binary", zero_division=0)
    return {
        "threshold": float(fallback_threshold),
        "precision": float(precision),
        "recall": float(recall),
        "f1": float(f1),
        "predicted_positive_rate": float(preds.mean()),
        "predicted_positive_count": int(preds.sum()),
        "selection_policy": "DEFAULT_THRESHOLD_PRODUCTION_SAFE",
        "warning": "No validation threshold created controlled useful BUY calls. Model remains supporting signal.",
    }


def select_live_threshold_from_validation(y_true: Iterable[int], probs: np.ndarray, *, default_threshold: float = 0.55) -> dict:
    p = np.asarray(probs, dtype=float)
    if p.size == 0:
        return {"action_threshold": float(default_threshold), "rank_cutoffs": {}}
    return {
        "action_threshold": float(min(MAX_SELECTED_THRESHOLD, max(0.30, default_threshold))),
        "rank_cutoffs": {
            "top_1pct": float(np.quantile(p, 0.99)),
            "top_2pct": float(np.quantile(p, 0.98)),
            "top_5pct": float(np.quantile(p, 0.95)),
            "top_10pct": float(np.quantile(p, 0.90)),
            "top_20pct": float(np.quantile(p, 0.80)),
        },
    }


def evaluate_split(name: str, y_true: Iterable[int], probs: np.ndarray, threshold: float) -> dict:
    y = np.asarray(list(y_true), dtype=int)
    p = np.asarray(probs, dtype=float)
    preds = (p >= threshold).astype(int)
    precision, recall, f1, _ = precision_recall_fscore_support(y, preds, average="binary", zero_division=0)
    predicted_positive_count = int(preds.sum())
    return {
        "split": name,
        "roc_auc": _safe_auc(y, p),
        "average_precision": _safe_ap(y, p),
        "precision": float(precision),
        "recall": float(recall),
        "f1": float(f1),
        "positive_rate_actual": float(y.mean()),
        "positive_rate_predicted": float(preds.mean()),
        "predicted_positive_count": predicted_positive_count,
        "probability_diagnostics": probability_diagnostics(p),
        "top_bucket_metrics": top_bucket_metrics(y, p),
        "classification_report": classification_report(y, preds, output_dict=True, zero_division=0),
        "metric_warning": "Too few BUY calls at threshold; use top-bucket/ranking metrics instead." if predicted_positive_count < MIN_TEST_BUY_CALLS_FOR_METRIC_SANITY else None,
    }
