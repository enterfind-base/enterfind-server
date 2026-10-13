# Custom Dev Container for Enterfind Server
# Based on Ubuntu 24.04 with Java 25 and Maven

FROM ubuntu:24.04

# Accept build argument for container name
ARG name

# Add label with the container name
LABEL container.name="${name}"

# Set environment variables
ENV DEBIAN_FRONTEND=noninteractive
ENV GIT_EDITOR="code --wait"
# Required for correct UTF-8 filename handling by the JVM (sun.jnu.encoding).
# Without this, bash -c invocations (e.g. a CI runner via docker exec) do not source
# /etc/default/locale, leaving sun.jnu.encoding=ANSI_X3.4-1968 (ASCII), which
# corrupts non-ASCII filenames read from the filesystem.
ENV LANG=C.UTF-8
ENV LC_ALL=C.UTF-8

RUN apt-get update && apt-get upgrade -y && \
    apt-get install -y \
    wget \
    gnupg \
    curl \
    git \
    sudo \
    ca-certificates \
    postgresql-client \
    postgresql \
    postgresql-contrib \
    samba \
    samba-common \
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
    && rm -rf /var/lib/apt/lists/*

# Create keyring directory
RUN mkdir -p /etc/apt/keyrings

# Add Adoptium GPG key and repository
RUN wget -O - https://packages.adoptium.net/artifactory/api/gpg/key/public | tee /etc/apt/keyrings/adoptium.asc && \
    CODENAME=$(awk -F= '/^VERSION_CODENAME/{print$2}' /etc/os-release) && \
    echo "deb [signed-by=/etc/apt/keyrings/adoptium.asc] https://packages.adoptium.net/artifactory/deb $CODENAME main" | tee /etc/apt/sources.list.d/adoptium.list

# Update package list with new repository and install Java 25 and Maven
RUN apt-get update && \
    apt-get install -y temurin-25-jdk maven && \
    rm -rf /var/lib/apt/lists/*

# Create arch-agnostic JAVA_HOME symlink (works for amd64, arm64, etc.)
RUN ARCH=$(dpkg --print-architecture) && \
    ln -sf /usr/lib/jvm/temurin-25-jdk-${ARCH} /usr/lib/jvm/temurin-25-jdk
ENV JAVA_HOME=/usr/lib/jvm/temurin-25-jdk
ENV PATH="$JAVA_HOME/bin:$PATH"

# Install latest Node.js (LTS)
RUN curl -fsSL https://deb.nodesource.com/setup_current.x | bash - && \
    apt-get install -y nodejs && \
    rm -rf /var/lib/apt/lists/*

# Install claude-code natively
RUN curl -fsSL https://claude.ai/install.sh | bash && \
    ln -sf ~/.local/bin/claude /usr/local/bin/claude

# Verify installations
RUN java -version && mvn -version

# PostgreSQL configuration for dev container
# Create PostgreSQL data directory and set permissions
RUN mkdir -p /var/run/postgresql && \
    chown -R postgres:postgres /var/run/postgresql && \
    chmod 2775 /var/run/postgresql && \
    mkdir -p /var/lib/postgresql/data && \
    chown -R postgres:postgres /var/lib/postgresql/data && \
    chmod 700 /var/lib/postgresql/data

# Initialize PostgreSQL cluster
RUN PG_VERSION=$(ls /usr/lib/postgresql/ | sort -V | tail -n1) && \
    su - postgres -c "/usr/lib/postgresql/$PG_VERSION/bin/initdb -D /var/lib/postgresql/data"

# Create initialization script for test database
RUN mkdir -p /docker-entrypoint-initdb.d
COPY --chown=postgres:postgres init-test-db.sql /docker-entrypoint-initdb.d/01-init-test-db.sql

RUN PG_VERSION=$(ls /usr/lib/postgresql/ | sort -V | tail -n1) && \
    echo "listen_addresses = '*'" >> /etc/postgresql/$PG_VERSION/main/postgresql.conf && \
    echo "host all all 127.0.0.1/32 trust" >> /etc/postgresql/$PG_VERSION/main/pg_hba.conf

# Samba configuration for testing
RUN mkdir -p /samba/shares/scratch && \
    chown -R root:root /samba && \
    chmod 755 /samba/shares/scratch

# Copy Samba configuration file
COPY smb.conf /etc/samba/smb.conf

# Create smbtest Unix user (Samba password will be set at runtime)
RUN useradd -m -s /usr/sbin/nologin smbtest 2>/dev/null || true

# Copy startup script for Samba
COPY start-samba.sh /usr/local/bin/start-samba.sh
RUN chmod +x /usr/local/bin/start-samba.sh

# Install MinIO server (S3-compatible object storage for integration tests).
# Pinned to the last community release: MinIO archived the open-source server and
# dl.min.io now returns 410 for every community build and hotfix, so the GitHub
# release asset is the only remaining source. A pinned URL cannot float, so the
# checksum turns any future substitution or disappearance into a loud build failure.
ARG MINIO_RELEASE=RELEASE.2025-09-07T16-13-09Z
RUN ARCH=$(dpkg --print-architecture) && \
    case "$ARCH" in \
        amd64) MINIO_SHA256="7c5bd8512c6e966455b1d198209358b2d191c77a83ab377c4073281065fb855f" ;; \
        arm64) MINIO_SHA256="5c83cd2cf151717ba0243f73e1c7802ff36e272b67144bdd7f1f7d684fd6f03d" ;; \
        *) echo "Unsupported arch: $ARCH" && exit 1 ;; \
    esac && \
    curl -fsSL "https://github.com/minio/minio/releases/download/${MINIO_RELEASE}/minio.linux-${ARCH}.${MINIO_RELEASE}" -o /usr/local/bin/minio && \
    echo "${MINIO_SHA256}  /usr/local/bin/minio" | sha256sum -c - && \
    chmod +x /usr/local/bin/minio && \
    mkdir -p /minio/data /var/log

# Copy startup script for MinIO
COPY start-minio.sh /usr/local/bin/start-minio.sh
RUN chmod +x /usr/local/bin/start-minio.sh

# Set working directory
WORKDIR /workspaces/enterfind-server

