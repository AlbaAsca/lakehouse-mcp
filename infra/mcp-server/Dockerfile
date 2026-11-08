# Build context: the repository root, reduced by the root .dockerignore. McpServerImageIT sends the same paths.
FROM maven:3.10.0-eclipse-temurin-25-noble AS build
WORKDIR /src
# Maven reads every module of the parent pom, including the ones it does not build.
COPY pom.xml ./
COPY producer/pom.xml producer/
COPY mcp-server/pom.xml mcp-server/
COPY agent/pom.xml agent/
COPY semantic/pom.xml semantic/
COPY evals/pom.xml evals/
COPY testkit/pom.xml testkit/
COPY maintenance/pom.xml maintenance/
# Downloads in a layer of their own: changing the code does not fetch them again. With no code yet there is nothing to repackage.
RUN mvn -B -q -pl mcp-server -am -Dmaven.test.skip=true -Dspring-boot.repackage.skip=true package
# mcp-server only uses testkit in its tests, but Maven still needs it in the build.
COPY testkit/src/main testkit/src/main
COPY semantic/src/main semantic/src/main
COPY mcp-server/src/main mcp-server/src/main
RUN mvn -B -q -pl mcp-server -am -Dmaven.test.skip=true package
RUN mkdir /out && cp mcp-server/target/mcp-server-*.jar /out/mcp-server.jar
# Installed by the driver the server runs with, so the extensions always match its DuckDB version.
COPY infra/mcp-server/PreloadExtensions.java /tmp/preload/
WORKDIR /tmp/preload
RUN jar xf /out/mcp-server.jar BOOT-INF/lib \
 && java -cp 'BOOT-INF/lib/*' PreloadExtensions.java /opt/duckdb-extensions httpfs avro iceberg

FROM eclipse-temurin:25-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
RUN useradd --create-home mcp
COPY --from=build /out/mcp-server.jar /app/mcp-server.jar
COPY --from=build /opt/duckdb-extensions /opt/duckdb-extensions
# With this directory set, the server only loads from it and never downloads.
ENV DUCKDB_EXTENSION_DIRECTORY=/opt/duckdb-extensions
USER mcp
WORKDIR /app
EXPOSE 8090
# Here and not in compose.yaml so that McpServerImageIT waits on this very check.
HEALTHCHECK --interval=10s --timeout=5s --retries=18 CMD curl -fs http://127.0.0.1:8090/actuator/health >/dev/null
# 256 MB of the 1 GB budget (docs/architecture.md): DuckDB's memory is outside the heap.
ENTRYPOINT ["java", "-Xmx256m", "-jar", "/app/mcp-server.jar"]
