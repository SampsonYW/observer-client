package com.animabot.visionobserver;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public final class ObserverConfig {
    // 存放从 .env 文件中读取到的变量
    private static final Map<String, String> dotEnvMap = new HashMap<>();

    public final String wsUrl;
    public final String targetBotName;
    public final int fps;
    public final int frameWidth;
    public final int frameHeight;
    public final float jpegQuality;
    public final long checkIntervalMs;
    public final long attachTimeoutMs;
    public final int fastRetryMax;
    public final long fastRetryMs;
    public final long backoffMaxMs;
    public final long dimensionSettleMs;
    public final long statusIntervalMs;

    private ObserverConfig(
            String wsUrl,
            String targetBotName,
            int fps,
            int frameWidth,
            int frameHeight,
            float jpegQuality,
            long checkIntervalMs,
            long attachTimeoutMs,
            int fastRetryMax,
            long fastRetryMs,
            long backoffMaxMs,
            long dimensionSettleMs,
            long statusIntervalMs
    ) {
        this.wsUrl = wsUrl;
        this.targetBotName = targetBotName;
        this.fps = fps;
        this.frameWidth = frameWidth;
        this.frameHeight = frameHeight;
        this.jpegQuality = jpegQuality;
        this.checkIntervalMs = checkIntervalMs;
        this.attachTimeoutMs = attachTimeoutMs;
        this.fastRetryMax = fastRetryMax;
        this.fastRetryMs = fastRetryMs;
        this.backoffMaxMs = backoffMaxMs;
        this.dimensionSettleMs = dimensionSettleMs;
        this.statusIntervalMs = statusIntervalMs;
    }

    /**
     * 加载当前运行目录或父目录下的 .env 文件
     */
    private static void loadDotEnv() {
        // 尝试当前目录以及上一级目录（针对 Gradle 子项目结构）
        File[] targets = { new File(".env"), new File("../.env") };
        
        for (File file : targets) {
            if (file.exists()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        // 忽略空行和注释
                        if (line.isEmpty() || line.startsWith("#")) continue;
                        
                        int sep = line.indexOf('=');
                        if (sep > 0) {
                            String key = line.substring(0, sep).trim();
                            String value = line.substring(sep + 1).trim();
                            // 去除可能的引号
                            if (value.startsWith("\"") && value.endsWith("\"")) {
                                value = value.substring(1, value.length() - 1);
                            }
                            dotEnvMap.put(key, value);
                        }
                    }
                    System.out.println("Loaded config from: " + file.getAbsolutePath());
                    break; // 找到一个就停止
                } catch (IOException e) {
                    System.err.println("Failed to read .env file: " + e.getMessage());
                }
            }
        }
    }

    public static ObserverConfig fromEnv() {
        // 1. 先加载文件
        loadDotEnv();

        // 2. 正常初始化
        return new ObserverConfig(
                env("OBS_WS_URL", "ws://127.0.0.1:8000/ws/vision"),
                env("OBS_TARGET_BOT", "animabot"),
                parseInt(env("OBS_FPS", "60"), 60, 1, 60),
                parseInt(env("OBS_FRAME_WIDTH", "1920"), 1920, 160, 3840),
                parseInt(env("OBS_FRAME_HEIGHT", "1080"), 1080, 90, 2160),
                parseFloat(env("OBS_JPEG_QUALITY", "0.6"), 0.6f, 0.1f, 1.0f),
                parseLong(env("OBS_CHECK_INTERVAL_MS", "1000"), 1000L, 200L, 10000L),
                parseLong(env("OBS_ATTACH_TIMEOUT_MS", "3000"), 3000L, 500L, 60000L),
                parseInt(env("OBS_FAST_RETRY_MAX", "8"), 8, 0, 100),
                parseLong(env("OBS_FAST_RETRY_MS", "1200"), 1200L, 200L, 30000L),
                parseLong(env("OBS_BACKOFF_MAX_MS", "15000"), 15000L, 1000L, 120000L),
                parseLong(env("OBS_DIMENSION_SETTLE_MS", "2500"), 2500L, 200L, 30000L),
                parseLong(env("OBS_STATUS_INTERVAL_MS", "1000"), 1000L, 200L, 10000L)
        );
    }

    private static String env(String key, String fallback) {
        // 优先从 .env 文件 Map 中取值
        String value = dotEnvMap.get(key);
        
        // 如果文件里没有，再从系统环境变量取
        if (value == null || value.isBlank()) {
            value = System.getenv(key);
        }

        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static int parseInt(String raw, int fallback, int min, int max) {
        try {
            int value = Integer.parseInt(raw);
            return Math.max(min, Math.min(max, value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static long parseLong(String raw, long fallback, long min, long max) {
        try {
            long value = Long.parseLong(raw);
            return Math.max(min, Math.min(max, value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static float parseFloat(String raw, float fallback, float min, float max) {
        try {
            float value = Float.parseFloat(raw);
            if (Float.isNaN(value) || Float.isInfinite(value)) {
                return fallback;
            }
            return Math.max(min, Math.min(max, value));
        } catch (Exception ignored) {
            return fallback;
        }
    }
}