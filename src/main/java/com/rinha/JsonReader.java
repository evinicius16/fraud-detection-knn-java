package com.rinha;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parser JSON manual (cursor-based) especializado para o payload da rinha.
 * Zero reflection, zero alocação além do FraudRequest.
 * ~5µs por request vs ~50-100µs do Jackson.
 *
 * Trade-off: se o schema mudar, quebra. Mas o contrato da rinha é fixo.
 */
public class JsonReader {

    private final char[] buf;
    private int pos;

    public JsonReader(byte[] data, int offset, int length) {
        // Converte bytes para chars (ASCII payload)
        this.buf = new char[length];
        for (int i = 0; i < length; i++) {
            this.buf[i] = (char) (data[offset + i] & 0xFF);
        }
        this.pos = 0;
    }

    public FraudRequest parse() {
        String id = null;
        FraudRequest.Transaction transaction = null;
        FraudRequest.Customer customer = null;
        FraudRequest.Merchant merchant = null;
        FraudRequest.Terminal terminal = null;
        FraudRequest.LastTransaction lastTransaction = null;

        skipWhitespace();
        expect('{');

        while (true) {
            skipWhitespace();
            if (peek() == '}') { pos++; break; }
            if (peek() == ',') pos++;
            skipWhitespace();

            String key = readString();
            skipWhitespace();
            expect(':');
            skipWhitespace();

            switch (key) {
                case "id" -> id = readString();
                case "transaction" -> transaction = parseTransaction();
                case "customer" -> customer = parseCustomer();
                case "merchant" -> merchant = parseMerchant();
                case "terminal" -> terminal = parseTerminal();
                case "last_transaction" -> lastTransaction = parseLastTransaction();
                default -> skipValue();
            }
        }

        return new FraudRequest(id, transaction, customer, merchant, terminal, lastTransaction);
    }

    private FraudRequest.Transaction parseTransaction() {
        double amount = 0;
        int installments = 0;
        String requestedAt = null;

        expect('{');
        while (true) {
            skipWhitespace();
            if (peek() == '}') { pos++; break; }
            if (peek() == ',') pos++;
            skipWhitespace();

            String key = readString();
            skipWhitespace();
            expect(':');
            skipWhitespace();

            switch (key) {
                case "amount" -> amount = readDouble();
                case "installments" -> installments = readInt();
                case "requested_at" -> requestedAt = readString();
                default -> skipValue();
            }
        }
        return new FraudRequest.Transaction(amount, installments, requestedAt);
    }

    private FraudRequest.Customer parseCustomer() {
        double avgAmount = 0;
        int txCount24h = 0;
        List<String> knownMerchants = Collections.emptyList();

        expect('{');
        while (true) {
            skipWhitespace();
            if (peek() == '}') { pos++; break; }
            if (peek() == ',') pos++;
            skipWhitespace();

            String key = readString();
            skipWhitespace();
            expect(':');
            skipWhitespace();

            switch (key) {
                case "avg_amount" -> avgAmount = readDouble();
                case "tx_count_24h" -> txCount24h = readInt();
                case "known_merchants" -> knownMerchants = readStringArray();
                default -> skipValue();
            }
        }
        return new FraudRequest.Customer(avgAmount, txCount24h, knownMerchants);
    }

    private FraudRequest.Merchant parseMerchant() {
        String id = null;
        String mcc = null;
        double avgAmount = 0;

        expect('{');
        while (true) {
            skipWhitespace();
            if (peek() == '}') { pos++; break; }
            if (peek() == ',') pos++;
            skipWhitespace();

            String key = readString();
            skipWhitespace();
            expect(':');
            skipWhitespace();

            switch (key) {
                case "id" -> id = readString();
                case "mcc" -> mcc = readString();
                case "avg_amount" -> avgAmount = readDouble();
                default -> skipValue();
            }
        }
        return new FraudRequest.Merchant(id, mcc, avgAmount);
    }

