package com.powaildi.statictosand;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.Event;

/**
 * Requests that the connected block cluster at {@link #getPos()} be moved.
 *
 * <p>Post this event on {@code NeoForge.EVENT_BUS} to trigger a task without
 * requiring the static_to_sand item interaction. The selection limit belongs
 * to the event so different callers can use different limits.</p>
 */
public final class StaticToSandEvent extends Event {
    private final ServerLevel level;
    private final BlockPos pos;
    private final Player player;
    private final int maxConnectedBlocks;

    public StaticToSandEvent(ServerLevel level, BlockPos pos, Player player, int maxConnectedBlocks) {
        if (maxConnectedBlocks < 1) {
            throw new IllegalArgumentException("maxConnectedBlocks must be at least 1");
        }
        this.level = level;
        this.pos = pos.immutable();
        this.player = player;
        this.maxConnectedBlocks = maxConnectedBlocks;
    }

    public ServerLevel getLevel() {
        return level;
    }

    public BlockPos getPos() {
        return pos;
    }

    public Player getPlayer() {
        return player;
    }

    public int getMaxConnectedBlocks() {
        return maxConnectedBlocks;
    }
}
