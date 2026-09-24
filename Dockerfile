# --- build stage: produce the uberjar ---
FROM clojure:temurin-21-tools-deps-bookworm AS build
WORKDIR /src
COPY deps.edn build.clj ./
COPY src ./src
COPY resources ./resources
COPY test ./test
COPY deploy ./deploy
RUN clojure -T:build ci

# --- runtime stage: sshd (forced commands) + engram-proxy (bb) + server (jar) ---
FROM eclipse-temurin:21-jre-bookworm
ARG BABASHKA_VERSION=1.12.196
RUN apt-get update \
 && apt-get install -y --no-install-recommends openssh-server curl ca-certificates tar gzip \
 && rm -rf /var/lib/apt/lists/* \
 && curl -sL "https://github.com/babashka/babashka/releases/download/v${BABASHKA_VERSION}/babashka-${BABASHKA_VERSION}-linux-amd64-static.tar.gz" \
      | tar -xz -C /usr/local/bin \
 && useradd -m -d /home/engram -s /bin/bash engram \
 && mkdir -p /home/engram/.ssh /data /config /backups /run/sshd \
 && chown -R engram:engram /home/engram /data /backups

COPY --from=build /src/target/engram-*-standalone.jar /app/engram.jar
COPY bin/engram-proxy /usr/local/bin/engram-proxy
COPY deploy/backup.sh /usr/local/bin/engram-backup
COPY deploy/sshd_config /etc/ssh/sshd_config.d/engram.conf
COPY deploy/entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/engram-proxy /usr/local/bin/engram-backup /usr/local/bin/entrypoint.sh

ENV ENGRAM_DATA_DIR=/data/engram \
    ENGRAM_CONFIG=/config/engram-config.edn \
    ENGRAM_PORT=8080 \
    PORT=8080 \
    JAVA_OPTS="--add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED --enable-native-access=ALL-UNNAMED"

EXPOSE 22
VOLUME ["/data", "/config"]
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
