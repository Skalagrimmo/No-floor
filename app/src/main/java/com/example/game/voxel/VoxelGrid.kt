package com.example.game.voxel

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * High-performance 3D Voxel Grid for real-time collision detection,
 * raymarching line-of-sight queries, and procedural breach destruction.
 */
class VoxelGrid(
    val width: Int = VoxelTypes.GRID_W,
    val height: Int = VoxelTypes.GRID_H,
    val depth: Int = VoxelTypes.GRID_D
) {
    val voxels = ByteArray(width * height * depth)

    // Door states by key "gx:gz"
    val doorStates = mutableMapOf<String, DoorDef>()

    fun index(x: Int, y: Int, z: Int): Int {
        return x + z * width + y * width * depth
    }

    fun inBounds(x: Int, y: Int, z: Int): Boolean {
        return x in 0 until width && y in 0 until height && z in 0 until depth
    }

    fun get(x: Int, y: Int, z: Int): Byte {
        return if (inBounds(x, y, z)) voxels[index(x, y, z)] else VoxelTypes.V_WALL
    }

    fun set(x: Int, y: Int, z: Int, type: Byte) {
        if (inBounds(x, y, z)) {
            voxels[index(x, y, z)] = type
        }
    }

    fun clear() {
        voxels.fill(VoxelTypes.V_EMPTY)
        doorStates.clear()
    }

    fun rasterize(level: LevelData) {
        clear()

        // 1. Store doors
        for (d in level.doors) {
            val gx = (d.x / VoxelTypes.VOX_SIZE).toInt()
            val gz = (d.z / VoxelTypes.VOX_SIZE).toInt()
            doorStates["$gx:$gz"] = d
        }

        // 2. Rasterize Floor for Rooms
        for (r in level.rooms) {
            val vx0 = max(0, floor(r.x / VoxelTypes.VOX_SIZE).toInt())
            val vz0 = max(0, floor(r.z / VoxelTypes.VOX_SIZE).toInt())
            val vx1 = min(width - 1, ceil((r.x + r.w) / VoxelTypes.VOX_SIZE).toInt())
            val vz1 = min(depth - 1, ceil((r.z + r.d) / VoxelTypes.VOX_SIZE).toInt())

            for (z in vz0 until vz1) {
                for (x in vx0 until vx1) {
                    set(x, 0, z, VoxelTypes.V_FLOOR)
                }
            }
        }

        // 3. Rasterize Floor for Corridors
        for (c in level.corridors) {
            val vx0 = max(0, floor(c.x / VoxelTypes.VOX_SIZE).toInt())
            val vz0 = max(0, floor(c.z / VoxelTypes.VOX_SIZE).toInt())
            val vx1 = min(width - 1, ceil((c.x + c.w) / VoxelTypes.VOX_SIZE).toInt())
            val vz1 = min(depth - 1, ceil((c.z + c.d) / VoxelTypes.VOX_SIZE).toInt())

            for (z in vz0 until vz1) {
                for (x in vx0 until vx1) {
                    set(x, 0, z, VoxelTypes.V_FLOOR)
                }
            }
        }

        // 4. Pin door mouth as floor so wall growth leaves passage open
        for (d in level.doors) {
            val cx = (d.x / VoxelTypes.VOX_SIZE).toInt()
            val cz = (d.z / VoxelTypes.VOX_SIZE).toInt()
            for (i in -2..1) {
                val x = if (d.axis == 'z') cx else cx + i
                val z = if (d.axis == 'z') cz + i else cz
                set(x, 0, z, VoxelTypes.V_FLOOR)
            }
        }

        // 5. Grow walls around empty voxels adjacent to floors
        val wallCandidates = mutableListOf<Pair<Int, Int>>()
        for (z in 1 until depth - 1) {
            for (x in 1 until width - 1) {
                if (get(x, 0, z) != VoxelTypes.V_EMPTY) continue

                val hasFloorNeighbor = (
                    get(x + 1, 0, z) == VoxelTypes.V_FLOOR ||
                    get(x - 1, 0, z) == VoxelTypes.V_FLOOR ||
                    get(x, 0, z + 1) == VoxelTypes.V_FLOOR ||
                    get(x, 0, z - 1) == VoxelTypes.V_FLOOR
                )

                if (hasFloorNeighbor) {
                    wallCandidates.add(Pair(x, z))
                }
            }
        }

        for (cand in wallCandidates) {
            for (h in 1..VoxelTypes.WALL_HEIGHT_VOXELS) {
                set(cand.first, h, cand.second, VoxelTypes.V_WALL)
            }
        }

        // 6. Mark breachable cracked walls
        for (b in level.breaches) {
            for (cell in b.cells) {
                for (h in 1..VoxelTypes.WALL_HEIGHT_VOXELS) {
                    if (get(cell.first, h, cell.second) == VoxelTypes.V_WALL) {
                        set(cell.first, h, cell.second, VoxelTypes.V_CRACK)
                    }
                }
            }
        }

        // 7. Rasterize crates into waist-high cover
        for (c in level.crates) {
            val cx = (c.x / VoxelTypes.VOX_SIZE).toInt()
            val cz = (c.z / VoxelTypes.VOX_SIZE).toInt()
            for (dz in 0..1) {
                for (dx in 0..1) {
                    val x = cx + dx
                    val z = cz + dz
                    if (get(x, 0, z) == VoxelTypes.V_FLOOR) {
                        for (h in 1..VoxelTypes.COVER_HEIGHT_VOXELS) {
                            set(x, h, z, VoxelTypes.V_COVER)
                        }
                    }
                }
            }
        }
    }

    fun isBlocked(worldX: Float, worldZ: Float, radius: Float = 0.35f): Boolean {
        val offsets = arrayOf(
            Pair(-radius, -radius), Pair(radius, -radius),
            Pair(-radius, radius), Pair(radius, radius),
            Pair(0f, -radius), Pair(0f, radius),
            Pair(-radius, 0f), Pair(radius, 0f)
        )

        for (off in offsets) {
            val vx = (floor((worldX + off.first) / VoxelTypes.VOX_SIZE)).toInt()
            val vz = (floor((worldZ + off.second) / VoxelTypes.VOX_SIZE)).toInt()

            // Check walls (height 1 to 3)
            for (h in 1..3) {
                val v = get(vx, h, vz)
                if (v == VoxelTypes.V_WALL || v == VoxelTypes.V_CRACK || v == VoxelTypes.V_COVER) {
                    return true
                }
            }

            // Check closed locked doors
            val door = doorStates["$vx:$vz"]
            if (door != null && door.locked) {
                return true
            }
        }
        return false
    }

    fun hasLineOfSight(x0: Float, z0: Float, x1: Float, z1: Float, lowCoverCheck: Boolean): Boolean {
        val dx = x1 - x0
        val dz = z1 - z0
        val dist = hypot(dx, dz)
        val steps = max(1, ceil(dist / 0.25f).toInt())

        for (i in 1 until steps) {
            val t = i.toFloat() / steps
            val curX = x0 + dx * t
            val curZ = z0 + dz * t

            val vx = (floor(curX / VoxelTypes.VOX_SIZE)).toInt()
            val vz = (floor(curZ / VoxelTypes.VOX_SIZE)).toInt()

            val door = doorStates["$vx:$vz"]
            if (door != null && door.locked) return false

            val wallVoxel = get(vx, 2, vz)
            if (wallVoxel == VoxelTypes.V_WALL || wallVoxel == VoxelTypes.V_CRACK) return false

            if (lowCoverCheck) {
                val coverVoxel = get(vx, 1, vz)
                if (coverVoxel == VoxelTypes.V_COVER) return false
            }
        }
        return true
    }

    fun detonateBreach(breach: BreachDef) {
        for (cell in breach.cells) {
            for (k in 0 until breach.depth) {
                val vx = cell.first + breach.dirX * k
                val vz = cell.second + breach.dirZ * k
                set(vx, 0, vz, VoxelTypes.V_FLOOR)
                for (h in 1..VoxelTypes.WALL_HEIGHT_VOXELS) {
                    set(vx, h, vz, VoxelTypes.V_EMPTY)
                }
            }
        }
    }
}
