#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
JAR="$ROOT/demo-target/target/demo-target-0.1.0-SNAPSHOT.jar"
JMX_PORT="${JMX_PORT:-9010}"
HTTP_PORT="${DEMO_HTTP_PORT:-8081}"

if [[ ! -f "$JAR" ]]; then
  echo "Missing $JAR — run: mvn -q -DskipTests package" >&2
  exit 1
fi

# Always-on bounded Flight Recorder + local JMX (no auth; local/dev only).
export DEMO_HTTP_PORT="$HTTP_PORT"
exec "$JAVA_BIN" \
  -XX:StartFlightRecording=name=jvm-avis,settings=default,maxage=5m,maxsize=64m,disk=true,dumponexit=true \
  -Dcom.sun.management.jmxremote \
  -Dcom.sun.management.jmxremote.port="$JMX_PORT" \
  -Dcom.sun.management.jmxremote.rmi.port="$JMX_PORT" \
  -Dcom.sun.management.jmxremote.ssl=false \
  -Dcom.sun.management.jmxremote.authenticate=false \
  -Djava.rmi.server.hostname=127.0.0.1 \
  -jar "$JAR"
