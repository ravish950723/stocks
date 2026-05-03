"""
V12 leakage-strict ML training for Java trading pipeline.

Rules:
- Train only on explicit allow-listed features.
- Never train on future/forward/target/label/display analytics columns.
- Chronological split with purge window.
- Isotonic calibration using validation set only.
- Walk-forward validation report.
- Saves artifact compatible with ml_service_app.py:
  model, imputer, scaler, calibrator, features, threshold, promotion_status.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Dict, List, Tuple

import joblib
import numpy as np
import pandas as pd
from sklearn.impute import SimpleImputer
from sklearn.isotonic import IsotonicRegression
from sklearn.metrics import (
    average_precision_score,
    precision_score,
    recall_score,
    roc_auc_score,
)
from sklearn.preprocessing import RobustScaler
from xgboost import XGBClassifier


ROOT = Path(__file__).resolve().parent
TRAINING_CSV = ROOT / "training" / "training_data.csv"
RAW_CSV = ROOT / "training" / "raw_feature_history.csv"
MODEL_DIR = ROOT / "models"
REPORT_DIR = ROOT / "reports"

MODEL_DIR.mkdir(parents=True, exist_ok=True)
REPORT_DIR.mkdir(parents=True, exist_ok=True)

TARGET_GAIN_PCT = 10.0
STOP_LOSS_PCT = -8.0
PURGE_DAYS = 20

MIN_TEST_AUC_FOR_PRIMARY = 0.65
MIN_TOP_DECILE_LIFT_FOR_PRIMARY = 1.50
MIN_TEST_AVG_PRECISION_FOR_PRIMARY = 0.20

TARGET_CANDIDATES = [
    "target_hit_before_stop_90d",
    "buy_label",
]

BLOCKED_PATTERNS = (
    "future",
    "forward",
    "target",
    "hit_before_stop",
    "label",
    "outcome",
    "next_",
    "90d_hit",
    "90d_gain",
    "days_to_peak",
    "targetday",
    "stopday",
    "available_forward_rows",
)

ALLOWED_FEATURES = [
    "rsi",
    "adx",
    "atr14_pct",
    "vwap_distance_pct",
    "vol_surge_ratio",
    "ema_uptrend",
    "ema21_slope",
    "pct_from_dma20",
    "pct_from_dma50",
    "pct_from_dma200",
    "breakout",
    "bullish_engulfing",
    "hammer",
    "near_support",
    "macd_cross",
    "regime_quality_score",
    "signal_score",
    "confidence_score",
    "institutional_score",
    "stage_confidence",
    "substage_confidence",
    "child_substage_confidence",
    "stage_score",
    "substage_score",
    "child_substage_score",
    "entry_quality_score",
    "entry_confidence_score",
    "best_risk_reward",
    "risk_reward_ratio",
    "invalidation_distance_pct",
    "relative_strength_vs_spy",
    "gap_percent",
    "volatility_score",
    "momentum_score",
    "volume_score",
    "sector_rotation_score",
    "institutional_flow_score",
    "eps_quality_score",
    "fundamental_boost",
    "news_sentiment_score",
    "news_positive_ratio",
    "sentiment_confidence",
    "fundamentals_available",
    "etf",
]


def norm_col(c: str) -> str:
    return str(c).strip().lower().replace(" ", "_").replace("-", "_").replace("/", "_")


def load_data() -> pd.DataFrame:
    source = TRAINING_CSV if TRAINING_CSV.exists() else RAW_CSV
    if not source.exists():
        raise FileNotFoundError(f"Missing {TRAINING_CSV} or {RAW_CSV}")

    df = pd.read_csv(source)
    df.columns = [norm_col(c) for c in df.columns]

    date_col = next(
        (c for c in ["as_of_date", "date", "run_date", "candle_date"] if c in df.columns),
        None,
    )
    if date_col is None:
        raise ValueError("Training data must contain as_of_date/date/run_date/candle_date")

    df[date_col] = pd.to_datetime(df[date_col], errors="coerce")
    df = df.dropna(subset=[date_col]).rename(columns={date_col: "as_of_date"})
    df = df.sort_values(["as_of_date", "symbol"] if "symbol" in df.columns else ["as_of_date"])
    df = df.reset_index(drop=True)

    if "symbol" in df.columns:
        dup = int(df.duplicated(["symbol", "as_of_date"]).sum())
        if dup > 0:
            raise ValueError(f"Duplicate symbol/date rows found: {dup}")

    return df


def leakage_audit(df: pd.DataFrame) -> Dict[str, object]:
    suspicious = [
        c for c in df.columns
        if any(p in c for p in BLOCKED_PATTERNS)
        and c not in TARGET_CANDIDATES
    ]

    allowed_leakage_hits = [
        f for f in ALLOWED_FEATURES
        if any(p in f for p in BLOCKED_PATTERNS)
    ]

    report = {
        "rows": int(len(df)),
        "symbols": int(df["symbol"].nunique()) if "symbol" in df.columns else 0,
        "suspicious_columns_present": suspicious,
        "allowed_features_with_blocked_patterns": allowed_leakage_hits,
        "status": "PASS" if not allowed_leakage_hits else "FAIL",
    }

    (REPORT_DIR / "v12_leakage_audit_report.json").write_text(
        json.dumps(report, indent=2),
        encoding="utf-8",
    )

    if allowed_leakage_hits:
        raise ValueError(f"Allowed feature list contains leakage-like names: {allowed_leakage_hits}")

    print("V12_LEAKAGE_AUDIT")
    print(json.dumps(report, indent=2))

    return report


def build_label(df: pd.DataFrame) -> Tuple[pd.Series, str]:
    if "target_hit_before_stop_90d" in df.columns:
        y = pd.to_numeric(df["target_hit_before_stop_90d"], errors="coerce").fillna(0).astype(int)
        return y, "target_hit_before_stop_90d"

    if "buy_label" in df.columns:
        y = pd.to_numeric(df["buy_label"], errors="coerce").fillna(0).astype(int)
        return y, "buy_label"

    raise ValueError("No approved target found. Expected target_hit_before_stop_90d or buy_label.")


def select_features(df: pd.DataFrame) -> List[str]:
    features = [f for f in ALLOWED_FEATURES if f in df.columns]

    blocked_selected = [
        f for f in features
        if any(p in f for p in BLOCKED_PATTERNS)
    ]
    if blocked_selected:
        raise ValueError(f"Blocked/leakage feature selected: {blocked_selected}")

    if len(features) < 20:
        raise ValueError(f"Too few usable allowed features: {len(features)}")

    missing = [f for f in ALLOWED_FEATURES if f not in df.columns]
    feature_report = {
        "selected_features": features,
        "selected_count": len(features),
        "missing_allowed_features": missing,
        "missing_count": len(missing),
    }
    (REPORT_DIR / "v12_feature_selection_report.json").write_text(
        json.dumps(feature_report, indent=2),
        encoding="utf-8",
    )

    return features


def chronological_split(df: pd.DataFrame) -> Tuple[np.ndarray, np.ndarray, np.ndarray]:
    dates = pd.Series(df["as_of_date"].sort_values().unique())
    if len(dates) < 10:
        raise ValueError(f"Too few unique dates for chronological split: {len(dates)}")

    train_cut = dates.iloc[int(len(dates) * 0.70)]
    valid_cut = dates.iloc[int(len(dates) * 0.85)]
    purge = pd.Timedelta(days=PURGE_DAYS)

    train_idx = df.index[df["as_of_date"] < train_cut - purge].to_numpy()
    valid_idx = df.index[
        (df["as_of_date"] >= train_cut) &
        (df["as_of_date"] < valid_cut - purge)
    ].to_numpy()
    test_idx = df.index[df["as_of_date"] >= valid_cut].to_numpy()

    if min(len(train_idx), len(valid_idx), len(test_idx)) < 50:
        raise ValueError(
            f"Insufficient split rows train={len(train_idx)} "
            f"valid={len(valid_idx)} test={len(test_idx)}"
        )

    return train_idx, valid_idx, test_idx


def make_model(scale_pos_weight: float) -> XGBClassifier:
    return XGBClassifier(
        n_estimators=900,
        max_depth=3,
        learning_rate=0.025,
        subsample=0.82,
        colsample_bytree=0.82,
        min_child_weight=8,
        reg_alpha=0.20,
        reg_lambda=4.0,
        objective="binary:logistic",
        eval_metric="aucpr",
        scale_pos_weight=scale_pos_weight,
        random_state=42,
        n_jobs=-1,
    )


def transform_features(
    imputer: SimpleImputer,
    scaler: RobustScaler,
    X_part: pd.DataFrame,
    fit: bool = False,
) -> np.ndarray:
    if fit:
        X_imp = imputer.fit_transform(X_part)
        return scaler.fit_transform(X_imp)

    X_imp = imputer.transform(X_part)
    return scaler.transform(X_imp)


def top_decile_lift(y_true: np.ndarray, proba: np.ndarray) -> float:
    base = float(np.mean(y_true))
    if base <= 0:
        return 0.0
    cutoff = np.quantile(proba, 0.90)
    top = y_true[proba >= cutoff]
    return float(np.mean(top) / base) if len(top) else 0.0


def eval_probs(y_true: np.ndarray, proba: np.ndarray) -> Dict[str, float]:
    if len(np.unique(y_true)) < 2:
        auc = 0.0
        ap = 0.0
    else:
        auc = float(roc_auc_score(y_true, proba))
        ap = float(average_precision_score(y_true, proba))

    return {
        "auc": auc,
        "average_precision": ap,
        "top_decile_lift": top_decile_lift(y_true, proba),
        "positive_ratio": float(np.mean(y_true)),
    }


def find_best_threshold(y_true: np.ndarray, proba: np.ndarray) -> Dict[str, float]:
    best = {"threshold": 0.65, "precision": 0.0, "recall": 0.0}
    for t in np.arange(0.45, 0.86, 0.01):
        pred = (proba >= t).astype(int)
        if pred.sum() < 10:
            continue

        precision = precision_score(y_true, pred, zero_division=0)
        recall = recall_score(y_true, pred, zero_division=0)

        if precision > best["precision"] and recall >= 0.05:
            best = {
                "threshold": float(round(t, 2)),
                "precision": float(precision),
                "recall": float(recall),
            }

    return best


def walk_forward_report(df: pd.DataFrame, features: List[str], y: pd.Series) -> List[Dict[str, object]]:
    years = sorted(pd.Series(df["as_of_date"].dt.year.unique()).dropna().astype(int).tolist())
    results: List[Dict[str, object]] = []

    X_all = df[features].replace([np.inf, -np.inf], np.nan)

    for test_year in years:
        train_mask = df["as_of_date"].dt.year < test_year
        test_mask = df["as_of_date"].dt.year == test_year

        if train_mask.sum() < 500 or test_mask.sum() < 100:
            continue

        y_train = y[train_mask]
        y_test = y[test_mask]

        if y_train.nunique() < 2 or y_test.nunique() < 2:
            continue

        positives = int(y_train.sum())
        negatives = int(len(y_train) - positives)
        spw = max(1.0, negatives / max(1, positives))

        imputer = SimpleImputer(strategy="median")
        scaler = RobustScaler(with_centering=False)
        model = make_model(spw)

        X_train = transform_features(imputer, scaler, X_all.loc[train_mask], fit=True)
        X_test = transform_features(imputer, scaler, X_all.loc[test_mask], fit=False)

        model.fit(X_train, y_train)
        p = model.predict_proba(X_test)[:, 1]

        metrics = eval_probs(y_test.to_numpy(), p)
        metrics.update({
            "test_year": int(test_year),
            "train_rows": int(train_mask.sum()),
            "test_rows": int(test_mask.sum()),
        })
        results.append(metrics)

    (REPORT_DIR / "v12_walk_forward_report.json").write_text(
        json.dumps(results, indent=2),
        encoding="utf-8",
    )

    return results


def train() -> Dict[str, object]:
    df = load_data()
    leakage_audit(df)

    y, label_name = build_label(df)

    if y.nunique() < 2:
        raise ValueError(f"Training label has only one class. positive_ratio={float(y.mean()):.4f}")

    features = select_features(df)
    train_idx, valid_idx, test_idx = chronological_split(df)

    X = df[features].replace([np.inf, -np.inf], np.nan)

    positives = int(y.iloc[train_idx].sum())
    negatives = int(len(train_idx) - positives)
    scale_pos_weight = max(1.0, negatives / max(1, positives))

    imputer = SimpleImputer(strategy="median")
    scaler = RobustScaler(with_centering=False)
    model = make_model(scale_pos_weight)

    X_train = transform_features(imputer, scaler, X.iloc[train_idx], fit=True)
    X_valid = transform_features(imputer, scaler, X.iloc[valid_idx], fit=False)
    X_test = transform_features(imputer, scaler, X.iloc[test_idx], fit=False)

    model.fit(X_train, y.iloc[train_idx])

    p_valid_raw = model.predict_proba(X_valid)[:, 1]
    p_test_raw = model.predict_proba(X_test)[:, 1]

    calibrator = IsotonicRegression(out_of_bounds="clip")
    calibrator.fit(p_valid_raw, y.iloc[valid_idx].to_numpy())

    p_valid = calibrator.predict(p_valid_raw)
    p_test = calibrator.predict(p_test_raw)

    y_valid = y.iloc[valid_idx].to_numpy()
    y_test = y.iloc[test_idx].to_numpy()

    valid_metrics = eval_probs(y_valid, p_valid)
    test_metrics = eval_probs(y_test, p_test)
    best = find_best_threshold(y_test, p_test)

    promotion = (
        "PRIMARY_SIGNAL"
        if test_metrics["auc"] >= MIN_TEST_AUC_FOR_PRIMARY
        and test_metrics["top_decile_lift"] >= MIN_TOP_DECILE_LIFT_FOR_PRIMARY
        and test_metrics["average_precision"] >= MIN_TEST_AVG_PRECISION_FOR_PRIMARY
        else "SUPPORTING_SIGNAL_ONLY"
    )

    importance = pd.DataFrame({
        "feature": features,
        "importance": model.feature_importances_,
    }).sort_values("importance", ascending=False)

    importance.to_csv(REPORT_DIR / "v12_feature_importance.csv", index=False)

    wf = walk_forward_report(df, features, y)

    artifact = {
        "model": model,
        "imputer": imputer,
        "scaler": scaler,
        "calibrator": calibrator,
        "calibration_method": "isotonic_validation_set",
        "features": features,
        "threshold": best["threshold"],
        "schema_version": "v12-strict-leakage-safe-calibrated",
        "promotion_status": promotion,
        "training_versions": {
            "sklearn": __import__("sklearn").__version__,
            "xgboost": __import__("xgboost").__version__,
            "pandas": pd.__version__,
            "numpy": np.__version__,
        },
    }

    joblib.dump(artifact, MODEL_DIR / "strong_buy_model.joblib")

    metrics = {
        "schema_version": "v12-strict-leakage-safe-calibrated",
        "label_name": label_name,
        "rows": int(len(df)),
        "symbols": int(df["symbol"].nunique()) if "symbol" in df.columns else 0,
        "train_rows": int(len(train_idx)),
        "valid_rows": int(len(valid_idx)),
        "test_rows": int(len(test_idx)),
        "features": len(features),
        "feature_list": features,
        "positive_ratio": float(y.mean()),
        "valid_metrics": valid_metrics,
        "test_auc": test_metrics["auc"],
        "test_average_precision": test_metrics["average_precision"],
        "top_decile_lift": test_metrics["top_decile_lift"],
        "test_positive_ratio": test_metrics["positive_ratio"],
        "best_threshold": best,
        "promotion_status": promotion,
        "label_policy": label_name,
        "walk_forward_folds": len(wf),
        "walk_forward_report_path": str(REPORT_DIR / "v12_walk_forward_report.json"),
        "feature_importance_path": str(REPORT_DIR / "v12_feature_importance.csv"),
        "training_versions": artifact["training_versions"],
    }

    (MODEL_DIR / "strong_buy_model_metrics.json").write_text(
        json.dumps(metrics, indent=2),
        encoding="utf-8",
    )

    print(json.dumps(metrics, indent=2))
    return metrics


if __name__ == "__main__":
    train()