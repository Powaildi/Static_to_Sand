package com.powaildi.statictosand;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

@Mod(value = StatictoSand.MODID, dist = Dist.CLIENT)
@SuppressWarnings("null")
public class StatictoSandClient {
    public StatictoSandClient(ModContainer container) {
        NeoForge.EVENT_BUS.addListener(this::onMouseScrolled);
        // Allows NeoForge to create a config screen for this mod's configs.
        // The config screen is accessed by going to the Mods screen > clicking on your mod > clicking on config.
        // Do not forget to add translations for your config options to the en_us.json file.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    private void onMouseScrolled(InputEvent.MouseScrollingEvent event) {
        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (player == null
                || !player.isShiftKeyDown()
                || !player.getMainHandItem().is(StatictoSand.STATIC_TO_SAND.get())
                || event.getScrollDeltaY() == 0.0D) {
            return;
        }

        int direction = event.getScrollDeltaY() > 0.0D ? 1 : -1;
        PacketDistributor.sendToServer(new StaticToSandSettingsPayload(direction));
        event.setCanceled(true);
    }
}
