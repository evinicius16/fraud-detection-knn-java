package com.rinha;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ponto de entrada — JDK HttpServer puro, zero frameworks.
 *
 * Otimizações:
 * - Respostas pré-computadas em byte[] (6 possíveis)
 * - ThreadLocal para vetor de query (zero alocação por request)
 * - Warmup realista com vetores do dataset (força JIT C2)
 * - 2 workers (match do CPU budget: 0.475 CPU por container)
 * - Fallback approved:true em caso de erro (melhor que HTTP 500)
 */
public class Main {

    private static final byte[][] RESPONSES = new byte[6][];
    static {
        for (int i = 0; i <= 5; i++) {
            double score = Math.round((double) i / 5.0 * 100.0) / 100.0;
            boolean approved = score < 0.6;
            String json = "{\"approved\":" + approved + ",\"fraud_score\":" + formatScore(score) + "}";
            RESPONSES[i] = json.getBytes();
        }
    }

    private static final byte[] OK_BYTES = "OK".getBytes();
    private static final byte[] NOT_FOUND_BYTES = "Not Found".getBytes();
    private static final String CT_JSON = "application/json";
    private static final String CT_TEXT = "text/plain";

    public static void main(String[] args) {
        long start = System.currentTimeMillis();

        // Configurações
        String ivfPath = env("IVF_INDEX_PATH", "/app/data/ivf_index.bin");
        String mccPath = env("MCC_RISK_PATH", "/app/data/mcc_risk.json");
        String normPath = env("NORMALIZATION_PATH", "/app/data/normalization.json");
        int port = Integer.parseInt(env("PORT", "8080"));
        int workers = Integer.parseInt(env("WORKERS", "2"));

        // Carregar dados
        System.out.println("[startup] Loading normalization constants...");
        NormConstants norm = DataLoader.loadNormConstants(normPath);

        System.out.println("[startup] Loading MCC risk...");
        MccRisk mccRisk = DataLoader.loadMccRisk(mccPath);

        System.out.println("[startup] Loading IVF index from " + ivfPath + "...");
        IvfIndex index = DataLoader.loadIvfIndex(ivfPath);

        Vectorizer vectorizer = new Vectorizer(norm, mccRisk);

        long loadTime = System.currentTimeMillis() - start;
        System.out.println("[startup] Data loaded in " + loadTime + "ms (" +
                index.totalVectors() + " vectors, " + index.numClusters() + " clusters)");

        // Warmup realista
        System.out.println("[startup] Warming up JIT (3000 queries from dataset)...");
        long warmupStart = System.currentTimeMillis();
        warmup(index);
        System.out.println("[startup] Warmup done in " + (System.currentTimeMillis() - warmupStart) + "ms");

        // ThreadLocal para vetor de query
        ThreadLocal<float[]> queryVectorLocal = ThreadLocal.withInitial(() -> new float[Vectorizer.VECTOR_DIM]);

        // Iniciar servidor
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 256);

            ExecutorService executor = Executors.newFixedThreadPool(workers);
            server.setExecutor(executor);

            server.createContext("/ready", exchange -> {
                if ("GET".equals(exchange.getRequestMethod())) {
                    exchange.getResponseHeaders().set("Content-Type", CT_TEXT);
                    exchange.sendResponseHeaders(200, OK_BYTES.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(OK_BYTES);
                    }
                } else {
                    exchange.sendResponseHeaders(405, -1);
                    exchange.close();
                }
            });

            server.createContext("/fraud-score", exchange -> {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    exchange.close();
                    return;
                }

                try {
                    byte[] body = exchange.getRequestBody().readAllBytes();

                    // Parse JSON manual
                    JsonReader reader = new JsonReader(body, 0, body.length);
                    FraudRequest req = reader.parse();

                    // Vetorizar (ordem original das 14 dims)
                    float[] query = queryVectorLocal.get();
                    vectorizer.vectorize(req, query);

                    // Buscar KNN via IVF (reordena dims internamente)
                    int fraudCount = index.findFraudCount(query);

                    // Resposta pré-computada
                    sendJson(exchange, 200, RESPONSES[fraudCount]);

                } catch (Exception e) {
                    // Fallback: approved=true, fraud_score=0.0
                    sendJson(exchange, 200, RESPONSES[0]);
                }
            });

            server.start();
            System.out.println("[startup] Listening on :" + port +
                    " (workers=" + workers + ", total startup: " + (System.currentTimeMillis() - start) + "ms)");

        } catch (IOException e) {
            System.err.println("Failed to start server: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Warmup realista: amostra vetores do dataset com ruído leve.
     * Exercita o hot path completo (centroid scan + cluster scan + bbox pruning + early exit).
     * 3000 queries garante que o JIT C2 compila tudo antes do /ready.
     */
    private static void warmup(IvfIndex index) {
        Random rng = new Random(42);
        float[] query = new float[Vectorizer.VECTOR_DIM];
        short[] vectors = index.vectors();
        int totalVectors = index.totalVectors();
        int[] dimOrder = index.dimOrder();

        for (int w = 0; w < 3000; w++) {
            // Pegar vetor aleatório do dataset, converter de volta para float na ordem original
            int vecIdx = rng.nextInt(totalVectors);
            int off = vecIdx * Vectorizer.VECTOR_DIM;

            // Os vetores no index estão na ordem reordenada (dimOrder).
            // O findFraudCount espera query na ordem ORIGINAL e reordena internamente.
            // Então precisamos "des-reordenar" para simular uma query real.
            for (int d = 0; d < Vectorizer.VECTOR_DIM; d++) {
                // vectors[off + d] está na posição d da ordem reordenada
                // dimOrder[d] = dimensão original que está na posição d
                // Queremos query[dimOrder[d]] = vectors[off + d] / SCALE + noise
                query[dimOrder[d]] = vectors[off + d] / 10000.0f + (rng.nextFloat() - 0.5f) * 0.005f;
            }

            index.findFraudCount(query);
        }
    }

    private static void sendJson(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", CT_JSON);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private static String formatScore(double score) {
        if (score == 0.0) return "0.0";
        if (score == 1.0) return "1.0";
        return String.valueOf(score);
    }

    private static String env(String key, String fallback) {
        String val = System.getenv(key);
        return (val != null && !val.isEmpty()) ? val : fallback;
    }
}
