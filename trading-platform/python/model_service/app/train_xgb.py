import json
from pathlib import Path

import joblib
import pandas as pd
from sklearn.metrics import roc_auc_score, average_precision_score
from xgboost import XGBClassifier
import json
from pathlib import Path

import joblib
import pandas as pd
from sklearn.metrics import roc_auc_score, average_precision_score
from xgboost import XGBClassifier

# from app.splits import time_based_train_valid_test_split


BASE_DIR = Path(__file__).resolve().parents[1]
TRAINING_FILE = BASE_DIR / "training" / "training_data.csv"
MODELS_DIR = BASE_DIR / "models"

XGB_MODEL_PATH = MODELS_DIR / "xgb_model.joblib"
XGB_METRICS_PATH = MODELS_DIR / "xgb_metrics.json"

LABEL_COL = "buy_label"


def train_xgb():
    MODELS_DIR.mkdir(parents=True, exist_ok=True)

    df = pd.read_csv(TRAINING_FILE)

    if LABEL_COL not in df.columns:
        raise ValueError(f"Missing label column: {LABEL_COL}")

    drop_cols = {
        LABEL_COL,
        "symbol",
        "date",
        "target_hit_before_stop_90d",
        "future_return_20d",
        "future_max_gain_20d",
        "future_max_drawdown_20d",
    }

    feature_cols = [
        c for c in df.columns
        if c not in drop_cols and pd.api.types.is_numeric_dtype(df[c])
    ]

    df = df.dropna(subset=[LABEL_COL])
    df[feature_cols] = df[feature_cols].fillna(0.0)

    train_df, valid_df, test_df = time_split(df)

    X_train = train_df[feature_cols]
    y_train = train_df[LABEL_COL].astype(int)

    X_test = test_df[feature_cols]
    y_test = test_df[LABEL_COL].astype(int)

    model = XGBClassifier(
        n_estimators=350,
        max_depth=4,
        learning_rate=0.035,
        subsample=0.85,
        colsample_bytree=0.85,
        min_child_weight=5,
        reg_lambda=3.0,
        reg_alpha=0.5,
        objective="binary:logistic",
        eval_metric="auc",
        random_state=42,
        n_jobs=4,
    )

    model.fit(X_train, y_train)

    test_prob = model.predict_proba(X_test)[:, 1]

    auc = roc_auc_score(y_test, test_prob)
    avg_precision = average_precision_score(y_test, test_prob)

    model_bundle = {
        "model": model,
        "feature_cols": feature_cols,
        "label_col": LABEL_COL,
        "schema_version": "ml-schema-v3-hedge-java-compatible",
    }

    joblib.dump(model_bundle, XGB_MODEL_PATH)

    metrics = {
        "rows": int(len(df)),
        "train_rows": int(len(train_df)),
        "valid_rows": int(len(valid_df)),
        "test_rows": int(len(test_df)),
        "features": int(len(feature_cols)),
        "positive_ratio": float(df[LABEL_COL].mean()),
        "test_auc": float(auc),
        "test_average_precision": float(avg_precision),
        "promotion_status": "SUPPORTING_SIGNAL_ONLY",
        "model_path": str(XGB_MODEL_PATH),
        "feature_count": len(feature_cols),
    }

    with open(XGB_METRICS_PATH, "w", encoding="utf-8") as f:
        json.dump(metrics, f, indent=2)

    print(json.dumps(metrics, indent=2))
    return metrics


def time_split(df):
    df = df.sort_values(["date", "symbol"]).reset_index(drop=True)

    n = len(df)
    train_end = int(n * 0.68)
    valid_end = int(n * 0.83)

    train_df = df.iloc[:train_end].copy()
    valid_df = df.iloc[train_end:valid_end].copy()
    test_df = df.iloc[valid_end:].copy()

    return train_df, valid_df, test_df


if __name__ == "__main__":
    train_xgb()