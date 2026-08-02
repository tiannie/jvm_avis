const state = {
  targetId: null,
  charts: {},
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
}

function fmtTime(ts) {
  return new Date(ts).toLocaleTimeString();
}

function mb(bytes) {
  return bytes / (1024 * 1024);
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

async function refreshMetrics() {
  if (!state.targetId) return;
  const samples = await api(`/api/targets/${encodeURIComponent(state.targetId)}/metrics`);
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
}

async function refreshProfile() {
  if (!state.targetId) return;
  const profile = await api(`/api/targets/${encodeURIComponent(state.targetId)}/profile`);
  const meta = document.getElementById("profile-meta");
  const tbody = document.getElementById("hot-methods");
  tbody.innerHTML = "";
  if (!profile.timestampMs) {
    meta.textContent = profile.message || "Waiting for first dump…";
    return;
  }
  const windowSec = Math.max(1, Math.round((profile.windowEndMs - profile.windowStartMs) / 1000));
  meta.textContent = `${fmtTime(profile.timestampMs)} · ${profile.totalSamples} samples · ~${windowSec}s window`;
  for (const row of profile.hotMethods || []) {
    const tr = document.createElement("tr");
    tr.innerHTML = `<td>${row.percent.toFixed(2)}</td><td>${row.samples}</td><td>${escapeHtml(row.method)}</td>`;
    tbody.appendChild(tr);
  }
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
    await Promise.all([refreshMetrics(), refreshProfile()]);
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
