package com.rinha;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes end-to-end: pipeline completo (vetoriza → busca → score → decisão).
 */
class IntegrationTest {

    private static Dataset dataset;
    private static Vectorizer vectorizer;
    private static KnnSearch knnSearch;

    @BeforeAll
    static void setup() {
        // Carregar dataset de teste
        dataset = DataLoader.loadReferencesJson("src/test/resources/references_small.json");
        vectorizer = new Vectorizer(NormConstants.defaults(), MccRisk.defaults());
        knnSearch = new KnnSearch(dataset);
    }

    @Test
    void shouldApproveLegitTransaction() {
        var req = new FraudRequest(
                "tx-1329056812",
                new FraudRequest.Transaction(41.12, 2, "2026-03-11T18:45:53Z"),
                new FraudRequest.Customer(82.24, 3, List.of("MERC-003", "MERC-016")),
                new FraudRequest.Merchant("MERC-016", "5411", 60.25),
                new FraudRequest.Terminal(false, true, 29.23),
                null
        );

        float[] query = new float[14];
        vectorizer.vectorize(req, query);
        int fraudCount = knnSearch.findFraudCount(query);
        double score = Math.round((double) fraudCount / 5.0 * 100.0) / 100.0;
        boolean approved = score < 0.6;

        assertThat(approved).isTrue();
        System.out.println("Legit: fraud_score=" + score + ", approved=" + approved);
    }

    @Test
    void shouldDenyFraudTransaction() {
        var req = new FraudRequest(
                "tx-3330991687",
                new FraudRequest.Transaction(9505.97, 10, "2026-03-14T05:15:12Z"),
                new FraudRequest.Customer(81.28, 20, List.of("MERC-008", "MERC-007", "MERC-005")),
                new FraudRequest.Merchant("MERC-068", "7802", 54.86),
                new FraudRequest.Terminal(false, true, 952.27),
                null
        );

        float[] query = new float[14];
        vectorizer.vectorize(req, query);
        int fraudCount = knnSearch.findFraudCount(query);
        double score = Math.round((double) fraudCount / 5.0 * 100.0) / 100.0;
        boolean approved = score < 0.6;

        assertThat(approved).isFalse();
        System.out.println("Fraud: fraud_score=" + score + ", approved=" + approved);
    }

    @Test
    void shouldHandleTransactionWithLastTx() {
        var req = new FraudRequest(
                "tx-100",
                new FraudRequest.Transaction(384.88, 3, "2026-03-11T20:23:35Z"),
                new FraudRequest.Customer(769.76, 3, List.of("MERC-009", "MERC-001")),
                new FraudRequest.Merchant("MERC-001", "5912", 298.95),
                new FraudRequest.Terminal(false, true, 13.709),
                new FraudRequest.LastTransaction("2026-03-11T14:58:35Z", 18.863)
        );

        float[] query = new float[14];
        vectorizer.vectorize(req, query);

        // Indices 5 e 6 NÃO devem ser -1
        assertThat(query[5]).isNotEqualTo(-1.0f);
        assertThat(query[6]).isNotEqualTo(-1.0f);

        int fraudCount = knnSearch.findFraudCount(query);
        double score = Math.round((double) fraudCount / 5.0 * 100.0) / 100.0;
        System.out.println("With last_tx: fraud_score=" + score + ", vector[5]=" + query[5] + ", vector[6]=" + query[6]);
    }

    @Test
    void shouldHandleUnknownMcc() {
        var req = new FraudRequest(
                "tx-200",
                new FraudRequest.Transaction(100, 1, "2026-01-01T12:00:00Z"),
                new FraudRequest.Customer(200, 1, Collections.emptyList()),
                new FraudRequest.Merchant("MERC-999", "9999", 150),
                new FraudRequest.Terminal(true, false, 0),
                null
        );

        float[] query = new float[14];
        vectorizer.vectorize(req, query);

        assertThat(query[12]).isEqualTo(0.5f); // default MCC risk
    }

    @Test
    void allDimensionsShouldBeValid() {
        var req = new FraudRequest(
                "tx-dim",
                new FraudRequest.Transaction(500, 6, "2026-06-15T14:30:00Z"),
                new FraudRequest.Customer(1000, 5, List.of("MERC-001")),
                new FraudRequest.Merchant("MERC-002", "5812", 300),
                new FraudRequest.Terminal(true, false, 50),
                new FraudRequest.LastTransaction("2026-06-15T12:00:00Z", 25)
        );

        float[] query = new float[14];
        vectorizer.vectorize(req, query);

        // Nenhum NaN ou Inf
        for (int i = 0; i < 14; i++) {
            assertThat(Float.isNaN(query[i])).isFalse();
            assertThat(Float.isInfinite(query[i])).isFalse();
        }

        // Dims 0-4, 7-13 devem estar em [0, 1]
        for (int i : new int[]{0, 1, 2, 3, 4, 7, 8, 9, 10, 11, 12, 13}) {
            assertThat(query[i]).isBetween(0.0f, 1.0f);
        }

        // Dims 5 e 6 devem estar em [0, 1] quando last_transaction presente
        assertThat(query[5]).isBetween(0.0f, 1.0f);
        assertThat(query[6]).isBetween(0.0f, 1.0f);
    }
}
