# The Iceberg project publishes no connector zip: its docs say to build it from source.
FROM eclipse-temurin:21-jdk AS build
ARG ICEBERG_VERSION=1.12.0
RUN apt-get update && apt-get install -y --no-install-recommends curl unzip && rm -rf /var/lib/apt/lists/*
WORKDIR /src
RUN curl -fsSL "https://github.com/apache/iceberg/archive/refs/tags/apache-iceberg-${ICEBERG_VERSION}.tar.gz" | tar xz --strip-components=1 \
 && ./gradlew --no-daemon -x test -x integrationTest :iceberg-kafka-connect:iceberg-kafka-connect-runtime:distZip \
 && mkdir /out && unzip -q kafka-connect/kafka-connect-runtime/build/distributions/iceberg-kafka-connect-runtime-*.zip -d /out

FROM confluentinc/cp-kafka-connect:8.4.0
COPY --from=build /out /opt/connectors
