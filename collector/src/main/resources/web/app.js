const state = {
  targetId: null,
  charts: {},
  // Keyed by container id so each flame graph only re-renders when its snapshot changes.
  flames: {},
};

const chartDefaults = {
  responsive: true,
  animation: false,
  scales: {
    x: {
      ticks: { color: "#9aa8c7", maxTicksLimit: 6 },
      grid: { color: "rgba(49,65,95,0.45)" },
    },
    y: {
      ticks: { color: "#9aa8c7" },
      grid: { color: "rgba(49,65,95,0.45)" },
    },
  },
  plugins: {
    legend: { labels: { color: "#e8eefc" } },
  },
};

function makeChart(id, label, color) {
  const ctx = document.getElementById(id);
  return new Chart(ctx, {
    type: "line",
    data: {
      labels: [],
      datasets: [{
        label,
        data: [],
        borderColor: color,
        backgroundColor: color + "33",
        tension: 0.2,
        pointRadius: 0,
        borderWidth: 2,
        fill: true,
      }],
    },
    options: chartDefaults,
  });
}

function makeMultiChart(id, datasets) {
  const ctx = document.getElementById(id);
  return new Chart(ctx, {
    type: "line",
    data: {
      labels: [],
      datasets: datasets.map((d) => ({
        label: d.label,
        data: [],
        borderColor: d.color,
        backgroundColor: d.color + "22",
        tension: 0.2,
        pointRadius: 0,
        borderWidth: 2,
        fill: false,
      })),
    },
    options: chartDefaults,
  });
}

const SERIES_COLORS = [
  "#6ea8ff", "#3ecf8e", "#ffc857", "#ff6b7a", "#9b7bff", "#5ad1e6", "#ff9f43", "#c3f584",
];

/** Pool and collector names are discovered at runtime, so datasets are rebuilt when they change. */
function setDynamicSeries(chart, labels, series) {
  const signature = series.map((s) => s.label).join("|");
  if (chart.seriesSignature !== signature) {
    chart.data.datasets = series.map((s, i) => ({
      label: s.label,
      data: [],
      borderColor: SERIES_COLORS[i % SERIES_COLORS.length],
      backgroundColor: SERIES_COLORS[i % SERIES_COLORS.length] + "22",
      tension: 0.2,
      pointRadius: 0,
      borderWidth: 2,
      fill: false,
    }));
    chart.seriesSignature = signature;
  }
  chart.data.labels = labels;
  series.forEach((s, i) => {
    chart.data.datasets[i].data = s.data;
  });
  chart.update();
}

function initCharts() {
  state.charts.heap = makeMultiChart("heap-chart", [
    { label: "used MB", color: "#6ea8ff" },
    { label: "committed MB", color: "#9b7bff" },
  ]);
  state.charts.cpu = makeChart("cpu-chart", "process CPU %", "#3ecf8e");
  state.charts.threads = makeMultiChart("thread-chart", [
    { label: "live", color: "#ffc857" },
    { label: "runnable", color: "#6ea8ff" },
    { label: "blocked", color: "#ff6b7a" },
    { label: "waiting", color: "#9aa8c7" },
  ]);
  state.charts.gc = makeMultiChart("gc-chart", [
    { label: "collections", color: "#ff9f43" },
    { label: "time ms", color: "#5ad1e6" },
  ]);
  state.charts.pools = makeMultiChart("pool-chart", []);
  state.charts.collectors = makeMultiChart("collector-chart", []);
  state.charts.gcPause = new Chart(document.getElementById("gc-pause-chart"), {
    type: "bar",
    data: {
      labels: [],
      datasets: [{ label: "pause ms", data: [], backgroundColor: "#ff6b7a", borderWidth: 0 }],
    },
    options: chartDefaults,
  });
}

function fmtTime(ts) {
  return new Date(ts).toLocaleTimeString();
}

function mb(bytes) {
  return bytes / (1024 * 1024);
}

