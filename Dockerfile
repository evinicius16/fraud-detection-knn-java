# ============================================================
# Stage 1: Build fat JAR with Maven
# ============================================================
FROM maven:3.9-eclipse-temurin-21 AS maven-build

WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn package -DskipTests -B

# ============================================================
# Stage 2: Build IVF index (JSON.gz → binário otimizado)
# Roda k-means + quantização + reordenação por cluster
# ============================================================
FROM eclipse-temurin:21-jre AS dataset-builder

WORKDIR /app

COPY --from=maven-build /build/target/fraud-detection-knn-1.0.0.jar /app/app.jar

# Download do dataset (3M vetores, ~16MB comprimido)
ADD https://github.com/zanfranceschi/rinha-de-backend-2026/raw/main/resources/references.json.gz /app/data/references.json.gz

# Rodar DatasetBuilder: k-means + quantização + bbox
RUN java -Xmx2g -cp /app/app.jar com.rinha.DatasetBuilder \
    /app/data/references.json.gz /app/data/ivf_index.bin

# ============================================================
# Stage 3: Runtime mínimo (JRE 21 slim)
# ============================================================
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Copiar JAR (sem Jackson em runtime, mas precisa do .jar para o Main)
COPY --from=maven-build /build/target/fraud-detection-knn-1.0.0.jar /app/app.jar

# Copiar índice IVF pré-processado
COPY --from=dataset-builder /app/data/ivf_index.bin /app/data/ivf_index.bin

# Copiar configs
COPY data/mcc_risk.json /app/data/mcc_risk.json
COPY data/normalization.json /app/data/normalization.json

EXPOSE 8080

# JVM flags otimizadas para baixa latência e pouca memória
CMD ["java", \
     "-Xms128m", "-Xmx150m", \
     "-XX:+UseSerialGC", \
     "-XX:+TieredCompilation", \
     "-XX:TieredStopAtLevel=4", \
     "-jar", "/app/app.jar"]
