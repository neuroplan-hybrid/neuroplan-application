FROM docker.io/library/maven:3.9.16-eclipse-temurin-21-alpine AS build
WORKDIR /workspace
COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline
COPY src ./src
RUN mvn -B -ntp clean package -DskipTests

FROM docker.io/library/eclipse-temurin:21-jre-alpine-3.24
WORKDIR /app
LABEL org.opencontainers.image.title="NeuroPlan Backend"
LABEL org.opencontainers.image.description="NeuroPlan Backend API"
COPY --from=build /workspace/target/neuroplan-auth-api-0.8.0.jar /app/app.jar
# Kubernetes에서는 1001, OpenShift/ROSA에서는 프로젝트에 할당된 임의 UID로 실행됩니다.
# 런타임 쓰기 경로는 /tmp(emptyDir)뿐이며 이미지 루트 파일시스템은 읽기 전용입니다.
RUN chgrp -R 0 /app \
    && chmod -R g=u /app
ENV HOME=/tmp
USER 1001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-Djava.io.tmpdir=/tmp", "-jar", "/app/app.jar"]

# CI verification: backend-only deployment

# CI verification: simultaneous deployment
# SCM polling test 2026-10-09: backend-only auto deployment