function fmtBytes(value) {
  if (!value) return "0 B";
  const units = ["B", "KB", "MB", "GB", "TB"];
  let n = value;
  let i = 0;
  while (n >= 1024 && i < units.length - 1) {
    n /= 1024;
    i++;
  }
  return `${i === 0 ? n : n.toFixed(1)} ${units[i]}`;
}

async function api(path, options) {
  const res = await fetch(path, options);
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body.error || res.statusText);
  }
  return res.json();
}

async function refreshTargets() {
  const targets = await api("/api/targets");
  const select = document.getElementById("target-select");
  const previous = state.targetId;
  select.innerHTML = "";
  if (!targets.length) {
    select.innerHTML = "<option value=\"\">No targets</option>";
    state.targetId = null;
    document.getElementById("target-meta").textContent = "Add a JMX target to begin.";
    clearAllFlameGraphs();
    return;
  }
  for (const t of targets) {
    const opt = document.createElement("option");
    opt.value = t.id;
    opt.textContent = `${t.label} (${t.status})`;
    select.appendChild(opt);
  }
  if (previous && targets.some((t) => t.id === previous)) {
    select.value = previous;
    state.targetId = previous;
  } else {
    state.targetId = targets[0].id;
    select.value = state.targetId;
  }
  const current = targets.find((t) => t.id === state.targetId);
  const statusClass = current.status === "OK" || current.status === "CONNECTED" ? "status-ok" : "status-bad";
  document.getElementById("target-meta").innerHTML =
    `<span class="${statusClass}">${current.status}</span> · ${current.host}:${current.port}` +
    (current.lastError ? ` · ${current.lastError}` : "");
}

function setSeries(chart, labels, seriesList) {
  chart.data.labels = labels;
  seriesList.forEach((values, idx) => {
    chart.data.datasets[idx].data = values;
  });
  chart.update();
}

// Retention is an hour; sending every sample on a 2s poll would grow to megabytes per request.
const METRIC_VIEW_MS = 10 * 60 * 1000;

async function refreshMetrics() {
  if (!state.targetId) return;
  const from = Date.now() - METRIC_VIEW_MS;
  const samples = await api(
    `/api/targets/${encodeURIComponent(state.targetId)}/metrics?from=${from}`);
  const labels = samples.map((s) => fmtTime(s.timestampMs));
  setSeries(state.charts.heap, labels, [
    samples.map((s) => mb(s.heapUsedBytes)),
    samples.map((s) => mb(s.heapCommittedBytes)),
  ]);
  setSeries(state.charts.cpu, labels, [
    samples.map((s) => (Number.isFinite(s.processCpuLoad) ? s.processCpuLoad * 100 : null)),
  ]);
  setSeries(state.charts.threads, labels, [
    samples.map((s) => s.threadCount),
    samples.map((s) => (s.threadStates && s.threadStates.RUNNABLE) || 0),
    samples.map((s) => (s.threadStates && s.threadStates.BLOCKED) || 0),
    samples.map((s) =>
      ((s.threadStates && s.threadStates.WAITING) || 0) +
      ((s.threadStates && s.threadStates.TIMED_WAITING) || 0)),
  ]);
  setSeries(state.charts.gc, labels, [
    samples.map((s) => s.gcCollectionCount),
    samples.map((s) => s.gcCollectionTimeMs),
  ]);
  renderMemoryPools(labels, samples);
  renderCollectors(labels, samples);

  const latest = samples[samples.length - 1];
  if (latest && latest.deadlockedThreadCount > 0) {
    const meta = document.getElementById("target-meta");
    meta.innerHTML +=
      ` · <span class="status-bad">${latest.deadlockedThreadCount} DEADLOCKED THREADS</span>`;
  }
}

function namesFrom(samples, field, key) {
  const names = new Set();
  for (const sample of samples) {
    for (const entry of sample[field] || []) {
      names.add(entry[key]);
    }
  }
  return [...names].sort();
}

