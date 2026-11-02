FROM maven:3.10.0-eclipse-temurin-25-noble
ARG DUCKDB_VERSION=v2.0.0
RUN apt-get update && apt-get install -y --no-install-recommends make curl jq unzip git python3 python3-venv docker.io docker-compose-v2 \
 && rm -rf /var/lib/apt/lists/*
RUN curl -fsSL "https://github.com/duckdb/duckdb/releases/download/${DUCKDB_VERSION}/duckdb-cli-linux-amd64.tar.gz" | tar xz -C /usr/local/bin
RUN python3 -m venv /opt/py && /opt/py/bin/pip install --quiet kaggle pytest fastapi httpx uvicorn \
 && ln -s /opt/py/bin/kaggle /usr/local/bin/kaggle
RUN git config --system --add safe.directory /workspace
