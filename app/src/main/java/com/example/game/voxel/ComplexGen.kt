package com.example.game.voxel

import java.util.Random
import kotlin.math.max
import kotlin.math.min

data class Room(
    val id: String,
    val x: Float,
    val z: Float,
    val w: Float,
    val d: Float,
    val lit: Boolean = true,
    val name: String = "Sector"
)

data class Corridor(
    val x: Float,
    val z: Float,
    val w: Float,
    val d: Float
)

data class DoorDef(
    val id: String,
    val x: Float,
    val z: Float,
    val axis: Char, // 'x' or 'z'
    var locked: Boolean = false
)

data class TerminalDef(
    val id: String,
    val x: Float,
    val z: Float,
    val kind: String // "relay", "node", "augment"
)

data class CrateDef(
    val x: Float,
    val z: Float,
    val loot: Int // 0=none, 1=supply, 2=intel
)

data class CameraDef(
    val id: String,
    val x: Float,
    val z: Float,
    val yaw: Float,
    val sweep: Float = 0.8f
)

data class LampDef(
    val x: Float,
    val z: Float
)

data class GuardSpawnDef(
    val id: String,
    val type: String, // "patrol", "tech", "heavy", "drone", "hound", "sniper", "nullifier", "warden", "overseer"
    val x: Float,
    val z: Float,
    val yaw: Float,
    val route: List<Pair<Float, Float>>
)

data class BreachDef(
    val id: String,
    val cells: List<Pair<Int, Int>>, // grid (x, z)
    val dirX: Int,
    val dirZ: Int,
    val depth: Int
)

data class LevelData(
    val index: Int,
    val name: String,
    val rooms: List<Room>,
    val corridors: List<Corridor>,
    val doors: List<DoorDef>,
    val terminals: List<TerminalDef>,
    val crates: List<CrateDef>,
    val cameras: List<CameraDef>,
    val lamps: List<LampDef>,
    val breaches: List<BreachDef>,
    val guards: List<GuardSpawnDef>,
    val spawnX: Float,
    val spawnZ: Float,
    val liftX: Float,
    val liftZ: Float,
    val vaultX: Float? = null,
    val vaultZ: Float? = null,
    val stairsX: Float? = null,
    val stairsZ: Float? = null
)

/**
 * Procedural facility generator creating connected multi-level complexes
 * with rich stealth infiltration topologies.
 */
object ComplexGen {

    fun generateComplex(seed: Long, levelCount: Int = 3): List<LevelData> {
        val rng = Random(seed)
        val levels = mutableListOf<LevelData>()

        for (li in 0 until levelCount) {
            val levelName = when (li) {
                0 -> "Sublevel B1 - Infiltration Hub"
                1 -> "Sublevel B2 - Security & Server Array"
                else -> "Sublevel B3 - Core Vault Facility"
            }
            levels.add(generateLevel(li, levelName, rng, isVaultLevel = (li == levelCount - 1)))
        }

        return levels
    }

