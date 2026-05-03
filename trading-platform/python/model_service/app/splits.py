from __future__ import annotations

import numpy as np
import pandas as pd


def chronological_split(df: pd.DataFrame, validation_fraction: float = 0.20, test_fraction: float = 0.20):
    dates = np.array(sorted(df["date"].dropna().unique()))
    if len(dates) < 80:
        raise ValueError("Need at least 80 unique dates for stable chronological train/validation/test split.")
    test_start = dates[int(len(dates) * (1.0 - test_fraction))]
    val_start = dates[int(len(dates) * (1.0 - test_fraction - validation_fraction))]
    train = df[df["date"] < val_start].copy()
    val = df[(df["date"] >= val_start) & (df["date"] < test_start)].copy()
    test = df[df["date"] >= test_start].copy()
    for name, part in {"train": train, "validation": val, "test": test}.items():
        if part.empty or part["buy_label"].nunique() < 2:
            raise ValueError(f"{name} split has insufficient class diversity. Add more history/symbols.")
    return train, val, test


def walk_forward_folds(df: pd.DataFrame, *, min_train_fraction: float = 0.45, fold_fraction: float = 0.10, max_folds: int = 5):
    """Create expanding-window walk-forward folds by date.

    Returns list of (train_idx, test_idx, fold_name). This is used only for diagnostics, not for leakage-prone tuning.
    """
    dates = np.array(sorted(df["date"].dropna().unique()))
    if len(dates) < 120:
        return []
    min_train_end = int(len(dates) * min_train_fraction)
    fold_size = max(10, int(len(dates) * fold_fraction))
    folds = []
    start = min_train_end
    fold_no = 1
    while start + fold_size <= len(dates) and fold_no <= max_folds:
        train_end_date = dates[start]
        test_end_date = dates[start + fold_size - 1]
        train_idx = df.index[df["date"] < train_end_date].to_numpy()
        test_idx = df.index[(df["date"] >= train_end_date) & (df["date"] <= test_end_date)].to_numpy()
        if len(train_idx) and len(test_idx):
            part = df.loc[test_idx]
            if part["buy_label"].nunique() == 2:
                folds.append((train_idx, test_idx, f"wf_{fold_no}"))
        start += fold_size
        fold_no += 1
    return folds
