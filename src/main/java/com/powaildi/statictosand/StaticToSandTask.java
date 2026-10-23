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
import net.minecraft.network.chat.Component;
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

@SuppressWarnings("null")
final class StaticToSandTask {
    private static final int MAX_BLOCKS = 1024;
    private static final int HORIZONTAL_MOVE_THRESHOLD = 4;
    private static final int SET_BLOCK_FLAGS = 2;
    private static final BlockPos[] HORIZONTAL_DIRECTIONS = {
            new BlockPos(1, 0, 0), new BlockPos(0, 0, 1),
            new BlockPos(-1, 0, 0), new BlockPos(0, 0, -1)
    };
    private static final BlockPos[] BFS_DIRECTIONS = {
            new BlockPos(0, 1, 0), new BlockPos(1, 0, 0),
            new BlockPos(0, 0, 1), new BlockPos(-1, 0, 0),
            new BlockPos(0, 0, -1), new BlockPos(0, -1, 0)
    };

    private final List<Entry> active = new ArrayList<>();
    private final List<Entry> waiting = new ArrayList<>();
    private final Map<String, Entry> index = new HashMap<>();
    private final Set<ServerPlayer> participants = new HashSet<>();
    private boolean running;
    private int phase = 1;
    private int tickCount;
    private int totalBlocks;

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || event.getHand() != InteractionHand.MAIN_HAND
                || !event.getItemStack().is(StatictoSand.STATIC_TO_SAND.get())
                || event.getLevel().getBlockState(event.getPos()).isAir()) {
            return;
        }

        Player player = event.getEntity();
        boolean directPhaseTwo = player.isShiftKeyDown();
        Block block = level.getBlockState(event.getPos()).getBlock();
        List<BlockPos> selected = selectBlocks(level, event.getPos(), block);
        if (selected.isEmpty()) {
            return;
        }

        addSelection(level, selected, block, directPhaseTwo
                ? new Center(player.getBlockX(), player.getBlockZ()) : null);
        if (player instanceof ServerPlayer serverPlayer && !(player instanceof FakePlayer)) {
            participants.add(serverPlayer);
        }
        if (!running) {
            running = true;
            phase = directPhaseTwo ? 2 : 1;
            tickCount = 0;
        } else if (directPhaseTwo) {
            phase = 2;
        }
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (!running) {
            return;
        }
        tickCount++;
        int snapshotLength = active.size();
        List<Entry> pendingActive = new ArrayList<>();
        for (int i = 0; i < snapshotLength; i++) {
            Entry entry = active.get(i);
            if (entry.zone == Zone.ACTIVE) {
                processEntry(entry, pendingActive);
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

        while (!queue.isEmpty() && selected.size() < MAX_BLOCKS) {
            BlockPos pos = queue.remove();
            if (!visited.add(pos) || level.getBlockState(pos).getBlock() != block) {
                continue;
            }
            selected.add(pos);
            for (BlockPos direction : BFS_DIRECTIONS) {
                if (selected.size() < MAX_BLOCKS) {
                    queue.add(pos.offset(direction));
                }
            }
        }
        return selected;
    }

    private void addSelection(ServerLevel level, List<BlockPos> selected, Block block, Center center) {
        if (center == null) {
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
            center = new Center(Math.floorDiv(minX + maxX, 2), Math.floorDiv(minZ + maxZ, 2));
        }

        List<Entry> additions = new ArrayList<>();
        for (BlockPos pos : selected) {
            String key = key(level, pos);
            if (index.containsKey(key)) {
                continue;
            }
            Entry entry = new Entry(level, pos, block, center);
            index.put(key, entry);
            additions.add(entry);
            totalBlocks++;
        }
        additions.sort(entryComparator());
        appendUnique(active, additions);
    }

    private void processEntry(Entry entry, List<Entry> pendingActive) {
        ServerLevel level = entry.level;
        if (level.getBlockState(entry.pos).getBlock() != entry.block) {
            removeEntry(entry);
            return;
        }

        BlockPos target = null;
        boolean movedDown = false;
        BlockPos down = entry.pos.below();
        if (isAir(level, down) && !down.equals(entry.lastPos)) {
            target = down;
            movedDown = true;
        } else if (!(phase == 2 && entry.pos.getX() == entry.center.x && entry.pos.getZ() == entry.center.z)) {
            int currentDistance = manhattan(entry.pos, entry.center);
            List<Candidate> candidates = new ArrayList<>();
            for (int i = 0; i < HORIZONTAL_DIRECTIONS.length; i++) {
                BlockPos candidate = entry.pos.offset(HORIZONTAL_DIRECTIONS[i]);
                if (isAir(level, candidate) && !candidate.equals(entry.lastPos)) {
                    int distance = manhattan(candidate, entry.center);
                    if (entry.horizontalMoves < HORIZONTAL_MOVE_THRESHOLD
                            || (phase == 1 && distance >= currentDistance)
                            || (phase == 2 && distance <= currentDistance)) {
                        candidates.add(new Candidate(candidate, distance, i));
                    }
                }
            }
            candidates.sort(Comparator
                    .comparingInt((Candidate candidate) -> phase == 1 ? -candidate.distance : candidate.distance)
                    .thenComparingInt(candidate -> candidate.directionIndex));
            if (!candidates.isEmpty()) {
                target = candidates.get(0).pos;
            }
        }

        if (target == null) {
            entry.zone = Zone.WAITING;
            waiting.add(entry);
            return;
        }

        BlockPos oldPos = entry.pos;
        BlockState state = level.getBlockState(oldPos);
        level.setBlock(oldPos, Blocks.AIR.defaultBlockState(), SET_BLOCK_FLAGS);
        level.setBlock(target, state, SET_BLOCK_FLAGS);
        index.remove(key(level, oldPos));
        entry.pos = target;
        entry.lastPos = oldPos;
        entry.zone = Zone.ACTIVE;
        index.put(key(level, target), entry);
        entry.horizontalMoves = movedDown ? 0 : entry.horizontalMoves + 1;
        pullBack(level, oldPos, pendingActive);
    }

    private void pullBack(ServerLevel level, BlockPos oldPos, List<Entry> pendingActive) {
        BlockPos[] offsets = {
                new BlockPos(1, 0, 0), new BlockPos(0, 0, 1),
                new BlockPos(-1, 0, 0), new BlockPos(0, 0, -1),
                new BlockPos(0, 1, 0)
        };
        for (BlockPos offset : offsets) {
            Entry entry = index.get(key(level, oldPos.offset(offset)));
            if (entry != null && entry.zone == Zone.WAITING) {
                entry.zone = Zone.ACTIVE;
                if (!pendingActive.contains(entry)) {
                    pendingActive.add(entry);
                }
            }
        }
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
            }
            active.sort(entryComparator());
            phase = 2;
        } else {
            String message = "Moved " + totalBlocks + " blocks in " + tickCount + " ticks";
            participants.forEach(player -> player.sendSystemMessage(Component.literal(message)));
            running = false;
            phase = 1;
            tickCount = 0;
            totalBlocks = 0;
            participants.clear();
            active.clear();
            waiting.clear();
            index.clear();
        }
    }

    private void removeEntry(Entry entry) {
        entry.zone = Zone.REMOVED;
        index.remove(key(entry.level, entry.pos));
    }

    private void appendUnique(List<Entry> target, List<Entry> additions) {
        Set<Entry> existing = new HashSet<>(target);
        for (Entry entry : additions) {
            if (existing.add(entry) && entry.zone == Zone.ACTIVE) {
                target.add(entry);
            }
        }
    }

    private boolean isAir(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).isAir();
    }

    private int manhattan(BlockPos pos, Center center) {
        return Math.abs(pos.getX() - center.x) + Math.abs(pos.getZ() - center.z);
    }

    private String key(ServerLevel level, BlockPos pos) {
        return level.dimension().location() + "|" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private Comparator<Entry> entryComparator() {
        return Comparator.comparingInt((Entry entry) -> manhattan(entry.pos, entry.center))
                .thenComparing(Comparator.comparingInt((Entry entry) -> entry.pos.getX()).reversed())
                .thenComparing(Comparator.comparingInt((Entry entry) -> entry.pos.getZ()).reversed())
                .thenComparingInt(entry -> entry.pos.getY());
    }

    private enum Zone { ACTIVE, WAITING, REMOVED }

    private static final class Entry {
        private final ServerLevel level;
        private final Block block;
        private final Center center;
        private BlockPos pos;
        private BlockPos lastPos;
        private int horizontalMoves;
        private Zone zone = Zone.ACTIVE;

        private Entry(ServerLevel level, BlockPos pos, Block block, Center center) {
            this.level = level;
            this.pos = pos;
            this.block = block;
            this.center = center;
        }
    }

    private record Center(int x, int z) {}
    private record Candidate(BlockPos pos, int distance, int directionIndex) {}
}
