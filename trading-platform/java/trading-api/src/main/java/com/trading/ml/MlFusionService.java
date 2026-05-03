package com.trading.ml;

import com.trading.ml.MlPredictionResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class MlFusionService {

    public void writeMlColumns(Map<String, Object> row, MlPredictionResponse ml) {
        if (row == null) return;
        MlPredictionResponse r = ml == null ? MlPredictionResponse.fallback("ML response null") : ml;

        row.put("ml_probability", round(r.getProbability()));
        row.put("ml_xgb_probability", round(r.getXgbProbability()));
        row.put("ml_dl_probability", round(r.getDlProbability()));
        row.put("ml_ensemble_probability", round(r.getEnsembleProbability()));
        row.put("ml_rank_score", round(r.getRankScore()));
        row.put("ml_rank_bucket", nz(r.getRankBucket()));
        row.put("ml_confidence_band", nz(r.getConfidenceBand()));
        row.put("ml_model_driven_buy", r.isModelDrivenBuy());
        row.put("ml_model_driven_strong_buy", r.isModelDrivenStrongBuy());
        row.put("ml_schema_compatible", r.isSchemaCompatible());
        row.put("ml_hedge_safe_gating_passed", r.isHedgeSafeGatingPassed());
        row.put("ml_probability_cap_applied", r.isProbabilityCapApplied());
        row.put("ml_promotion_status", nz(r.getPromotionStatus()));
        row.put("ml_java_action_hint", nz(r.getJavaActionHint()));
        row.put("ml_status", nz(r.getStatus()));
        row.put("ml_model_version", nz(r.getModelVersion()));
        row.put("ml_decision_reason", nz(r.getDecisionReason()));
        row.put("ml_missing_required_features", r.getMissingRequiredFeatures() == null ? "" : String.join(",", r.getMissingRequiredFeatures()));
        row.put("ml_missing_optional_features", r.getMissingOptionalFeatures() == null ? "" : String.join(",", r.getMissingOptionalFeatures()));
    }

    /**
     * Conservative final fusion: Java remains authority; ML confirms/ranks only.
     */
    public String fuseFinalAction(String currentJavaAction,
                                  MlPredictionResponse ml,
                                  boolean javaStageBullish,
                                  double entryQualityScore,
                                  double bestRiskReward,
                                  double ruleScore) {
        if (ml == null || ml.isFallbackUsed()) return currentJavaAction;

        boolean schemaOk = ml.isSchemaCompatible()
                && ml.getMissingRequiredFeatures() != null
                && ml.getMissingRequiredFeatures().isEmpty();

        boolean mlWatchBoost = schemaOk
                && ml.getRankScore() >= 50.0
                && ml.getProbability() >= 0.50
                && !ml.isProbabilityCapApplied();

        boolean mlBuyConfirmation = schemaOk
                && ml.isHedgeSafeGatingPassed()
                && ml.getProbability() >= 0.55
                && ml.getRankScore() >= 55.0
                && javaStageBullish
                && entryQualityScore >= 65.0
                && bestRiskReward >= 1.80;

        boolean mlStrongConfirmation = mlBuyConfirmation
                && ml.getProbability() >= 0.72
                && "PROMOTED".equalsIgnoreCase(nz(ml.getPromotionStatus()))
                && ml.isModelDrivenStrongBuy();

        if (mlStrongConfirmation && ruleScore >= 75.0) return "STRONG_BUY";
        if (mlBuyConfirmation && ruleScore >= 68.0) return "BUY";
        if (mlWatchBoost && !containsAny(currentJavaAction, "BUY", "STRONG_BUY")) return "WATCH";
        return currentJavaAction;
    }

    private boolean containsAny(String text, String... needles) {
        String t = nz(text).toUpperCase();
        for (String n : needles) if (t.contains(n.toUpperCase())) return true;
        return false;
    }

    private String nz(String s) { return s == null ? "" : s; }
    private double round(double v) { return Math.round(v * 10000.0) / 10000.0; }
}
