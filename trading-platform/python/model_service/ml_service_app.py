import json
from pathlib import Path
from typing import Dict, Any

import joblib
import numpy as np
import pandas as pd
from fastapi import FastAPI
from pydantic import BaseModel


BASE_DIR = Path(__file__).resolve().parent
MODELS_DIR = BASE_DIR / "models"

XGB_MODEL_PATH = MODELS_DIR / "xgb_model.joblib"
XGB_METRICS_PATH = MODELS_DIR / "xgb_metrics.json"

STRONG_BUY_MODEL_PATH = MODELS_DIR / "strong_buy_model.joblib"
STRONG_BUY_METRICS_PATH = MODELS_DIR / "strong_buy_model_metrics.json"

SCHEMA_VERSION = "ml-schema-v4-java-compatible-calibrated"

app = FastAPI(title="Trading ML Service", version="4.0")

xgb_model = None
xgb_features = []
xgb_metrics = {}

strong_bundle = None
strong_buy_metrics = {}

XGB_THRESHOLD = 0.55
LIVE_STRONG_BUY_THRESHOLD = 0.72


class PredictRequest(BaseModel):
    symbol: str
    features: Dict[str, Any]


class PredictResponse(BaseModel):
    symbol: str
    probability: float
    rankScore: float
    status: str
    promotionStatus: str
    hedgeGate: bool
    schemaOk: bool
    missingRequired: list[str]
    xgbLoaded: bool
    strongBuyLoaded: bool


def load_json(path: Path) -> Dict[str, Any]:
    if not path.exists():
        return {}
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return {}


def load_model_bundle(path: Path):
    if not path.exists():
        return None

    bundle = joblib.load(path)

    if isinstance(bundle, dict):
        return bundle

    return {
        "model": bundle,
        "features": [],
        "imputer": None,
        "scaler": None,
        "calibrator": None,
        "threshold": None,
        "promotion_status": "SUPPORTING_SIGNAL_ONLY",
    }


@app.on_event("startup")
def startup():
    global xgb_model, xgb_features, xgb_metrics
    global strong_bundle, strong_buy_metrics
    global XGB_THRESHOLD, LIVE_STRONG_BUY_THRESHOLD

    xgb_metrics = load_json(XGB_METRICS_PATH)
    strong_buy_metrics = load_json(STRONG_BUY_METRICS_PATH)

    if XGB_MODEL_PATH.exists():
        try:
            xgb_bundle = load_model_bundle(XGB_MODEL_PATH)

            if xgb_bundle:
                xgb_model = xgb_bundle.get("model")
                xgb_features = (
                    xgb_bundle.get("features")
                    or xgb_bundle.get("feature_cols")
                    or []
                )
                XGB_THRESHOLD = float(
                    xgb_bundle.get("threshold")
                    or xgb_metrics.get("threshold")
                    or XGB_THRESHOLD
                )

            print(f"XGB_MODEL_LOADED path={XGB_MODEL_PATH} features={len(xgb_features)}")

        except Exception as e:
            xgb_model = None
            xgb_features = []
            print(f"XGB_MODEL_LOAD_FAILED path={XGB_MODEL_PATH} error={e}")
    else:
        print(f"XGB_MODEL_NOT_FOUND path={XGB_MODEL_PATH}")

    if STRONG_BUY_MODEL_PATH.exists():
        try:
            strong_bundle = load_model_bundle(STRONG_BUY_MODEL_PATH)

            if strong_bundle:
                LIVE_STRONG_BUY_THRESHOLD = float(
                    strong_bundle.get("threshold")
                    or strong_buy_metrics.get("best_threshold", {}).get("threshold", LIVE_STRONG_BUY_THRESHOLD)
                    if isinstance(strong_buy_metrics.get("best_threshold"), dict)
                    else strong_bundle.get("threshold") or LIVE_STRONG_BUY_THRESHOLD
                )

            print(f"STRONG_BUY_MODEL_LOADED path={STRONG_BUY_MODEL_PATH}")

        except Exception as e:
            strong_bundle = None
            print(f"STRONG_BUY_MODEL_LOAD_FAILED path={STRONG_BUY_MODEL_PATH} error={e}")
    else:
        print(f"STRONG_BUY_MODEL_NOT_FOUND path={STRONG_BUY_MODEL_PATH}")