    private fun generateLevel(
        index: Int,
        name: String,
        rng: Random,
        isVaultLevel: Boolean
    ): LevelData {
        val rooms = mutableListOf<Room>()
        val corridors = mutableListOf<Corridor>()
        val doors = mutableListOf<DoorDef>()
        val terminals = mutableListOf<TerminalDef>()
        val crates = mutableListOf<CrateDef>()
        val cameras = mutableListOf<CameraDef>()
        val lamps = mutableListOf<LampDef>()
        val breaches = mutableListOf<BreachDef>()
        val guards = mutableListOf<GuardSpawnDef>()

        // Layout a connected grid of 6 to 9 tactical rooms
        val roomCount = 6 + rng.nextInt(3)
        val roomGrid = mutableListOf<Room>()

        // 3x3 layout partitions (meters: 10 to 54)
        val startX = 14f
        val startZ = 14f
        val stepX = 14f
        val stepZ = 14f

        for (gridIdx in 0 until roomCount) {
            val gx = gridIdx % 3
            val gz = gridIdx / 3
            val rx = startX + gx * stepX + (rng.nextFloat() * 2f - 1f)
            val rz = startZ + gz * stepZ + (rng.nextFloat() * 2f - 1f)
            val rw = 8f + rng.nextFloat() * 4f
            val rd = 8f + rng.nextFloat() * 4f
            val isLit = (rng.nextFloat() > 0.3f) || (gridIdx == 0)

            val room = Room(
                id = "r_${index}_$gridIdx",
                x = rx,
                z = rz,
                w = rw,
                d = rd,
                lit = isLit,
                name = when {
                    gridIdx == 0 -> "Entry Hub"
                    gridIdx == roomCount - 1 && isVaultLevel -> "Classified Vault Chamber"
                    gridIdx == roomCount - 1 -> "Lower Lift Bay"
                    gridIdx == 2 -> "Security Node Alpha"
                    gridIdx == 4 -> "Terminal Relays"
                    else -> "Operations Ward $gridIdx"
                }
            )
            roomGrid.add(room)
            rooms.add(room)

            // Add room central lamp if lit
            if (isLit) {
                lamps.add(LampDef(rx + rw * 0.5f, rz + rd * 0.5f))
            }
        }

        // Connect adjacent rooms with corridors and doors
        for (i in 0 until roomGrid.size - 1) {
            val r1 = roomGrid[i]
            val r2 = roomGrid[i + 1]

            val c1x = r1.x + r1.w * 0.5f
            val c1z = r1.z + r1.d * 0.5f
            val c2x = r2.x + r2.w * 0.5f
            val c2z = r2.z + r2.d * 0.5f

            // Corridor width 2.5m
            val cx = min(c1x, c2x)
            val cz = min(c1z, c2z)
            val cw = max(2.5f, Math.abs(c2x - c1x))
            val cd = max(2.5f, Math.abs(c2z - c1z))

            corridors.add(Corridor(cx, cz, cw, 2.5f))
            corridors.add(Corridor(c2x, cz, 2.5f, cd))

            // Door at corridor mouth
            val isLocked = (rng.nextFloat() < 0.4f && i > 0)
            doors.add(
                DoorDef(
                    id = "door_${index}_$i",
                    x = cx + 2f,
                    z = cz + 1.25f,
                    axis = if (cw > cd) 'x' else 'z',
                    locked = isLocked
                )
            )
        }

        // Spawn location in Room 0
        val spawnX = roomGrid[0].x + 3f
        val spawnZ = roomGrid[0].z + 3f

        // Lift location in last room or room 1
        val lastRoom = roomGrid.last()
        val liftX = lastRoom.x + lastRoom.w * 0.5f
        val liftZ = lastRoom.z + lastRoom.d * 0.5f

        // Stairs between floors
        val stairsRoom = roomGrid[min(1, roomGrid.size - 1)]
        val stairsX = stairsRoom.x + 2.5f
        val stairsZ = stairsRoom.z + 2.5f

        // Vault core (if vault level)
        var vaultX: Float? = null
        var vaultZ: Float? = null
        if (isVaultLevel) {
            vaultX = lastRoom.x + lastRoom.w * 0.5f
            vaultZ = lastRoom.z + lastRoom.d * 0.5f
        }

        // Distribute Terminals (Relays, Security Nodes, Augments)
        for (i in 1 until roomGrid.size) {
            val r = roomGrid[i]
            when {
                i == 1 -> terminals.add(TerminalDef("term_${index}_$i", r.x + 2f, r.z + 2f, "relay"))
                i == 2 -> terminals.add(TerminalDef("term_${index}_$i", r.x + r.w - 2f, r.z + 2f, "node"))
                i == 3 -> terminals.add(TerminalDef("term_${index}_$i", r.x + 2f, r.z + r.d - 2f, "augment"))
                i == 4 -> terminals.add(TerminalDef("term_${index}_$i", r.x + r.w - 2f, r.z + r.d - 2f, "node"))
                else -> terminals.add(TerminalDef("term_${index}_$i", r.x + 2f, r.z + 2f, "relay"))
            }

            // Crates (Cover and loot)
            crates.add(CrateDef(r.x + 4f, r.z + 3f, loot = if (rng.nextFloat() < 0.5f) 1 else 2))
            crates.add(CrateDef(r.x + 4f, r.z + 4f, loot = 0))

            // Security Cameras in important rooms
            if (i % 2 == 1) {
                cameras.add(
                    CameraDef(
                        id = "cam_${index}_$i",
                        x = r.x + 1f,
                        z = r.z + 1f,
                        yaw = (rng.nextFloat() * Math.PI.toFloat() * 2f),
                        sweep = 0.9f
                    )
                )
            }
        }

        // Breachable walls between rooms
        for (i in 0 until min(2, roomGrid.size - 1)) {
            val r = roomGrid[i]
            val vx = (r.x / VoxelTypes.VOX_SIZE).toInt()
            val vz = (r.z / VoxelTypes.VOX_SIZE).toInt()
            val cells = mutableListOf<Pair<Int, Int>>()
            for (offset in 0..4) {
                cells.add(Pair(vx + offset, vz))
            }
            breaches.add(
                BreachDef(
                    id = "breach_${index}_$i",
                    cells = cells,
                    dirX = 0,
                    dirZ = 1,
                    depth = 4
                )
            )
        }

        // Spawn Guards with distinctive specialties
        for (i in 1 until roomGrid.size) {
            val r = roomGrid[i]
            val type = when {
                isVaultLevel && i == roomGrid.size - 1 -> "overseer" // Final boss
                index == 1 && i == roomGrid.size - 1 -> "warden"    // Mid boss
                i % 4 == 0 -> "drone"
                i % 4 == 1 -> "hound"
                i % 4 == 2 -> "sniper"
                i % 4 == 3 -> "nullifier"
                else -> "patrol"
            }

            val route = listOf(
                Pair(r.x + 2f, r.z + 2f),
                Pair(r.x + r.w - 2f, r.z + 2f),
                Pair(r.x + r.w - 2f, r.z + r.d - 2f),
                Pair(r.x + 2f, r.z + r.d - 2f)
            )

            guards.add(
                GuardSpawnDef(
                    id = "guard_${index}_$i",
                    type = type,
                    x = r.x + r.w * 0.5f,
                    z = r.z + r.d * 0.5f,
                    yaw = rng.nextFloat() * 6.28f,
                    route = route
                )
            )
        }

        return LevelData(
            index = index,
            name = name,
            rooms = rooms,
            corridors = corridors,
            doors = doors,
            terminals = terminals,
            crates = crates,
            cameras = cameras,
            lamps = lamps,
            breaches = breaches,
            guards = guards,
            spawnX = spawnX,
            spawnZ = spawnZ,
            liftX = liftX,
            liftZ = liftZ,
            vaultX = vaultX,
            vaultZ = vaultZ,
            stairsX = stairsX,
            stairsZ = stairsZ
        )
    }
}
