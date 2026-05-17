package com.rinha;

import java.util.List;

/**
 * Representa o payload de uma requisição POST /fraud-score.
 * Records imutáveis, zero overhead.
 */
public record FraudRequest(
        String id,
        Transaction transaction,
        Customer customer,
        Merchant merchant,
        Terminal terminal,
        LastTransaction lastTransaction
) {
    public record Transaction(double amount, int installments, String requestedAt) {}
    public record Customer(double avgAmount, int txCount24h, List<String> knownMerchants) {}
    public record Merchant(String id, String mcc, double avgAmount) {}
    public record Terminal(boolean isOnline, boolean cardPresent, double kmFromHome) {}
    public record LastTransaction(String timestamp, double kmFromCurrent) {}
}