@app.get("/health")
def health():
    return {
        "status": "ok",
        "xgbLoaded": xgb_model is not None,
        "strongBuyLoaded": strong_bundle is not None,
        "dlLoaded": False,
        "xgbModelPath": str(XGB_MODEL_PATH),
        "strongBuyModelPath": str(STRONG_BUY_MODEL_PATH),
        "xgbMetricsPath": str(XGB_METRICS_PATH),
        "schemaVersion": SCHEMA_VERSION,
        "liveBuyThreshold": XGB_THRESHOLD,
        "liveStrongBuyThreshold": LIVE_STRONG_BUY_THRESHOLD,
        "featureCount": len(xgb_features),
        "promotionStatus": (
            strong_bundle.get("promotion_status")
            if isinstance(strong_bundle, dict)
            else xgb_metrics.get("promotion_status", "SUPPORTING_SIGNAL_ONLY")
        ),
        "xgbThreshold": XGB_THRESHOLD,
        "ensembleMetricsLoaded": False,
    }


def to_float(value: Any) -> float:
    try:
        v = float(value)
        if np.isfinite(v):
            return v
    except Exception:
        pass
    return 0.0


def build_frame_from_features(
    input_features: Dict[str, Any],
    expected_features: list[str],
):
    missing = []
    row = {}

    if expected_features:
        for col in expected_features:
            value = input_features.get(col)
            if value is None:
                missing.append(col)
                value = 0.0
            row[col] = to_float(value)
    else:
        for k, v in input_features.items():
            row[k] = to_float(v)

    return pd.DataFrame([row]), missing


def predict_from_bundle(bundle: Dict[str, Any], input_features: Dict[str, Any]):
    model = bundle.get("model")
    features = bundle.get("features") or bundle.get("feature_cols") or []
    imputer = bundle.get("imputer")
    scaler = bundle.get("scaler")
    calibrator = bundle.get("calibrator")

    X, missing = build_frame_from_features(input_features, features)

    X_input = X

    if imputer is not None:
        X_input = imputer.transform(X_input)

    if scaler is not None:
        X_input = scaler.transform(X_input)

    probability = float(model.predict_proba(X_input)[0][1])

    if calibrator is not None:
        probability = float(calibrator.predict([probability])[0])

    probability = max(0.0, min(1.0, probability))

    return probability, missing


@app.post("/predict", response_model=PredictResponse)
def predict(req: PredictRequest):
    symbol = (req.symbol or "UNKNOWN").strip().upper()

    if strong_bundle is not None:
        probability, missing = predict_from_bundle(strong_bundle, req.features)
        promotion_status = strong_bundle.get("promotion_status", "SUPPORTING_SIGNAL_ONLY")
        threshold = float(strong_bundle.get("threshold") or LIVE_STRONG_BUY_THRESHOLD)

    elif xgb_model is not None:
        temp_bundle = {
            "model": xgb_model,
            "features": xgb_features,
            "imputer": None,
            "scaler": None,
            "calibrator": None,
            "threshold": XGB_THRESHOLD,
            "promotion_status": xgb_metrics.get("promotion_status", "SUPPORTING_SIGNAL_ONLY"),
        }
        probability, missing = predict_from_bundle(temp_bundle, req.features)
        promotion_status = temp_bundle["promotion_status"]
        threshold = XGB_THRESHOLD

    else:
        return PredictResponse(
            symbol=symbol,
            probability=0.0,
            rankScore=0.0,
            status="MODEL_NOT_LOADED",
            promotionStatus="MODEL_NOT_LOADED",
            hedgeGate=False,
            schemaOk=False,
            missingRequired=[],
            xgbLoaded=False,
            strongBuyLoaded=False,
        )

    rank_score = round(probability * 100.0, 2)

    if probability >= LIVE_STRONG_BUY_THRESHOLD:
        status = "STRONG_BUY_CANDIDATE"
    elif probability >= XGB_THRESHOLD:
        status = "BUY_SUPPORTING_SIGNAL"
    else:
        status = "SUPPORTING_SIGNAL_ONLY"

    return PredictResponse(
        symbol=symbol,
        probability=round(probability, 6),
        rankScore=rank_score,
        status=status,
        promotionStatus=promotion_status,
        hedgeGate=probability >= threshold,
        schemaOk=len(missing) == 0,
        missingRequired=missing[:25],
        xgbLoaded=xgb_model is not None,
        strongBuyLoaded=strong_bundle is not None,
    )