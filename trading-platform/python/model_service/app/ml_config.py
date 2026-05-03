from __future__ import annotations

import os
from pathlib import Path

APP_DIR = Path(__file__).resolve().parent
BASE_DIR = APP_DIR.parent
TRAINING_DIR = BASE_DIR / "training"
MODELS_DIR = BASE_DIR / "models"

RAW_FEATURE_HISTORY_CSV = TRAINING_DIR / "raw_feature_history.csv"
TRAINING_CSV = TRAINING_DIR / "training_data.csv"

XGB_MODEL_PATH = MODELS_DIR / "xgb_model.joblib"
XGB_METRICS_PATH = MODELS_DIR / "xgb_metrics.json"
DL_MODEL_PATH = MODELS_DIR / "dl_model.pt"
DL_BUNDLE_PATH = MODELS_DIR / "dl_model.joblib"
DL_METRICS_PATH = MODELS_DIR / "dl_metrics.json"
ENSEMBLE_METRICS_PATH = MODELS_DIR / "ensemble_metrics.json"
DIAGNOSTICS_PATH = MODELS_DIR / "training_diagnostics.json"

MODEL_PATH = XGB_MODEL_PATH
METRICS_PATH = XGB_METRICS_PATH

SCHEMA_VERSION = "ml-schema-v8-hedge-java-compatible"
TARGET = "buy_label"

PROJECT_ROOT = BASE_DIR.parents[1]
JAVA_CONFIG_YML = PROJECT_ROOT / "config.yml"

CORE_FEATURES = [
    "current_price", "rsi", "adx", "atr14_pct", "vwap_distance_pct", "vol_surge_ratio",
    "ema_uptrend", "ema21_slope", "dma20", "dma50", "dma200", "pct_from_dma20",
    "pct_from_dma50", "pct_from_dma200", "breakout", "bullish_engulfing", "hammer",
    "near_support", "macd_cross", "regime_quality_score", "signal_score", "confidence_score",
    "institutional_score",
]

# These are the strongest Java-pipeline features. The Python service is backward-compatible if
# they are absent, but accuracy will materially improve when Java exports them.
OPTIONAL_FEATURES = [
    "relative_strength_vs_spy", "gap_percent", "volatility_score", "entry_quality_score",
    "stage_confidence", "substage_confidence", "child_substage_confidence", "best_risk_reward",
    "days_to_peak_score", "eps_quality_score", "fundamental_boost", "news_sentiment_score",
    "news_positive_ratio", "sector_rotation_score", "institutional_flow_score",
]

FEATURES = CORE_FEATURES + OPTIONAL_FEATURES

BOOLEAN_FEATURES = [
    "ema_uptrend", "breakout", "bullish_engulfing", "hammer", "near_support", "macd_cross",
]

# Do not require all 38 fields live; Java can roll out advanced fields incrementally.
# These are the minimum fields needed to avoid garbage model input.
REQUIRED_LIVE_FEATURES = [
    "current_price", "rsi", "adx", "atr14_pct", "vwap_distance_pct", "vol_surge_ratio",
    "ema_uptrend", "dma20", "dma50", "dma200", "regime_quality_score", "signal_score",
    "confidence_score", "institutional_score",
]

FEATURE_RANGES = {
    "rsi": (0.0, 100.0),
    "adx": (0.0, 100.0),
    "atr14_pct": (0.0, 50.0),
    "vol_surge_ratio": (0.0, 20.0),
    "vwap_distance_pct": (-50.0, 50.0),
    "pct_from_dma20": (-80.0, 300.0),
    "pct_from_dma50": (-80.0, 300.0),
    "pct_from_dma200": (-90.0, 500.0),
    "relative_strength_vs_spy": (-100.0, 300.0),
    "gap_percent": (-50.0, 50.0),
    "volatility_score": (0.0, 100.0),
    "best_risk_reward": (0.0, 10.0),
    "news_positive_ratio": (0.0, 1.0),
}

for _score in [
    "regime_quality_score", "signal_score", "confidence_score", "institutional_score",
    "entry_quality_score", "stage_confidence", "substage_confidence", "child_substage_confidence",
    "days_to_peak_score", "eps_quality_score", "fundamental_boost", "news_sentiment_score",
    "sector_rotation_score", "institutional_flow_score",
]:
    FEATURE_RANGES.setdefault(_score, (-100.0, 100.0))

