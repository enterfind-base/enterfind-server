#!/bin/sh
set -e

# Let the operator's own UID scheme win over the image's baked-in default
# (998).
: "${PUID:=998}"
if [ "$(id -u enterfind)" != "$PUID" ]; then
  usermod -u "$PUID" enterfind 2>/dev/null || true
fi

# /app/logs may be a bind mount or named volume supplied at runtime, so its
# ownership can't be baked in at build time. Best-effort fix it here - but
# this can genuinely fail (e.g. Docker Desktop's VirtioFS bridge on Mac runs
# as an unprivileged host user and can never chown a path it doesn't already
# own, no matter what "root" tries from inside the container). So this must
# never be fatal; the writability probe below is what actually decides.
# On native Linux this always succeeds regardless of any prior owner (real
# root bypasses DAC checks) - so a mature installation converges cleanly
# onto PUID here, on this first attempt, every time.
mkdir -p /app/logs 2>/dev/null || true
chown -R enterfind:enterfind /app/logs 2>/dev/null || true

if gosu enterfind sh -c 'touch /app/logs/.write-test 2>/dev/null && rm -f /app/logs/.write-test'; then
  LOG_DIR=/app/logs
else
  # The primary identity (explicit PUID, or the 998 default) can't write
  # here - expected on hosts where a bind mount can't be chowned from
  # inside the container at all (Mac/Windows Docker Desktop). Before
  # falling back, try adopting whatever already owns the directory: this
  # keeps a mature installation's existing logs working on such hosts too,
  # without requiring the operator to know/set PUID themselves.
  EXISTING_OWNER=$(stat -c '%u' /app/logs 2>/dev/null || echo "")
  if [ -n "$EXISTING_OWNER" ] && [ "$EXISTING_OWNER" != "0" ] && [ "$EXISTING_OWNER" != "$(id -u enterfind)" ]; then
    usermod -u "$EXISTING_OWNER" enterfind 2>/dev/null || true
  fi

  if gosu enterfind sh -c 'touch /app/logs/.write-test 2>/dev/null && rm -f /app/logs/.write-test'; then
    LOG_DIR=/app/logs
  else
    LOG_DIR=/var/log/enterfind-internal
    mkdir -p "$LOG_DIR"
    chown -R enterfind:enterfind "$LOG_DIR"
    echo "WARNING: /app/logs is not writable by the enterfind user (uid $(id -u enterfind))." >&2
    echo "WARNING: Logs will go to $LOG_DIR inside the container instead - they will NOT" >&2
    echo "WARNING: persist across container recreation and won't be visible on the host." >&2
    echo "WARNING: To fix: make the directory mounted at /app/logs writable by that uid." >&2
  fi
fi
export LOG_DIR

# Defaults for all runtime-configurable startup variables live here, next to
# where they're consumed - this is the single place to touch when adding,
# removing, or renaming one.
: "${SPRING_PROFILES_ACTIVE:=beta}"
: "${DB_URL:=jdbc:postgresql://localhost:5432/enterfind}"
: "${DB_USERNAME:=enterfind}"
: "${DB_PASSWORD:=enterfind}"
: "${ENTERFIND_ENCRYPTION_KEY:=}"
: "${ENTERFIND_APPLIANCE_MODE:=false}"
: "${JAVA_OPTS:=-XX:+UseContainerSupport -XX:MaxRAMPercentage=50.0 -XX:InitialRAMPercentage=25.0 -XX:+UseG1GC -XX:+UseStringDeduplication -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom}"

exec gosu enterfind java $JAVA_OPTS -jar app.jar \
  --spring.profiles.active=${SPRING_PROFILES_ACTIVE} \
  --spring.datasource.url=${DB_URL} \
  --spring.datasource.username=${DB_USERNAME} \
  --spring.datasource.password=${DB_PASSWORD} \
  --enterfind.encryption.key=${ENTERFIND_ENCRYPTION_KEY} \
  --enterfind.appliance-mode=${ENTERFIND_APPLIANCE_MODE}
