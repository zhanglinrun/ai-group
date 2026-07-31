# P110 source pin. Do not replace with a moving branch, tag, or latest image.
ARG SAA_ADMIN_SOURCE_REF=c8a9c3ed40e0d9d626341921d5d7a76080cf699c

FROM maven:3.9.11-eclipse-temurin-21 AS source
ARG SAA_ADMIN_SOURCE_REF
RUN apt-get update \
    && apt-get install --no-install-recommends -y git \
    && rm -rf /var/lib/apt/lists/* \
    && git clone --no-checkout https://github.com/spring-ai-alibaba/spring-ai-alibaba-admin.git /opt/saa-admin \
    && cd /opt/saa-admin \
    && git checkout --detach "${SAA_ADMIN_SOURCE_REF}" \
    && test "$(git rev-parse HEAD)" = "${SAA_ADMIN_SOURCE_REF}"

FROM source AS build
WORKDIR /opt/saa-admin
RUN mvn -pl spring-ai-alibaba-admin-server-start -am -DskipTests package

# This target contains the upstream schema copied from the exact source revision.
FROM mysql:8.0.35 AS schema
COPY --from=source /opt/saa-admin/docker/middleware/init/mysql/admin-schema.sql /schema/admin-schema.sql

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
COPY --from=build /opt/saa-admin/spring-ai-alibaba-admin-server-start/target/spring-ai-alibaba-admin-server-start.jar /app/saa-admin.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/saa-admin.jar"]
