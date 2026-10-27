package com.powaildi.statictosand;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Moves a connected cluster of blocks one block per tick. Phase one pushes the
 * cluster away from its center; shift-click starts phase two toward the player.
 */
@SuppressWarnings("null")
public final class StaticToSandTask {
    private static StaticToSandTask INSTANCE;
    private static final Map<UUID, PlayerSettings> PLAYER_SETTINGS = new HashMap<>();
    private static final TagKey<Block> GRAVITY_BLOCK_WHITELIST = TagKey.create(
            Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath(StatictoSand.MODID, "gravity_block_whitelist"));
    private static final TagKey<Block> GRAVITY_BLOCK_BLACKLIST = TagKey.create(
            Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath(StatictoSand.MODID, "gravity_block_blacklist"));

    /** Only notify clients; no neighbour updates, scheduled ticks or drops. */
    private static final int SET_BLOCK_FLAGS = 2;

    /** Cardinal horizontal directions starting at +X: +X, +Z, -X, -Z. */
    private static final BlockPos[] HORIZONTAL_DIRECTIONS = {
            new BlockPos(1, 0, 0),
            new BlockPos(0, 0, 1),
            new BlockPos(-1, 0, 0),
            new BlockPos(0, 0, -1)
    };
    private static final BlockPos[] BFS_DIRECTIONS = {
            new BlockPos(0, 1, 0),
            new BlockPos(1, 0, 0),
            new BlockPos(0, 0, 1),
            new BlockPos(-1, 0, 0),
            new BlockPos(0, 0, -1),
            new BlockPos(0, -1, 0)
    };
    /** Horizontal neighbors plus the block directly above the trigger position. */
    private static final int[][] ACTIVATION_OFFSETS = {
            {-1, 0, 0},
            {0, 0, -1},
            {0, 0, 1},
            {1, 0, 0},
            {0, 1, 0}
    };
    private static final int PHASE_2_NEIGHBOR_WEIGHT = 12;
    private static final int PHASE_2_DISTANCE_WEIGHT = 2;

    private final List<Entry> active = new ArrayList<>();
    private final List<Entry> waiting = new ArrayList<>();
    /**
     * Entries are partitioned by world so a coordinate key only contains the
     * packed block position. This avoids allocating a composite key and
     * hashing the dimension on every neighbour lookup.
     */
    private final Map<ServerLevel, Map<Long, Entry>> index = new IdentityHashMap<>();
    private final Map<ResourceLocation, Long> lastLevelGameTimes = new HashMap<>();
    private final Set<ServerPlayer> participants = new HashSet<>();

    private boolean running;
    private int phase = 1;
    private long tickCount;
    private int totalBlocks;

    public StaticToSandTask() {
        INSTANCE = this;
    }

    public static boolean triggerAttraction(
            ServerLevel level, BlockPos sourcePos, BlockPos target, int maxBlocks,
            boolean approximateMatching, boolean protectBlockEntities) {
        StaticToSandTask task = INSTANCE;
        BlockState clickedState = level.getBlockState(sourcePos);
        List<TagKey<Block>> approximateTags = approximateMatching
                ? approximateMatchingTags(clickedState)
                : List.of();
        List<BlockPos> selected = task.selectBlocks(
                level, sourcePos, clickedState.getBlock(), approximateTags, maxBlocks);
        if (selected.isEmpty()) {
            return false;
        }

        if (!task.running) {
            task.resetTaskState();
        }
        task.addSelection(
                level, selected, new Center(target.getX(), target.getZ()), true,
                protectBlockEntities);
        task.phase = 2;
        task.running = true;
        return true;
    }

