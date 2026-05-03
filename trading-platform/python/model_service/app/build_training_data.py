from __future__ import annotations

import json
import os
from typing import Iterable

import numpy as np
import pandas as pd

from app.ml_config import (
    BOOLEAN_FEATURES,
    CORE_FEATURES,
    DROP_INSUFFICIENT_FORWARD_ROWS,
    FEATURES,
    MAX_FORWARD_DRAWDOWN_PCT,
    MIN_FORWARD_GAIN_PCT,
    MIN_TRAINING_SYMBOLS,
    OUTLIER_CLIP_QUANTILE_HIGH,
    OUTLIER_CLIP_QUANTILE_LOW,
    RAW_FEATURE_HISTORY_CSV,
    TARGET,
    TRAINING_CSV,
)

FORWARD_WINDOW_DAYS = int(os.getenv("FORWARD_WINDOW_DAYS", "20"))
TARGET_GAIN_PCT = float(os.getenv("MIN_FORWARD_GAIN_PCT", str(MIN_FORWARD_GAIN_PCT)))
STOP_LOSS_PCT = float(os.getenv("MAX_FORWARD_DRAWDOWN_PCT", str(MAX_FORWARD_DRAWDOWN_PCT)))

ALIASES = {
    "currentPrice": "current_price", "atr14Pct": "atr14_pct", "vwapDistancePct": "vwap_distance_pct",
    "volSurgeRatio": "vol_surge_ratio", "emaUptrend": "ema_uptrend", "ema21Slope": "ema21_slope",
    "pctFromDma20": "pct_from_dma20", "pctFromDma50": "pct_from_dma50", "pctFromDma200": "pct_from_dma200",
    "bullishEngulfing": "bullish_engulfing", "nearSupport": "near_support", "macdCross": "macd_cross",
    "regimeQualityScore": "regime_quality_score", "signalScore": "signal_score", "confidenceScore": "confidence_score",
    "institutionalScore": "institutional_score", "relativeStrengthVsSpy": "relative_strength_vs_spy",
    "gapPercent": "gap_percent", "volatilityScore": "volatility_score", "entryQualityScore": "entry_quality_score",
    "stageConfidence": "stage_confidence", "substageConfidence": "substage_confidence",
    "childSubstageConfidence": "child_substage_confidence", "bestRiskReward": "best_risk_reward",
    "daysToPeakScore": "days_to_peak_score", "epsQualityScore": "eps_quality_score", "fundamentalBoost": "fundamental_boost",
    "newsSentimentScore": "news_sentiment_score", "newsPositiveRatio": "news_positive_ratio",
    "sectorRotationScore": "sector_rotation_score", "institutionalFlowScore": "institutional_flow_score",
}


def _rename_aliases(df: pd.DataFrame) -> pd.DataFrame:
    return df.rename(columns={k: v for k, v in ALIASES.items() if k in df.columns})


def _to_bool_series(series: pd.Series) -> pd.Series:
    return (
        series.astype(str).str.strip().str.lower()
        .map({"true": 1, "false": 0, "1": 1, "0": 0, "yes": 1, "no": 0, "y": 1, "n": 0})
        .fillna(0).astype(int)
    )


def _first_existing_column(columns: Iterable[str], candidates: list[str]) -> str | None:
    existing = set(columns)
    return next((c for c in candidates if c in existing), None)


def _clip_outliers_by_symbol(df: pd.DataFrame, cols: list[str]) -> pd.DataFrame:
    out = df.copy()
    for col in cols:
        if col not in out.columns or col in BOOLEAN_FEATURES:
            continue
        def clip_one(s: pd.Series) -> pd.Series:
            if s.notna().sum() < 50:
                return s
            lo = s.quantile(OUTLIER_CLIP_QUANTILE_LOW)
            hi = s.quantile(OUTLIER_CLIP_QUANTILE_HIGH)
            if pd.isna(lo) or pd.isna(hi) or lo >= hi:
                return s
            return s.clip(lo, hi)
        out[col] = out.groupby("symbol")[col].transform(clip_one)
    return out


def leakage_audit(training: pd.DataFrame) -> dict:
    leakage_tokens = [
        "future", "forward", "target", "label",
        "90d", "days_to_peak", "hit_before_stop",
        "available_forward_rows", "outcome"
    ]

    suspicious = [
        c for c in training.columns
        if any(t in c.lower() for t in leakage_tokens)
        and c not in {TARGET, "label_outcome"}
    ]

    duplicate_rows = int(training.duplicated(["symbol", "date"]).sum())

    report = {
        "suspicious_future_columns_present": suspicious,
        "duplicate_symbol_date_rows": duplicate_rows,
        "rows": int(len(training)),
        "symbols": int(training["symbol"].nunique())
    }

    audit_path = TRAINING_CSV.parent / "leakage_audit_report.json"
    audit_path.write_text(json.dumps(report, indent=2), encoding="utf-8")

    print("LEAKAGE_AUDIT:")
    print(json.dumps(report, indent=2))

    return report


