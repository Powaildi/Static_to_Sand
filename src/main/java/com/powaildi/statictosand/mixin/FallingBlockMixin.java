package com.powaildi.statictosand.mixin;

import com.powaildi.statictosand.StaticToSandTask;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FallingBlock.class)
abstract class FallingBlockMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void statictosand$handleGravityBlock(
            BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo callbackInfo) {
        if (pos.getY() >= level.getMinBuildHeight()
                && FallingBlock.isFree(level.getBlockState(pos.below()))
                && StaticToSandTask.handleGravityBlock(level, pos, state)) {
            callbackInfo.cancel();
        }
    }
}