    @SubscribeEvent
    public void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (toggleApproximateMatching(event.getEntity(), event.getItemStack())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onLeftClickEmpty(PlayerInteractEvent.LeftClickEmpty event) {
        toggleApproximateMatching(event.getEntity(), event.getItemStack());
    }

    private static boolean toggleApproximateMatching(
            Player player, net.minecraft.world.item.ItemStack stack) {
        if (!player.isShiftKeyDown() || !stack.is(StatictoSand.STATIC_TO_SAND.get())) {
            return false;
        }

        PlayerSettings settings = settings(player);
        settings.approximateMatching = !settings.approximateMatching;
        player.displayClientMessage(
                Component.translatable("statictosand.message.approximate_matching")
                        .append(Component.translatable(settings.approximateMatching
                                ? "statictosand.value.true" : "statictosand.value.false")
                                .withStyle(settings.approximateMatching
                                        ? ChatFormatting.GREEN : ChatFormatting.RED)),
                true);
        return true;
    }

    public static void adjustSelectionLimit(ServerPlayer player, int direction) {
        if (direction == 0) {
            return;
        }

        PlayerSettings settings = settings(player);
        int current = settings.maxConnectedBlocks;
        int maximum = Config.STATIC_TO_SAND_MAX_BLOCKS.getAsInt();
        settings.maxConnectedBlocks = direction > 0
                ? Math.min(maximum, doubled(current))
                : Math.max(1, (current + 1) / 2);
        player.displayClientMessage(
                Component.translatable("statictosand.message.selection_limit")
                        .append(Component.literal(Integer.toString(settings.maxConnectedBlocks))
                                .withStyle(ChatFormatting.AQUA)),
                true);
    }

    private static int doubled(int value) {
        return value > Integer.MAX_VALUE / 2 ? Integer.MAX_VALUE : value * 2;
    }

    private static PlayerSettings settings(Player player) {
        return PLAYER_SETTINGS.computeIfAbsent(
                player.getUUID(), ignored -> new PlayerSettings(Config.STATIC_TO_SAND_MAX_BLOCKS.getAsInt()));
    }

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || event.getHand() != InteractionHand.MAIN_HAND
                || !event.getItemStack().is(StatictoSand.STATIC_TO_SAND.get())) {
            return;
        }