def normalize_raw_features(df: pd.DataFrame) -> tuple[pd.DataFrame, dict]:
    df = _rename_aliases(df)
    close_col = _first_existing_column(df.columns, ["close", "Close", "current_price", "currentPrice"])
    if close_col is None:
        raise ValueError("Missing close/current_price column needed for forward-label generation.")
    if close_col != "close":
        df["close"] = df[close_col]

    if "current_price" not in df.columns and "close" in df.columns:
        df["current_price"] = df["close"]

    required = {"symbol", "date", "close", *CORE_FEATURES}
    missing_core = sorted(required - set(df.columns))
    if missing_core:
        raise ValueError(f"Missing required raw feature columns exported by Java: {missing_core}")

    out = df.copy()
    print("===================================================")
    print("RAW_FEATURE_INPUT_ROWS =", len(out))
    print("RAW_FEATURE_COLUMNS =", list(out.columns))
    print("RAW_FEATURE_SYMBOLS =", out["symbol"].astype(str).str.upper().nunique())
    print("RAW_FEATURE_TOP_SYMBOL_COUNTS =")
    print(out["symbol"].astype(str).str.upper().value_counts().head(30).to_string())
    print("===================================================")
    out["symbol"] = out["symbol"].astype(str).str.strip().str.upper()
    out["date"] = pd.to_datetime(out["date"], errors="coerce")

    optional_present = sorted([c for c in FEATURES if c in out.columns and c not in CORE_FEATURES])
    for col in FEATURES:
        if col not in out.columns:
            out[col] = 0.0

    numeric_cols = [c for c in FEATURES + ["close"] if c not in BOOLEAN_FEATURES]
    for col in numeric_cols:
        out[col] = pd.to_numeric(out[col], errors="coerce").replace([np.inf, -np.inf], np.nan).fillna(0.0)
    for col in BOOLEAN_FEATURES:
        out[col] = _to_bool_series(out[col]) if col in out.columns else 0

    before = len(out)

    bad_close = out[pd.to_numeric(out["close"], errors="coerce").fillna(0) <= 0]
    if not bad_close.empty:
        print("BAD_CLOSE_SYMBOLS:")
        print(bad_close["symbol"].value_counts().head(50).to_string())

    bad_date = out[pd.to_datetime(out["date"], errors="coerce").isna()]
    if not bad_date.empty:
        print("BAD_DATE_SYMBOLS:")
        print(bad_date["symbol"].value_counts().head(50).to_string())


    out = out.dropna(subset=["symbol", "date"])
    out = out[out["symbol"] != ""]
    out = out[out["close"] > 0]
    out = out.sort_values(["symbol", "date"]).drop_duplicates(["symbol", "date"], keep="last")
    out = _clip_outliers_by_symbol(out, FEATURES + ["close"])

    print("CLEAN_ROWS =", len(out))
    print("REMOVED_ROWS =", before - len(out))
    print("FINAL_SYMBOLS =", out["symbol"].nunique())

    lost = set(df["symbol"].astype(str).str.upper()) - set(out["symbol"].astype(str).str.upper())
    if lost:
        print("FULLY_REMOVED_SYMBOLS =", sorted(list(lost))[:200])

    diagnostics = {
        "raw_rows": int(before),
        "clean_rows_before_labeling": int(len(out)),
        "optional_features_present": optional_present,
        "optional_feature_coverage_ratio": round(len(optional_present) / max(len(FEATURES) - len(CORE_FEATURES), 1), 4),
    }
    return out, diagnostics


def _path_aware_label(entry: float, future_prices: pd.Series) -> tuple[int, float, float, int, str]:
    target_price = entry * (1.0 + TARGET_GAIN_PCT / 100.0)
    stop_price = entry * (1.0 + STOP_LOSS_PCT / 100.0)
    max_ret = float(((future_prices.max() - entry) / entry) * 100.0)
    min_ret = float(((future_prices.min() - entry) / entry) * 100.0)
    peak_pos = int(future_prices.reset_index(drop=True).idxmax() + 1)
    for day, price in enumerate(future_prices, start=1):
        if price <= stop_price:
            return 0, max_ret, min_ret, peak_pos, f"STOP_FIRST_DAY_{day}"
        if price >= target_price:
            return 1, max_ret, min_ret, day, f"TARGET_FIRST_DAY_{day}"
    return 0, max_ret, min_ret, peak_pos, "NO_TARGET_WITHIN_WINDOW"


