package com.powaildi.statictosand;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@SuppressWarnings("null")
public final class StaticToSandCommands {
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("statictosand")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(context -> triggerStaticToSand(context, null, null, null))
                        .then(Commands.argument("maxBlocks", IntegerArgumentType.integer(1))
                                .executes(context -> triggerStaticToSand(
                                        context, "maxBlocks", null, null))
                                .then(Commands.argument("approximateMatching", BoolArgumentType.bool())
                                        .executes(context -> triggerStaticToSand(
                                                context, "maxBlocks", "approximateMatching", null))
                                        .then(Commands.argument(
                                                        "protectBlockEntities", BoolArgumentType.bool())
                                                .executes(context -> triggerStaticToSand(
                                                        context, "maxBlocks", "approximateMatching",
                                                        "protectBlockEntities")))))));

        var positionTarget = Commands.argument("target", BlockPosArgument.blockPos())
                .executes(context -> triggerAttraction(
                        context, BlockPosArgument.getLoadedBlockPos(context, "target"),
                        null, null, null));
        var positionTargetMax = Commands.argument("maxBlocks", IntegerArgumentType.integer(1))
                .executes(context -> triggerAttraction(
                        context, BlockPosArgument.getLoadedBlockPos(context, "target"),
                        "maxBlocks", null, null));
        var positionTargetApproximate = Commands.argument(
                        "approximateMatching", BoolArgumentType.bool())
                .executes(context -> triggerAttraction(
                        context, BlockPosArgument.getLoadedBlockPos(context, "target"),
                        "maxBlocks", "approximateMatching", null));
        positionTargetApproximate.then(Commands.argument(
                        "protectBlockEntities", BoolArgumentType.bool())
                .executes(context -> triggerAttraction(
                        context, BlockPosArgument.getLoadedBlockPos(context, "target"),
                        "maxBlocks", "approximateMatching", "protectBlockEntities")));
        positionTargetMax.then(positionTargetApproximate);
        positionTarget.then(positionTargetMax);

        var entityTarget = Commands.argument("targetEntity", EntityArgument.entity())
                .executes(context -> triggerAttraction(
                        context, EntityArgument.getEntity(context, "targetEntity").blockPosition(),
                        null, null, null));
        var entityTargetMax = Commands.argument("maxBlocks", IntegerArgumentType.integer(1))
                .executes(context -> triggerAttraction(
                        context, EntityArgument.getEntity(context, "targetEntity").blockPosition(),
                        "maxBlocks", null, null));
        var entityTargetApproximate = Commands.argument(
                        "approximateMatching", BoolArgumentType.bool())
                .executes(context -> triggerAttraction(
                        context, EntityArgument.getEntity(context, "targetEntity").blockPosition(),
                        "maxBlocks", "approximateMatching", null));
        entityTargetApproximate.then(Commands.argument(
                        "protectBlockEntities", BoolArgumentType.bool())
                .executes(context -> triggerAttraction(
                        context, EntityArgument.getEntity(context, "targetEntity").blockPosition(),
                        "maxBlocks", "approximateMatching", "protectBlockEntities")));
        entityTargetMax.then(entityTargetApproximate);
        entityTarget.then(entityTargetMax);

        event.getDispatcher().register(Commands.literal("statictoattraction")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("source", BlockPosArgument.blockPos())
                        .then(positionTarget)
                        .then(entityTarget)));
    }

    private static int triggerStaticToSand(
            CommandContext<CommandSourceStack> context,
            String maxBlocksArgument, String approximateArgument, String protectArgument)
            throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        int maxBlocks = maxBlocksArgument == null
                ? Config.STATIC_TO_SAND_MAX_BLOCKS.getAsInt()
                : IntegerArgumentType.getInteger(context, maxBlocksArgument);
        boolean approximateMatching = approximateArgument != null
                && BoolArgumentType.getBool(context, approximateArgument);
        boolean protectBlockEntities = protectArgument != null
                && BoolArgumentType.getBool(context, protectArgument);

        StaticToSandEvent event = new StaticToSandEvent(
                source.getLevel(), BlockPosArgument.getLoadedBlockPos(context, "pos"),
                source.getPlayer(), maxBlocks, approximateMatching, protectBlockEntities);
        NeoForge.EVENT_BUS.post(event);
        if (event.isCanceled()) {
            source.sendFailure(Component.translatable("statictosand.command.static_to_sand.failed"));
            return 0;
        }

        source.sendSuccess(
                () -> Component.translatable("statictosand.command.static_to_sand.success"), true);
        return 1;
    }

    private static int triggerAttraction(
            CommandContext<CommandSourceStack> context, BlockPos target,
            String maxBlocksArgument, String approximateArgument, String protectArgument)
            throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        if (!(source.getLevel() instanceof ServerLevel level)) {
            source.sendFailure(Component.translatable("statictosand.command.attraction.invalid_level"));
            return 0;
        }

        BlockPos sourcePos = BlockPosArgument.getLoadedBlockPos(context, "source");
        if (level.getBlockState(sourcePos).isAir()) {
            source.sendFailure(Component.translatable("statictosand.command.attraction.source_is_air"));
            return 0;
        }

        int maxBlocks = maxBlocksArgument == null
                ? Config.STATIC_TO_SAND_MAX_BLOCKS.getAsInt()
                : IntegerArgumentType.getInteger(context, maxBlocksArgument);
        boolean approximateMatching = approximateArgument != null
                && BoolArgumentType.getBool(context, approximateArgument);
        boolean protectBlockEntities = protectArgument != null
                && BoolArgumentType.getBool(context, protectArgument);

        if (!StaticToSandTask.triggerAttraction(
                level, sourcePos, target, maxBlocks, approximateMatching, protectBlockEntities)) {
            source.sendFailure(Component.translatable("statictosand.command.attraction.no_blocks"));
            return 0;
        }

        source.sendSuccess(
                () -> Component.translatable("statictosand.command.attraction.success"), true);
        return 1;
    }
}
