# Vision Observer Client

This is a standalone Fabric client mod that streams real Minecraft client view frames over WebSocket.

## What it does

- Connects to a local WebSocket server (`/ws/vision` by default)
- Forces observer account into spectator mode
- Rebinds spectator camera to the target bot player name
- Captures the real rendered frame from client framebuffer
- Encodes frames as JPEG base64 and pushes `vision_frame` packets
- Pushes `vision_status` and `vision_event` packets for monitoring

## Environment variables

- `OBS_WS_URL` (default: `ws://127.0.0.1:8000/ws/vision`)
- `OBS_TARGET_BOT` (default: `animabot`)
- `OBS_FPS` (default: `60`)
- `OBS_FRAME_WIDTH` (default: `1920`)
- `OBS_FRAME_HEIGHT` (default: `1080`)
- `OBS_JPEG_QUALITY` (default: `0.6`)
- `OBS_CHECK_INTERVAL_MS` (default: `1000`)
- `OBS_ATTACH_TIMEOUT_MS` (default: `3000`)
- `OBS_FAST_RETRY_MAX` (default: `8`)
- `OBS_FAST_RETRY_MS` (default: `1200`)
- `OBS_BACKOFF_MAX_MS` (default: `15000`)
- `OBS_DIMENSION_SETTLE_MS` (default: `2500`)
- `OBS_STATUS_INTERVAL_MS` (default: `1000`)

## Build

1. Install Java 17.
2. Install Gradle, or generate a Gradle wrapper for this subproject.
3. Build:

```bash
cd observer-client
gradle build
```

## Run in development

```bash
cd observer-client
gradle runClient
```

## Standalone debug (without agent backend)

If you want to test the observer client before integrating with the main backend:

By default, root `.env` is configured for integrated mode (`OBS_WS_URL=ws://127.0.0.1:8000/ws/vision`).
For standalone mock debug, override to port `18000` in your terminal session.

1. Start mock WebSocket server (in terminal A):

PowerShell:

```powershell
cd observer-client
python -m pip install fastapi uvicorn websockets
$env:MOCK_VISION_PORT="18000"
python mock_vision_server.py
```

CMD:

```bat
cd observer-client
python -m pip install fastapi uvicorn websockets
set MOCK_VISION_PORT=18000
python mock_vision_server.py
```

Bash:

```bash
cd observer-client
python -m pip install fastapi uvicorn websockets
export MOCK_VISION_PORT=18000
python mock_vision_server.py
```

2. Start Minecraft dev client (in terminal B):

PowerShell:

```powershell
cd observer-client
$env:OBS_WS_URL="ws://127.0.0.1:18000/ws/vision"
gradle runClient
```

CMD:

```bat
cd observer-client
set OBS_WS_URL=ws://127.0.0.1:18000/ws/vision
gradle runClient
```

Bash:

```bash
cd observer-client
export OBS_WS_URL=ws://127.0.0.1:18000/ws/vision
gradle runClient
```

3. Verify terminal A receives packets:

- `vision_status`
- `vision_event`
- `vision_frame`

4. Open browser viewer:

- `http://127.0.0.1:18000/viewer`

If the observer can connect and packets arrive, the standalone client pipeline is healthy.

Note: the main anima-bot backend uses port 8000 by default. The mock server intentionally uses 18000 to avoid conflicts with `python -m agent.core`.

Before launching the client, make sure:

- your Minecraft server is running
- your observer account has permission to run `/gamemode spectator` and `/spectate`
- your target bot name matches `OBS_TARGET_BOT`

## Wire protocol (outgoing)

- `vision_status`
- `vision_event`
- `vision_frame`

All packets use JSON with shape:

```json
{
  "source": "client_vision",
  "type": "vision_status | vision_event | vision_frame",
  "content": {}
}
```
