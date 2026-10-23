package com.powaildi.statictosand;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Moves a connected cluster of blocks one block per tick. Phase one pushes the
 * cluster away from its center, phase two pulls it back.
 */
@SuppressWarnings("null")
final class StaticToSandTask {
    private static final int MAX_BLOCKS = 1024;
    private static final int HORIZONTAL_MOVE_THRESHOLD = 4;
    /** Only notify clients; no neighbour updates, scheduled ticks or drops. */
    private static final int SET_BLOCK_FLAGS = 2;

    /** Clockwise ring order starting at +X: +X, +X+Z, +Z, ... +X-Z. */
    private static final BlockPos[] HORIZONTAL_DIRECTIONS = {
            new BlockPos(1, 0, 0),
            new BlockPos(1, 0, 1),
            new BlockPos(0, 0, 1),
            new BlockPos(-1, 0, 1),
            new BlockPos(-1, 0, 0),
            new BlockPos(-1, 0, -1),
            new BlockPos(0, 0, -1),
            new BlockPos(1, 0, -1)
    };
    private static final BlockPos[] BFS_DIRECTIONS = {
            new BlockPos(0, 1, 0),
            new BlockPos(1, 0, 0),
            new BlockPos(0, 0, 1),
            new BlockPos(-1, 0, 0),
            new BlockPos(0, 0, -1),
            new BlockPos(0, -1, 0)
    };
    private static final BlockPos[] PULL_BACK_OFFSETS = {
            new BlockPos(1, 0, 0),
            new BlockPos(0, 0, 1),
            new BlockPos(-1, 0, 0),
            new BlockPos(0, 0, -1),
            new BlockPos(0, 1, 0)
    };

    private static final int PHASE_1_NEIGHBOR_WEIGHT = 12;
    private static final int PHASE_1_DISTANCE_WEIGHT = 1;
    private static final int PHASE_1_CENTROID_WEIGHT = 2;
    private static final int PHASE_1_ISOLATION_PENALTY = 8;

    private static final int PHASE_2_NEIGHBOR_WEIGHT = 12;
    private static final int PHASE_2_DISTANCE_WEIGHT = 2;
    private static final int PHASE_2_CENTROID_WEIGHT = 0;
    private static final int PHASE_2_ISOLATION_PENALTY = 8;

    private final List<Entry> active = new ArrayList<>();
    private final List<Entry> waiting = new ArrayList<>();
    private final Map<PositionKey, Entry> index = new HashMap<>();
    private final Set<ServerPlayer> participants = new HashSet<>();

    private boolean running;
    private int phase = 1;
    private long tickCount;
    private int totalBlocks;

