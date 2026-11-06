# Build context: the repository root, reduced by the root .dockerignore. ExpireSnapshotsIT sends the same paths.
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
RUN mvn -B -q -pl maintenance -am -Dmaven.test.skip=true -Dspring-boot.repackage.skip=true package
# maintenance only uses testkit in its tests, but Maven still needs it in the build.
COPY testkit/src/main testkit/src/main
COPY maintenance/src/main maintenance/src/main
RUN mvn -B -q -pl maintenance -am -Dmaven.test.skip=true package
RUN mkdir /out && cp maintenance/target/maintenance-*.jar /out/maintenance.jar

FROM eclipse-temurin:25-jre
RUN useradd --create-home maintenance
COPY --from=build /out/maintenance.jar /app/maintenance.jar
USER maintenance
WORKDIR /app
# One run on demand, outside the always-on budget. Not measured with a long history.
ENTRYPOINT ["java", "-Xmx512m", "-jar", "/app/maintenance.jar"]