def add_forward_labels(df: pd.DataFrame) -> pd.DataFrame:
    frames: list[pd.DataFrame] = []
    for _, group in df.groupby("symbol", sort=False):
        g = group.sort_values("date").reset_index(drop=True).copy()
        closes = g["close"].astype(float)
        labels, max_rets, min_rets, days_to_peak, outcomes, forward_rows = [], [], [], [], [], []
        for i in range(len(g)):
            entry = float(closes.iloc[i])
            end = min(i + FORWARD_WINDOW_DAYS, len(g) - 1)
            available_forward = end - i
            forward_rows.append(available_forward)
            if available_forward < FORWARD_WINDOW_DAYS or entry <= 0:
                labels.append(0); max_rets.append(0.0); min_rets.append(0.0); days_to_peak.append(0); outcomes.append("INSUFFICIENT_FORWARD_WINDOW")
                continue
            label, mx, mn, days, outcome = _path_aware_label(entry, closes.iloc[i + 1 : end + 1])
            labels.append(label); max_rets.append(mx); min_rets.append(mn); days_to_peak.append(days); outcomes.append(outcome)

        g[f"future_max_return_pct_{FORWARD_WINDOW_DAYS}d"] = max_rets
        g[f"future_min_return_pct_{FORWARD_WINDOW_DAYS}d"] = min_rets
        g[f"days_to_peak_{FORWARD_WINDOW_DAYS}d"] = days_to_peak
        g["available_forward_rows"] = forward_rows
        g["label_outcome"] = outcomes
        g[TARGET] = labels
        frames.append(g)
    out = pd.concat(frames, ignore_index=True)
    if DROP_INSUFFICIENT_FORWARD_ROWS:
        out = out[out["label_outcome"] != "INSUFFICIENT_FORWARD_WINDOW"].copy()
    return out


def main() -> None:
    if not RAW_FEATURE_HISTORY_CSV.exists():
        raise FileNotFoundError(f"Input feature history not found: {RAW_FEATURE_HISTORY_CSV}")
    raw = pd.read_csv(RAW_FEATURE_HISTORY_CSV)
    clean, diagnostics = normalize_raw_features(raw)
    if clean["symbol"].nunique() < MIN_TRAINING_SYMBOLS:
        raise ValueError(f"Need at least {MIN_TRAINING_SYMBOLS} symbols for useful ML training. Current: {clean['symbol'].nunique()}.")
    if clean.groupby("symbol")["date"].nunique().max() <= FORWARD_WINDOW_DAYS:
        raise ValueError(f"Need more than {FORWARD_WINDOW_DAYS} historical rows per symbol.")

    training = add_forward_labels(clean)
    TRAINING_CSV.parent.mkdir(parents=True, exist_ok=True)
    leakage_audit(training)
    symbol_dir = TRAINING_CSV.parent / "by_symbol_training"
    symbol_dir.mkdir(parents=True, exist_ok=True)

    for symbol, g in training.groupby("symbol"):
        safe_symbol = str(symbol).replace("/", "_").replace("\\", "_")
        g.sort_values("date").to_csv(symbol_dir / f"{safe_symbol}.csv", index=False)

    training.to_csv(TRAINING_CSV, index=False)

    pos = int(training[TARGET].sum())
    outcome_counts = training["label_outcome"].value_counts().head(20).to_dict()
    summary = {
        "rows": int(len(training)),
        "symbols": int(training["symbol"].nunique()),
        "positive_rows": pos,
        "negative_rows": int(len(training) - pos),
        "positive_ratio": round(pos / max(len(training), 1), 4),
        "forward_window_days": FORWARD_WINDOW_DAYS,
        "target_gain_pct": TARGET_GAIN_PCT,
        "stop_loss_pct": STOP_LOSS_PCT,
        "label_policy": "PATH_AWARE_TARGET_FIRST_BEFORE_STOP_DROP_INCOMPLETE_WINDOW",
        "feature_count": len(FEATURES),
        "optional_features_present": diagnostics["optional_features_present"],
        "optional_feature_coverage_ratio": diagnostics["optional_feature_coverage_ratio"],
        "label_outcome_counts_top20": outcome_counts,
    }
    print(json.dumps(summary, indent=2))
    print(f"Saved training data to {TRAINING_CSV}")


if __name__ == "__main__":
    main()
