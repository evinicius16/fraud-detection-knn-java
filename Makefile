.PHONY: test build run docker-up docker-down smoke-test load-test clean download-data

# Download the official reference dataset
download-data:
	@echo "Downloading references.json.gz (~16 MB)..."
	curl -L -o data/references.json.gz \
		https://github.com/zanfranceschi/rinha-de-backend-2026/raw/main/resources/references.json.gz
	@echo "Done!"

# Run tests
test:
	mvn test

# Build fat JAR
build:
	mvn package -DskipTests

# Run locally (JVM mode, for development)
run: build
	java -jar target/rinha-2026-1.0.0.jar

# Docker compose up
docker-up:
	docker compose up --build -d
	@echo "Waiting for API to be ready..."
	@for i in $$(seq 1 120); do \
		if curl -s http://localhost:9999/ready > /dev/null 2>&1; then \
			echo "API is ready!"; \
			break; \
		fi; \
		sleep 1; \
	done

# Docker compose down
docker-down:
	docker compose down

# Run smoke tests against running instance
smoke-test:
	../rinha-2026/test/smoke_test.sh http://localhost:9999

# Run k6 load test
load-test:
	k6 run ../rinha-2026/test/loadtest.js

# Clean build artifacts
clean:
	mvn clean
	docker compose down --rmi local --volumes 2>/dev/null || true
