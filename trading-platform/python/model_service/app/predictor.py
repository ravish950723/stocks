from app.predict_service import predict_probability


def predict_probability_only(req):
    return predict_probability({
        "signal_score": getattr(req, "signalScore", 0.0),
        "regime_quality_score": getattr(req, "regimeQualityScore", 0.0),
        "institutional_score": getattr(req, "institutionalScore", 0.0),
    })