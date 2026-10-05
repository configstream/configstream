# Builds the two sample apps from this repository, so the quick start needs nothing but Docker.
# Built by quickstart/compose.yml, with the repository root as the build context.

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY . .
# install, not package: demo-service runs configstream-processor as an annotation processor, which Maven resolves
# from the local repository
RUN mvn -B -ntp -q install -DskipTests -pl samples/demo-admin,samples/demo-service -am

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/samples/demo-admin/target/demo-admin.jar /src/samples/demo-service/target/demo-service.jar ./
