package com.powaildi.statictosand;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

@SuppressWarnings("null")
public record StaticToSandSettingsPayload(int direction) implements CustomPacketPayload {
    public static final Type<StaticToSandSettingsPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(StatictoSand.MODID, "settings"));
    public static final StreamCodec<RegistryFriendlyByteBuf, StaticToSandSettingsPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, StaticToSandSettingsPayload::direction,
                    StaticToSandSettingsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
