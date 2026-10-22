const STATIC_TO_SAND = 'kubejs:static_to_sand'
const MAX_BLOCKS = 1024
const HORIZONTAL_MOVE_THRESHOLD = 4
const SET_BLOCK_FLAGS = 2
const AIR_ID = 'minecraft:air'
const HORIZONTAL_DIRECTIONS = [
    { x: 1, z: 0 },
    { x: 0, z: 1 },
    { x: -1, z: 0 },
    { x: 0, z: -1 }
]
const BFS_DIRECTIONS = [
    { x: 0, y: 1, z: 0 },
    { x: 1, y: 0, z: 0 },
    { x: 0, y: 0, z: 1 },
    { x: -1, y: 0, z: 0 },
    { x: 0, y: 0, z: -1 },
    { x: 0, y: -1, z: 0 }
]

const staticToSandTask = {
    running: false,
    phase: 1,
    tickCount: 0,
    totalBlocks: 0,
    participants: new Set(),
    A: [],
    W: [],
    index: new Map()
}

function positionKey(level, pos) {
    let dimension = level.dimension ? String(level.dimension) : String(level)
    return dimension + '|' + pos.x + ',' + pos.y + ',' + pos.z
}

function samePosition(a, b) {
    return b != null && a.x === b.x && a.y === b.y && a.z === b.z
}

function manhattan(pos, centerX, centerZ) {
    return Math.abs(pos.x - centerX) + Math.abs(pos.z - centerZ)
}

function isAir(level, pos) {
    let block = level.getBlock(pos.x, pos.y, pos.z)
    return block.id === AIR_ID || block.id === 'minecraft:cave_air'
}

function worldPosition(level, pos) {
    return level.getBlock(pos.x, pos.y, pos.z).pos
}

function sortEntries(entries) {
    entries.sort((left, right) => {
        let leftDistance = manhattan(left.pos, left.centerX, left.centerZ)
        let rightDistance = manhattan(right.pos, right.centerX, right.centerZ)
        if (leftDistance !== rightDistance) return leftDistance - rightDistance
        if (left.pos.x !== right.pos.x) return right.pos.x - left.pos.x
        if (left.pos.z !== right.pos.z) return right.pos.z - left.pos.z
        return left.pos.y - right.pos.y
    })
}

function removeEntry(entry) {
    entry.zone = null
    staticToSandTask.index.delete(positionKey(entry.level, entry.pos))
}

function appendUnique(entries, additions) {
    let existing = new Set(entries)
    additions.forEach(entry => {
        if (!existing.has(entry) && entry.zone === 'A') {
            entries.push(entry)
            existing.add(entry)
        }
    })
}

function compactActive() {
    staticToSandTask.A = staticToSandTask.A.filter(entry => entry.zone === 'A')
}

function compactWaiting() {
    staticToSandTask.W = staticToSandTask.W.filter(entry => entry.zone === 'W')
}

function isFakePlayer(player) {
    if (typeof player.isFake === 'function') return player.isFake()
    if (typeof player.isFakePlayer === 'function') return player.isFakePlayer()
    return false
}

function selectBlocks(level, start, blockId) {
    let queue = [{ x: start.x, y: start.y, z: start.z }]
    let visited = new Set()
    let selected = []

    while (queue.length > 0 && selected.length < MAX_BLOCKS) {
        let pos = queue.shift()
        let key = pos.x + ',' + pos.y + ',' + pos.z
        if (visited.has(key)) continue
        visited.add(key)

        if (level.getBlock(pos.x, pos.y, pos.z).id !== blockId) continue
        selected.push(pos)

        BFS_DIRECTIONS.forEach(direction => {
            if (selected.length >= MAX_BLOCKS) return
            queue.push({
                x: pos.x + direction.x,
                y: pos.y + direction.y,
                z: pos.z + direction.z
            })
        })
    }
    return selected
}

function addSelection(level, selected, blockId, center) {
    if (selected.length === 0) return

    if (center == null) {
        let minX = selected[0].x
        let maxX = selected[0].x
        let minZ = selected[0].z
        let maxZ = selected[0].z
        selected.forEach(pos => {
            minX = Math.min(minX, pos.x)
            maxX = Math.max(maxX, pos.x)
            minZ = Math.min(minZ, pos.z)
            maxZ = Math.max(maxZ, pos.z)
        })
        center = {
            x: Math.floor((minX + maxX) / 2),
            z: Math.floor((minZ + maxZ) / 2)
        }
    }

    let additions = []
    selected.forEach(pos => {
        let key = positionKey(level, pos)
        if (staticToSandTask.index.has(key)) return

        let entry = {
            level: level,
            pos: pos,
            blockId: blockId,
            centerX: center.x,
            centerZ: center.z,
            lastPos: null,
            horizontalMoves: 0,
            zone: 'A'
        }
        staticToSandTask.index.set(key, entry)
        additions.push(entry)
        staticToSandTask.totalBlocks++
    })

    sortEntries(additions)
    appendUnique(staticToSandTask.A, additions)
}

