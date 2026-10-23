package com.powaildi.statictosand;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(StatictoSand.MODID)
@PrefixGameTestTemplate(false)
public final class StaticToSandGameTest {
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void runsFullPhaseCycle(GameTestHelper helper) {
        // A two-block corridor with closed ends and closed sides. The single
        // selected block can only move away once, then has to return to the
        // selection center after the phase switch.
        for (int x = 4; x <= 6; x++) {
            for (int z = 4; z <= 6; z++) {
                helper.setBlock(x, 0, z, Blocks.STONE);
            }
        }
        for (int x = 4; x <= 7; x++) {
            helper.setBlock(x, 1, 4, Blocks.STONE);
            helper.setBlock(x, 1, 6, Blocks.STONE);
        }
        helper.setBlock(4, 1, 5, Blocks.STONE);
        helper.setBlock(7, 1, 5, Blocks.STONE);
        helper.setBlock(5, 1, 5, Blocks.DIAMOND_BLOCK);

        BlockPos absPos = helper.absolutePos(new BlockPos(5, 1, 5));
        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        player.setItemInHand(InteractionHand.MAIN_HAND,
                new ItemStack(StatictoSand.STATIC_TO_SAND.get()));
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(absPos), Direction.UP, absPos, false);
        NeoForge.EVENT_BUS.post(
                new PlayerInteractEvent.RightClickBlock(player, InteractionHand.MAIN_HAND, absPos, hit));

        boolean[] movedAway = {false};
        int[] centerTicks = {0};
        helper.succeedWhen(() -> {
            boolean atAway = helper.getBlockState(new BlockPos(6, 1, 5)).is(Blocks.DIAMOND_BLOCK);
            boolean atCenter = helper.getBlockState(new BlockPos(5, 1, 5)).is(Blocks.DIAMOND_BLOCK);

            if (atAway) {
                movedAway[0] = true;
            }
            if (movedAway[0]) {
                centerTicks[0] = atCenter && !atAway ? centerTicks[0] + 1 : 0;
            }
            if (movedAway[0] && centerTicks[0] >= 2) {
                return;
            }
            throw new GameTestAssertException("block has not completed the phase cycle");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void shiftClickUsesPlayerCenterAndPhaseTwo(GameTestHelper helper) {
        for (int x = 4; x <= 6; x++) {
            for (int z = 4; z <= 6; z++) {
                helper.setBlock(x, 0, z, Blocks.STONE);
            }
        }
        helper.setBlock(6, 1, 5, Blocks.DIAMOND_BLOCK);

        BlockPos absPos = helper.absolutePos(new BlockPos(6, 1, 5));
        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        player.setPos(absPos.getX() - 0.5D, absPos.getY(), absPos.getZ() + 0.5D);
        player.setShiftKeyDown(true);
        player.setItemInHand(InteractionHand.MAIN_HAND,
                new ItemStack(StatictoSand.STATIC_TO_SAND.get()));
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(absPos), Direction.UP, absPos, false);
        NeoForge.EVENT_BUS.post(
                new PlayerInteractEvent.RightClickBlock(player, InteractionHand.MAIN_HAND, absPos, hit));

        int[] centerTicks = {0};
        helper.succeedWhen(() -> {
            boolean atCenter = helper.getBlockState(new BlockPos(5, 1, 5)).is(Blocks.DIAMOND_BLOCK);
            boolean awayEmpty = helper.getBlockState(new BlockPos(6, 1, 5)).isAir();
            centerTicks[0] = atCenter && awayEmpty ? centerTicks[0] + 1 : 0;
            if (centerTicks[0] >= 2) {
                return;
            }
            throw new GameTestAssertException("shift-clicked block has not reached the player center");
        });
    }
}
