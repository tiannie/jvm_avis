#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
JAR="$ROOT/collector/target/collector-0.1.0-SNAPSHOT.jar"

if [[ ! -f "$JAR" ]]; then
  echo "Missing $JAR — run: mvn -q -DskipTests package" >&2
  exit 1
fi

exec "$JAVA_BIN" -jar "$JAR" \
  --target "${JVM_AVIS_TARGET:-127.0.0.1:9010}" \
  "$@"