function pullBack(level, oldPos, pendingA) {
    let offsets = [
        { x: 1, y: 0, z: 0 },
        { x: 0, y: 0, z: 1 },
        { x: -1, y: 0, z: 0 },
        { x: 0, y: 0, z: -1 },
        { x: 0, y: 1, z: 0 }
    ]
    let pendingSet = new Set(pendingA)
    offsets.forEach(offset => {
        let pos = {
            x: oldPos.x + offset.x,
            y: oldPos.y + offset.y,
            z: oldPos.z + offset.z
        }
        let entry = staticToSandTask.index.get(positionKey(level, pos))
        if (entry != null && entry.zone === 'W') {
            entry.zone = 'A'
            if (!pendingSet.has(entry)) {
                pendingA.push(entry)
                pendingSet.add(entry)
            }
        }
    })
}

function processEntry(entry, pendingA) {
    let level = entry.level
    if (level.getBlock(entry.pos.x, entry.pos.y, entry.pos.z).id !== entry.blockId) {
        removeEntry(entry)
        return
    }

    let target = null
    let moveType = null
    let down = { x: entry.pos.x, y: entry.pos.y - 1, z: entry.pos.z }
    if (isAir(level, down) && !samePosition(down, entry.lastPos)) {
        target = down
        moveType = 'DOWN'
    } else if (!(staticToSandTask.phase === 2 && entry.pos.x === entry.centerX && entry.pos.z === entry.centerZ)) {
        let currentDistance = manhattan(entry.pos, entry.centerX, entry.centerZ)
        let candidates = []
        HORIZONTAL_DIRECTIONS.forEach((direction, directionIndex) => {
            let candidate = {
                x: entry.pos.x + direction.x,
                y: entry.pos.y,
                z: entry.pos.z + direction.z
            }
            if (!isAir(level, candidate) || samePosition(candidate, entry.lastPos)) return

            let distance = manhattan(candidate, entry.centerX, entry.centerZ)
            if (entry.horizontalMoves >= HORIZONTAL_MOVE_THRESHOLD) {
                if (staticToSandTask.phase === 1 && distance < currentDistance) return
                if (staticToSandTask.phase === 2 && distance > currentDistance) return
            }
            candidates.push({ pos: candidate, distance: distance, directionIndex: directionIndex })
        })

        candidates.sort((left, right) => {
            let distanceDifference = staticToSandTask.phase === 1
                ? right.distance - left.distance
                : left.distance - right.distance
            return distanceDifference || left.directionIndex - right.directionIndex
        })
        if (candidates.length > 0) {
            target = candidates[0].pos
            moveType = 'HORIZONTAL'
        }
    }

    if (target == null) {
        entry.zone = 'W'
        staticToSandTask.W.push(entry)
        return
    }

    let oldPos = entry.pos
    level.setBlock(worldPosition(level, oldPos), AIR_ID, SET_BLOCK_FLAGS)
    level.setBlock(worldPosition(level, target), entry.blockId, SET_BLOCK_FLAGS)
    staticToSandTask.index.delete(positionKey(level, oldPos))
    entry.pos = target
    entry.lastPos = oldPos
    entry.zone = 'A'
    staticToSandTask.index.set(positionKey(level, target), entry)

    if (moveType === 'DOWN') entry.horizontalMoves = 0
    else entry.horizontalMoves++
    pullBack(level, oldPos, pendingA)
}

function finishTask() {
    let message = '移动了' + staticToSandTask.totalBlocks + '个方块，经历了' + staticToSandTask.tickCount + ' tick'
    staticToSandTask.participants.forEach(player => player.tell(message))
    staticToSandTask.running = false
    staticToSandTask.phase = 1
    staticToSandTask.tickCount = 0
    staticToSandTask.totalBlocks = 0
    staticToSandTask.participants.clear()
    staticToSandTask.A = []
    staticToSandTask.W = []
    staticToSandTask.index.clear()
}

function advancePhaseOrFinish() {
    if (staticToSandTask.A.length !== 0) return
    if (staticToSandTask.phase === 1) {
        staticToSandTask.A = staticToSandTask.W
        
        staticToSandTask.W = []
        staticToSandTask.A.forEach(entry => {
            entry.zone = 'A'
            entry.lastPos = null
            entry.horizontalMoves = 0
        })
        sortEntries(staticToSandTask.A)
        staticToSandTask.phase = 2
        
        return
    }
    finishTask()
}

BlockEvents.rightClicked(e => {
    let { player, hand, block, level } = e
    if (hand !== 'MAIN_HAND') return
    if (player.getMainHandItem().id !== STATIC_TO_SAND) return
    if (block.id === AIR_ID) return

    let directPhaseTwo = player.isShiftKeyDown()
    let center = directPhaseTwo
        ? { x: Math.floor(player.xo), z: Math.floor(player.zo) }
        : null
    let selected = selectBlocks(level, block.pos, block.id)
    if (selected.length === 0) return
    addSelection(level, selected, block.id, center)
    if (!isFakePlayer(player)) staticToSandTask.participants.add(player)
    if (!staticToSandTask.running) {
        staticToSandTask.running = true
        staticToSandTask.phase = directPhaseTwo ? 2 : 1
        staticToSandTask.tickCount = 0
    } else if (directPhaseTwo) {
        staticToSandTask.phase = 2
    }
})

ServerEvents.tick(e => {
    if (!staticToSandTask.running) return
    staticToSandTask.tickCount++

    let snapshotLength = staticToSandTask.A.length
    let pendingA = []
    for (let i = 0; i < snapshotLength; i++) {
        let entry = staticToSandTask.A[i]
        if (entry.zone === 'A') processEntry(entry, pendingA)
    }

    appendUnique(staticToSandTask.A, pendingA)
    compactActive()
    compactWaiting()
    advancePhaseOrFinish()
})