function renderMemoryPools(labels, samples) {
  setDynamicSeries(state.charts.pools, labels, namesFrom(samples, "memoryPools", "name").map((name) => ({
    label: name.replace(/^G1 /, ""),
    data: samples.map((s) => {
      const pool = (s.memoryPools || []).find((p) => p.name === name);
      return pool ? mb(pool.usedBytes) : null;
    }),
  })));

  const latest = samples[samples.length - 1];
  document.getElementById("pool-meta").textContent = latest
    ? `non-heap ${fmtBytes(latest.nonHeapUsedBytes)}`
    : "";
}

function renderCollectors(labels, samples) {
  const names = namesFrom(samples, "gcCollectors", "name");
  setDynamicSeries(state.charts.collectors, labels, names.map((name) => ({
    label: name,
    data: samples.map((s) => {
      const gc = (s.gcCollectors || []).find((c) => c.name === name);
      return gc ? gc.collections : null;
    }),
  })));
}

function clearFlameGraph(containerId = "flame-graph") {
  document.getElementById(containerId).innerHTML = "";
  delete state.flames[containerId];
}

function clearAllFlameGraphs() {
  clearFlameGraph("flame-graph");
  clearFlameGraph("alloc-flame-graph");
}

function renderFlameGraph(containerId, tree, formatValue) {
  const container = document.getElementById(containerId);
  container.innerHTML = "";
  const width = Math.max(320, container.clientWidth || container.parentElement.clientWidth || 800);
  const createFlamegraph = typeof flamegraph === "function" ? flamegraph : flamegraph.default;
  const tip = flamegraph.tooltip
    .defaultFlamegraphTooltip()
    .text((d) => `${d.data.name}: ${formatValue(d.data.value)}`);
  const chart = createFlamegraph()
    .width(width)
    .cellHeight(18)
    .minFrameSize(1)
    .tooltip(tip)
    .setColorMapper((d) => {
      if (!d.data.name || d.data.name === "all") {
        return "#243049";
      }
      let hash = 0;
      for (let i = 0; i < d.data.name.length; i++) {
        hash = ((hash << 5) - hash) + d.data.name.charCodeAt(i);
        hash |= 0;
      }
      const hue = Math.abs(hash) % 360;
      return `hsl(${hue} 42% 38%)`;
    });
  d3.select(container).datum(tree).call(chart);
}

/** Re-rendering a flame graph resets zoom, so only redraw when the snapshot actually moved. */
function drawFlameGraphIfChanged(containerId, tree, timestampMs, formatValue, emptyText) {
  const previous = state.flames[containerId];
  if (previous && previous.targetId === state.targetId && previous.timestampMs === timestampMs) {
    return;
  }
  if (tree && tree.value > 0) {
    renderFlameGraph(containerId, tree, formatValue);
    state.flames[containerId] = { targetId: state.targetId, timestampMs };
  } else {
    clearFlameGraph(containerId);
    document.getElementById(containerId).textContent = emptyText;
  }
}

function fillTable(tbodyId, rows, toCells) {
  const tbody = document.getElementById(tbodyId);
  tbody.innerHTML = "";
  for (const row of rows || []) {
    const tr = document.createElement("tr");
    tr.innerHTML = toCells(row);
    tbody.appendChild(tr);
  }
}

