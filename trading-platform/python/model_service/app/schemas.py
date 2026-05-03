from __future__ import annotations

from pydantic import BaseModel, ConfigDict, Field, field_validator


class MlRequest(BaseModel):
    """Java-compatible request DTO.

    Java can send camelCase fields exactly as before. The service also accepts snake_case
    names through extra fields handled in ml_service.py, so older/newer mappers do not break.
    """
    model_config = ConfigDict(extra="allow")

    symbol: str
    schemaVersion: str | None = None
    asOfDate: str | None = None
    assetType: str | None = None

    currentPrice: float | None = None
    rsi: float | None = None
    adx: float | None = None
    atr14Pct: float | None = None
    vwapDistancePct: float | None = None
    volSurgeRatio: float | None = None
    emaUptrend: float | bool | None = None
    ema21Slope: float | None = None
    dma20: float | None = None
    dma50: float | None = None
    dma200: float | None = None
    pctFromDma20: float | None = None
    pctFromDma50: float | None = None
    pctFromDma200: float | None = None
    breakout: bool | float | None = None
    bullishEngulfing: bool | float | None = None
    hammer: bool | float | None = None
    nearSupport: bool | float | None = None
    macdCross: bool | float | None = None
    regimeQualityScore: float | None = None
    signalScore: float | None = None
    confidenceScore: float | None = None
    institutionalScore: float | None = None

    relativeStrengthVsSpy: float | None = None
    gapPercent: float | None = None
    volatilityScore: float | None = None
    entryQualityScore: float | None = None
    stageConfidence: float | None = None
    substageConfidence: float | None = None
    childSubstageConfidence: float | None = None
    bestRiskReward: float | None = None
    daysToPeakScore: float | None = None
    epsQualityScore: float | None = None
    fundamentalBoost: float | None = None
    newsSentimentScore: float | None = None
    newsPositiveRatio: float | None = None
    sectorRotationScore: float | None = None
    institutionalFlowScore: float | None = None

    @field_validator("symbol")
    @classmethod
    def symbol_required(cls, value: str) -> str:
        cleaned = (value or "").strip().upper()
        if not cleaned:
            raise ValueError("symbol is required")
        return cleaned


class MlResponse(BaseModel):
    # Existing Java-friendly fields preserved.
    probability: float = Field(..., ge=0.0, le=1.0)
    xgbProbability: float | None = None
    dlProbability: float | None = None
    confidenceBand: str
    modelDrivenBuy: bool
    modelDrivenStrongBuy: bool
    mlEntryTarget: float
    techFallbackScore: float
    decisionReason: str
    fallbackUsed: bool
    modelVersion: str
    schemaVersion: str
    status: str
    thresholdUsed: float
    modelAuc: float | None = None
    precisionAtThreshold: float | None = None
    missingFeatures: list[str] = Field(default_factory=list)
    missingRequiredFeatures: list[str] = Field(default_factory=list)
    defaultedFeatures: list[str] = Field(default_factory=list)
    featureCount: int = 0
    promotionStatus: str = "SUPPORTING_SIGNAL_ONLY"

    # New safe-integration fields. If your Java DTO is strict, either add these fields or
    # configure Jackson to ignore unknown properties.
    javaActionHint: str = "ML_SUPPORT_ONLY"
    probabilityCapApplied: bool = False
    liveBuyFloor: float = 0.55
    liveStrongBuyFloor: float = 0.72
    schemaCompatible: bool = True
    optionalFeatureCoveragePct: float = 0.0
    modelAgreementGap: float | None = None
    hedgeSafeGatingPassed: bool = False
    rankBucket: str = "IGNORE"
    rankScore: float = 0.0
