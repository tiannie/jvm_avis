# AGENTS.md

## Cursor Cloud specific instructions

`jvm-avis` is a Java 17+ / Maven multi-module project (no test suite exists yet). It has two runnable services:

- `demo-target` — a load-generator JVM started with always-on bounded JFR + unauthenticated local JMX on port `9010` (HTTP on `8081`). It exists purely to give the collector something to observe.
- `collector` — scrapes the target over JMX every second, periodically dumps/parses JFR (`jdk.ExecutionSample`) into hot-methods + a CPU flame graph, and serves the UI/API at `http://127.0.0.1:8080`.

### Build / run / lint / test

- Build: `mvn -q -DskipTests package` (produces shaded jars under `collector/target/` and `demo-target/target/`).
- Run both locally: `./scripts/run-local.sh` — it auto-builds if the jars are missing, then launches both processes in the background. Logs go to `target/logs/{demo,collector}.log` and PIDs to `target/logs/*.pid`. Open `http://127.0.0.1:8080`.
- Lint: there is no linter/formatter configured; `mvn package` compilation is the only static check.
- Test: there are no automated tests (`src/test` does not exist). Verify changes by running the stack and hitting the API/UI.
- The system `java` is JDK 21, which satisfies the JDK 17+ requirement. Do NOT set `JAVA_HOME=/opt/homebrew/...` — that path (from the README/scripts default) does not exist here; leave `JAVA_HOME` unset so the scripts use the system `java` on `PATH`.

### Non-obvious caveats

- The flame graph and hot-methods table only appear AFTER the first JFR dump interval elapses (default 10s via `run-local.sh`, otherwise `--jfr-dump-interval-s`, default 15s). JMX metric charts appear within ~1s; give profiling data ~10-15s before judging the UI as broken.
- `POST /api/targets` dedupes by `host:port`, so re-posting the auto-registered `127.0.0.1:9010` returns the existing target rather than creating a duplicate — this is expected, not a failure.
- Docker Compose (`docker compose up --build`) is an alternative full-stack run but is unnecessary for local dev; the local scripts are faster. In Compose the demo sets `-Djava.rmi.server.hostname=demo-target` so JMX RMI works across containers.
- `./scripts/run-local.sh` kills and restarts both services on each invocation (via the pidfiles), so re-running it is the clean way to restart after a rebuild.