async function refreshProfile() {
  if (!state.targetId) return;
  const since = state.profileTimestampMs && state.profileTargetId === state.targetId
    ? `?since=${state.profileTimestampMs}`
    : "";
  const profile = await api(`/api/targets/${encodeURIComponent(state.targetId)}/profile${since}`);
  if (profile.unchanged) return;
  state.profileTimestampMs = profile.timestampMs;
  state.profileTargetId = state.targetId;
  const meta = document.getElementById("profile-meta");
  if (!profile.timestampMs) {
    meta.textContent = profile.message || "Waiting for first dump…";
    for (const id of ["hot-methods", "alloc-types", "thread-cpu", "leak-candidates", "monitor-events"]) {
      fillTable(id, [], () => "");
    }
    clearAllFlameGraphs();
    return;
  }
  const windowSec = Math.max(1, Math.round((profile.windowEndMs - profile.windowStartMs) / 1000));

  meta.textContent = `${fmtTime(profile.timestampMs)} · ${profile.totalSamples} samples · ~${windowSec}s window`;
  fillTable("hot-methods", profile.hotMethods, (row) =>
    `<td>${row.percent.toFixed(2)}</td><td>${row.samples}</td><td>${escapeHtml(row.method)}</td>`);
  drawFlameGraphIfChanged(
    "flame-graph",
    profile.flameGraph,
    profile.timestampMs,
    (v) => `${v} samples`,
    "No stack samples in this dump.");

  const allocRate = fmtBytes(Math.round(profile.allocatedBytes / windowSec));
  document.getElementById("alloc-meta").textContent = profile.allocationSamples
    ? `${fmtBytes(profile.allocatedBytes)} est. · ${allocRate}/s · ${profile.allocationSamples} samples`
    : "No allocation samples in window";
  fillTable("alloc-types", profile.topAllocations, (row) =>
    `<td>${row.percent.toFixed(2)}</td><td>${fmtBytes(row.bytes)}</td><td>${escapeHtml(row.type)}</td>`);
  drawFlameGraphIfChanged(
    "alloc-flame-graph",
    profile.allocationFlameGraph,
    profile.timestampMs,
    fmtBytes,
    "No allocation samples in this dump.");

  renderGcPauses(profile.gcPauses);
  renderThreadCpu(profile.threadCpu);
  renderLeakCandidates(profile.leakCandidates);
  renderMonitors(profile.monitorEvents);
  renderExceptions(profile.exceptions);
}

function renderThreadCpu(threads) {
  const rows = threads || [];
  const total = rows.reduce((sum, t) => sum + t.userPercent + t.systemPercent, 0);
  document.getElementById("thread-cpu-meta").textContent = rows.length
    ? `${rows.length} threads · ${total.toFixed(2)}% of one core`
    : "No thread CPU samples in window";
  fillTable("thread-cpu", rows, (row) =>
    `<td>${row.userPercent.toFixed(2)}</td><td>${row.systemPercent.toFixed(2)}</td>` +
    `<td>${escapeHtml(row.thread)}</td>`);
}

/** Colour ramp for lane intensity: dim slate through blue to a hot amber. */
function laneColor(fraction) {
  if (fraction <= 0) return "var(--bg2)";
  const hue = 210 - 175 * Math.min(1, fraction);
  const light = 22 + 33 * Math.min(1, fraction);
  return `hsl(${hue} 70% ${light}%)`;
}

async function refreshThreadLanes() {
  if (!state.targetId) return;
  const series = await api(`/api/targets/${encodeURIComponent(state.targetId)}/threads`);
  const container = document.getElementById("thread-lanes");
  const slots = (series.timestampsMs || []).length;
  if (!slots || !series.lanes.length) {
    container.innerHTML = "<div class=\"lane-empty\">Waiting for thread CPU readings…</div>";
    return;
  }

  // Percentages are small in absolute terms, so scale intensity to the window's own peak.
  const peak = series.maxPercent || 1;
  const parts = [];
  for (const lane of series.lanes) {
    parts.push(`<div class="lane-label" title="${escapeHtml(lane.thread)}">` +
      `${escapeHtml(lane.thread)}</div>`);
    const cells = [];
    for (let i = 0; i < slots; i++) {
      const user = lane.userPercent[i];
      const system = lane.systemPercent[i];
      if (user === null || user === undefined) {
        cells.push("<div class=\"lane-cell\" title=\"no reading\"></div>");
        continue;
      }
      const value = user + system;
      const tip = `${lane.thread}\n${fmtTime(series.timestampsMs[i])}\n` +
        `user ${user.toFixed(2)}%  system ${system.toFixed(2)}%`;
      cells.push(`<div class="lane-cell" style="background:${laneColor(value / peak)}" ` +
        `title="${escapeHtml(tip)}"></div>`);
    }
    parts.push(`<div class="lane-track" style="grid-template-columns:repeat(${slots},1fr)">` +
      `${cells.join("")}</div>`);
  }
  parts.push("<div></div>");
  parts.push(`<div class="lane-axis"><span>${fmtTime(series.timestampsMs[0])}</span>` +
    `<span>peak ${peak.toFixed(2)}% of one core</span>` +
    `<span>${fmtTime(series.timestampsMs[slots - 1])}</span></div>`);
  container.innerHTML = parts.join("");
}

