package com.rinha;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class VectorizerTest {

    private final Vectorizer vectorizer = new Vectorizer(NormConstants.defaults(), MccRisk.defaults());

    @Test
    void shouldVectorizeLegitTransaction() {
        // Exemplo da documentação: transação legítima
        var req = new FraudRequest(
                "tx-1329056812",
                new FraudRequest.Transaction(41.12, 2, "2026-03-11T18:45:53Z"),
                new FraudRequest.Customer(82.24, 3, List.of("MERC-003", "MERC-016")),
                new FraudRequest.Merchant("MERC-016", "5411", 60.25),
                new FraudRequest.Terminal(false, true, 29.23),
                null
        );

        float[] dst = new float[14];
        vectorizer.vectorize(req, dst);

        // Verificar valores esperados (da documentação)
        assertThat((double) dst[0]).isCloseTo(0.0041, within(0.01));  // amount: 41.12/10000
        assertThat((double) dst[1]).isCloseTo(0.1667, within(0.01));  // installments: 2/12
        assertThat((double) dst[2]).isCloseTo(0.05, within(0.01));    // amount_vs_avg: (41.12/82.24)/10
        assertThat((double) dst[3]).isCloseTo(18.0 / 23.0, within(0.01)); // hour: 18/23
        assertThat((double) dst[4]).isCloseTo(2.0 / 6.0, within(0.01));   // Wednesday=2, 2/6
        assertThat(dst[5]).isEqualTo(-1.0f);  // null last_transaction
        assertThat(dst[6]).isEqualTo(-1.0f);  // null last_transaction
        assertThat((double) dst[7]).isCloseTo(0.0292, within(0.01));  // km_from_home: 29.23/1000
        assertThat((double) dst[8]).isCloseTo(0.15, within(0.01));    // tx_count_24h: 3/20
        assertThat(dst[9]).isEqualTo(0.0f);   // is_online: false
        assertThat(dst[10]).isEqualTo(1.0f);  // card_present: true
        assertThat(dst[11]).isEqualTo(0.0f);  // known merchant
        assertThat((double) dst[12]).isCloseTo(0.15, within(0.01));   // mcc_risk: 5411→0.15
        assertThat((double) dst[13]).isCloseTo(0.006, within(0.01));  // merchant_avg: 60.25/10000
    }

    @Test
    void shouldVectorizeFraudTransaction() {
        // Exemplo da documentação: transação fraudulenta
        var req = new FraudRequest(
                "tx-3330991687",
                new FraudRequest.Transaction(9505.97, 10, "2026-03-14T05:15:12Z"),
                new FraudRequest.Customer(81.28, 20, List.of("MERC-008", "MERC-007", "MERC-005")),
                new FraudRequest.Merchant("MERC-068", "7802", 54.86),
                new FraudRequest.Terminal(false, true, 952.27),
                null
        );

        float[] dst = new float[14];
        vectorizer.vectorize(req, dst);

        assertThat((double) dst[0]).isCloseTo(0.9506, within(0.01));  // amount
        assertThat((double) dst[1]).isCloseTo(0.8333, within(0.01));  // installments
        assertThat((double) dst[2]).isCloseTo(1.0, within(0.01));     // amount_vs_avg (clamped)
        assertThat((double) dst[3]).isCloseTo(5.0 / 23.0, within(0.01)); // hour: 5/23
        assertThat((double) dst[4]).isCloseTo(5.0 / 6.0, within(0.01));  // Saturday=5, 5/6
        assertThat(dst[5]).isEqualTo(-1.0f);
        assertThat(dst[6]).isEqualTo(-1.0f);
        assertThat((double) dst[7]).isCloseTo(0.9523, within(0.01));  // km_from_home
        assertThat((double) dst[8]).isCloseTo(1.0, within(0.01));     // tx_count_24h (clamped)
        assertThat(dst[9]).isEqualTo(0.0f);
        assertThat(dst[10]).isEqualTo(1.0f);
        assertThat(dst[11]).isEqualTo(1.0f);  // unknown merchant
        assertThat((double) dst[12]).isCloseTo(0.75, within(0.01));   // mcc_risk: 7802→0.75
        assertThat((double) dst[13]).isCloseTo(0.0055, within(0.01)); // merchant_avg
    }

    @Test
    void shouldHandleLastTransaction() {
        var req = new FraudRequest(
                "tx-100",
                new FraudRequest.Transaction(384.88, 3, "2026-03-11T20:23:35Z"),
                new FraudRequest.Customer(769.76, 3, List.of("MERC-009", "MERC-001")),
                new FraudRequest.Merchant("MERC-001", "5912", 298.95),
                new FraudRequest.Terminal(false, true, 13.709),
                new FraudRequest.LastTransaction("2026-03-11T14:58:35Z", 18.863)
        );

        float[] dst = new float[14];
        vectorizer.vectorize(req, dst);

        // minutes: 20:23:35 - 14:58:35 = 5h25m = 325 min → 325/1440 = 0.2257
        assertThat((double) dst[5]).isCloseTo(325.0 / 1440.0, within(0.01));
        // km: 18.863/1000 = 0.018863
        assertThat((double) dst[6]).isCloseTo(18.863 / 1000.0, within(0.01));
        // Should NOT be -1
        assertThat(dst[5]).isNotEqualTo(-1.0f);
        assertThat(dst[6]).isNotEqualTo(-1.0f);
    }

    @Test
    void shouldClampOverflowValues() {
        var req = new FraudRequest(
                "tx-300",
                new FraudRequest.Transaction(50000, 48, "2026-06-01T23:59:59Z"),
                new FraudRequest.Customer(100, 100, List.of("MERC-001")),
                new FraudRequest.Merchant("MERC-001", "5411", 99999),
                new FraudRequest.Terminal(false, true, 99999),
                null
        );

        float[] dst = new float[14];
        vectorizer.vectorize(req, dst);

        assertThat(dst[0]).isEqualTo(1.0f);  // amount clamped
        assertThat(dst[1]).isEqualTo(1.0f);  // installments clamped
        assertThat(dst[7]).isEqualTo(1.0f);  // km_from_home clamped
        assertThat(dst[8]).isEqualTo(1.0f);  // tx_count_24h clamped
        assertThat(dst[13]).isEqualTo(1.0f); // merchant_avg clamped
    }

    @Test
    void shouldUseDefaultMccRiskForUnknownMcc() {
        var req = new FraudRequest(
                "tx-200",
                new FraudRequest.Transaction(100, 1, "2026-01-01T12:00:00Z"),
                new FraudRequest.Customer(200, 1, Collections.emptyList()),
                new FraudRequest.Merchant("MERC-999", "9999", 150),  // MCC desconhecido
                new FraudRequest.Terminal(true, false, 0),
                null
        );

        float[] dst = new float[14];
        vectorizer.vectorize(req, dst);

        assertThat(dst[12]).isEqualTo(0.5f);  // default MCC risk
        assertThat(dst[9]).isEqualTo(1.0f);   // is_online = true
        assertThat(dst[10]).isEqualTo(0.0f);  // card_present = false
        assertThat(dst[11]).isEqualTo(1.0f);  // unknown merchant
    }
}
