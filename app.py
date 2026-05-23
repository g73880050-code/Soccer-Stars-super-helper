"""
app.py — Soccer Stars Analyzer: Replit Web Dashboard
=====================================================
A lightweight Flask web app that lets you explore and test the core
analyzer.py engine (pure Python/OpenCV) from a browser.

This is a development/testing interface only.
The real app runs on Android (Kivy + python-for-android).
"""

from __future__ import annotations
import base64
import io
import json
import os

import cv2
import numpy as np
from flask import Flask, jsonify, render_template_string, request

from analyzer import (
    AnalyzerConfig,
    DetectedObject,
    analyse_frame,
    compute_trajectory,
)

app = Flask(__name__)

# ---------------------------------------------------------------------------
# HTML template
# ---------------------------------------------------------------------------

HTML = """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Soccer Stars Analyzer</title>
  <style>
    *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
      background: #0a0e1a;
      color: #e2e8f0;
      min-height: 100vh;
    }
    header {
      background: linear-gradient(135deg, #1a1f35 0%, #0f172a 100%);
      border-bottom: 1px solid #2d3748;
      padding: 1.25rem 2rem;
      display: flex;
      align-items: center;
      gap: 1rem;
    }
    header .logo { font-size: 2rem; }
    header h1 { font-size: 1.4rem; font-weight: 700; color: #63b3ed; }
    header p { font-size: 0.8rem; color: #718096; }
    .badge {
      margin-left: auto;
      background: #2d7d46;
      color: #68d391;
      font-size: 0.72rem;
      font-weight: 600;
      padding: 0.3rem 0.75rem;
      border-radius: 9999px;
    }
    main { max-width: 1100px; margin: 0 auto; padding: 2rem; }

    .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 1.5rem; }
    @media (max-width: 700px) { .grid { grid-template-columns: 1fr; } }

    .card {
      background: #1a1f35;
      border: 1px solid #2d3748;
      border-radius: 12px;
      padding: 1.5rem;
    }
    .card h2 { font-size: 1rem; font-weight: 600; color: #90cdf4; margin-bottom: 1rem; }

    .info-row { display: flex; justify-content: space-between; align-items: center;
                padding: 0.4rem 0; border-bottom: 1px solid #2d3748; font-size: 0.85rem; }
    .info-row:last-child { border-bottom: none; }
    .info-row .label { color: #718096; }
    .info-row .value { color: #e2e8f0; font-weight: 500; }
    .tag { background: #2b4c7e; color: #90cdf4; padding: 0.15rem 0.5rem;
           border-radius: 4px; font-size: 0.75rem; }

    .demo-wrap { grid-column: 1 / -1; }
    canvas { display: block; width: 100%; border-radius: 8px;
             border: 1px solid #2d3748; background: #111827; cursor: crosshair; }
    .controls {
      display: flex; flex-wrap: wrap; gap: 0.75rem; align-items: center;
      margin-top: 1rem;
    }
    button {
      background: #2b6cb0; color: #fff; border: none; border-radius: 8px;
      padding: 0.5rem 1.1rem; font-size: 0.85rem; cursor: pointer; font-weight: 500;
    }
    button:hover { background: #3182ce; }
    button.danger { background: #c53030; }
    button.danger:hover { background: #e53e3e; }
    button.secondary { background: #2d3748; color: #cbd5e0; }
    button.secondary:hover { background: #4a5568; }
    .hint { font-size: 0.78rem; color: #718096; }
    #result-msg {
      margin-top: 0.75rem; font-size: 0.82rem; min-height: 1.2em;
      color: #68d391;
    }

    .hsv-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 1rem; }
    label { font-size: 0.8rem; color: #a0aec0; display: block; margin-bottom: 0.25rem; }
    input[type=range] { width: 100%; accent-color: #3182ce; }
    .range-row { display: flex; align-items: center; gap: 0.5rem; }
    .range-row span { font-size: 0.78rem; color: #718096; min-width: 2.5rem; text-align: right; }
    .swatch { width: 24px; height: 24px; border-radius: 4px; border: 1px solid #4a5568;
              display: inline-block; vertical-align: middle; margin-left: 0.5rem; }
    .section-title { font-size: 0.78rem; text-transform: uppercase; letter-spacing: 0.08em;
                     color: #4a5568; margin-bottom: 0.75rem; }
  </style>
</head>
<body>
<header>
  <div class="logo">⚽</div>
  <div>
    <h1>Soccer Stars Analyzer</h1>
    <p>Android overlay · trajectory predictor · OpenCV engine</p>
  </div>
  <span class="badge">Dev Dashboard</span>
</header>

<main>
  <div class="grid">

    <!-- Project Info -->
    <div class="card">
      <h2>Project Overview</h2>
      <div class="info-row"><span class="label">Platform</span>
        <span class="value">Android (API 26–34)</span></div>
      <div class="info-row"><span class="label">UI Framework</span>
        <span class="value">Kivy 2.3.0</span></div>
      <div class="info-row"><span class="label">CV Engine</span>
        <span class="value">OpenCV {{ cv2_ver }}</span></div>
      <div class="info-row"><span class="label">Python</span>
        <span class="value">{{ py_ver }}</span></div>
      <div class="info-row"><span class="label">Build System</span>
        <span class="value">python-for-android / Buildozer</span></div>
      <div class="info-row"><span class="label">Architecture</span>
        <span class="value">arm64-v8a</span></div>
    </div>

    <!-- Architecture -->
    <div class="card">
      <h2>Architecture</h2>
      <div class="info-row"><span class="label">Detection</span>
        <span class="value">HSV colour-mask → contour → circle</span></div>
      <div class="info-row"><span class="label">Trajectory</span>
        <span class="value">Ray-cast with wall reflections (NumPy)</span></div>
      <div class="info-row"><span class="label">IPC</span>
        <span class="value">UDP localhost 54321 / 54322</span></div>
      <div class="info-row"><span class="label">Screen Capture</span>
        <span class="value">Android MediaProjection API</span></div>
      <div class="info-row"><span class="label">Power Modes</span>
        <span class="value">Active (15 FPS) · Hibernate (0.5 s)</span></div>
      <div class="info-row"><span class="label">Auto-detect</span>
        <span class="value">Hough lines + motion delta</span></div>
    </div>

    <!-- Source Files -->
    <div class="card">
      <h2>Source Files</h2>
      {% for f in files %}
      <div class="info-row">
        <span class="label"><code>{{ f.name }}</code></span>
        <span class="value">{{ f.lines }} lines · <span class="tag">{{ f.role }}</span></span>
      </div>
      {% endfor %}
    </div>

    <!-- Engine Status -->
    <div class="card">
      <h2>Engine Status</h2>
      <div class="info-row"><span class="label">numpy</span>
        <span class="value">{{ np_ver }} ✓</span></div>
      <div class="info-row"><span class="label">cv2</span>
        <span class="value">{{ cv2_ver }} ✓</span></div>
      <div class="info-row"><span class="label">analyzer.py</span>
        <span class="value">imported ✓</span></div>
      <div class="info-row"><span class="label">Max bounces</span>
        <span class="value">5</span></div>
      <div class="info-row"><span class="label">Default scale</span>
        <span class="value">0.5×</span></div>
      <div class="info-row"><span class="label">Android runtime</span>
        <span class="value">not available (desktop)</span></div>
    </div>

    <!-- Interactive Demo -->
    <div class="card demo-wrap">
      <h2>Trajectory Demo</h2>
      <p class="hint" style="margin-bottom:0.75rem">
        Click to place the <strong style="color:#68d391">ball</strong> (first click) and
        <strong style="color:#63b3ed">player</strong> (second click), then see the predicted
        shot trajectory computed by the real engine.
      </p>
      <canvas id="demo" width="800" height="450"></canvas>
      <div class="controls">
        <button id="btn-run">Compute Trajectory</button>
        <button class="secondary" id="btn-reset">Reset</button>
        <span class="hint">Click on the field to place ball &amp; player</span>
      </div>
      <div id="result-msg"></div>
    </div>

    <!-- HSV Config -->
    <div class="card" style="grid-column:1/-1">
      <h2>HSV Colour Ranges (read-only preview)</h2>
      <div class="hsv-grid">
        <div>
          <p class="section-title">Ball (white / bright)</p>
          {% for row in ball_hsv %}
          <label>{{ row.label }}
            <div class="range-row">
              <input type="range" min="0" max="{{ row.max }}" value="{{ row.value }}" disabled>
              <span>{{ row.value }}</span>
            </div>
          </label>
          {% endfor %}
        </div>
        <div>
          <p class="section-title">Active Player (blue disc)</p>
          {% for row in player_hsv %}
          <label>{{ row.label }}
            <div class="range-row">
              <input type="range" min="0" max="{{ row.max }}" value="{{ row.value }}" disabled>
              <span>{{ row.value }}</span>
            </div>
          </label>
          {% endfor %}
        </div>
      </div>
    </div>

  </div><!-- /.grid -->
</main>

<script>
const canvas = document.getElementById('demo');
const ctx    = canvas.getContext('2d');
const W = canvas.width, H = canvas.height;
let ball = null, player = null, step = 0;

function drawField() {
  ctx.clearRect(0, 0, W, H);
  // pitch green
  const g = ctx.createLinearGradient(0, 0, 0, H);
  g.addColorStop(0, '#1a4a2e');
  g.addColorStop(1, '#0f3020');
  ctx.fillStyle = g;
  ctx.fillRect(0, 0, W, H);
  // stripes
  ctx.strokeStyle = 'rgba(255,255,255,0.04)';
  ctx.lineWidth = 1;
  for (let x = 0; x < W; x += 40) {
    ctx.beginPath(); ctx.moveTo(x, 0); ctx.lineTo(x, H); ctx.stroke();
  }
  // border
  ctx.strokeStyle = 'rgba(255,255,255,0.15)';
  ctx.lineWidth = 2;
  ctx.strokeRect(10, 10, W-20, H-20);
  // centre circle
  ctx.beginPath(); ctx.arc(W/2, H/2, 60, 0, 2*Math.PI);
  ctx.strokeStyle = 'rgba(255,255,255,0.12)'; ctx.lineWidth = 1.5; ctx.stroke();
  ctx.beginPath(); ctx.moveTo(W/2, 10); ctx.lineTo(W/2, H-10);
  ctx.stroke();
}

function drawCircle(pos, color, label) {
  ctx.beginPath();
  ctx.arc(pos.x, pos.y, 14, 0, 2*Math.PI);
  ctx.fillStyle = color;
  ctx.fill();
  ctx.strokeStyle = '#fff';
  ctx.lineWidth = 2;
  ctx.stroke();
  ctx.fillStyle = '#fff';
  ctx.font = 'bold 11px sans-serif';
  ctx.textAlign = 'center';
  ctx.fillText(label, pos.x, pos.y + 4);
}

function drawTrajectory(pts) {
  if (!pts || pts.length < 2) return;
  ctx.save();
  ctx.setLineDash([6, 4]);
  ctx.strokeStyle = '#f6e05e';
  ctx.lineWidth = 2;
  ctx.shadowColor = '#f6e05e';
  ctx.shadowBlur = 6;
  ctx.beginPath();
  ctx.moveTo(pts[0][0], pts[0][1]);
  for (let i = 1; i < pts.length; i++) ctx.lineTo(pts[i][0], pts[i][1]);
  ctx.stroke();
  ctx.restore();
  pts.slice(1, -1).forEach(p => {
    ctx.beginPath();
    ctx.arc(p[0], p[1], 4, 0, 2*Math.PI);
    ctx.fillStyle = '#f6e05e';
    ctx.fill();
  });
}

drawField();

canvas.addEventListener('click', e => {
  const rect = canvas.getBoundingClientRect();
  const sx = canvas.width  / rect.width;
  const sy = canvas.height / rect.height;
  const x  = Math.round((e.clientX - rect.left) * sx);
  const y  = Math.round((e.clientY - rect.top)  * sy);

  if (step === 0) {
    ball = {x, y}; step = 1;
    drawField();
    drawCircle(ball, '#68d391', 'B');
    document.getElementById('result-msg').textContent = 'Ball placed — now click to place the player.';
  } else {
    player = {x, y}; step = 0;
    drawField();
    drawCircle(ball,   '#68d391', 'B');
    drawCircle(player, '#63b3ed', 'P');
    document.getElementById('result-msg').textContent = 'Player placed — click Compute Trajectory.';
  }
});

document.getElementById('btn-run').addEventListener('click', async () => {
  if (!ball || !player) {
    document.getElementById('result-msg').textContent = 'Place ball and player first.';
    return;
  }
  document.getElementById('result-msg').textContent = 'Computing…';
  try {
    const res  = await fetch('/api/trajectory', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({ball, player, width: W, height: H}),
    });
    const data = await res.json();
    drawField();
    drawTrajectory(data.waypoints);
    drawCircle(ball,   '#68d391', 'B');
    drawCircle(player, '#63b3ed', 'P');
    document.getElementById('result-msg').textContent =
      `Trajectory: ${data.waypoints.length} waypoints · ${data.bounces} wall bounce(s)`;
  } catch(e) {
    document.getElementById('result-msg').textContent = 'Error: ' + e.message;
  }
});

document.getElementById('btn-reset').addEventListener('click', () => {
  ball = null; player = null; step = 0;
  drawField();
  document.getElementById('result-msg').textContent = '';
});
</script>
</body>
</html>
"""