function renderLeakCandidates(candidates) {
  const rows = candidates || [];
  document.getElementById("leak-meta").textContent = rows.length
    ? "sampled objects still reachable — surviving is normal, growing is not"
    : "No surviving sampled objects in window";
  fillTable("leak-candidates", rows, (row) =>
    `<td>${row.samples}</td><td>${fmtDuration(row.maxAgeSeconds)}</td>` +
    `<td>${escapeHtml(row.type)}</td><td>${escapeHtml(row.allocatedBy)}</td>`);
}

function renderMonitors(events) {
  const rows = events || [];
  const blocked = rows.filter((e) => e.kind === "BLOCKED").length;
  document.getElementById("monitor-meta").textContent = rows.length
    ? `${blocked} contended · WAITING is deliberate Object.wait, not contention`
    : "No monitor events — default JFR settings only record blocking over 20ms";
  fillTable("monitor-events", rows, (row) =>
    `<td>${row.kind}</td><td>${row.events}</td><td>${row.totalMs.toFixed(0)} ms</td>` +
    `<td>${row.maxMs.toFixed(1)} ms</td><td>${escapeHtml(row.monitorClass)}</td>`);
}

function renderExceptions(rate) {
  if (!rate) return;
  const meta = document.getElementById("profile-meta");
  if (rate.thrownInWindow > 0) {
    meta.textContent += ` · ${rate.perSecond}/s exceptions`;
  }
}

function fmtDuration(seconds) {
  if (seconds < 60) return `${seconds}s`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m`;
  return `${Math.floor(seconds / 3600)}h ${Math.floor((seconds % 3600) / 60)}m`;
}

function renderGcPauses(gc) {
  const meta = document.getElementById("gc-pause-meta");
  if (!gc || !gc.count) {
    meta.textContent = "No pauses in window";
    setSeries(state.charts.gcPause, [], [[]]);
    return;
  }
  meta.textContent =
    `${gc.count} pauses · p50 ${gc.p50Ms}ms · p95 ${gc.p95Ms}ms · p99 ${gc.p99Ms}ms · max ${gc.maxMs}ms`;
  setSeries(
    state.charts.gcPause,
    gc.pauses.map((p) => fmtTime(p.timestampMs)),
    [gc.pauses.map((p) => p.durationMs)]);
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;");
}

async function tick() {
  try {
    await refreshTargets();
    await Promise.all([refreshMetrics(), refreshProfile(), refreshThreadLanes()]);
  } catch (err) {
    console.error(err);
    document.getElementById("target-meta").textContent = String(err.message || err);
  }
}

document.getElementById("target-select").addEventListener("change", (e) => {
  state.targetId = e.target.value || null;
  tick();
});

document.getElementById("add-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const host = document.getElementById("host").value.trim();
  const port = Number(document.getElementById("port").value);
  const label = document.getElementById("label").value.trim();
  await api("/api/targets", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ host, port, label }),
  });
  await tick();
});

initCharts();
tick();
setInterval(tick, 2000);
