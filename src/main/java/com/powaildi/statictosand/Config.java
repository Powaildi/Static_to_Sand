package com.powaildi.statictosand;

import java.util.List;

import net.neoforged.neoforge.common.ModConfigSpec;

@SuppressWarnings("null")
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue STATIC_TO_SAND_MAX_BLOCKS = BUILDER
            .comment("Maximum number of connected blocks selected by the static_to_sand item")
            .defineInRange("staticToSandMaxBlocks", 1024, 1, Integer.MAX_VALUE);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> APPROXIMATE_MATCHING_TAGS = BUILDER
            .comment("Block tags used by StaticToSandEvent approximate matching")
            .defineList("approximateMatchingTags",
                    List.of("minecraft:dirt", "statictosand:approximate_stone",
                            "minecraft:terracotta"),
                    () -> "",
                    value -> value instanceof String);

    public static final ModConfigSpec.EnumValue<GravityBlockMode> GRAVITY_BLOCK_MODE = BUILDER
            .comment("Whether gravity blocks are handled as StaticToSandEvents")
            .defineEnum("gravityBlockMode", GravityBlockMode.NONE);

    public static final ModConfigSpec.IntValue GRAVITY_BLOCK_MAX_BLOCKS = BUILDER
            .comment("Maximum number of connected blocks selected for gravity block events")
            .defineInRange("gravityBlockMaxBlocks", 3, 1, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue HORIZONTAL_MOVE_LIMIT = BUILDER
            .comment("Number of horizontal moves before an entry is put into the waiting zone")
            .defineInRange("horizontalMoveLimit", 32, 1, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue ISOLATION_MOVE_LIMIT = BUILDER
            .comment("Minimum horizontal moves before an isolated entry enters the waiting zone")
            .defineInRange("isolationMoveLimit", 4, 0, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue ACTIVE_BLOCK_LIMIT = BUILDER
            .comment("Maximum number of blocks in the active zone")
            .defineInRange("activeBlockLimit", 4096, 1, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue WAITING_BLOCK_LIMIT = BUILDER
            .comment("Maximum number of blocks in the waiting zone")
            .defineInRange("waitingBlockLimit", 16384, 1, Integer.MAX_VALUE);

    public static final ModConfigSpec.BooleanValue FLUIDS_ARE_AIR = BUILDER
            .comment("Whether fluids count as air for movement targets")
            .define("fluidsAreAir", false);

    public static final ModConfigSpec.BooleanValue REPLACE_FALLING_BLOCK_WITH_FLUID = BUILDER
            .comment("Whether a falling block leaves a neighboring fluid source behind")
            .define("replaceFallingBlockWithFluid", false);

    static final ModConfigSpec SPEC = BUILDER.build();

    public enum GravityBlockMode {
        NONE,
        WHITELIST,
        BLACKLIST,
        ALL
    }
}
