# Build context: the repository root, reduced by the root .dockerignore. AgentImageIT sends the same paths.
FROM maven:3.10.0-eclipse-temurin-25-noble AS build
WORKDIR /src
# Maven reads every module of the parent pom, including the ones it does not build.
COPY pom.xml ./
COPY producer/pom.xml producer/
COPY mcp-server/pom.xml mcp-server/
COPY agent/pom.xml agent/
COPY semantic/pom.xml semantic/
COPY evals/pom.xml evals/
COPY bench/pom.xml bench/
COPY testkit/pom.xml testkit/
COPY maintenance/pom.xml maintenance/
# Downloads in a layer of their own: changing the code does not fetch them again. With no code yet there is nothing to repackage.
RUN mvn -B -q -pl agent -am -Dmaven.test.skip=true -Dspring-boot.repackage.skip=true package
# agent only uses testkit in its tests, but Maven still needs it in the build.
COPY testkit/src/main testkit/src/main
COPY agent/src/main agent/src/main
RUN mvn -B -q -pl agent -am -Dmaven.test.skip=true package
RUN mkdir /out && cp agent/target/agent-*.jar /out/agent.jar

FROM eclipse-temurin:25-jre
RUN useradd --create-home agent
COPY --from=build /out/agent.jar /app/agent.jar
COPY infra/agent/ask.sh /app/ask.sh
USER agent
WORKDIR /app
ENV MCP_SERVER_URL=http://mcp-server:8090
# Checks the key before Java starts, then runs the jar with the arguments: `ask <question>`.
ENTRYPOINT ["sh", "/app/ask.sh"]
