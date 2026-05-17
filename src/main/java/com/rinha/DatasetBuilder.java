package com.rinha;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.GZIPInputStream;

/**
 * Pré-processa references.json.gz em formato binário IVF otimizado.
 *
 * Otimizações vs versão anterior:
 * - K=1024 clusters (vs 256) → clusters menores, bbox mais apertado, mais poda
 * - Cluster splitting: se um cluster > MAX_CLUSTER_SIZE, re-split com k-means local
 * - Reordenação de dimensões por variância (dims que discriminam mais vêm primeiro → early exit)
 * - 20 iterações de k-means (vs 15)
 *
 * Formato de saída:
 *   - 4 bytes: numClusters (int32 LE)
 *   - 4 bytes: totalVectors (int32 LE)
 *   - 14 * 4 bytes: dimOrder (int32 LE) — ordem das dimensões por variância decrescente
 *   - numClusters * DIM * 4 bytes: centroids (float32 LE, dims reordenadas)
 *   - (numClusters + 1) * 4 bytes: clusterOffsets (int32 LE)
 *   - numClusters * DIM * 2 bytes: bboxMin (int16 LE)
 *   - numClusters * DIM * 2 bytes: bboxMax (int16 LE)
 *   - totalVectors * DIM * 2 bytes: vectors (int16 LE, reordenados por cluster, dims reordenadas)
 *   - totalVectors bytes: labels (reordenados por cluster)
 *
 * Uso: java -Xmx2g -cp app.jar com.rinha.DatasetBuilder <input.json.gz> <output.bin>
 */
public class DatasetBuilder {

