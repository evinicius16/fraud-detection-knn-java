package com.rinha;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KnnSearchTest {

    private Dataset makeTestDataset() {
        // 10 vetores: 5 legit + 5 fraud
        float[] vectors = {
                // 0-4: legit (valores baixos, padrão normal)
                0.01f, 0.0833f, 0.05f, 0.8261f, 0.1667f, -1f, -1f, 0.0432f, 0.25f, 0f, 1f, 0f, 0.2f, 0.0416f,
                0.02f, 0.0833f, 0.06f, 0.8261f, 0.1667f, -1f, -1f, 0.0450f, 0.25f, 0f, 1f, 0f, 0.2f, 0.0420f,
                0.015f, 0.0833f, 0.055f, 0.8261f, 0.3333f, -1f, -1f, 0.0440f, 0.20f, 0f, 1f, 0f, 0.15f, 0.0400f,
                0.03f, 0.1667f, 0.04f, 0.7826f, 0.3333f, -1f, -1f, 0.0292f, 0.15f, 0f, 1f, 0f, 0.15f, 0.0060f,
                0.025f, 0.0833f, 0.07f, 0.8261f, 0.1667f, -1f, -1f, 0.0500f, 0.30f, 0f, 1f, 0f, 0.2f, 0.0450f,
                // 5-9: fraud (valores altos, padrão anômalo)
                0.5796f, 0.9167f, 1.0f, 0.0435f, 0f, 0.0056f, 0.4394f, 0.4598f, 0.4f, 1f, 0f, 1f, 0.85f, 0.0032f,
                0.9506f, 0.8333f, 1.0f, 0.2174f, 0.8333f, -1f, -1f, 0.9523f, 1.0f, 0f, 1f, 1f, 0.75f, 0.0055f,
                0.8500f, 0.7500f, 0.95f, 0.1304f, 0.6667f, -1f, -1f, 0.8800f, 0.9f, 1f, 0f, 1f, 0.80f, 0.0040f,
                0.7200f, 0.9167f, 0.88f, 0.0870f, 0.5000f, 0.0100f, 0.5000f, 0.7500f, 0.85f, 1f, 0f, 1f, 0.85f, 0.0028f,
                0.9000f, 1.0f, 0.99f, 0.1739f, 0.8333f, -1f, -1f, 0.9100f, 0.95f, 0f, 1f, 1f, 0.75f, 0.0050f,
        };

        boolean[] labels = {false, false, false, false, false, true, true, true, true, true};

        return new Dataset(vectors, labels, 10);
    }

    @Test
    void shouldFindMostlyLegitForLegitQuery() {
        var ds = makeTestDataset();
        var knn = new KnnSearch(ds);

        // Query similar aos vetores legit
        float[] query = {0.02f, 0.0833f, 0.05f, 0.8261f, 0.1667f, -1f, -1f, 0.04f, 0.25f, 0f, 1f, 0f, 0.2f, 0.04f};

        int fraudCount = knn.findFraudCount(query);
        assertThat(fraudCount).isLessThanOrEqualTo(2); // Maioria legit
    }

    @Test
    void shouldFindMostlyFraudForFraudQuery() {
        var ds = makeTestDataset();
        var knn = new KnnSearch(ds);

        // Query similar aos vetores fraud
        float[] query = {0.9f, 0.9f, 1.0f, 0.2f, 0.8f, -1f, -1f, 0.9f, 0.95f, 0f, 1f, 1f, 0.75f, 0.005f};

        int fraudCount = knn.findFraudCount(query);
        assertThat(fraudCount).isGreaterThanOrEqualTo(3); // Maioria fraud
    }

    @Test
    void shouldFindExactMatch() {
        var ds = makeTestDataset();
        var knn = new KnnSearch(ds);

        // Cópia exata do vetor[6] (fraud)
        float[] query = {0.9506f, 0.8333f, 1.0f, 0.2174f, 0.8333f, -1f, -1f, 0.9523f, 1.0f, 0f, 1f, 1f, 0.75f, 0.0055f};

        int fraudCount = knn.findFraudCount(query);
        assertThat(fraudCount).isGreaterThanOrEqualTo(4); // Quase todos fraud
    }

    @Test
    void shouldComputeCorrectFraudScores() {
        // fraud_score = fraudCount / 5
        assertThat(fraudScore(0)).isEqualTo(0.0);
        assertThat(fraudScore(1)).isEqualTo(0.2);
        assertThat(fraudScore(2)).isEqualTo(0.4);
        assertThat(fraudScore(3)).isEqualTo(0.6);
        assertThat(fraudScore(4)).isEqualTo(0.8);
        assertThat(fraudScore(5)).isEqualTo(1.0);
    }

    @Test
    void shouldApplyThresholdCorrectly() {
        // approved = fraud_score < 0.6
        assertThat(isApproved(0)).isTrue();   // 0.0
        assertThat(isApproved(1)).isTrue();   // 0.2
        assertThat(isApproved(2)).isTrue();   // 0.4
        assertThat(isApproved(3)).isFalse();  // 0.6 → NOT approved
        assertThat(isApproved(4)).isFalse();  // 0.8
        assertThat(isApproved(5)).isFalse();  // 1.0
    }

    private double fraudScore(int fraudCount) {
        return Math.round((double) fraudCount / 5.0 * 100.0) / 100.0;
    }

    private boolean isApproved(int fraudCount) {
        return fraudScore(fraudCount) < 0.6;
    }
}
