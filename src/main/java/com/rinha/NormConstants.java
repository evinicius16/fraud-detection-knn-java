package com.rinha;

/**
 * Constantes de normalização carregadas de normalization.json.
 * Usadas para transformar os campos do payload em valores entre 0 e 1.
 *
 * Cada constante define o "teto" de uma dimensão:
 * - max_amount: valor máximo de transação (10000)
 * - max_installments: máximo de parcelas (12)
 * - amount_vs_avg_ratio: razão máxima amount/avg (10)
 * - max_minutes: janela de tempo em minutos (1440 = 24h)
 * - max_km: distância máxima em km (1000)
 * - max_tx_count_24h: máximo de transações em 24h (20)
 * - max_merchant_avg_amount: ticket médio máximo do comerciante (10000)
 */
public record NormConstants(
        double maxAmount,
        double maxInstallments,
        double amountVsAvgRatio,
        double maxMinutes,
        double maxKm,
        double maxTxCount24h,
        double maxMerchantAvgAmount
) {

    /** Constantes padrão conforme normalization.json */
    public static NormConstants defaults() {
        return new NormConstants(10000, 12, 10, 1440, 1000, 20, 10000);
    }
}
