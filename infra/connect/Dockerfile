# The Iceberg project publishes no connector zip: its docs say to build it from source.
# A git clone, not the tarball: the build reads version and properties from .git.
FROM eclipse-temurin:21-jdk AS build
ARG ICEBERG_VERSION=1.12.0
RUN apt-get update && apt-get install -y --no-install-recommends curl unzip git && rm -rf /var/lib/apt/lists/*
RUN git clone --depth 1 --branch "apache-iceberg-${ICEBERG_VERSION}" https://github.com/apache/iceberg.git /src
WORKDIR /src
# Without version.txt the build derives a version from the tag: 1.12.0 would announce itself as 1.13.0-SNAPSHOT.
RUN echo "${ICEBERG_VERSION}" > version.txt
RUN ./gradlew --no-daemon -x test -x integrationTest :iceberg-kafka-connect:iceberg-kafka-connect-runtime:distZip \
 && mkdir /out && unzip -q kafka-connect/kafka-connect-runtime/build/distributions/iceberg-kafka-connect-runtime-*.zip -d /out

# Not 8.4 like the other Confluent images: its Kafka moved AppInfoParser to common.utils.internals and the sink,
# which still calls the old class, dies with NoClassDefFoundError. Workers talk to newer brokers without trouble.
FROM confluentinc/cp-kafka-connect:8.1.0
COPY --from=build /out /opt/connectors
