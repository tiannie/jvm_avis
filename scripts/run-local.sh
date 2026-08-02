#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

mkdir -p target/logs

if [[ ! -f collector/target/collector-0.1.0-SNAPSHOT.jar || ! -f demo-target/target/demo-target-0.1.0-SNAPSHOT.jar ]]; then
  export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk}"
  export PATH="$JAVA_HOME/bin:$PATH"
  mvn -DskipTests package
fi

stop_pidfile() {
  local file="$1"
  if [[ -f "$file" ]]; then
    local pid
    pid="$(cat "$file")"
    if kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null || true
      sleep 0.5
      kill -9 "$pid" 2>/dev/null || true
    fi
    rm -f "$file"
  fi
}

stop_pidfile target/logs/collector.pid
stop_pidfile target/logs/demo.pid

nohup "$ROOT/scripts/run-demo-target.sh" > target/logs/demo.log 2>&1 &
echo $! > target/logs/demo.pid
sleep 1
nohup "$ROOT/scripts/run-collector.sh" --jfr-dump-interval-s 10 > target/logs/collector.log 2>&1 &
echo $! > target/logs/collector.pid

echo "demo pid=$(cat target/logs/demo.pid)  collector pid=$(cat target/logs/collector.pid)"
echo "UI: http://127.0.0.1:8080"
echo "logs: target/logs/demo.log target/logs/collector.log"
