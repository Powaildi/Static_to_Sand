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
    public static void isolatedPhaseOneBlockWaits(GameTestHelper helper) {
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

        helper.succeedWhen(() -> {
            boolean atCenter = helper.getBlockState(new BlockPos(5, 1, 5)).is(Blocks.DIAMOND_BLOCK);
            boolean atAway = helper.getBlockState(new BlockPos(6, 1, 5)).is(Blocks.DIAMOND_BLOCK);
            if (atCenter && !atAway) {
                return;
            }
            throw new GameTestAssertException("isolated phase-one block did not wait");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void isolatedPhaseTwoBlockReachesCenter(GameTestHelper helper) {
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
            boolean startEmpty = helper.getBlockState(new BlockPos(6, 1, 5)).isAir();
            centerTicks[0] = atCenter && startEmpty ? centerTicks[0] + 1 : 0;
            if (centerTicks[0] >= 2) {
                return;
            }
            throw new GameTestAssertException("isolated phase-two block did not reach center");
        });
    }
}
