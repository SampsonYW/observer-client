package com.animabot.visionobserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class VisionWsClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("VisionWsClient");

    private final URI wsUri;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private final AtomicReference<WebSocket> wsRef;
    private final AtomicBoolean connected;
    private final AtomicBoolean connecting;
    private final AtomicBoolean stopped;

    private volatile String sessionId;
    private volatile String lastError;

    public VisionWsClient(String wsUrl) {
        this.wsUri = URI.create(wsUrl);
        this.httpClient = HttpClient.newHttpClient();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "vision-ws-scheduler");
            t.setDaemon(true);
            return t;
        });
        this.wsRef = new AtomicReference<>();
        this.connected = new AtomicBoolean(false);
        this.connecting = new AtomicBoolean(false);
        this.stopped = new AtomicBoolean(false);
        this.sessionId = "session-not-connected";
        this.lastError = "";
    }

    public void start() {
        stopped.set(false);
        connectAsync();
    }

    public void stop() {
        stopped.set(true);
        connected.set(false);
        connecting.set(false);

        WebSocket ws = wsRef.getAndSet(null);
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "observer shutdown");
            } catch (Exception ignored) {
            }
        }

        scheduler.shutdownNow();
    }

    public boolean isConnected() {
        return connected.get();
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getLastError() {
        return lastError;
    }

    public boolean sendJson(String payload) {
        WebSocket ws = wsRef.get();
        if (ws == null || !connected.get()) {
            return false;
        }

        try {
            ws.sendText(payload, true).exceptionally(ex -> {
                onDisconnected("send_failed: " + ex.getMessage());
                return null;
            });
            return true;
        } catch (Exception ex) {
            onDisconnected("send_exception: " + ex.getMessage());
            return false;
        }
    }

    private void connectAsync() {
        if (stopped.get() || connected.get()) {
            return;
        }
        if (!connecting.compareAndSet(false, true)) {
            return;
        }

        httpClient
                .newWebSocketBuilder()
                .buildAsync(wsUri, new WsListener())
                .whenComplete((ws, ex) -> {
                    if (ex != null) {
                        connecting.set(false);
                        onDisconnected("connect_failed: " + ex.getMessage());
                    }
                });
    }

    private void scheduleReconnect() {
        if (stopped.get()) {
            return;
        }
        scheduler.schedule(this::connectAsync, 2, TimeUnit.SECONDS);
    }

    private void onDisconnected(String reason) {
        lastError = reason == null ? "disconnected" : reason;
        connected.set(false);
        connecting.set(false);
        wsRef.set(null);

        if (!stopped.get()) {
            LOGGER.warn("Vision WS disconnected: {}", lastError);
            scheduleReconnect();
        }
    }

    private class WsListener implements WebSocket.Listener {
        @Override
        public void onOpen(WebSocket webSocket) {
            wsRef.set(webSocket);
            sessionId = UUID.randomUUID().toString();
            connected.set(true);
            connecting.set(false);
            lastError = "";

            LOGGER.info("Vision WS connected: {}", wsUri);
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            onDisconnected("close(" + statusCode + "): " + reason);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            onDisconnected("error: " + error.getMessage());
        }
    }
}
