package com.rinha;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Transforma um FraudRequest em um vetor de 14 dimensões normalizado.
 * Zero alocações — reutiliza o array passado como parâmetro.
 */
public class Vectorizer {

    public static final int VECTOR_DIM = 14;

    private final NormConstants norm;
    private final MccRisk mccRisk;

    public Vectorizer(NormConstants norm, MccRisk mccRisk) {
        this.norm = norm;
        this.mccRisk = mccRisk;
    }

    /**
     * Preenche o array dst (tamanho 14) com o vetor normalizado da transação.
     */
    public void vectorize(FraudRequest req, float[] dst) {
        var tx = req.transaction();
        var cust = req.customer();
        var merch = req.merchant();
        var term = req.terminal();
        var lastTx = req.lastTransaction();

        // 0: amount
        dst[0] = clamp((float) (tx.amount() / norm.maxAmount()));

        // 1: installments
        dst[1] = clamp((float) (tx.installments() / norm.maxInstallments()));

        // 2: amount_vs_avg
        double avgAmount = cust.avgAmount();
        if (avgAmount > 0) {
            dst[2] = clamp((float) ((tx.amount() / avgAmount) / norm.amountVsAvgRatio()));
        } else {
            dst[2] = clamp((float) (tx.amount() / norm.amountVsAvgRatio()));
        }

        // Parse timestamp
        ZonedDateTime dateTime = Instant.parse(tx.requestedAt()).atZone(ZoneOffset.UTC);

        // 3: hour_of_day (0-23 UTC / 23)
        dst[3] = (float) dateTime.getHour() / 23.0f;

        // 4: day_of_week (seg=0, dom=6 / 6)
        int javaDow = dateTime.getDayOfWeek().getValue(); // 1=Monday, 7=Sunday
        int rinhaDow = (javaDow == 7) ? 6 : javaDow - 1;
        dst[4] = (float) rinhaDow / 6.0f;

        // 5: minutes_since_last_tx
        // 6: km_from_last_tx
        if (lastTx == null) {
            dst[5] = -1.0f;
            dst[6] = -1.0f;
        } else {
            Instant lastInstant = Instant.parse(lastTx.timestamp());
            Instant currentInstant = Instant.parse(tx.requestedAt());
            long minutes = ChronoUnit.MINUTES.between(lastInstant, currentInstant);
            dst[5] = clamp((float) (minutes / norm.maxMinutes()));
            dst[6] = clamp((float) (lastTx.kmFromCurrent() / norm.maxKm()));
        }

        // 7: km_from_home
        dst[7] = clamp((float) (term.kmFromHome() / norm.maxKm()));

        // 8: tx_count_24h
        dst[8] = clamp((float) (cust.txCount24h() / norm.maxTxCount24h()));

        // 9: is_online
        dst[9] = term.isOnline() ? 1.0f : 0.0f;

        // 10: card_present
        dst[10] = term.cardPresent() ? 1.0f : 0.0f;

        // 11: unknown_merchant
        boolean known = false;
        for (String m : cust.knownMerchants()) {
            if (m.equals(merch.id())) {
                known = true;
                break;
            }
        }
        dst[11] = known ? 0.0f : 1.0f;

        // 12: mcc_risk
        dst[12] = (float) mccRisk.getRisk(merch.mcc());

        // 13: merchant_avg_amount
        dst[13] = clamp((float) (merch.avgAmount() / norm.maxMerchantAvgAmount()));
    }

    private static float clamp(float x) {
        if (x < 0.0f) return 0.0f;
        if (x > 1.0f) return 1.0f;
        return x;
    }
}
