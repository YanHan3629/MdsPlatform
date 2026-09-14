ARG BACKEND_BASE_IMAGE=eclipse-temurin:17-jre
FROM ${BACKEND_BASE_IMAGE}

WORKDIR /app

ARG DEBIAN_FRONTEND=noninteractive
ARG INSTALL_SYSTEM_PACKAGES=1
RUN if [ "$INSTALL_SYSTEM_PACKAGES" = "1" ]; then \
      apt-get update -o Acquire::Retries=5 -o Acquire::http::Timeout="30" \
      && apt-get install -y --no-install-recommends ca-certificates docker.io \
      && rm -rf /var/lib/apt/lists/*; \
    fi

# 把本地打好的 jar 拷进去
COPY target/sirius-backend-1.0-SNAPSHOT.jar sirius-backend.jar
COPY src/main/resources/script/*.sh /app/script/
# 可选：容器时区（你也可以不写）
ENV TZ=UTC

EXPOSE 8888

# 推荐用 exec 形式
ENTRYPOINT ["java","-jar","/app/sirius-backend.jar"]