FORWARD_WINDOW_DAYS = int(os.getenv("FORWARD_WINDOW_DAYS", "20"))
MIN_FORWARD_GAIN_PCT = float(os.getenv("MIN_FORWARD_GAIN_PCT", "5.5"))
MAX_FORWARD_DRAWDOWN_PCT = float(os.getenv("MAX_FORWARD_DRAWDOWN_PCT", "-4.0"))

# Live Java policy: keep the model safe. Probabilities can boost/rank; BUY authority is gated.
DEFAULT_THRESHOLD = float(os.getenv("ML_DEFAULT_THRESHOLD", "0.55"))
MIN_PRECISION_FOR_BUY = float(os.getenv("ML_MIN_PRECISION_FOR_BUY", "0.55"))
MIN_RECALL_FOR_THRESHOLD = float(os.getenv("ML_MIN_RECALL_FOR_THRESHOLD", "0.05"))
MIN_PREDICTED_POSITIVE_RATE = float(os.getenv("ML_MIN_PRED_POS_RATE", "0.005"))
MAX_PREDICTED_POSITIVE_RATE = float(os.getenv("ML_MAX_PRED_POS_RATE", "0.20"))
MIN_PROMOTION_ROC_AUC = float(os.getenv("ML_MIN_PROMOTION_AUC", "0.64"))
MIN_PROMOTION_PRECISION = float(os.getenv("ML_MIN_PROMOTION_PRECISION", "0.60"))
MIN_PROMOTION_RECALL = float(os.getenv("ML_MIN_PROMOTION_RECALL", "0.10"))
MIN_PROMOTION_TOP10_LIFT = float(os.getenv("ML_MIN_PROMOTION_TOP10_LIFT", "1.35"))
MAX_SELECTED_THRESHOLD = float(os.getenv("ML_MAX_SELECTED_THRESHOLD", "0.60"))
MIN_TEST_BUY_CALLS_FOR_METRIC_SANITY = int(os.getenv("ML_MIN_TEST_BUY_CALLS", "25"))

XGB_ENSEMBLE_WEIGHT = float(os.getenv("ML_XGB_WEIGHT", "0.60"))
DL_ENSEMBLE_WEIGHT = float(os.getenv("ML_DL_WEIGHT", "0.40"))

# Even if threshold search falls to 0.30, live Java BUY gating should not become loose.
LIVE_BUY_FLOOR = float(os.getenv("ML_LIVE_BUY_FLOOR", "0.55"))
LIVE_STRONG_BUY_FLOOR = float(os.getenv("ML_LIVE_STRONG_BUY_FLOOR", "0.72"))
MAX_PROBABILITY_WHEN_REQUIRED_MISSING = float(os.getenv("ML_MAX_PROB_REQUIRED_MISSING", "0.49"))
MIN_OPTIONAL_FEATURE_COVERAGE_FOR_BUY = float(os.getenv("ML_MIN_OPTIONAL_COVERAGE_FOR_BUY", "0.35"))
MAX_PROBABILITY_WHEN_OPTIONAL_COVERAGE_LOW = float(os.getenv("ML_MAX_PROB_OPTIONAL_LOW", "0.54"))
ENSEMBLE_MAX_MODEL_GAP_FOR_BUY = float(os.getenv("ML_ENSEMBLE_MAX_MODEL_GAP", "0.25"))
MIN_TRAINING_SYMBOLS = int(os.getenv("ML_MIN_TRAINING_SYMBOLS", "10"))
DROP_INSUFFICIENT_FORWARD_ROWS = os.getenv("ML_DROP_INSUFFICIENT_FORWARD_ROWS", "true").lower() in {"1", "true", "yes"}
OUTLIER_CLIP_QUANTILE_LOW = float(os.getenv("ML_OUTLIER_CLIP_LOW", "0.005"))
OUTLIER_CLIP_QUANTILE_HIGH = float(os.getenv("ML_OUTLIER_CLIP_HIGH", "0.995"))


def load_java_symbols() -> list[str]:
    try:
        import yaml
    except ImportError:
        raise RuntimeError("PyYAML is required. Add pyyaml to requirements.txt")

    if not JAVA_CONFIG_YML.exists():
        raise FileNotFoundError(f"Java config.yml not found: {JAVA_CONFIG_YML}")

    with open(JAVA_CONFIG_YML, "r", encoding="utf-8") as f:
        cfg = yaml.safe_load(f) or {}

    symbols = cfg.get("symbols") or []
    symbols = [str(s).strip().upper() for s in symbols if str(s).strip()]

    if not symbols:
        raise RuntimeError(f"No symbols found in config.yml: {JAVA_CONFIG_YML}")

    return sorted(set(symbols))