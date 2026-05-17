# Fraud Detection KNN — Java

Solução em Java para a [Rinha de Backend 2026](https://github.com/zanfranceschi/rinha-de-backend-2026): detecção de fraude em transações de cartão usando busca vetorial KNN-5 sobre 3 milhões de vetores de referência.

## Algoritmo: IVF com Bounding-Box Pruning

Busca exata (mesmo resultado que brute-force float32) mas varrendo tipicamente <10% do dataset.

```
Query chega
  → Parser JSON manual (~5µs, zero reflection)
  → Vetorização (14 dims normalizadas)
  → Encontra cluster mais próximo (scan de 1024 centróides)
  → Varre o cluster (~3K vetores)
  → Para cada outro cluster:
    → Calcula lower-bound via bounding box
    → Se lower-bound > worst do top-5 → SKIP
    → Senão → varre com early exit
  → Retorna top-5 → fraud_score → approved/denied
```

### Otimizações

| Técnica | Impacto |
|---------|---------|
| IVF K=1024 com cluster splitting (max 6K) | Scan de ~3K vetores vs 3M |
| Bounding-box lower-bound pruning | Descarta 90%+ dos clusters |
| Dimensões reordenadas por variância | Early exit dispara em 2-3 dims |
| Quantização int16 (×10000) | Metade da memória (84 MB vs 168 MB) |
| Early exit a cada 2 dimensões | Maioria dos candidatos descartados cedo |
| Top-5 em 5 campos long (registradores) | Sem array, sem heap allocation |
| Parser JSON manual (cursor-based) | ~5µs vs ~50-100µs do Jackson |
| JDK HttpServer puro | Zero framework overhead |
| Warmup realista (3000 queries do dataset) | JIT C2 compila hot path antes do /ready |
| Respostas pré-computadas em byte[] | Zero StringBuilder por request |
| mmap compartilhado via volume Docker | 2 instâncias, 1 cópia dos dados |

### Zero dependências em runtime

Jackson é usado apenas no build time (DatasetBuilder lê o JSON.gz). Em runtime, o servidor usa:
- `com.sun.net.httpserver.HttpServer` — JDK puro
- Parser JSON manual — zero reflection
- Nenhum framework (sem Spring, sem Vert.x, sem Netty)

## Arquitetura

```
Nginx (porta 9999, round-robin)
  ├── API #1 (Java 21, porta 8080)
  └── API #2 (Java 21, porta 8080)
       └── mmap compartilhado (ivf_index.bin, ~87 MB)
```

## Recursos (dentro do limite da rinha)

| Serviço | CPU | Memória |
|---------|-----|---------|
| Nginx | 0.05 | 10 MB |
| API #1 | 0.475 | 170 MB |
| API #2 | 0.475 | 170 MB |
| **Total** | **1.0** | **350 MB** |

## Como rodar

```bash
# Subir com Docker (build inclui download do dataset + k-means + quantização)
docker compose up --build -d

# Esperar /ready
until curl -sf http://localhost:9999/ready > /dev/null; do sleep 1; done

# Teste rápido
curl -X POST http://localhost:9999/fraud-score \
  -H "Content-Type: application/json" \
  -d '{"id":"tx-1","transaction":{"amount":100,"installments":1,"requested_at":"2026-03-11T20:00:00Z"},"customer":{"avg_amount":500,"tx_count_24h":2,"known_merchants":["MERC-001"]},"merchant":{"id":"MERC-001","mcc":"5411","avg_amount":200},"terminal":{"is_online":false,"card_present":true,"km_from_home":5},"last_transaction":{"timestamp":"2026-03-11T18:00:00Z","km_from_current":10}}'

# Load test com k6 (requer k6 instalado)
k6 run test/loadtest.js
```

## Estrutura

```
├── src/main/java/com/rinha/
│   ├── Main.java               # HTTP server + warmup + startup
│   ├── JsonReader.java         # Parser JSON manual (cursor-based)
│   ├── FraudRequest.java       # Records imutáveis do payload
│   ├── Vectorizer.java         # Normalização → vetor 14D
│   ├── IvfIndex.java           # IVF + bbox pruning (busca KNN exata)
│   ├── DataLoader.java         # Carrega índice via mmap
│   ├── DatasetBuilder.java     # Build: JSON.gz → k-means → int16 → binário
│   ├── MccRisk.java            # Mapa de risco por MCC
│   └── NormConstants.java      # Constantes de normalização
├── data/                       # mcc_risk.json, normalization.json
├── pom.xml                     # Maven, Java 21, Jackson só no build
├── Dockerfile                  # Multi-stage: Maven → DatasetBuilder → JRE runtime
├── docker-compose.yml
└── nginx.conf
```

## Pré-requisitos

- Docker + Docker Compose
- k6 (para load test)
- Java 21+ e Maven 3.9+ (para desenvolvimento local)
