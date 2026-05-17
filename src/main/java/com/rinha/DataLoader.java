package com.rinha;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Carrega os arquivos de referência (índice IVF, mcc_risk, normalization).
 * Zero dependências externas — parser manual para JSONs pequenos, mmap para o índice.
 */
public class DataLoader {

    private static final int DIM = 14;

    /**
     * Carrega o índice IVF via mmap do arquivo binário pré-processado.
     *
     * Formato:
     *   - 4 bytes: numClusters (int32 LE)
     *   - 4 bytes: totalVectors (int32 LE)
     *   - 14 * 4 bytes: dimOrder (int32 LE)
     *   - numClusters * DIM * 4 bytes: centroids (float32 LE)
     *   - (numClusters + 1) * 4 bytes: clusterOffsets (int32 LE)
     *   - numClusters * DIM * 2 bytes: bboxMin (int16 LE)
     *   - numClusters * DIM * 2 bytes: bboxMax (int16 LE)
     *   - totalVectors * DIM * 2 bytes: vectors (int16 LE)
     *   - totalVectors bytes: labels
     */
    public static IvfIndex loadIvfIndex(String path) {
        try (FileChannel channel = FileChannel.open(Path.of(path), StandardOpenOption.READ)) {
            long fileSize = channel.size();
            MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, fileSize);
            buffer.order(ByteOrder.LITTLE_ENDIAN);

            // Header
            int numClusters = buffer.getInt();
            int totalVectors = buffer.getInt();

            System.out.println("[data] IVF index: " + numClusters + " clusters, " + totalVectors + " vectors");

            // Dimension order
            int[] dimOrder = new int[DIM];
            for (int d = 0; d < DIM; d++) {
                dimOrder[d] = buffer.getInt();
            }

            // Centroids
            float[] centroids = new float[numClusters * DIM];
            buffer.asFloatBuffer().get(centroids);
            buffer.position(buffer.position() + numClusters * DIM * 4);

            // Cluster offsets
            int[] clusterOffsets = new int[numClusters + 1];
            buffer.asIntBuffer().get(clusterOffsets);
            buffer.position(buffer.position() + (numClusters + 1) * 4);

            // BBox min
            short[] bboxMin = new short[numClusters * DIM];
            buffer.asShortBuffer().get(bboxMin);
            buffer.position(buffer.position() + numClusters * DIM * 2);

            // BBox max
            short[] bboxMax = new short[numClusters * DIM];
            buffer.asShortBuffer().get(bboxMax);
            buffer.position(buffer.position() + numClusters * DIM * 2);

            // Vectors (int16)
            short[] vectors = new short[totalVectors * DIM];
            buffer.asShortBuffer().get(vectors);
            buffer.position(buffer.position() + totalVectors * DIM * 2);

            // Labels
            boolean[] labels = new boolean[totalVectors];
            for (int i = 0; i < totalVectors; i++) {
                labels[i] = buffer.get() == 1;
            }

            System.out.println("[data] IVF loaded (" +
                    String.format("%.1f", fileSize / (1024.0 * 1024.0)) + " MB)");

            return new IvfIndex(numClusters, totalVectors, dimOrder, vectors, labels,
                    centroids, clusterOffsets, bboxMin, bboxMax);

        } catch (IOException e) {
            throw new RuntimeException("Failed to load IVF index: " + e.getMessage(), e);
        }
    }

    /**
     * Carrega o mapa de risco MCC. Parser manual (arquivo pequeno e fixo).
     */
    public static MccRisk loadMccRisk(String path) {
        try {
            String content = new String(new FileInputStream(path).readAllBytes());
            Map<String, Double> map = new HashMap<>();

            int i = content.indexOf('{') + 1;
            while (i < content.length()) {
                int qStart = content.indexOf('"', i);
                if (qStart < 0) break;
                int qEnd = content.indexOf('"', qStart + 1);
                String key = content.substring(qStart + 1, qEnd);

                int colon = content.indexOf(':', qEnd);
                int valStart = colon + 1;
                while (valStart < content.length() && content.charAt(valStart) == ' ') valStart++;

                int valEnd = valStart;
                while (valEnd < content.length() && (Character.isDigit(content.charAt(valEnd)) || content.charAt(valEnd) == '.')) {
                    valEnd++;
                }
                double value = Double.parseDouble(content.substring(valStart, valEnd));
                map.put(key, value);

                i = valEnd;
            }

            return new MccRisk(map);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load MCC risk: " + e.getMessage(), e);
        }
    }

    /**
     * Carrega as constantes de normalização. Parser manual.
     */
    public static NormConstants loadNormConstants(String path) {
        try {
            String content = new String(new FileInputStream(path).readAllBytes());

            double maxAmount = extractDouble(content, "max_amount");
            double maxInstallments = extractDouble(content, "max_installments");
            double amountVsAvgRatio = extractDouble(content, "amount_vs_avg_ratio");
            double maxMinutes = extractDouble(content, "max_minutes");
            double maxKm = extractDouble(content, "max_km");
            double maxTxCount24h = extractDouble(content, "max_tx_count_24h");
            double maxMerchantAvgAmount = extractDouble(content, "max_merchant_avg_amount");

            return new NormConstants(maxAmount, maxInstallments, amountVsAvgRatio,
                    maxMinutes, maxKm, maxTxCount24h, maxMerchantAvgAmount);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load normalization constants: " + e.getMessage(), e);
        }
    }

    private static double extractDouble(String json, String key) {
        int idx = json.indexOf("\"" + key + "\"");
        int colon = json.indexOf(':', idx);
        int start = colon + 1;
        while (start < json.length() && json.charAt(start) == ' ') start++;
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '.')) {
            end++;
        }
        return Double.parseDouble(json.substring(start, end));
    }
}