# ---------------------------------------------------------------------------
# Routes
# ---------------------------------------------------------------------------

@app.route("/")
def index():
    import sys
    cfg = AnalyzerConfig()

    def _count(path):
        try:
            return sum(1 for _ in open(path))
        except Exception:
            return 0

    files = [
        {"name": "main.py",        "lines": _count("main.py"),        "role": "Kivy UI"},
        {"name": "analyzer.py",    "lines": _count("analyzer.py"),    "role": "CV engine"},
        {"name": "overlay.py",     "lines": _count("overlay.py"),     "role": "Android overlay"},
        {"name": "service/main.py","lines": _count("service/main.py"),"role": "BG service"},
        {"name": "hsv_tuner.py",   "lines": _count("hsv_tuner.py"),   "role": "HSV settings"},
    ]

    def _hsv_rows(lower, upper):
        names  = ["H low", "S low", "V low", "H high", "S high", "V high"]
        maxes  = [180, 255, 255, 180, 255, 255]
        vals   = list(lower) + list(upper)
        return [{"label": n, "value": int(v), "max": m}
                for n, v, m in zip(names, vals, maxes)]

    return render_template_string(
        HTML,
        cv2_ver=cv2.__version__,
        np_ver=np.__version__,
        py_ver=f"{sys.version_info.major}.{sys.version_info.minor}.{sys.version_info.micro}",
        files=files,
        ball_hsv=_hsv_rows(cfg.ball_lower_hsv, cfg.ball_upper_hsv),
        player_hsv=_hsv_rows(cfg.player_lower_hsv, cfg.player_upper_hsv),
    )


@app.route("/api/trajectory", methods=["POST"])
def trajectory():
    data   = request.get_json(force=True)
    ball   = data["ball"]
    player = data["player"]
    width  = int(data.get("width",  800))
    height = int(data.get("height", 450))

    cfg = AnalyzerConfig()
    b   = DetectedObject(int(ball["x"]),   int(ball["y"]),   10)
    p   = DetectedObject(int(player["x"]), int(player["y"]), 10)

    waypoints = compute_trajectory(b, p, (height, width, 3), cfg)
    return jsonify({
        "waypoints": [[x, y] for x, y in waypoints],
        "bounces":   max(0, len(waypoints) - 2),
    })


@app.route("/api/health")
def health():
    return jsonify({"status": "ok"})


if __name__ == "__main__":
    port = int(os.environ.get("PORT", 5000))
    app.run(host="0.0.0.0", port=port, debug=False)
