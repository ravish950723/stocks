package com.trading.logic;

import lombok.Builder;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class MlEntryDecisionEngine {

    public void applyToRow(Map<String,Object> row) {

        MlEntryDecision d = decide(row);

        row.put("ML Entry Target", d.getMlEntryTarget());
        row.put("ML Entry Mode", d.getMlEntryMode());
        row.put("ML Entry Bias ATR", d.getMlEntryBiasAtr());

        row.put("ml_entry_target", d.getMlEntryTarget());
        row.put("ml_entry_mode", d.getMlEntryMode());
        row.put("ml_entry_bias_atr", d.getMlEntryBiasAtr());
    }

    public MlEntryDecision decide(Map<String,Object> row) {

        double close = firstDouble(row,
                "current_price","Current Price","Close","close");

        double atr = firstDouble(row,
                "ATR","atr","ATR14","atr14");

        double prob = firstDouble(row,
                "model_probability",
                "Model Probability",
                "ml_probability");

        String stage = firstString(row,
                "market_stage","Market Stage");

        String sub = firstString(row,
                "market_substage","Market Sub-Stage");

        String child = firstString(row,
                "child_substage","Child Substage");

        if(close <= 0 || atr <= 0 || prob <= 0){
            return MlEntryDecision.builder()
                    .mlEntryTarget(null)
                    .mlEntryMode("ML_NOT_AVAILABLE")
                    .mlEntryBiasAtr(null)
                    .build();
        }

        double bias;
        String mode;

        if(prob >= 0.75){
            bias = 0.25;
            mode = "AGGRESSIVE_ENTRY";
        } else if(prob >= 0.62){
            bias = 0.50;
            mode = "NORMAL_ENTRY";
        } else if(prob >= 0.52){
            bias = 0.75;
            mode = "PULLBACK_ENTRY";
        } else {
            bias = 1.00;
            mode = "WATCH_ONLY";
        }

        if(stage.equalsIgnoreCase("MARKUP")){
            bias -= 0.10;
        }

        if(stage.equalsIgnoreCase("ACCUMULATION")
                && containsAny(child,"BREAKOUT","COILED","BASE")){
            bias -= 0.10;
        }

        if(stage.equalsIgnoreCase("MARKDOWN")
                || stage.equalsIgnoreCase("DISTRIBUTION")){
            bias += 0.50;
            mode = "AVOID_LONG_ENTRY";
        }

        if(bias < 0.20) bias = 0.20;

        double target = close - atr * bias;

        return MlEntryDecision.builder()
                .mlEntryTarget(round2(target))
                .mlEntryMode(mode)
                .mlEntryBiasAtr(round2(bias))
                .build();
    }

    private boolean containsAny(String v,String...arr){
        String x=v.toUpperCase();
        for(String a:arr){
            if(x.contains(a.toUpperCase())) return true;
        }
        return false;
    }

    private double firstDouble(Map<String,Object> row,String...keys){
        for(String k:keys){
            Object v=row.get(k);
            if(v!=null){
                try{
                    double d=Double.parseDouble(v.toString());
                    if(d!=0) return d;
                }catch(Exception ignored){}
            }
        }
        return 0;
    }

    private String firstString(Map<String,Object> row,String...keys){
        for(String k:keys){
            Object v=row.get(k);
            if(v!=null && !v.toString().isBlank()) return v.toString();
        }
        return "";
    }

    private double round2(double v){
        return Math.round(v*100.0)/100.0;
    }

    @Data
    @Builder
    public static class MlEntryDecision{
        private Double mlEntryTarget;
        private String mlEntryMode;
        private Double mlEntryBiasAtr;
    }
}