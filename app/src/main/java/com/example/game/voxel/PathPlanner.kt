package com.example.game.voxel

import com.example.engine.math.Vec3
import java.util.PriorityQueue
import kotlin.math.abs

/**
 * Grid-based A* Pathfinding Planner for guard patrol and alert investigations.
 */
class PathPlanner(val grid: VoxelGrid) {

    private data class Node(
        val x: Int,
        val z: Int,
        var gCost: Float = Float.MAX_VALUE,
        var hCost: Float = 0f,
        var parent: Node? = null
    ) : Comparable<Node> {
        val fCost: Float get() = gCost + hCost
        override fun compareTo(other: Node): Int = fCost.compareTo(other.fCost)
    }

    private fun heuristic(x1: Int, z1: Int, x2: Int, z2: Int): Float {
        return (abs(x1 - x2) + abs(z1 - z2)).toFloat()
    }

    private fun isPassable(cellX: Int, cellZ: Int): Boolean {
        if (!grid.inBounds(cellX, 0, cellZ)) return false
        val floorV = grid.get(cellX, 0, cellZ)
        if (floorV != VoxelTypes.V_FLOOR) return false

        // Check height 1 & 2 for obstacles
        val wallV1 = grid.get(cellX, 1, cellZ)
        val wallV2 = grid.get(cellX, 2, cellZ)
        return (wallV1 == VoxelTypes.V_EMPTY && wallV2 == VoxelTypes.V_EMPTY)
    }

    fun findPath(startX: Float, startZ: Float, targetX: Float, targetZ: Float): List<Vec3>? {
        val s = VoxelTypes.VOX_SIZE
        val startGx = (startX / s).toInt()
        val startGz = (startZ / s).toInt()
        val targetGx = (targetX / s).toInt()
        val targetGz = (targetZ / s).toInt()

        if (startGx == targetGx && startGz == targetGz) {
            return listOf(Vec3(targetX, 0f, targetZ))
        }

        val openSet = PriorityQueue<Node>()
        val allNodes = mutableMapOf<String, Node>()

        val startNode = Node(startGx, startGz, gCost = 0f, hCost = heuristic(startGx, startGz, targetGx, targetGz))
        openSet.add(startNode)
        allNodes["$startGx:$startGz"] = startNode

        val directions = arrayOf(
            Pair(1, 0), Pair(-1, 0), Pair(0, 1), Pair(0, -1)
        )

        var iterations = 0
        val maxIterations = 800 // Guard against budget mobile lag spikes

        while (openSet.isNotEmpty() && iterations < maxIterations) {
            iterations++
            val current = openSet.poll() ?: break

            if (current.x == targetGx && current.z == targetGz) {
                // Reconstruct path
                val path = mutableListOf<Vec3>()
                var curr: Node? = current
                while (curr != null) {
                    path.add(0, Vec3((curr.x + 0.5f) * s, 0f, (curr.z + 0.5f) * s))
                    curr = curr.parent
                }
                return smoothPath(path)
            }

            for (dir in directions) {
                val nx = current.x + dir.first
                val nz = current.z + dir.second

                if (!isPassable(nx, nz) && !(nx == targetGx && nz == targetGz)) {
                    continue
                }

                val tentativeG = current.gCost + 1.0f
                val key = "$nx:$nz"
                val neighbor = allNodes.getOrPut(key) { Node(nx, nz) }

                if (tentativeG < neighbor.gCost) {
                    neighbor.gCost = tentativeG
                    neighbor.hCost = heuristic(nx, nz, targetGx, targetGz)
                    neighbor.parent = current
                    openSet.remove(neighbor)
                    openSet.add(neighbor)
                }
            }
        }

        return null // Path unreachable
    }

    private fun smoothPath(rawPath: List<Vec3>): List<Vec3> {
        if (rawPath.size <= 2) return rawPath
        val smoothed = mutableListOf<Vec3>()
        smoothed.add(rawPath.first())

        for (i in 1 until rawPath.size - 1) {
            // Keep every 2nd or 3rd node for natural curved movement
            if (i % 2 == 0) {
                smoothed.add(rawPath[i])
            }
        }
        smoothed.add(rawPath.last())
        return smoothed
    }
}
