package com.rinha;

import java.util.HashMap;
import java.util.Map;

/**
 * Mapa de risco por MCC (Merchant Category Code).
 * Valores entre 0.0 (seguro) e 1.0 (arriscado).
 * Se o MCC não estiver no mapa, usa 0.5 como default.
 */
public class MccRisk {

    private final Map<String, Double> riskMap;

    public MccRisk(Map<String, Double> riskMap) {
        this.riskMap = riskMap;
    }

    /**
     * Retorna o risco do MCC. Default: 0.5 se não encontrado.
     */
    public double getRisk(String mcc) {
        return riskMap.getOrDefault(mcc, 0.5);
    }

    /** Mapa padrão conforme mcc_risk.json */
    public static MccRisk defaults() {
        Map<String, Double> map = new HashMap<>();
        map.put("5411", 0.15);
        map.put("5812", 0.30);
        map.put("5912", 0.20);
        map.put("5944", 0.45);
        map.put("7801", 0.80);
        map.put("7802", 0.75);
        map.put("7995", 0.85);
        map.put("4511", 0.35);
        map.put("5311", 0.25);
        map.put("5999", 0.50);
        return new MccRisk(map);
    }
}
