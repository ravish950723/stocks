from __future__ import annotations

from datetime import datetime, timezone
from typing import Callable

import numpy as np
import pandas as pd

from app.metrics_utils import evaluate_split
from app.ml_config import FEATURES, TARGET, TRAINING_CSV
from app.splits import chronological_split, walk_forward_folds


def load_training_data(label: str = "Model") -> pd.DataFrame:
    if not TRAINING_CSV.exists():
        raise FileNotFoundError(f"Training CSV not found: {TRAINING_CSV}")
    df = pd.read_csv(TRAINING_CSV)
    missing = sorted(set([TARGET, "date", "symbol"]) - set(df.columns))
    if missing:
        raise ValueError(f"Missing required training columns: {missing}")
    for col in FEATURES:
        if col not in df.columns:
            df[col] = 0.0
        df[col] = pd.to_numeric(df[col], errors="coerce").replace([np.inf, -np.inf], np.nan).fillna(0.0)
    df[TARGET] = pd.to_numeric(df[TARGET], errors="coerce").fillna(0).astype(int)
    df["date"] = pd.to_datetime(df["date"], errors="coerce")
    df["symbol"] = df["symbol"].astype(str).str.strip().str.upper()
    df = df.dropna(subset=["date"]).sort_values(["date", "symbol"]).reset_index(drop=True)
    counts = df[TARGET].value_counts().sort_index()
    print(f"{label} label distribution:")
    print(counts.to_string())
    if len(counts) < 2:
        raise ValueError("Only one class present in buy_label. Need broader history/label settings.")
    if counts.min() < 50:
        raise ValueError("Too few rows in one class. Need more training samples before model training.")
    return df


def make_base_metrics(df: pd.DataFrame, train: pd.DataFrame, val: pd.DataFrame, test: pd.DataFrame) -> dict:
    return {
        "trained_at_utc": datetime.now(timezone.utc).isoformat(),
        "rows": {"total": int(len(df)), "train": int(len(train)), "validation": int(len(val)), "test": int(len(test))},
        "labels": {
            "positive_rows": int(df[TARGET].sum()),
            "negative_rows": int(len(df) - int(df[TARGET].sum())),
            "positive_ratio": float(df[TARGET].mean()),
        },
        "symbol_count": int(df["symbol"].nunique()),
        "date_ranges": {
            "train": [str(train["date"].min().date()), str(train["date"].max().date())],
            "validation": [str(val["date"].min().date()), str(val["date"].max().date())],
            "test": [str(test["date"].min().date()), str(test["date"].max().date())],
        },
    }


def walk_forward_diagnostics(df: pd.DataFrame, build_model: Callable[[], object], threshold: float) -> dict:
    folds = walk_forward_folds(df)
    fold_metrics = []
    for train_idx, test_idx, name in folds:
        train = df.loc[train_idx].copy()
        test = df.loc[test_idx].copy()
        if train[TARGET].nunique() < 2 or test[TARGET].nunique() < 2:
            continue
        model = build_model()
        model.fit(train[FEATURES], train[TARGET])
        probs = model.predict_proba(test[FEATURES])[:, 1]
        m = evaluate_split(name, test[TARGET], probs, threshold)
        m["date_range"] = [str(test["date"].min().date()), str(test["date"].max().date())]
        m["rows"] = int(len(test))
        fold_metrics.append(m)
    if not fold_metrics:
        return {"available": False, "reason": "Not enough unique dates for walk-forward diagnostics."}
    keys = ["roc_auc", "average_precision", "precision", "recall", "f1"]
    summary = {k: float(np.nanmean([m[k] for m in fold_metrics if m.get(k) is not None])) for k in keys}
    return {"available": True, "fold_count": len(fold_metrics), "mean_metrics": summary, "folds": fold_metrics}
