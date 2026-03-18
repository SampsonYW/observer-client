package com.animabot.visionobserver;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VisionObserverMod implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("VisionObserverMod");

    private VisionWsClient wsClient;
    private ObserverController controller;

    @Override
    public void onInitializeClient() {
        ObserverConfig config = ObserverConfig.fromEnv();

        wsClient = new VisionWsClient(config.wsUrl);
        wsClient.start();

        controller = new ObserverController(config, wsClient);

        ClientTickEvents.END_CLIENT_TICK.register(controller::onTick);
        WorldRenderEvents.END.register(controller::onRenderEnd);

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            controller.shutdown();
            wsClient.stop();
        });

        LOGGER.info("Vision observer initialized: ws={}, target={}, fps={}",
                config.wsUrl,
                config.targetBotName,
                config.fps
        );
    }
}
