# jvm-avis

MVP collector for **always-on bounded JFR** + **1s JMX metrics** from remote HotSpot JVMs (traditional or Quarkus JVM mode). Works against **JRE images** when Flight Recorder is present and JMX is exposed — no `jcmd` required.

## What it does

- Scrapes heap, CPU, GC counters, and thread-state samples over JMX every second
- Periodically streams a snapshot from the target’s always-on JFR recording via `FlightRecorderMXBean`
- Parses `jdk.ExecutionSample` events into a hot-methods table and an inclusive CPU flame graph
- Serves a small charting UI at `http://localhost:8080`

## Target JVM prerequisites

Start the target with **bounded always-on JFR** and **JMX**:

```text
-XX:StartFlightRecording=name=jvm-avis,settings=default,maxage=5m,maxsize=64m,disk=true,dumponexit=true
-Dcom.sun.management.jmxremote
-Dcom.sun.management.jmxremote.port=9010
-Dcom.sun.management.jmxremote.rmi.port=9010
-Dcom.sun.management.jmxremote.ssl=false
-Dcom.sun.management.jmxremote.authenticate=false
-Djava.rmi.server.hostname=<reachable-host>
```

Notes:

- `maxage` / `maxsize` keep the recording bounded (ring buffer)
- Prefer auth + TLS for anything beyond local demos
- Quarkus **native** is out of scope; Quarkus on HotSpot is fine
- Container CPU/RAM limits still come from cgroup/K8s later — not in this MVP

## Build

Requires JDK 17+ (collector uses `jdk.jfr` to parse dumps).

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk   # or your JDK 17+
mvn -DskipTests package
```

## Run locally

```bash
chmod +x scripts/*.sh
./scripts/run-local.sh
```

Open [http://127.0.0.1:8080](http://127.0.0.1:8080). The flame graph and hot methods appear after the first JFR dump interval (default 10s in `run-local.sh`).

Or run the two processes yourself:

```bash
./scripts/run-demo-target.sh    # JFR + JMX :9010, HTTP :8081
./scripts/run-collector.sh      # UI/API :8080, auto-registers 127.0.0.1:9010
```

## API

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/health` | Liveness |
| `GET` | `/api/targets` | List targets |
| `POST` | `/api/targets` | `{"host","port","label?"}` |
| `DELETE` | `/api/targets/{id}` | Remove target |
| `GET` | `/api/targets/{id}/metrics` | Time series (`?from=&to=` epoch ms) |
| `GET` | `/api/targets/{id}/profile` | Latest snapshot (`hotMethods` + `flameGraph` tree) |

## Config

| Flag / env | Default | Meaning |
|------------|---------|---------|
| `--port` / `JVM_AVIS_PORT` | `8080` | UI/API port |
| `--target` / `JVM_AVIS_TARGET_HOST` + `_PORT` | none | Auto-register JMX target |
| `--metric-interval-ms` | `1000` | Metric scrape period |
| `--jfr-dump-interval-s` | `15` | JFR stream dump period |
| `JVM_AVIS_RETENTION_S` | `3600` | In-memory metric retention |
| `JVM_AVIS_RECORDING_NAME` | `jvm-avis` | Preferred JFR recording name |

## Layout

```text
collector/     JMX scrape, JFR stream dump, API, UI
demo-target/   Load generator for local verification
scripts/       Launch helpers with recommended JVM flags
```