    private int centroidX;
    private int centroidZ;
    private boolean centroidValid;
    private boolean centroidDirty;

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || event.getHand() != InteractionHand.MAIN_HAND
                || !event.getItemStack().is(StatictoSand.STATIC_TO_SAND.get())) {
            return;
        }

        BlockState clickedState = level.getBlockState(event.getPos());
        if (clickedState.isAir()) {
            return;
        }

        Block block = clickedState.getBlock();
        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(block);
        List<BlockPos> selected = selectBlocks(level, event.getPos(), block);
        if (selected.isEmpty()) {
            return;
        }

        Player player = event.getEntity();
        boolean shift = player.isShiftKeyDown();
        Center center = shift
                ? new Center(player.getBlockX(), player.getBlockZ())
                : centerOf(selected);

        boolean startNewTask = !running;
        if (startNewTask) {
            resetTaskState();
        }

        addSelection(level, selected, block, blockId, center, shift);

        if (player instanceof ServerPlayer serverPlayer && !(player instanceof FakePlayer)) {
            participants.add(serverPlayer);
        }

        if (startNewTask) {
            running = true;
            phase = shift ? 2 : 1;
            tickCount = 0;
            centroidValid = false;
            centroidDirty = phase == 1;
        }
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (!running) {
            return;
        }

        tickCount++;

        if (phase == 1 && centroidDirty) {
            computeCentroid();
        }

        int snapshotLength = active.size();
        List<Entry> pendingActive = new ArrayList<>();
        Set<Entry> pendingSet = new HashSet<>();

        for (int i = 0; i < snapshotLength; i++) {
            Entry entry = active.get(i);
            if (entry.zone == Zone.ACTIVE && tickCount >= entry.eligibleTick) {
                processEntry(entry, pendingActive, pendingSet);
            }
        }

        appendUnique(active, pendingActive);
        active.removeIf(entry -> entry.zone != Zone.ACTIVE);
        waiting.removeIf(entry -> entry.zone != Zone.WAITING);

        advancePhaseOrFinish();
    }

    private List<BlockPos> selectBlocks(ServerLevel level, BlockPos start, Block block) {
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        List<BlockPos> selected = new ArrayList<>();

        queue.add(start);
        visited.add(start);

        while (!queue.isEmpty() && selected.size() < MAX_BLOCKS) {
            BlockPos pos = queue.removeFirst();
            if (level.getBlockState(pos).getBlock() != block) {
                continue;
            }

            selected.add(pos);

            for (BlockPos direction : BFS_DIRECTIONS) {
                if (selected.size() >= MAX_BLOCKS) {
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

    private void addSelection(ServerLevel level, List<BlockPos> selected, Block block,
            ResourceLocation blockId, Center center, boolean forcePhase2) {
        List<Entry> additions = new ArrayList<>();
        long eligibleTick = tickCount + 1;
        for (BlockPos pos : selected) {
            PositionKey key = key(level, pos);
            if (index.containsKey(key)) {
                continue;
            }

            Entry entry = new Entry(level, pos, block, blockId, center, forcePhase2, eligibleTick);
            index.put(key, entry);
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
        centroidDirty = true;
    }

    private void processEntry(Entry entry, List<Entry> pendingActive, Set<Entry> pendingSet) {
        ServerLevel level = entry.level;
        if (level.getBlockState(entry.pos).getBlock() != entry.block) {
            removeEntry(entry);
            return;
        }

        int effectivePhase = entry.forcePhase2 ? 2 : phase;
        BlockPos target = null;
        boolean movedDown = false;

        BlockPos down = entry.pos.below();
        if (isAir(level, down) && !down.equals(entry.lastPos)) {
            target = down;
            movedDown = true;
        } else if (!(effectivePhase == 2
                && entry.pos.getX() == entry.centerX
                && entry.pos.getZ() == entry.centerZ)) {
            target = chooseHorizontalTarget(entry, effectivePhase);
        }

        if (target == null) {
            park(entry);
            return;
        }

        moveEntry(entry, target, movedDown, pendingActive, pendingSet);
    }

    private BlockPos chooseHorizontalTarget(Entry entry, int effectivePhase) {
        ServerLevel level = entry.level;
        long currentDistance = dist2(entry.pos, entry.centerX, entry.centerZ);
        Candidate best = null;

        for (int directionIndex = 0; directionIndex < HORIZONTAL_DIRECTIONS.length; directionIndex++) {
            BlockPos direction = HORIZONTAL_DIRECTIONS[directionIndex];
            BlockPos candidatePos = entry.pos.offset(direction);

            if (!isAir(level, candidatePos) || candidatePos.equals(entry.lastPos)) {
                continue;
            }

            if (direction.getX() != 0 && direction.getZ() != 0) {
                BlockPos sideX = new BlockPos(candidatePos.getX(), entry.pos.getY(), entry.pos.getZ());
                BlockPos sideZ = new BlockPos(entry.pos.getX(), entry.pos.getY(), candidatePos.getZ());
                if (!isAir(level, sideX) && !isAir(level, sideZ)) {
                    continue;
                }
            }

            long candidateDistance = dist2(candidatePos, entry.centerX, entry.centerZ);
            if (entry.horizontalMoves >= HORIZONTAL_MOVE_THRESHOLD) {
                if (effectivePhase == 1 && candidateDistance < currentDistance) {
                    continue;
                }
                if (effectivePhase == 2 && candidateDistance > currentDistance) {
                    continue;
                }
            }

            int neighborCount = countNeighbors(level, candidatePos, entry.blockId);
            long centroidDistance = phase == 1 && centroidValid
                    ? dist2(candidatePos, centroidX, centroidZ)
                    : 0L;

            int neighborWeight = effectivePhase == 1
                    ? PHASE_1_NEIGHBOR_WEIGHT : PHASE_2_NEIGHBOR_WEIGHT;
            int distanceWeight = effectivePhase == 1
                    ? PHASE_1_DISTANCE_WEIGHT : PHASE_2_DISTANCE_WEIGHT;
            int centroidWeight = effectivePhase == 1
                    ? PHASE_1_CENTROID_WEIGHT : PHASE_2_CENTROID_WEIGHT;
            int isolationPenalty = effectivePhase == 1
                    ? PHASE_1_ISOLATION_PENALTY : PHASE_2_ISOLATION_PENALTY;

            long score = (long) neighborWeight * neighborCount
                    - (long) distanceWeight * (candidateDistance - currentDistance)
                    - (long) centroidWeight * centroidDistance
                    - (neighborCount == 0 ? isolationPenalty : 0);

            Candidate candidate = new Candidate(candidatePos, candidateDistance, directionIndex, score);
            if (best == null || compareCandidates(candidate, best, effectivePhase) < 0) {
                best = candidate;
            }
        }

        return best == null ? null : best.pos;
    }

    private int countNeighbors(ServerLevel level, BlockPos pos, ResourceLocation blockId) {
        int count = 0;
        for (BlockPos direction : HORIZONTAL_DIRECTIONS) {
            Entry neighbor = index.get(key(level, pos.offset(direction)));
            if (neighbor != null
                    && (neighbor.zone == Zone.ACTIVE || neighbor.zone == Zone.WAITING)
                    && neighbor.blockId.equals(blockId)) {
                count++;
            }
        }
        return count;
    }

    private void moveEntry(Entry entry, BlockPos target, boolean movedDown,
            List<Entry> pendingActive, Set<Entry> pendingSet) {
        ServerLevel level = entry.level;
        BlockPos oldPos = entry.pos;

        level.setBlock(oldPos, Blocks.AIR.defaultBlockState(), SET_BLOCK_FLAGS);
        level.setBlock(target, entry.block.defaultBlockState(), SET_BLOCK_FLAGS);

        index.remove(key(level, oldPos));
        entry.pos = target;
        entry.lastPos = oldPos;
        entry.zone = Zone.ACTIVE;
        index.put(key(level, target), entry);

        if (movedDown) {
            entry.horizontalMoves = 0;
        } else {
            entry.horizontalMoves++;
        }
        centroidDirty = true;

        for (BlockPos offset : PULL_BACK_OFFSETS) {
            Entry waitingEntry = index.get(key(level, oldPos.offset(offset)));
            if (waitingEntry != null && waitingEntry.zone == Zone.WAITING) {
                waitingEntry.zone = Zone.ACTIVE;
                if (pendingSet.add(waitingEntry)) {
                    pendingActive.add(waitingEntry);
                }
            }
        }
    }

    private void park(Entry entry) {
        entry.zone = Zone.WAITING;
        if (!waiting.contains(entry)) {
            waiting.add(entry);
        }
    }

    private void removeEntry(Entry entry) {
        entry.zone = Zone.REMOVED;
        index.remove(key(entry.level, entry.pos));
        centroidDirty = true;
    }

    private void appendUnique(List<Entry> target, List<Entry> additions) {
        Set<Entry> existing = new HashSet<>(target);
        for (Entry entry : additions) {
            if (entry.zone == Zone.ACTIVE && existing.add(entry)) {
                target.add(entry);
            }
        }
    }

    private void computeCentroid() {
        long sumX = 0L;
        long sumZ = 0L;
        int count = 0;

        for (Entry entry : active) {
            if (entry.zone == Zone.ACTIVE) {
                sumX += entry.pos.getX();
                sumZ += entry.pos.getZ();
                count++;
            }
        }
        for (Entry entry : waiting) {
            if (entry.zone == Zone.WAITING) {
                sumX += entry.pos.getX();
                sumZ += entry.pos.getZ();
                count++;
            }
        }

        if (count == 0) {
            centroidX = 0;
            centroidZ = 0;
        } else {
            centroidX = (int) Math.floorDiv(sumX, count);
            centroidZ = (int) Math.floorDiv(sumZ, count);
        }
        centroidValid = true;
        centroidDirty = false;
    }

    private void advancePhaseOrFinish() {
        if (!active.isEmpty()) {
            return;
        }

        if (phase == 1) {
            active.addAll(waiting);
            waiting.clear();
            for (Entry entry : active) {
                entry.zone = Zone.ACTIVE;
                entry.lastPos = null;
                entry.horizontalMoves = 0;
                entry.forcePhase2 = false;
            }
            active.sort(entryComparator());
            phase = 2;
            centroidValid = false;
            centroidDirty = false;
        } else {
            finishTask();
        }
    }

    private void finishTask() {
        String message = "移动了" + totalBlocks + "个方块，经历了" + tickCount + " tick";
        if (!participants.isEmpty()) {
            participants.forEach(player -> player.sendSystemMessage(Component.literal(message)));
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
        centroidX = 0;
        centroidZ = 0;
        centroidValid = false;
        centroidDirty = false;
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
        return level.getBlockState(pos).isAir();
    }

    private static long dist2(BlockPos pos, int centerX, int centerZ) {
        long dx = (long) pos.getX() - centerX;
        long dz = (long) pos.getZ() - centerZ;
        return dx * dx + dz * dz;
    }

    /**
     * Maps an offset to the nearest of the eight ring directions. The center
     * itself uses index zero.
     */
    private static int ringIndex(int dx, int dz) {
        if (dx == 0 && dz == 0) {
            return 0;
        }
        double angle = Math.atan2(dz, dx);
        if (angle < 0.0D) {
            angle += Math.PI * 2.0D;
        }
        return (int) Math.floor((angle + Math.PI / 8.0D) / (Math.PI / 4.0D)) & 7;
    }

    private static int compareCandidates(Candidate left, Candidate right, int effectivePhase) {
        int scoreComparison = Long.compare(right.score, left.score);
        if (scoreComparison != 0) {
            return scoreComparison;
        }

        int distanceComparison = effectivePhase == 1
                ? Long.compare(right.distance, left.distance)
                : Long.compare(left.distance, right.distance);
        if (distanceComparison != 0) {
            return distanceComparison;
        }
        return Integer.compare(left.directionIndex, right.directionIndex);
    }

    private static Comparator<Entry> entryComparator() {
        return Comparator
                .comparingLong((Entry entry) -> dist2(entry.pos, entry.centerX, entry.centerZ))
                .thenComparingInt(entry -> ringIndex(entry.pos.getX() - entry.centerX,
                        entry.pos.getZ() - entry.centerZ))
                .thenComparingInt(entry -> entry.pos.getY());
    }

    private static PositionKey key(ServerLevel level, BlockPos pos) {
        return new PositionKey(level.dimension().location(), pos);
    }

    private enum Zone {
        ACTIVE,
        WAITING,
        REMOVED
    }

    private record PositionKey(ResourceLocation dimension, BlockPos pos) {}

    private record Center(int x, int z) {}

    private record Candidate(BlockPos pos, long distance, int directionIndex, long score) {}

    private static final class Entry {
        private final ServerLevel level;
        private final Block block;
        private final ResourceLocation blockId;
        private final int centerX;
        private final int centerZ;

        private BlockPos pos;
        private BlockPos lastPos;
        private int horizontalMoves;
        private Zone zone = Zone.ACTIVE;
        private boolean forcePhase2;
        private long eligibleTick;

        private Entry(ServerLevel level, BlockPos pos, Block block, ResourceLocation blockId,
                Center center, boolean forcePhase2, long eligibleTick) {
            this.level = level;
            this.pos = pos;
            this.block = block;
            this.blockId = blockId;
            this.centerX = center.x;
            this.centerZ = center.z;
            this.forcePhase2 = forcePhase2;
            this.eligibleTick = eligibleTick;
        }
    }
}
