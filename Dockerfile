# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Stage 1 (jarprep): strip native libraries this image can never load.
#
# The Spring Boot fat jar is architecture-independent: it bundles ONNX Runtime
# natives for every OS/arch (macOS, Windows, linux-x64, linux-aarch64). Every
# enterfind runtime target is a *Linux container*, so the macOS + Windows
# natives (~224 MB, incl. 144 MB of macOS debug symbols) are dead weight, and
# each per-arch image only needs its own Linux native - we drop the rest. This
# trims the jar from ~335 MB to ~210 MB.
#
# Runs on $BUILDPLATFORM (never emulated) because the jar is arch-independent;
# TARGETARCH still selects which Linux native to keep. The modified nested jar
# is re-added with `zip -0` (STORED, uncompressed) because Spring Boot's
# executable-jar loader requires nested jars to be stored, not deflated.
# ---------------------------------------------------------------------------
FROM --platform=$BUILDPLATFORM ubuntu:24.04 AS jarprep
ARG TARGETARCH
RUN apt-get update && apt-get install -y --no-install-recommends zip unzip && \
    rm -rf /var/lib/apt/lists/*
WORKDIR /build
COPY target/enterfind-server.jar app.jar
# The digit-anchored glob matches Microsoft's onnxruntime-<version>.jar (which
# carries the natives) but NOT DJL's onnxruntime-engine-<version>.jar.
RUN set -eu; \
    onnx="$(unzip -Z1 app.jar 'BOOT-INF/lib/onnxruntime-[0-9]*.jar' 2>/dev/null || true)"; \
    if [ -n "$onnx" ]; then \
      echo "Stripping unused ONNX natives from $onnx (TARGETARCH=$TARGETARCH)"; \
      unzip -q app.jar "$onnx"; \
      zip -q -d "$onnx" 'ai/onnxruntime/native/osx-*' 'ai/onnxruntime/native/win-*' || true; \
      if [ "$TARGETARCH" = "arm64" ]; then \
        zip -q -d "$onnx" 'ai/onnxruntime/native/linux-x64/*' || true; \
      else \
        zip -q -d "$onnx" 'ai/onnxruntime/native/linux-aarch64/*' || true; \
      fi; \
      zip -q -0 -X app.jar "$onnx"; \
      rm -rf BOOT-INF; \
    else \
      echo "No Microsoft ONNX Runtime jar found; skipping native strip"; \
    fi

# ---------------------------------------------------------------------------
# Stage 2: runtime image (Ubuntu 24.04 + Temurin 25 JRE + Tesseract)
# ---------------------------------------------------------------------------
FROM ubuntu:24.04

# Architecture provided by buildx (amd64 / arm64); matches Temurin's per-arch install path.
ARG TARGETARCH

# Set environment variables
ENV DEBIAN_FRONTEND=noninteractive
ENV LANG=C.UTF-8
ENV LC_ALL=C.UTF-8
ENV JAVA_HOME=/usr/lib/jvm/temurin-25-jre-${TARGETARCH}
ENV PATH="$JAVA_HOME/bin:$PATH"

# Install Java 25 JRE and other dependencies
RUN apt-get update && apt-get upgrade -y && \
    apt-get install -y \
    wget \
    gnupg \
    curl \
    ca-certificates \
    tesseract-ocr \
    tesseract-ocr-eng \
    tesseract-ocr-fra \
    tesseract-ocr-deu \
    tesseract-ocr-spa \
    tesseract-ocr-script-cyrl \
    tesseract-ocr-script-arab \
    tesseract-ocr-heb \
    tesseract-ocr-script-grek \
    tesseract-ocr-chi-sim \
    tesseract-ocr-jpn \
    tesseract-ocr-kor \
    tesseract-ocr-script-deva \
    tesseract-ocr-ben \
    tesseract-ocr-tam \
    tesseract-ocr-script-telu \
    tesseract-ocr-script-knda \
    tesseract-ocr-tha \
    gosu \
    && rm -rf /var/lib/apt/lists/*

# Create keyring directory
RUN mkdir -p /etc/apt/keyrings

# Add Adoptium GPG key and repository
RUN wget -O - https://packages.adoptium.net/artifactory/api/gpg/key/public | tee /etc/apt/keyrings/adoptium.asc && \
    CODENAME=$(awk -F= '/^VERSION_CODENAME/{print$2}' /etc/os-release) && \
    echo "deb [signed-by=/etc/apt/keyrings/adoptium.asc] https://packages.adoptium.net/artifactory/deb $CODENAME main" | tee /etc/apt/sources.list.d/adoptium.list

# Update package list with new repository and install Java 25 JRE
RUN apt-get update && \
    apt-get install -y temurin-25-jre && \
    rm -rf /var/lib/apt/lists/*

# ubuntu:24.04 bakes in a default "ubuntu" user at uid/gid 1000, unused by
# this image. Remove it so 1000 - the single most common uid anyone would
# pick for PUID, since it's the default first-user uid on most Linux
# distros - is actually free; otherwise entrypoint.sh's `usermod -u` fails
# with "UID already exists" (silently, since it's non-fatal by design).
RUN userdel -r ubuntu 2>/dev/null || true && groupdel ubuntu 2>/dev/null || true

# Create a non-root user for security. UID is pinned so it's predictable
# across rebuilds; overridable at runtime via PUID (see entrypoint.sh).
# Created BEFORE the jar is copied so `COPY --chown` sets ownership in the copy
# layer itself; a later `chown -R /app` would duplicate the whole jar into a
# second image layer (~350 MB of waste on every build).
RUN groupadd -r enterfind && useradd -r -m -u 998 -g enterfind enterfind

# Set working directory
WORKDIR /app

# Copy the native-stripped pre-built JAR, owned by enterfind at copy time.
COPY --from=jarprep --chown=enterfind:enterfind /build/app.jar app.jar

# The licence texts travel with the binary: LICENSE (AGPLv3) for Enterfind
# itself, NOTICE for the third-party components bundled in the jar.
COPY LICENSE NOTICE /app/

# Runtime log dir. Non-recursive chown of just the two directories (the jar is
# already enterfind-owned via --chown above), so this stays a tiny layer.
RUN mkdir -p /app/logs && chown enterfind:enterfind /app /app/logs

# Entrypoint fixes ownership of runtime-mounted paths (e.g. /app/logs), then
# drops privileges to the enterfind user before exec'ing the JVM. Container
# must start as root for this to work; it never runs the app as root.
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

# Expose the default Spring Boot port
EXPOSE 8080

# Runtime-configurable startup variables (PUID, SPRING_PROFILES_ACTIVE,
# DB_URL, DB_USERNAME, DB_PASSWORD, ENTERFIND_ENCRYPTION_KEY,
# ENTERFIND_APPLIANCE_MODE, JAVA_OPTS) default in entrypoint.sh, the single
# place that both declares and consumes them.

# Health check for container orchestration
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["/entrypoint.sh"]
