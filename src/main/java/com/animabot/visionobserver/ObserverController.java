package com.animabot.visionobserver;

import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.c2s.play.ChatMessageC2SPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.client.option.Perspective;
import com.google.gson.JsonArray;

public class ObserverController {
    private static final Logger LOGGER = LoggerFactory.getLogger("ObserverController");

    private final ObserverConfig config;
    private final VisionWsClient wsClient;
    private final ExecutorService encoderExecutor;
    private final AtomicBoolean encoderBusy;
    private final long captureIntervalNs;

    private volatile ObserverState state;
    private volatile String stateReason;
    private volatile String currentDimension;
    private volatile long lastFrameSentAtMs;

    private int retryCount;
    private long nextActionAtMs;
    private long attachDeadlineAtMs;
    private long nextRetryAtMs;
    private long nextHealthCheckAtMs;
    private long nextStatusAtMs;
    private long nextCaptureAtNs;
    private long dimensionSettleUntilMs;

    private int captureStage = 0; // 0:空闲, 1:拍第一人称, 2:拍正面, 3:拍背面
    private NativeImage[] pendingFrames = new NativeImage[3];

    public ObserverController(ObserverConfig config, VisionWsClient wsClient) {
        this.config = config;
        this.wsClient = wsClient;
        this.encoderExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "vision-frame-encoder");
            t.setDaemon(true);
            return t;
        });
        this.encoderBusy = new AtomicBoolean(false);
        this.captureIntervalNs = 1_000_000_000L / Math.max(1, config.fps);

        this.state = ObserverState.INIT;
        this.stateReason = "startup";
        this.currentDimension = "unknown";
        this.lastFrameSentAtMs = 0L;
    }

    public void onTick(MinecraftClient client) {
        long now = System.currentTimeMillis();

        if (client.player == null || client.world == null) {
            updateState(ObserverState.INTERRUPTED, "not_in_world");
            if (now >= nextStatusAtMs) {
                publishStatus(client, now, "not_in_world");
                nextStatusAtMs = now + config.statusIntervalMs;
            }
            return;
        }

        if (state == ObserverState.INIT || state == ObserverState.INTERRUPTED) {
            updateState(ObserverState.JOINED, "joined_world");
            sendEvent("info", "joined_world", "observer joined world");
        }

        refreshDimension(client, now);

        if (now >= nextHealthCheckAtMs) {
            nextHealthCheckAtMs = now + config.checkIntervalMs;
            runHealthCheck(client, now);
        }

        if (now >= nextStatusAtMs) {
            nextStatusAtMs = now + config.statusIntervalMs;
            publishStatus(client, now, "tick");
        }
    }

    public void onRenderEnd(WorldRenderContext context) {
        if (state != ObserverState.STREAMING) {
            return;
        }
        if (!wsClient.isConnected()) {
            return;
        }

        long nowNs = System.nanoTime();
        if (nowNs < nextCaptureAtNs) {
            return;
        }
        if (!encoderBusy.compareAndSet(false, true)) {
            return;
        }

        nextCaptureAtNs = nowNs + captureIntervalNs;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            encoderBusy.set(false);
            return;
        }

        boolean cameraBound = isCameraBoundToTarget(client);
        long ts = System.currentTimeMillis();
        String dimension = currentDimension;

        if (captureStage == 0) {
            // 第二阶段：此时画面已经是第一人称，截图并切到正面
            pendingFrames[0] = ScreenshotRecorder.takeScreenshot(client.getFramebuffer());
            
            client.options.setPerspective(Perspective.THIRD_PERSON_FRONT);
            captureStage = 1;
            encoderBusy.set(false);
        }
        else if (captureStage == 1) {
            // 第三阶段：此时画面是正面，截图并切到背面
            pendingFrames[1] = ScreenshotRecorder.takeScreenshot(client.getFramebuffer());
            
            client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
            captureStage = 2;
            encoderBusy.set(false);
        }
        else if (captureStage == 2) {
            // 第四阶段：此时画面是背面，截图，打包发送并还原视角
            pendingFrames[2] = ScreenshotRecorder.takeScreenshot(client.getFramebuffer());
            
            NativeImage[] finalFrames = new NativeImage[]{ pendingFrames[0], pendingFrames[1], pendingFrames[2] };
            encoderExecutor.submit(() -> encodeAndSendFrames(finalFrames, System.currentTimeMillis(), cameraBound, dimension));
            
            // 还原视角
            client.options.setPerspective(Perspective.FIRST_PERSON);
            captureStage = 0;
            // 注意：这里才算真正完成一次抓取任务，重置 nextCaptureAtNs
        }
    }

    public void shutdown() {
        encoderExecutor.shutdownNow();
    }

    private void refreshDimension(MinecraftClient client, long now) {
        String nextDimension = client.world.getRegistryKey().getValue().toString();
        if (!nextDimension.equals(currentDimension)) {
            currentDimension = nextDimension;
            dimensionSettleUntilMs = now + config.dimensionSettleMs;
            retryCount = 0;
            nextRetryAtMs = dimensionSettleUntilMs;
            updateState(ObserverState.REBINDING, "dimension_change");
            sendEvent("info", "dimension_change", "dimension=" + nextDimension);
        }
    }

    private void runHealthCheck(MinecraftClient client, long now) {
        ClientPlayerEntity self = client.player;
        if (self == null) {
            updateState(ObserverState.INTERRUPTED, "player_null");
            return;
        }

        if (now < dimensionSettleUntilMs) {
            updateState(ObserverState.REBINDING, "dimension_settling");
            return;
        }

        if (!self.isSpectator()) {
            updateState(ObserverState.SPECTATOR_READY, "set_spectator");
            if (now >= nextActionAtMs) {
                sendChatCommand(self, "/gamemode spectator");
                nextActionAtMs = now + config.fastRetryMs;
            }
            return;
        }

        if (isCameraBoundToTarget(client)) {
            retryCount = 0;
            attachDeadlineAtMs = 0;
            if (state != ObserverState.STREAMING) {
                sendEvent("info", "attach_ok", "bound_target=" + config.targetBotName);
            }
            updateState(ObserverState.STREAMING, "attached");
            return;
        }

        if (state == ObserverState.ATTACHING) {
            if (now > attachDeadlineAtMs) {
                onAttachFailed(now, "attach_timeout");
            }
            return;
        }

        if (now < nextRetryAtMs) {
            return;
        }

        attemptAttach(client, now);
    }

    private void attemptAttach(MinecraftClient client, long now) {
        ClientPlayerEntity self = client.player;
        if (self == null) {
            return;
        }
        if (now < nextActionAtMs) {
            return;
        }

        PlayerEntity target = findTargetPlayer(client, config.targetBotName);
        if (target == null) {
            onAttachFailed(now, "target_not_visible");
            return;
        }

        sendChatCommand(self, "/spectate " + config.targetBotName);
        attachDeadlineAtMs = now + config.attachTimeoutMs;
        nextActionAtMs = now + config.fastRetryMs;
        updateState(ObserverState.ATTACHING, "attach_sent");
        sendEvent("info", "attach_sent", "target=" + config.targetBotName);
    }

    private void onAttachFailed(long now, String reason) {
        retryCount += 1;
        long delay = computeRetryDelayMs(retryCount);
        nextRetryAtMs = now + delay;
        attachDeadlineAtMs = 0;

        updateState(ObserverState.REBINDING, reason);
        sendEvent(
                "warn",
                "attach_retry",
                "reason=" + reason + ", retry=" + retryCount + ", next_retry_ms=" + delay
        );
    }

    private long computeRetryDelayMs(int retry) {
        if (retry <= config.fastRetryMax) {
            return config.fastRetryMs;
        }

        int backoffSteps = Math.min(8, retry - config.fastRetryMax);
        long delay = (1L << backoffSteps) * 1000L;
        return Math.min(config.backoffMaxMs, Math.max(config.fastRetryMs, delay));
    }

    private void encodeAndSendFrames(NativeImage[] frames, long ts, boolean cameraBound, String dimension) {
        try {
            JsonArray framesArray = new JsonArray();

            // 遍历处理这三个视角（frame1, frame2, frame3）
            for (NativeImage frame : frames) {
                if (frame == null) continue;

                // 执行 JPEG 编码
                FrameEncoder.EncodedFrame encoded = FrameEncoder.encodeToJpegBase64(
                        frame,
                        config.frameWidth,
                        config.frameHeight,
                        config.jpegQuality
                );

                // 封装单张图片数据
                JsonObject frameObj = new JsonObject();
                frameObj.addProperty("jpeg_base64", encoded.base64);
                frameObj.addProperty("width", encoded.width);
                frameObj.addProperty("height", encoded.height);
                
                framesArray.add(frameObj);
            }

            // 构建核心外壳
            JsonObject root = new JsonObject();
            root.addProperty("source", "client_vision");
            root.addProperty("type", "vision_frame_batch"); // 标记为多图批处理模式

            JsonObject content = new JsonObject();
            content.addProperty("session_id", wsClient.getSessionId());
            content.addProperty("ts", ts);
            content.addProperty("target_name", config.targetBotName);
            content.addProperty("camera_bound", cameraBound);
            content.addProperty("dimension", dimension);
            
            // 关键：将所有图片放进 frames 数组中
            content.add("frames", framesArray);
            root.add("content", content);

            // 发送打包后的 JSON
            if (wsClient.sendJson(root.toString())) {
                lastFrameSentAtMs = ts;
            }

        } catch (Exception ex) {
            LOGGER.warn("Failed to encode/send frames bundle: {}", ex.getMessage());
            sendEvent("error", "bundle_send_failed", ex.getMessage());
        } finally {
            // 依次关闭所有 NativeImage 释放堆外内存
            for (NativeImage frame : frames) {
                if (frame != null) {
                    frame.close();
                }
            }
            // 编码器任务结束，重置忙碌状态
            encoderBusy.set(false);
        }
    }

    private void publishStatus(MinecraftClient client, long now, String trigger) {
        JsonObject root = new JsonObject();
        root.addProperty("source", "client_vision");
        root.addProperty("type", "vision_status");

        JsonObject content = new JsonObject();
        content.addProperty("session_id", wsClient.getSessionId());
        content.addProperty("ts", now);
        content.addProperty("state", state.name().toLowerCase());
        content.addProperty("reason", stateReason);
        content.addProperty("trigger", trigger);
        content.addProperty("target_name", config.targetBotName);
        content.addProperty("retry_index", retryCount);
        content.addProperty("next_retry_ms", Math.max(0L, nextRetryAtMs - now));
        content.addProperty("is_spectator", client.player != null && client.player.isSpectator());
        content.addProperty("bound_target", getCameraEntityName(client));
        content.addProperty("camera_bound", isCameraBoundToTarget(client));
        content.addProperty("dimension", currentDimension);
        content.addProperty("connected", wsClient.isConnected());
        content.addProperty("last_frame_age_ms", lastFrameSentAtMs == 0 ? -1 : (now - lastFrameSentAtMs));
        if (!wsClient.getLastError().isBlank()) {
            content.addProperty("ws_error", wsClient.getLastError());
        }

        root.add("content", content);
        wsClient.sendJson(root.toString());
    }

    private void sendEvent(String level, String event, String detail) {
        JsonObject root = new JsonObject();
        root.addProperty("source", "client_vision");
        root.addProperty("type", "vision_event");

        JsonObject content = new JsonObject();
        content.addProperty("session_id", wsClient.getSessionId());
        content.addProperty("ts", System.currentTimeMillis());
        content.addProperty("level", level);
        content.addProperty("event", event);
        content.addProperty("detail", detail == null ? "" : detail);

        root.add("content", content);
        wsClient.sendJson(root.toString());
    }

    private void sendChatCommand(ClientPlayerEntity player, String command) {
        if (player.networkHandler == null) {
            return;
        }
        player.networkHandler.sendPacket(new ChatMessageC2SPacket(command));
        LOGGER.info("Observer command sent: {}", command);
    }

    private PlayerEntity findTargetPlayer(MinecraftClient client, String targetName) {
        if (client.world == null) {
            return null;
        }
        for (PlayerEntity player : client.world.getPlayers()) {
            if (player.getEntityName().equalsIgnoreCase(targetName)) {
                return player;
            }
        }
        return null;
    }

    private boolean isCameraBoundToTarget(MinecraftClient client) {
        Entity cameraEntity = client.getCameraEntity();
        if (cameraEntity == null) {
            return false;
        }
        return cameraEntity.getEntityName().equalsIgnoreCase(config.targetBotName);
    }

    private String getCameraEntityName(MinecraftClient client) {
        Entity cameraEntity = client.getCameraEntity();
        if (cameraEntity == null) {
            return "none";
        }
        return cameraEntity.getEntityName();
    }

    private void updateState(ObserverState nextState, String reason) {
        if (this.state == nextState && this.stateReason.equals(reason)) {
            return;
        }
        this.state = nextState;
        this.stateReason = reason;
        LOGGER.info("Observer state -> {} ({})", nextState, reason);
    }
}
