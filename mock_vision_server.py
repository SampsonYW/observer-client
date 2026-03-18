import asyncio
import json
import os
from datetime import datetime

from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.responses import HTMLResponse
import uvicorn


app = FastAPI()
app.state.latest_frame = None
app.state.last_status = None
app.state.frame_count = 0


@app.get("/")
async def root():
    return {"ok": True, "service": "mock-vision-server"}


@app.get("/viewer", response_class=HTMLResponse)
async def viewer():
    return """
<!doctype html>
<html>
<head>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <title>Mock Vision Viewer</title>
    <style>
        body { margin: 0; font-family: sans-serif; background: #111; color: #ddd; }
        .wrap { max-width: 980px; margin: 20px auto; padding: 0 12px; }
        .meta { margin: 0 0 10px; font-size: 14px; line-height: 1.5; }
        .stage { background: #000; border: 1px solid #333; border-radius: 8px; overflow: hidden; }
        img { display: block; width: 100%; height: auto; image-rendering: auto; }
        .hint { color: #9a9a9a; font-size: 13px; margin-top: 8px; }
    </style>
</head>
<body>
    <div class="wrap">
        <p class="meta" id="meta">Waiting for frames...</p>
        <div class="stage"><img id="frame" alt="vision frame" /></div>
        <p class="hint">Open this page after runClient starts and the observer is attached to target.</p>
    </div>
    <script>
        const meta = document.getElementById('meta');
        const frame = document.getElementById('frame');
        let lastTs = -1;
        let inFlight = false;

        async function refresh() {
            if (inFlight) {
                return;
            }
            inFlight = true;
            try {
                const resp = await fetch('/api/latest_frame?after_ts=' + encodeURIComponent(lastTs), { cache: 'no-store' });
                const data = await resp.json();

                if (!data.has_frame) {
                    if (!data.status) {
                        meta.textContent =
                            'No observer packets received yet. Check OBS_WS_URL and mock port (expect ws://127.0.0.1:18000/ws/vision).';
                    } else {
                        meta.textContent =
                            'No frame yet. state=' + (data.status.state || 'unknown') +
                            ' | reason=' + (data.status.reason || 'n/a') +
                            ' | bound=' + String(data.status.camera_bound) +
                            ' | frame_count=' + data.frame_count;
                    }
                    return;
                }

                if (data.changed && data.ts !== lastTs) {
                    frame.src = data.data_url;
                    lastTs = data.ts;
                }

                meta.textContent =
                    'state=' + (data.status?.state || 'unknown') +
                    ' | bound=' + String(data.camera_bound) +
                    ' | size=' + data.width + 'x' + data.height +
                    ' | frame_count=' + data.frame_count +
                    ' | ts=' + data.ts;
            } catch (e) {
                meta.textContent = 'Viewer fetch error: ' + e;
            } finally {
                inFlight = false;
            }
        }

        setInterval(refresh, 16);
        refresh();
    </script>
</body>
</html>
"""


@app.get("/api/latest_frame")
async def latest_frame(after_ts: int | None = None):
    frame = app.state.latest_frame
    status = app.state.last_status
    if not frame:
        return {
            "has_frame": False,
            "frame_count": app.state.frame_count,
            "status": status,
        }

    latest_ts = int(frame.get("ts", 0) or 0)
    if after_ts is not None and int(after_ts) == latest_ts:
        return {
            "has_frame": True,
            "changed": False,
            "ts": latest_ts,
            "width": frame["width"],
            "height": frame["height"],
            "camera_bound": frame["camera_bound"],
            "target_name": frame["target_name"],
            "frame_count": app.state.frame_count,
            "status": status,
        }

    return {
        "has_frame": True,
        "changed": True,
        "data_url": frame["data_url"],
        "ts": latest_ts,
        "width": frame["width"],
        "height": frame["height"],
        "camera_bound": frame["camera_bound"],
        "target_name": frame["target_name"],
        "frame_count": app.state.frame_count,
        "status": status,
    }


@app.websocket("/ws/vision")
async def ws_vision(websocket: WebSocket):
    await websocket.accept()
    print("[mock] observer connected")

    counts = {
        "vision_status": 0,
        "vision_event": 0,
        "vision_frame": 0,
        "other": 0,
    }

    try:
        while True:
            raw = await websocket.receive_text()
            now = datetime.now().strftime("%H:%M:%S")

            try:
                data = json.loads(raw)
                packet_type = data.get("type", "other")
                if packet_type not in counts:
                    packet_type = "other"
                counts[packet_type] += 1

                if packet_type == "vision_status":
                    content = data.get("content", {})
                    app.state.last_status = {
                        "state": content.get("state"),
                        "reason": content.get("reason"),
                        "camera_bound": content.get("camera_bound"),
                        "retry_index": content.get("retry_index"),
                    }
                    print(
                        f"[{now}] status={content.get('state')} "
                        f"reason={content.get('reason')} "
                        f"bound={content.get('camera_bound')} "
                        f"retry={content.get('retry_index')}"
                    )
                elif packet_type == "vision_event":
                    content = data.get("content", {})
                    print(
                        f"[{now}] event={content.get('event')} "
                        f"level={content.get('level')} "
                        f"detail={content.get('detail')}"
                    )
                elif packet_type == "vision_frame":
                    content = data.get("content", {})
                    jpeg_base64 = content.get("jpeg_base64")
                    if jpeg_base64:
                        app.state.latest_frame = {
                            "data_url": f"data:image/jpeg;base64,{jpeg_base64}",
                            "ts": content.get("ts", 0),
                            "width": content.get("width"),
                            "height": content.get("height"),
                            "camera_bound": content.get("camera_bound"),
                            "target_name": content.get("target_name"),
                        }
                    app.state.frame_count += 1

                    if counts[packet_type] % 10 == 0:
                        print(
                            f"[{now}] frame x{counts[packet_type]} "
                            f"{content.get('width')}x{content.get('height')} "
                            f"bound={content.get('camera_bound')}"
                        )
                else:
                    print(f"[{now}] packet={packet_type}")

            except json.JSONDecodeError:
                counts["other"] += 1
                print(f"[{now}] non-json packet, bytes={len(raw)}")

    except WebSocketDisconnect:
        print("[mock] observer disconnected")
        print(
            "[mock] summary: "
            f"status={counts['vision_status']} "
            f"event={counts['vision_event']} "
            f"frame={counts['vision_frame']} "
            f"other={counts['other']}"
        )


if __name__ == "__main__":
    host = os.getenv("MOCK_VISION_HOST", "127.0.0.1")
    port = int(os.getenv("MOCK_VISION_PORT", "18000"))
    print(f"[mock] listening on ws://{host}:{port}/ws/vision")
    uvicorn.run(app, host=host, port=port, log_level="warning")
