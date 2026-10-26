package com.powaildi.statictosand;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

@Mod(value = StatictoSand.MODID, dist = Dist.CLIENT)
@SuppressWarnings("null")
public class StatictoSandClient {
    public StatictoSandClient(ModContainer container) {
        NeoForge.EVENT_BUS.addListener(this::onMouseScrolled);
        NeoForge.EVENT_BUS.addListener(this::onItemTooltip);
        // Allows NeoForge to create a config screen for this mod's configs.
        // The config screen is accessed by going to the Mods screen > clicking on your mod > clicking on config.
        // Do not forget to add translations for your config options to the en_us.json file.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    private void onItemTooltip(ItemTooltipEvent event) {
        if (!event.getItemStack().is(StatictoSand.STATIC_TO_SAND.get())) {
            return;
        }

        var tooltip = event.getToolTip();
        if (Screen.hasShiftDown()) {
            tooltip.add(Component.translatable("statictosand.tooltip.right_click",
                    control("statictosand.tooltip.control.right_click")).withStyle(ChatFormatting.GRAY));
            tooltip.add(separator());
            tooltip.add(Component.translatable("statictosand.tooltip.shift_right_click",
                    control("statictosand.tooltip.control.shift_right_click")).withStyle(ChatFormatting.GRAY));
            tooltip.add(separator());
            tooltip.add(Component.translatable("statictosand.tooltip.shift_left_click",
                    control("statictosand.tooltip.control.shift_left_click")).withStyle(ChatFormatting.GRAY));
            tooltip.add(separator());
            tooltip.add(Component.translatable("statictosand.tooltip.shift_scroll",
                    control("statictosand.tooltip.control.shift_scroll")).withStyle(ChatFormatting.GRAY));
        } else {
            tooltip.add(Component.translatable("statictosand.tooltip.shift",
                    control("statictosand.tooltip.control.shift")).withStyle(ChatFormatting.GRAY));
        }
    }

    private static Component separator() {
        return Component.translatable("statictosand.tooltip.separator").withStyle(ChatFormatting.GRAY);
    }

    private static Component control(String translationKey) {
        return Component.literal("[")
                .append(Component.translatable(translationKey).withStyle(ChatFormatting.AQUA))
                .append("]");
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
