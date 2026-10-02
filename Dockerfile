# --- runtime stage: sshd (forced commands) + engram-proxy (bb) + server (jar) ---
# The uberjar is built ahead of this image, on the runner, and copied in. The jar
# is architecture-neutral, so one build serves both platforms. Only babashka and
# the base JRE differ per architecture.
FROM eclipse-temurin:21-jre-noble
ARG BABASHKA_VERSION=1.12.196
# buildx sets TARGETARCH to amd64 or arm64. babashka names arm64 as aarch64.
ARG TARGETARCH
RUN apt-get update \
 && apt-get install -y --no-install-recommends openssh-server curl ca-certificates tar gzip \
 && rm -rf /var/lib/apt/lists/* \
 && case "${TARGETARCH}" in \
      amd64) BB_ARCH=amd64 ;; \
      arm64) BB_ARCH=aarch64 ;; \
      *) echo "unsupported TARGETARCH: ${TARGETARCH}" >&2; exit 1 ;; \
    esac \
 && curl -sL "https://github.com/babashka/babashka/releases/download/v${BABASHKA_VERSION}/babashka-${BABASHKA_VERSION}-linux-${BB_ARCH}-static.tar.gz" \
      | tar -xz -C /usr/local/bin \
 && useradd -m -d /home/engram -s /bin/bash engram \
 && mkdir -p /home/engram/.ssh /data /config /run/sshd \
 && chown -R engram:engram /home/engram /data

COPY target/engram-*-standalone.jar /app/engram.jar
COPY bin/engram-proxy /usr/local/bin/engram-proxy
COPY deploy/sshd_config /etc/ssh/sshd_config.d/engram.conf
COPY deploy/entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/engram-proxy /usr/local/bin/entrypoint.sh

ENV ENGRAM_DATA_DIR=/data/engram \
    ENGRAM_CONFIG=/config/engram-config.edn \
    ENGRAM_PORT=8080 \
    PORT=8080 \
    JAVA_OPTS="--add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED --enable-native-access=ALL-UNNAMED"

EXPOSE 22
VOLUME ["/data", "/config"]
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