    private FraudRequest.Terminal parseTerminal() {
        boolean isOnline = false;
        boolean cardPresent = false;
        double kmFromHome = 0;

        expect('{');
        while (true) {
            skipWhitespace();
            if (peek() == '}') { pos++; break; }
            if (peek() == ',') pos++;
            skipWhitespace();

            String key = readString();
            skipWhitespace();
            expect(':');
            skipWhitespace();

            switch (key) {
                case "is_online" -> isOnline = readBoolean();
                case "card_present" -> cardPresent = readBoolean();
                case "km_from_home" -> kmFromHome = readDouble();
                default -> skipValue();
            }
        }
        return new FraudRequest.Terminal(isOnline, cardPresent, kmFromHome);
    }

    private FraudRequest.LastTransaction parseLastTransaction() {
        if (peek() == 'n') {
            // null
            pos += 4;
            return null;
        }

        String timestamp = null;
        double kmFromCurrent = 0;

        expect('{');
        while (true) {
            skipWhitespace();
            if (peek() == '}') { pos++; break; }
            if (peek() == ',') pos++;
            skipWhitespace();

            String key = readString();
            skipWhitespace();
            expect(':');
            skipWhitespace();

            switch (key) {
                case "timestamp" -> timestamp = readString();
                case "km_from_current" -> kmFromCurrent = readDouble();
                default -> skipValue();
            }
        }
        return new FraudRequest.LastTransaction(timestamp, kmFromCurrent);
    }

    // ========== Primitivas de leitura ==========

    private String readString() {
        expect('"');
        int start = pos;
        while (buf[pos] != '"') pos++;
        String s = new String(buf, start, pos - start);
        pos++; // skip closing "
        return s;
    }

    private double readDouble() {
        int start = pos;
        while (pos < buf.length && isNumberChar(buf[pos])) pos++;
        return Double.parseDouble(new String(buf, start, pos - start));
    }

    private int readInt() {
        int start = pos;
        while (pos < buf.length && isNumberChar(buf[pos])) pos++;
        return Integer.parseInt(new String(buf, start, pos - start));
    }

    private boolean readBoolean() {
        if (buf[pos] == 't') {
            pos += 4; // true
            return true;
        } else {
            pos += 5; // false
            return false;
        }
    }

    private List<String> readStringArray() {
        expect('[');
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return Collections.emptyList();
        }

        List<String> list = new ArrayList<>(4);
        while (true) {
            skipWhitespace();
            list.add(readString());
            skipWhitespace();
            if (peek() == ']') { pos++; break; }
            expect(',');
        }
        return list;
    }

    private void skipValue() {
        char c = peek();
        if (c == '"') {
            readString();
        } else if (c == '{') {
            skipObject();
        } else if (c == '[') {
            skipArray();
        } else if (c == 'n') {
            pos += 4;
        } else if (c == 't') {
            pos += 4;
        } else if (c == 'f') {
            pos += 5;
        } else {
            // number
            while (pos < buf.length && isNumberChar(buf[pos])) pos++;
        }
    }

    private void skipObject() {
        pos++; // {
        int depth = 1;
        boolean inString = false;
        while (depth > 0) {
            char c = buf[pos++];
            if (inString) {
                if (c == '\\') pos++;
                else if (c == '"') inString = false;
            } else {
                if (c == '"') inString = true;
                else if (c == '{') depth++;
                else if (c == '}') depth--;
            }
        }
    }

    private void skipArray() {
        pos++; // [
        int depth = 1;
        boolean inString = false;
        while (depth > 0) {
            char c = buf[pos++];
            if (inString) {
                if (c == '\\') pos++;
                else if (c == '"') inString = false;
            } else {
                if (c == '"') inString = true;
                else if (c == '[') depth++;
                else if (c == ']') depth--;
            }
        }
    }

    private void skipWhitespace() {
        while (pos < buf.length && (buf[pos] == ' ' || buf[pos] == '\n' || buf[pos] == '\r' || buf[pos] == '\t')) {
            pos++;
        }
    }

    private char peek() {
        return buf[pos];
    }

    private void expect(char c) {
        if (buf[pos] != c) {
            throw new RuntimeException("Expected '" + c + "' at pos " + pos + " but got '" + buf[pos] + "'");
        }
        pos++;
    }

    private static boolean isNumberChar(char c) {
        return (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '+' || c == 'e' || c == 'E';
    }
}
