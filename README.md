# jvm-avis

MVP collector for **always-on bounded JFR** + **1s JMX metrics** from remote HotSpot JVMs (traditional or Quarkus JVM mode). Works against **JRE images** when Flight Recorder is present and JMX is exposed — no `jcmd` required.

## What it does

- Scrapes heap, CPU, per-collector GC counters, heap pool usage, and thread-state samples over JMX every second
- Periodically streams a snapshot from the target’s always-on JFR recording via `FlightRecorderMXBean`, fetching only what the previous dump did not already read
- Parses each dump once into three views:
  - `jdk.ExecutionSample` → hot methods and an inclusive CPU flame graph
  - `jdk.ObjectAllocationSample` → top allocating types and an allocation flame graph weighted by bytes
  - `jdk.GCPhasePause` → pause distribution with p50/p95/p99, which cumulative GC time cannot show
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

## Docker

```bash
docker compose up --build
```

Open [http://127.0.0.1:8080](http://127.0.0.1:8080). The collector reaches the demo over the Compose network at `demo-target:9010`; the demo sets `-Djava.rmi.server.hostname=demo-target` so JMX RMI works across containers.

Tag images for Helm (or change `image.repository` / `image.tag` in values):

```bash
docker tag jvm_avis-collector:latest jvm-avis-collector:0.1.0-SNAPSHOT
docker tag jvm_avis-demo-target:latest jvm-avis-demo-target:0.1.0-SNAPSHOT
```

## Helm

Two charts under [`charts/`](charts/):

| Chart | What it deploys |
|-------|-----------------|
| `jvm-avis` | Collector only — point `target` at an existing JMX endpoint, or register via API |
| `jvm-avis-demo` | Demo-target + collector (subchart), wired over in-cluster JMX |

```bash
# Collector only (set target to your JVM)
helm upgrade --install jvm-avis charts/jvm-avis \
  --set target.host=my-app --set target.port=9010

# Full demo stack
helm dependency update charts/jvm-avis-demo
helm upgrade --install jvm-avis-demo charts/jvm-avis-demo

kubectl port-forward svc/collector 8080:8080
```

Package:

```bash
helm package charts/jvm-avis -d dist/
helm dependency update charts/jvm-avis-demo
helm package charts/jvm-avis-demo -d dist/
```

For the demo chart, `JAVA_RMI_SERVER_HOSTNAME` defaults to `demo-target` (the Service name). Override `demo.rmiHostname` if you change `demo.fullnameOverride`.

## API

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/health` | Liveness |
| `GET` | `/api/targets` | List targets |
| `POST` | `/api/targets` | `{"host","port","label?"}` |
| `DELETE` | `/api/targets/{id}` | Remove target |
| `GET` | `/api/targets/{id}/metrics` | Time series (`?from=&to=` epoch ms) |
| `GET` | `/api/targets/{id}/profile` | Merged profile: `hotMethods`, `flameGraph`, `topAllocations`, `allocationFlameGraph`, `gcPauses` |

The profile response is dominated by its flame trees but only changes once per dump interval. Pass
`?since=<timestampMs>` to get `{"unchanged":true}` back instead when nothing has moved.

## Config

| Flag / env | Default | Meaning |
|------------|---------|---------|
| `--port` / `JVM_AVIS_PORT` | `8080` | UI/API port |
| `--target` / `JVM_AVIS_TARGET_HOST` + `_PORT` | none | Auto-register JMX target |
| `--metric-interval-ms` | `1000` | Metric scrape period |
| `--jfr-dump-interval-s` | `15` | JFR stream dump period |
| `--profile-window-s` / `JVM_AVIS_PROFILE_WINDOW_S` | `300` | Profile window rebuilt by merging dumps |
| `JVM_AVIS_RETENTION_S` | `3600` | In-memory metric retention |
| `JVM_AVIS_RECORDING_NAME` | `jvm-avis` | Preferred JFR recording name |

## Layout

```text
collector/     JMX scrape, JFR stream dump, API, UI
demo-target/   Load generator for local verification
scripts/       Launch helpers with recommended JVM flags
charts/        Helm charts (jvm-avis, jvm-avis-demo)
```