        NeoForge.EVENT_BUS.post(new StaticToSandEvent(
                level, event.getPos(), event.getEntity(),
                settings(event.getEntity()).maxConnectedBlocks,
                settings(event.getEntity()).approximateMatching));
    }

    @SubscribeEvent
    public void onStaticToSand(StaticToSandEvent event) {
        if (event.isCanceled()) {
            return;
        }

        ServerLevel level = event.getLevel();
        BlockState clickedState = level.getBlockState(event.getPos());
        if (clickedState.isAir()) {
            event.setCanceled(true);
            return;
        }

        Block block = clickedState.getBlock();
        List<TagKey<Block>> approximateTags = event.isApproximateMatching()
                ? approximateMatchingTags(clickedState)
                : List.of();
        List<BlockPos> selected = selectBlocks(
                level, event.getPos(), block, approximateTags, event.getMaxConnectedBlocks());
        if (selected.isEmpty()) {
            return;
        }

        activateWaitingEntry(level, event.getPos());

        Player player = event.getPlayer();
        boolean shift = player != null && player.isShiftKeyDown();
        Center center = shift
                ? new Center(player.getBlockX(), player.getBlockZ())
                : centerOf(selected);

        boolean startNewTask = !running;
        if (startNewTask) {
            resetTaskState();
        }

        addSelection(level, selected, center, shift, event.isProtectBlockEntities());

        if (player instanceof ServerPlayer serverPlayer && !(player instanceof FakePlayer)) {
            participants.add(serverPlayer);
        }

        if (startNewTask) {
            running = true;
            phase = shift ? 2 : 1;
            tickCount = 0;
        }
    }

    public static boolean handleGravityBlock(ServerLevel level, BlockPos pos, BlockState state) {
        if (!shouldHandleGravityBlock(state)) {
            return false;
        }

        NeoForge.EVENT_BUS.post(new StaticToSandEvent(
                level, pos, Config.GRAVITY_BLOCK_MAX_BLOCKS.getAsInt()));
        return true;
    }

    private static boolean shouldHandleGravityBlock(BlockState state) {
        return switch (Config.GRAVITY_BLOCK_MODE.get()) {
            case NONE -> false;
            case WHITELIST -> state.is(GRAVITY_BLOCK_WHITELIST);
            case BLACKLIST -> !state.is(GRAVITY_BLOCK_BLACKLIST);
            case ALL -> true;
        };
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (!running) {
            return;
        }

        Set<ServerLevel> levelsWithEntries = index.keySet();
        Set<ServerLevel> tickingLevels =
                Collections.newSetFromMap(new IdentityHashMap<>());
        for (ServerLevel level : levelsWithEntries) {
            if (hasLevelAdvanced(level)) {
                tickingLevels.add(level);
            }
        }
        if (tickingLevels.isEmpty()) {
            return;
        }

        tickCount++;

        List<Entry> pendingActive = new ArrayList<>();
        Set<Entry> pendingSet = new HashSet<>();

        checkWaitingIntegrity(tickingLevels, pendingActive, pendingSet);

        int snapshotLength = active.size();
        for (int i = 0; i < snapshotLength; i++) {
            Entry entry = active.get(i);
            if (tickingLevels.contains(entry.level)
                    && entry.zone == Zone.ACTIVE && tickCount >= entry.eligibleTick) {
                processEntry(entry, pendingActive, pendingSet);
            }
        }

        appendUnique(active, pendingActive);
        active.removeIf(entry -> entry.zone != Zone.ACTIVE);
        enforceQueueLimits(pendingActive, pendingSet);
        advancePhaseOrFinish();
    }

    private boolean hasLevelAdvanced(ServerLevel level) {
        ResourceLocation dimension = level.dimension().location();
        long gameTime = level.getGameTime();
        Long previousGameTime = lastLevelGameTimes.put(dimension, gameTime);
        return previousGameTime == null || gameTime > previousGameTime;
    }

    private List<BlockPos> selectBlocks(ServerLevel level, BlockPos start, Block block,
            List<TagKey<Block>> approximateTags, int maxConnectedBlocks) {
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        List<BlockPos> selected = new ArrayList<>();

        queue.add(start);
        visited.add(start);

        while (!queue.isEmpty() && selected.size() < maxConnectedBlocks) {
            BlockPos pos = queue.removeFirst();
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() != block
                    && !matchesApproximateTags(state, approximateTags)) {
                continue;
            }

            selected.add(pos);

            for (BlockPos direction : BFS_DIRECTIONS) {
                if (selected.size() >= maxConnectedBlocks) {
                    break;
                }
                BlockPos neighbor = pos.offset(direction);
                if (visited.add(neighbor)) {
                    queue.addLast(neighbor);
                }
            }
        }
        return selected;
    }

    private void addSelection(
            ServerLevel level, List<BlockPos> selected, Center center, boolean forcePhase2,
            boolean protectBlockEntities) {
        List<Entry> additions = new ArrayList<>();
        lastLevelGameTimes.putIfAbsent(level.dimension().location(), level.getGameTime());
        long eligibleTick = tickCount + 1;
        Map<Long, Entry> levelIndex = index.computeIfAbsent(
                level, ignored -> new HashMap<>(Math.max(16, (int) (selected.size() / 0.75f) + 1)));
        for (BlockPos pos : selected) {
            long key = pos.asLong();
            if (levelIndex.containsKey(key)) {
                Entry existing = levelIndex.get(key);
                if (existing.zone == Zone.WAITING) {
                    activateWaiting(existing, null);
                    additions.add(existing);
                }
                continue;
            }

            Block block = level.getBlockState(pos).getBlock();
            ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(block);
            Entry entry = new Entry(
                    level, pos, block, blockId, center, forcePhase2, protectBlockEntities, eligibleTick);
            levelIndex.put(key, entry);
            additions.add(entry);
            totalBlocks++;
        }

        if (additions.isEmpty()) {
            return;
        }

        additions.sort(entryComparator());
        if (!running) {
            active.clear();
        }
        appendUnique(active, additions);
    }

    private void activateWaitingEntry(ServerLevel level, BlockPos pos) {
        Entry entry = getEntry(level, pos.getX(), pos.getY(), pos.getZ());
        if (entry == null || entry.zone != Zone.WAITING) {
            return;
        }

        activateWaiting(entry, null);
        appendUnique(active, List.of(entry));
    }

    /**
     * Moves a parked entry back into the active zone and grants it one move.
     *
     * <p>{@link #park} keeps {@code horizontalMoves} and
     * {@code horizontalLimitReached} as they were, so without help the
     * isolation rule in {@link #processEntry} parks a woken entry again on its
     * very next tick, purely because it has no ACTIVE neighbour, before it ever
     * moves. The grace flag skips that rule once and is cleared by the first
     * move (or by the next {@link #park}), so the activation is never a no-op
     * while the isolation budget itself is left untouched and the block still
     * settles after that single move.</p>
     */
    private void activateWaiting(Entry entry, BlockPos preferredDirection) {
        entry.zone = Zone.ACTIVE;
        entry.eligibleTick = tickCount + 1;
        entry.preferredDirection = preferredDirection;
        entry.lastPos = null;
        entry.isolationGrace = true;
        waiting.remove(entry);
    }

    private static List<TagKey<Block>> approximateMatchingTags(BlockState triggerState) {
        List<TagKey<Block>> matchingTags = new ArrayList<>();
        for (String tagId : Config.APPROXIMATE_MATCHING_TAGS.get()) {
            ResourceLocation location = ResourceLocation.tryParse(tagId);
            if (location == null) {
                continue;
            }
            TagKey<Block> tag = TagKey.create(Registries.BLOCK, location);
            if (triggerState.is(tag)) {
                matchingTags.add(tag);
            }
        }
        return matchingTags;
    }

    private static boolean matchesApproximateTags(
            BlockState state, List<TagKey<Block>> approximateTags) {
        for (TagKey<Block> tag : approximateTags) {
            if (state.is(tag)) {
                return true;
            }
        }
        return false;
    }

    private void processEntry(Entry entry, List<Entry> pendingActive, Set<Entry> pendingSet) {
        ServerLevel level = entry.level;
        Block blockAtEntry = level.getBlockState(entry.pos).getBlock();
        if (blockAtEntry != entry.block) {
            removeEntry(entry, pendingActive, pendingSet);
            return;
        }

        int effectivePhase = entry.forcePhase2 ? 2 : phase;
        BlockPos target = null;
        boolean movedDown = false;

        BlockPos down = entry.pos.below();
        boolean downIsAir = isAir(level, down);
        if (downIsAir && !down.equals(entry.lastPos)) {
            target = down;
            movedDown = true;
        } else {
            int fallbackStartDirection = -1;
            PreferredMove preferredMove =
                    consumePreferredHorizontalTarget(entry, effectivePhase);
            target = preferredMove.target();
            fallbackStartDirection = preferredMove.fallbackStartDirection();
            if (target == null
                    && !entry.isolationGrace
                    && entry.horizontalMoves >= Config.ISOLATION_MOVE_LIMIT.getAsInt()
                    && countActiveNeighbors(level, entry.pos, entry.blockId) == 0) {
                park(entry);
                return;
            }
            if (target == null && !(effectivePhase == 2
                    && entry.pos.getX() == entry.centerX
                    && entry.pos.getZ() == entry.centerZ)) {
                int fallbackDirection = fallbackStartDirection;
                target = chooseHorizontalTarget(entry, effectivePhase, fallbackDirection);
            }
        }

        if (target == null) {
            park(entry);
            return;
        }

        moveEntry(entry, target, movedDown, pendingActive, pendingSet);
    }

    private BlockPos chooseHorizontalTarget(
            Entry entry, int effectivePhase, int fallbackStartDirection) {
        ServerLevel level = entry.level;
        long currentDistance = effectivePhase == 2
                ? chebyshevDistance(entry.pos, entry.centerX, entry.centerZ) : 0L;
        int startDirection = 0;
        if (effectivePhase == 1) {
            startDirection = fallbackStartDirection >= 0
                    ? fallbackStartDirection
                    : ThreadLocalRandom.current().nextInt(HORIZONTAL_DIRECTIONS.length);
        }
        Candidate best = null;

        for (int directionIndex = 0; directionIndex < HORIZONTAL_DIRECTIONS.length; directionIndex++) {
            int selectedDirection = effectivePhase == 1
                    ? (startDirection + directionIndex) % HORIZONTAL_DIRECTIONS.length
                    : directionIndex;
            BlockPos direction = HORIZONTAL_DIRECTIONS[selectedDirection];
            BlockPos candidatePos = entry.pos.offset(direction);

            if (!isAir(level, candidatePos) || candidatePos.equals(entry.lastPos)) {
                continue;
            }

            long candidateDistance = effectivePhase == 2
                    ? chebyshevDistance(candidatePos, entry.centerX, entry.centerZ) : 0L;
            if (effectivePhase == 2 && candidateDistance > currentDistance) {
                continue;
            }

            if (effectivePhase == 1) {
                return candidatePos;
            }

            long score = 0L;
            int neighborCount = countNeighbors(level, candidatePos, entry.blockId);
            score = -(long) PHASE_2_DISTANCE_WEIGHT * (candidateDistance - currentDistance)
                    + (long) PHASE_2_NEIGHBOR_WEIGHT * neighborCount;
            Candidate candidate = new Candidate(
                    candidatePos, candidateDistance, selectedDirection, score);
            if (best == null || compareCandidates(candidate, best) < 0) {
                best = candidate;
            }
        }

        return best == null ? null : best.pos;
    }

    private int countNeighbors(ServerLevel level, BlockPos pos, ResourceLocation blockId) {
        int count = 0;
        for (BlockPos direction : HORIZONTAL_DIRECTIONS) {
            Entry neighbor = getEntry(level,
                    pos.getX() + direction.getX(),
                    pos.getY() + direction.getY(),
                    pos.getZ() + direction.getZ());
            if (neighbor != null
                    && (neighbor.zone == Zone.ACTIVE || neighbor.zone == Zone.WAITING)
                    && neighbor.blockId.equals(blockId)) {
                count++;
            }
        }
        return count;
    }

    private int countActiveNeighbors(ServerLevel level, BlockPos pos, ResourceLocation blockId) {
        int count = 0;
        for (BlockPos direction : HORIZONTAL_DIRECTIONS) {
            Entry neighbor = getEntry(level,
                    pos.getX() + direction.getX(),
                    pos.getY() + direction.getY(),
                    pos.getZ() + direction.getZ());
            if (neighbor != null
                    && neighbor.zone == Zone.ACTIVE
                    && neighbor.blockId.equals(blockId)) {
                count++;
            }
        }
        return count;
    }

    private PreferredMove consumePreferredHorizontalTarget(Entry entry, int effectivePhase) {
        BlockPos preferredDirection = entry.preferredDirection;
        entry.preferredDirection = null;
        if (preferredDirection == null) {
            return new PreferredMove(null, -1);
        }

        int preferredDirectionIndex = horizontalDirectionIndex(preferredDirection);
        if (preferredDirectionIndex < 0) {
            return new PreferredMove(null, -1);
        }

        BlockPos candidatePos = entry.pos.offset(preferredDirection);
        if (!isAir(entry.level, candidatePos) || candidatePos.equals(entry.lastPos)) {
            return new PreferredMove(
                    null, (preferredDirectionIndex + 1) % HORIZONTAL_DIRECTIONS.length);
        }

        if (effectivePhase == 2) {
            long currentDistance = chebyshevDistance(entry.pos, entry.centerX, entry.centerZ);
            long candidateDistance = chebyshevDistance(candidatePos, entry.centerX, entry.centerZ);
            if (candidateDistance > currentDistance) {
                return new PreferredMove(
                        null, (preferredDirectionIndex + 1) % HORIZONTAL_DIRECTIONS.length);
            }
        }
        return new PreferredMove(candidatePos, -1);
    }

    private void moveEntry(Entry entry, BlockPos target, boolean movedDown,
            List<Entry> pendingActive, Set<Entry> pendingSet) {
        ServerLevel level = entry.level;
        BlockPos oldPos = entry.pos;
        CompoundTag blockEntityData = null;
        if (entry.protectBlockEntities) {
            BlockEntity blockEntity = level.getBlockEntity(oldPos);
            if (blockEntity != null) {
                BlockEntity sourceBlockEntity = blockEntity;
                blockEntityData = sourceBlockEntity.saveCustomOnly(level.registryAccess());
            }
        }

        BlockState replacement = Blocks.AIR.defaultBlockState();
        if (movedDown && Config.REPLACE_FALLING_BLOCK_WITH_FLUID.getAsBoolean()) {
            replacement = neighboringFluidSource(level, oldPos);
        }
        BlockState oldBlockState = replacement;
        BlockState targetState = entry.block.defaultBlockState();
        level.setBlock(oldPos, oldBlockState, SET_BLOCK_FLAGS);
        level.setBlock(target, targetState, SET_BLOCK_FLAGS);
        CompoundTag savedBlockEntityData = blockEntityData;
        if (savedBlockEntityData != null) {
            BlockEntity targetBlockEntity = level.getBlockEntity(target);
            if (targetBlockEntity != null) {
                targetBlockEntity.loadCustomOnly(savedBlockEntityData, level.registryAccess());
                targetBlockEntity.setChanged();
            }
        }

        removeEntryAt(level, oldPos);
        indexFor(level).put(target.asLong(), entry);
        entry.pos = target;
        entry.lastPos = oldPos;
        entry.zone = Zone.ACTIVE;
        entry.isolationGrace = false;

        if (movedDown) {
            entry.horizontalMoves = 0;
        } else {
            entry.horizontalMoves++;
            if (entry.horizontalMoves >= Config.HORIZONTAL_MOVE_LIMIT.getAsInt()) {
                entry.horizontalLimitReached = true;
                park(entry);
            }
        }
        BlockPos movedDirection = horizontalDirection(oldPos, target);
        activateWaitingAround(
                entry.level, oldPos, movedDirection, pendingActive, pendingSet);
    }

    private void park(Entry entry) {
        entry.zone = Zone.WAITING;
        entry.isolationGrace = false;
        if (!waiting.contains(entry)) {
            waiting.add(entry);
        }
    }

    private void checkWaitingIntegrity(
            Set<ServerLevel> tickingLevels, List<Entry> pendingActive, Set<Entry> pendingSet) {
        for (Entry entry : new ArrayList<>(waiting)) {
            if (tickingLevels.contains(entry.level)
                    && entry.zone == Zone.WAITING
                    && entry.level.getBlockState(entry.pos).getBlock() != entry.block) {
                removeEntry(entry, pendingActive, pendingSet);
            }
        }
    }

    private void enforceQueueLimits(List<Entry> pendingActive, Set<Entry> pendingSet) {
        appendUnique(active, pendingActive);
        active.removeIf(entry -> entry.zone != Zone.ACTIVE);
        waiting.removeIf(entry -> entry.zone != Zone.WAITING);

        while (active.size() > Config.ACTIVE_BLOCK_LIMIT.getAsInt()) {
            Entry oldest = active.remove(0);
            if (oldest.zone == Zone.ACTIVE) {
                oldest.zone = Zone.WAITING;
                waiting.add(oldest);
            }
        }

        while (waiting.size() > Config.WAITING_BLOCK_LIMIT.getAsInt()) {
            Entry oldest = waiting.remove(0);
            if (oldest.zone == Zone.WAITING) {
                removeEntry(oldest, pendingActive, pendingSet);
            }
        }

        // Activations caused by waiting-zone eviction take effect next tick.
        appendUnique(active, pendingActive);
    }

    private void removeEntry(Entry entry, List<Entry> pendingActive, Set<Entry> pendingSet) {
        entry.zone = Zone.REMOVED;
        removeEntryAt(entry.level, entry.pos);
        activateWaitingAround(entry.level, entry.pos, null, pendingActive, pendingSet);
    }

    private void activateWaitingAround(ServerLevel level, BlockPos center, BlockPos preferredDirection,
            List<Entry> pendingActive, Set<Entry> pendingSet) {
        for (int[] offset : ACTIVATION_OFFSETS) {
                Entry waitingEntry = getEntry(level,
                    center.getX() + offset[0],
                    center.getY() + offset[1],
                        center.getZ() + offset[2]);
            if (waitingEntry != null
                    && waitingEntry.zone == Zone.WAITING
                    && (!waitingEntry.horizontalLimitReached || offset[1] == 1)) {
                waitingEntry.zone = Zone.ACTIVE;
                waitingEntry.preferredDirection = preferredDirection;
                if (pendingSet.add(waitingEntry)) {
                    pendingActive.add(waitingEntry);
                }
            }
        }
    }

    private static BlockPos horizontalDirection(BlockPos from, BlockPos to) {
        if (from.getY() != to.getY()) {
            return null;
        }
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        if (Math.abs(dx) + Math.abs(dz) != 1) {
            return null;
        }
        return new BlockPos(dx, 0, dz);
    }

    private static int horizontalDirectionIndex(BlockPos direction) {
        for (int i = 0; i < HORIZONTAL_DIRECTIONS.length; i++) {
            if (HORIZONTAL_DIRECTIONS[i].equals(direction)) {
                return i;
            }
        }
        return -1;
    }

    private void appendUnique(List<Entry> target, List<Entry> additions) {
        Set<Entry> existing = new HashSet<>(target);
        for (Entry entry : additions) {
            if (entry.zone == Zone.ACTIVE && existing.add(entry)) {
                target.add(entry);
            }
        }
    }

    private void advancePhaseOrFinish() {
        if (!active.isEmpty()) {
            return;
        }

        finishTask();
    }

    private void finishTask() {
        if (!participants.isEmpty()) {
            participants.forEach(player -> player.sendSystemMessage(
                    Component.translatable("statictosand.message.task_finished", totalBlocks, tickCount)));
        }
        resetTaskState();
    }

    private void resetTaskState() {
        running = false;
        phase = 1;
        tickCount = 0;
        totalBlocks = 0;
        participants.clear();
        active.clear();
        waiting.clear();
        index.clear();
        lastLevelGameTimes.clear();
    }

    private static Center centerOf(List<BlockPos> selected) {
        int minX = selected.get(0).getX();
        int maxX = minX;
        int minZ = selected.get(0).getZ();
        int maxZ = minZ;

        for (BlockPos pos : selected) {
            minX = Math.min(minX, pos.getX());
            maxX = Math.max(maxX, pos.getX());
            minZ = Math.min(minZ, pos.getZ());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        return new Center(Math.floorDiv(minX + maxX, 2), Math.floorDiv(minZ + maxZ, 2));
    }

    private static boolean isAir(ServerLevel level, BlockPos pos) {
        if (pos.getY() < level.getMinBuildHeight() || pos.getY() >= level.getMaxBuildHeight()) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        return state.isAir() || (Config.FLUIDS_ARE_AIR.getAsBoolean()
                && !level.getFluidState(pos).isEmpty());
    }

    private static BlockState neighboringFluidSource(ServerLevel level, BlockPos pos) {
        for (BlockPos direction : HORIZONTAL_DIRECTIONS) {
            var fluidState = level.getFluidState(pos.offset(direction));
            if (fluidState.isSource()) {
                return fluidState.createLegacyBlock();
            }
        }
        return Blocks.AIR.defaultBlockState();
    }

    private static long chebyshevDistance(BlockPos pos, int centerX, int centerZ) {
        long dx = Math.abs((long) pos.getX() - centerX);
        long dz = Math.abs((long) pos.getZ() - centerZ);
        return Math.max(dx, dz);
    }

    /** Maps an offset to the nearest cardinal direction. */
    private static int ringIndex(int dx, int dz) {
        if (dx == 0 && dz == 0) {
            return 0;
        }
        double angle = Math.atan2(dz, dx);
        if (angle < 0.0D) {
            angle += Math.PI * 2.0D;
        }
        return (int) Math.floor((angle + Math.PI / 4.0D) / (Math.PI / 2.0D)) & 3;
    }

    private static int compareCandidates(Candidate left, Candidate right) {
        int scoreComparison = Long.compare(right.score, left.score);
        if (scoreComparison != 0) {
            return scoreComparison;
        }

        int distanceComparison = Long.compare(left.distance, right.distance);
        if (distanceComparison != 0) {
            return distanceComparison;
        }
        return Integer.compare(left.directionIndex, right.directionIndex);
    }

    private static Comparator<Entry> entryComparator() {
        return Comparator
                .comparingLong((Entry entry) -> chebyshevDistance(
                        entry.pos, entry.centerX, entry.centerZ))
                .thenComparingInt(entry -> ringIndex(entry.pos.getX() - entry.centerX,
                        entry.pos.getZ() - entry.centerZ))
                .thenComparingInt(entry -> entry.pos.getY());
    }

    private Map<Long, Entry> indexFor(ServerLevel level) {
        return index.computeIfAbsent(level, ignored -> new HashMap<>());
    }

    private Entry getEntry(ServerLevel level, int x, int y, int z) {
        Map<Long, Entry> levelIndex = index.get(level);
        return levelIndex == null ? null : levelIndex.get(BlockPos.asLong(x, y, z));
    }

    private void removeEntryAt(ServerLevel level, BlockPos pos) {
        Map<Long, Entry> levelIndex = index.get(level);
        if (levelIndex == null) {
            return;
        }
        levelIndex.remove(pos.asLong());
        if (levelIndex.isEmpty()) {
            index.remove(level);
        }
    }

    private enum Zone {
        ACTIVE,
        WAITING,
        REMOVED
    }

    private record Center(int x, int z) {}

    private record Candidate(BlockPos pos, long distance, int directionIndex, long score) {}

    private record PreferredMove(BlockPos target, int fallbackStartDirection) {}

    private static final class PlayerSettings {
        private int maxConnectedBlocks;
        private boolean approximateMatching;

        private PlayerSettings(int maxConnectedBlocks) {
            this.maxConnectedBlocks = maxConnectedBlocks;
        }
    }

    private static final class Entry {
        private final ServerLevel level;
        private final Block block;
        private final ResourceLocation blockId;
        private final int centerX;
        private final int centerZ;

        private BlockPos pos;
        private BlockPos lastPos;
        private BlockPos preferredDirection;
        private int horizontalMoves;
        private boolean horizontalLimitReached;
        /** Lets a freshly woken entry move once even when the isolation rule applies. */
        private boolean isolationGrace;
        private Zone zone = Zone.ACTIVE;
        private boolean forcePhase2;
        private final boolean protectBlockEntities;
        private long eligibleTick;

        private Entry(ServerLevel level, BlockPos pos, Block block, ResourceLocation blockId,
                Center center, boolean forcePhase2, boolean protectBlockEntities, long eligibleTick) {
            this.level = level;
            this.pos = pos;
            this.block = block;
            this.blockId = blockId;
            this.centerX = center.x;
            this.centerZ = center.z;
            this.forcePhase2 = forcePhase2;
            this.protectBlockEntities = protectBlockEntities;
            this.eligibleTick = eligibleTick;
        }

    }
}