    private static final int DIM = 14;
    private static final int TARGET_CLUSTERS = 1024;
    private static final int MAX_CLUSTER_SIZE = 6000; // Se cluster > isso, re-split
    private static final int KMEANS_ITERATIONS = 20;
    private static final short SCALE = 10000;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: DatasetBuilder <input.json.gz> <output.bin>");
            System.exit(1);
        }

        String inputPath = args[0];
        String outputPath = args[1];

        System.out.println("[build] Reading " + inputPath + "...");

        // 1. Ler vetores do JSON
        int estimatedCount = 3_000_000;
        float[] rawVectors = new float[estimatedCount * DIM];
        boolean[] rawLabels = new boolean[estimatedCount];
        int count = 0;

        try (InputStream fis = new FileInputStream(inputPath);
             GZIPInputStream gis = new GZIPInputStream(fis, 65536);
             BufferedInputStream bis = new BufferedInputStream(gis, 131072)) {

            JsonFactory factory = new JsonFactory();
            JsonParser parser = factory.createParser(bis);
            parser.nextToken(); // START_ARRAY

            while (parser.nextToken() != JsonToken.END_ARRAY) {
                float[] vec = new float[DIM];
                String label = null;

                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    String field = parser.getCurrentName();
                    parser.nextToken();

                    if ("vector".equals(field)) {
                        for (int d = 0; d < DIM; d++) {
                            parser.nextToken();
                            vec[d] = parser.getFloatValue();
                        }
                        parser.nextToken(); // END_ARRAY
                    } else if ("label".equals(field)) {
                        label = parser.getText();
                    }
                }

                if (count >= rawVectors.length / DIM) {
                    int newCap = (int) (rawVectors.length * 1.5);
                    float[] nv = new float[newCap];
                    System.arraycopy(rawVectors, 0, nv, 0, count * DIM);
                    rawVectors = nv;
                    boolean[] nl = new boolean[(int) (rawLabels.length * 1.5)];
                    System.arraycopy(rawLabels, 0, nl, 0, count);
                    rawLabels = nl;
                }

                System.arraycopy(vec, 0, rawVectors, count * DIM, DIM);
                rawLabels[count] = "fraud".equals(label);
                count++;

                if (count % 500_000 == 0) {
                    System.out.println("[build] " + count + " entries read...");
                }
            }
        }

        System.out.println("[build] Total: " + count + " vectors");

        // 2. Calcular variância por dimensão e determinar ordem ótima
        System.out.println("[build] Computing dimension variance for reordering...");
        int[] dimOrder = computeDimOrder(rawVectors, count);
        System.out.println("[build] Dimension order (high variance first): " + Arrays.toString(dimOrder));

        // 3. Reordenar dimensões nos vetores
        System.out.println("[build] Reordering dimensions...");
        reorderDimensions(rawVectors, count, dimOrder);

        // 4. K-means
        System.out.println("[build] Running k-means (K=" + TARGET_CLUSTERS + ", iterations=" + KMEANS_ITERATIONS + ")...");
        float[] centroids = new float[TARGET_CLUSTERS * DIM];
        int[] assignments = new int[count];

        kmeansInit(rawVectors, count, centroids, TARGET_CLUSTERS);
        kmeansLloyd(rawVectors, count, centroids, assignments, TARGET_CLUSTERS, KMEANS_ITERATIONS);

        // 5. Cluster splitting: re-split clusters grandes
        System.out.println("[build] Checking for oversized clusters...");
        int[] clusterSizes = new int[TARGET_CLUSTERS];
        for (int i = 0; i < count; i++) {
            clusterSizes[assignments[i]]++;
        }

        // Contar clusters que precisam de split
        int extraClusters = 0;
        for (int c = 0; c < TARGET_CLUSTERS; c++) {
            if (clusterSizes[c] > MAX_CLUSTER_SIZE) {
                int splits = (clusterSizes[c] / MAX_CLUSTER_SIZE); // quantos sub-clusters extras
                extraClusters += splits;
            }
        }

        int finalNumClusters = TARGET_CLUSTERS + extraClusters;
        float[] finalCentroids;
        int[] finalAssignments;

        if (extraClusters > 0) {
            System.out.println("[build] Splitting " + extraClusters + " oversized clusters → " + finalNumClusters + " total");
            finalCentroids = new float[finalNumClusters * DIM];
            finalAssignments = new int[count];

            // Copiar centróides existentes
            System.arraycopy(centroids, 0, finalCentroids, 0, TARGET_CLUSTERS * DIM);

            int nextCluster = TARGET_CLUSTERS;

            for (int c = 0; c < TARGET_CLUSTERS; c++) {
                if (clusterSizes[c] <= MAX_CLUSTER_SIZE) {
                    // Manter assignments como estão
                    for (int i = 0; i < count; i++) {
                        if (assignments[i] == c) {
                            finalAssignments[i] = c;
                        }
                    }
                } else {
                    // Re-split este cluster
                    int numSplits = (clusterSizes[c] / MAX_CLUSTER_SIZE) + 1;
                    int[] clusterMembers = new int[clusterSizes[c]];
                    int memberIdx = 0;
                    for (int i = 0; i < count; i++) {
                        if (assignments[i] == c) {
                            clusterMembers[memberIdx++] = i;
                        }
                    }

                    // Mini k-means dentro deste cluster
                    int[] subAssignments = splitCluster(rawVectors, clusterMembers, memberIdx, numSplits);

                    // Atribuir: sub-cluster 0 fica no cluster c original, os outros vão pra novos IDs
                    for (int m = 0; m < memberIdx; m++) {
                        int vecIdx = clusterMembers[m];
                        if (subAssignments[m] == 0) {
                            finalAssignments[vecIdx] = c;
                        } else {
                            finalAssignments[vecIdx] = nextCluster + subAssignments[m] - 1;
                        }
                    }

                    // Recalcular centróides dos sub-clusters
                    recalcCentroid(rawVectors, clusterMembers, memberIdx, subAssignments, 0, finalCentroids, c);
                    for (int s = 1; s < numSplits; s++) {
                        recalcCentroid(rawVectors, clusterMembers, memberIdx, subAssignments, s, finalCentroids, nextCluster + s - 1);
                    }

                    nextCluster += numSplits - 1;
                }
            }

            // Preencher assignments que ficaram em clusters não-split
            for (int i = 0; i < count; i++) {
                if (assignments[i] < TARGET_CLUSTERS && clusterSizes[assignments[i]] <= MAX_CLUSTER_SIZE) {
                    finalAssignments[i] = assignments[i];
                }
            }

            finalNumClusters = nextCluster;
            // Trim centroids
            if (finalNumClusters < finalCentroids.length / DIM) {
                float[] trimmed = new float[finalNumClusters * DIM];
                System.arraycopy(finalCentroids, 0, trimmed, 0, finalNumClusters * DIM);
                finalCentroids = trimmed;
            }
        } else {
            finalNumClusters = TARGET_CLUSTERS;
            finalCentroids = centroids;
            finalAssignments = assignments;
        }

        System.out.println("[build] Final clusters: " + finalNumClusters);

        // 6. Reordenar por cluster e quantizar
        System.out.println("[build] Reordering by cluster and quantizing to int16...");

        int[] finalClusterSizes = new int[finalNumClusters];
        for (int i = 0; i < count; i++) {
            finalClusterSizes[finalAssignments[i]]++;
        }

        int[] clusterOffsets = new int[finalNumClusters + 1];
        for (int c = 1; c <= finalNumClusters; c++) {
            clusterOffsets[c] = clusterOffsets[c - 1] + finalClusterSizes[c - 1];
        }

        short[] orderedVectors = new short[count * DIM];
        boolean[] orderedLabels = new boolean[count];
        int[] writePos = new int[finalNumClusters];
        System.arraycopy(clusterOffsets, 0, writePos, 0, finalNumClusters);

        for (int i = 0; i < count; i++) {
            int c = finalAssignments[i];
            int destIdx = writePos[c]++;
            int srcOff = i * DIM;
            int dstOff = destIdx * DIM;

            for (int d = 0; d < DIM; d++) {
                orderedVectors[dstOff + d] = (short) (rawVectors[srcOff + d] * SCALE);
            }
            orderedLabels[destIdx] = rawLabels[i];
        }

        // 7. Calcular bounding boxes
        System.out.println("[build] Computing bounding boxes...");
        short[] bboxMin = new short[finalNumClusters * DIM];
        short[] bboxMax = new short[finalNumClusters * DIM];

        for (int c = 0; c < finalNumClusters; c++) {
            int off = c * DIM;
            for (int d = 0; d < DIM; d++) {
                bboxMin[off + d] = Short.MAX_VALUE;
                bboxMax[off + d] = Short.MIN_VALUE;
            }
        }

        for (int c = 0; c < finalNumClusters; c++) {
            int cStart = clusterOffsets[c];
            int cEnd = clusterOffsets[c + 1];
            int bOff = c * DIM;

            for (int idx = cStart; idx < cEnd; idx++) {
                int vOff = idx * DIM;
                for (int d = 0; d < DIM; d++) {
                    short val = orderedVectors[vOff + d];
                    if (val < bboxMin[bOff + d]) bboxMin[bOff + d] = val;
                    if (val > bboxMax[bOff + d]) bboxMax[bOff + d] = val;
                }
            }
        }

        // 8. Gravar arquivo binário
        System.out.println("[build] Writing " + outputPath + "...");
        try (FileOutputStream fos = new FileOutputStream(outputPath);
             BufferedOutputStream bos = new BufferedOutputStream(fos, 131072);
             DataOutputStream dos = new DataOutputStream(bos)) {

            ByteBuffer buf4 = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer buf2 = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN);

            // Header
            writeIntLE(dos, buf4, finalNumClusters);
            writeIntLE(dos, buf4, count);

            // Dimension order
            for (int d = 0; d < DIM; d++) {
                writeIntLE(dos, buf4, dimOrder[d]);
            }

            // Centroids
            for (int i = 0; i < finalNumClusters * DIM; i++) {
                buf4.clear();
                buf4.putFloat(0, finalCentroids[i]);
                dos.write(buf4.array());
            }

            // Cluster offsets
            for (int i = 0; i <= finalNumClusters; i++) {
                writeIntLE(dos, buf4, clusterOffsets[i]);
            }

            // BBox min
            for (int i = 0; i < finalNumClusters * DIM; i++) {
                buf2.clear();
                buf2.putShort(0, bboxMin[i]);
                dos.write(buf2.array());
            }

            // BBox max
            for (int i = 0; i < finalNumClusters * DIM; i++) {
                buf2.clear();
                buf2.putShort(0, bboxMax[i]);
                dos.write(buf2.array());
            }

            // Vectors (int16)
            for (int i = 0; i < count * DIM; i++) {
                buf2.clear();
                buf2.putShort(0, orderedVectors[i]);
                dos.write(buf2.array());
            }

            // Labels
            for (int i = 0; i < count; i++) {
                dos.writeByte(orderedLabels[i] ? 1 : 0);
            }
        }

        long fileSize = new File(outputPath).length();
        System.out.println("[build] Done! " + outputPath + " (" +
                String.format("%.1f", fileSize / (1024.0 * 1024.0)) + " MB)");
        System.out.println("[build] Clusters: " + finalNumClusters + ", Vectors: " + count);

        // Stats
        int maxSize = 0, minSize = Integer.MAX_VALUE;
        for (int c = 0; c < finalNumClusters; c++) {
            int size = clusterOffsets[c + 1] - clusterOffsets[c];
            if (size > maxSize) maxSize = size;
            if (size < minSize) minSize = size;
        }
        System.out.println("[build] Cluster sizes: min=" + minSize + ", max=" + maxSize +
                ", avg=" + (count / finalNumClusters));
    }

    /**
     * Calcula a ordem das dimensões por variância decrescente.
     * Dimensões com maior variância discriminam mais → early exit mais eficaz.
     */
    private static int[] computeDimOrder(float[] vectors, int count) {
        double[] means = new double[DIM];
        double[] variances = new double[DIM];

        // Calcular médias
        for (int i = 0; i < count; i++) {
            int off = i * DIM;
            for (int d = 0; d < DIM; d++) {
                means[d] += vectors[off + d];
            }
        }
        for (int d = 0; d < DIM; d++) {
            means[d] /= count;
        }

        // Calcular variâncias
        for (int i = 0; i < count; i++) {
            int off = i * DIM;
            for (int d = 0; d < DIM; d++) {
                double diff = vectors[off + d] - means[d];
                variances[d] += diff * diff;
            }
        }
        for (int d = 0; d < DIM; d++) {
            variances[d] /= count;
        }

        // Ordenar dimensões por variância decrescente
        Integer[] indices = new Integer[DIM];
        for (int d = 0; d < DIM; d++) indices[d] = d;
        Arrays.sort(indices, (a, b) -> Double.compare(variances[b], variances[a]));

        int[] order = new int[DIM];
        for (int d = 0; d < DIM; d++) order[d] = indices[d];
        return order;
    }

    /**
     * Reordena as dimensões de todos os vetores in-place segundo dimOrder.
     */
    private static void reorderDimensions(float[] vectors, int count, int[] dimOrder) {
        float[] temp = new float[DIM];
        for (int i = 0; i < count; i++) {
            int off = i * DIM;
            for (int d = 0; d < DIM; d++) {
                temp[d] = vectors[off + dimOrder[d]];
            }
            System.arraycopy(temp, 0, vectors, off, DIM);
        }
    }

    /**
     * k-means++ initialization.
     */
    private static void kmeansInit(float[] vectors, int count, float[] centroids, int k) {
        Random rng = new Random(42);

        int first = rng.nextInt(count);
        System.arraycopy(vectors, first * DIM, centroids, 0, DIM);

        float[] minDists = new float[count];
        Arrays.fill(minDists, Float.MAX_VALUE);

        for (int c = 1; c < k; c++) {
            int prevC = c - 1;
            int cOff = prevC * DIM;
            double totalDist = 0;

            for (int i = 0; i < count; i++) {
                int vOff = i * DIM;
                float dist = 0;
                for (int d = 0; d < DIM; d++) {
                    float diff = vectors[vOff + d] - centroids[cOff + d];
                    dist += diff * diff;
                }
                if (dist < minDists[i]) {
                    minDists[i] = dist;
                }
                totalDist += minDists[i];
            }

            double threshold = rng.nextDouble() * totalDist;
            double cumulative = 0;
            int chosen = count - 1;
            for (int i = 0; i < count; i++) {
                cumulative += minDists[i];
                if (cumulative >= threshold) {
                    chosen = i;
                    break;
                }
            }

            System.arraycopy(vectors, chosen * DIM, centroids, c * DIM, DIM);

            if (c % 100 == 0) {
                System.out.println("[build] k-means++ init: " + c + "/" + k);
            }
        }
    }

    /**
     * Lloyd's k-means iterations.
     */
    private static void kmeansLloyd(float[] vectors, int count, float[] centroids,
                                     int[] assignments, int k, int iterations) {
        for (int iter = 0; iter < iterations; iter++) {
            // Assign
            for (int i = 0; i < count; i++) {
                int bestC = 0;
                float bestDist = Float.MAX_VALUE;
                int vOff = i * DIM;
                for (int c = 0; c < k; c++) {
                    int cOff = c * DIM;
                    float dist = 0;
                    for (int d = 0; d < DIM; d++) {
                        float diff = vectors[vOff + d] - centroids[cOff + d];
                        dist += diff * diff;
                    }
                    if (dist < bestDist) {
                        bestDist = dist;
                        bestC = c;
                    }
                }
                assignments[i] = bestC;
            }

            // Update centroids
            float[] sums = new float[k * DIM];
            int[] counts = new int[k];
            for (int i = 0; i < count; i++) {
                int c = assignments[i];
                counts[c]++;
                int vOff = i * DIM;
                int cOff = c * DIM;
                for (int d = 0; d < DIM; d++) {
                    sums[cOff + d] += vectors[vOff + d];
                }
            }
            for (int c = 0; c < k; c++) {
                if (counts[c] > 0) {
                    int cOff = c * DIM;
                    for (int d = 0; d < DIM; d++) {
                        centroids[cOff + d] = sums[cOff + d] / counts[c];
                    }
                }
            }

            if ((iter + 1) % 5 == 0) {
                System.out.println("[build] k-means iteration " + (iter + 1) + "/" + iterations);
            }
        }
    }

    /**
     * Split um cluster grande em numSplits sub-clusters via mini k-means.
     */
    private static int[] splitCluster(float[] allVectors, int[] memberIndices, int memberCount, int numSplits) {
        Random rng = new Random(123);

        // Extrair vetores do cluster
        float[] clusterVecs = new float[memberCount * DIM];
        for (int m = 0; m < memberCount; m++) {
            System.arraycopy(allVectors, memberIndices[m] * DIM, clusterVecs, m * DIM, DIM);
        }

        // Mini k-means
        float[] subCentroids = new float[numSplits * DIM];
        int[] subAssignments = new int[memberCount];

        // Init: random
        for (int s = 0; s < numSplits; s++) {
            int idx = rng.nextInt(memberCount);
            System.arraycopy(clusterVecs, idx * DIM, subCentroids, s * DIM, DIM);
        }

        // 10 iterações
        for (int iter = 0; iter < 10; iter++) {
            for (int m = 0; m < memberCount; m++) {
                int bestS = 0;
                float bestDist = Float.MAX_VALUE;
                int vOff = m * DIM;
                for (int s = 0; s < numSplits; s++) {
                    int cOff = s * DIM;
                    float dist = 0;
                    for (int d = 0; d < DIM; d++) {
                        float diff = clusterVecs[vOff + d] - subCentroids[cOff + d];
                        dist += diff * diff;
                    }
                    if (dist < bestDist) {
                        bestDist = dist;
                        bestS = s;
                    }
                }
                subAssignments[m] = bestS;
            }

            float[] sums = new float[numSplits * DIM];
            int[] counts = new int[numSplits];
            for (int m = 0; m < memberCount; m++) {
                int s = subAssignments[m];
                counts[s]++;
                int vOff = m * DIM;
                int cOff = s * DIM;
                for (int d = 0; d < DIM; d++) {
                    sums[cOff + d] += clusterVecs[vOff + d];
                }
            }
            for (int s = 0; s < numSplits; s++) {
                if (counts[s] > 0) {
                    int cOff = s * DIM;
                    for (int d = 0; d < DIM; d++) {
                        subCentroids[cOff + d] = sums[cOff + d] / counts[s];
                    }
                }
            }
        }

        return subAssignments;
    }

    /**
     * Recalcula o centróide de um sub-cluster.
     */
    private static void recalcCentroid(float[] allVectors, int[] memberIndices, int memberCount,
                                        int[] subAssignments, int targetSub,
                                        float[] centroids, int centroidIdx) {
        int cOff = centroidIdx * DIM;
        int cnt = 0;
        Arrays.fill(centroids, cOff, cOff + DIM, 0f);

        for (int m = 0; m < memberCount; m++) {
            if (subAssignments[m] == targetSub) {
                int vOff = memberIndices[m] * DIM;
                for (int d = 0; d < DIM; d++) {
                    centroids[cOff + d] += allVectors[vOff + d];
                }
                cnt++;
            }
        }
        if (cnt > 0) {
            for (int d = 0; d < DIM; d++) {
                centroids[cOff + d] /= cnt;
            }
        }
    }

    private static void writeIntLE(DataOutputStream dos, ByteBuffer buf, int value) throws IOException {
        buf.clear();
        buf.putInt(0, value);
        dos.write(buf.array());
    }
}
