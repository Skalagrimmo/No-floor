package com.example.game.entities

import com.example.engine.math.Vec3
import com.example.game.voxel.PathPlanner
import com.example.game.voxel.VoxelGrid
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class GuardState {
    PATROL,
    CURIOUS,
    INVESTIGATE,
    ALERTED,
    COMBAT,
    SEARCH,
    DOWN
}

data class GuardArchetype(
    val type: String,
    val maxHp: Float,
    val walkSpeed: Float,
    val runSpeed: Float,
    val viewRange: Float,
    val fovRad: Float,
    val hearingMul: Float,
    val damage: Float,
    val fireDelay: Float,
    val isFlying: Boolean = false,
    val isMelee: Boolean = false,
    val isSniper: Boolean = false,
    val isBoss: Boolean = false,
    val jamRadius: Float = 0f,
    val scentRadius: Float = 0f
)

object GuardArchetypes {
    val PATROL = GuardArchetype("patrol", 50f, 2.1f, 4.4f, 13f, 1.22f, 1.0f, 11f, 0.75f)
    val TECH = GuardArchetype("tech", 40f, 1.9f, 3.8f, 11f, 1.13f, 0.9f, 7f, 1.0f)
    val HEAVY = GuardArchetype("heavy", 160f, 1.5f, 3.4f, 12f, 1.05f, 0.8f, 18f, 0.6f)
    val DRONE = GuardArchetype("drone", 20f, 2.7f, 5.0f, 16f, 0.45f, 0.4f, 0f, 99f, isFlying = true)
    val HOUND = GuardArchetype("hound", 35f, 2.4f, 5.6f, 9f, 1.1f, 1.6f, 15f, 99f, isMelee = true, scentRadius = 7f)
    val SNIPER = GuardArchetype("sniper", 40f, 1.2f, 3.0f, 24f, 0.5f, 0.7f, 35f, 1.8f, isSniper = true)
    val NULLIFIER = GuardArchetype("nullifier", 60f, 1.4f, 3.6f, 10f, 1.1f, 1.0f, 8f, 1.2f, jamRadius = 6.0f)
    val WARDEN = GuardArchetype("warden", 300f, 1.7f, 4.8f, 14f, 1.6f, 1.2f, 10f, 0.5f, isBoss = true)
    val OVERSEER = GuardArchetype("overseer", 400f, 1.6f, 4.0f, 16f, 1.3f, 1.0f, 12f, 0.35f, isBoss = true)

    fun forType(type: String): GuardArchetype {
        return when (type) {
            "tech" -> TECH
            "heavy" -> HEAVY
            "drone" -> DRONE
            "hound" -> HOUND
            "sniper" -> SNIPER
            "nullifier" -> NULLIFIER
            "warden" -> WARDEN
            "overseer" -> OVERSEER
            else -> PATROL
        }
    }
}

/**
 * AI simulation for Facility Guards, Drones, Hounds, Snipers, and Bosses.
 */
class Guard(
    val id: String,
    val type: String,
    initialX: Float,
    initialZ: Float,
    initialYaw: Float,
    val route: List<Pair<Float, Float>>
) {
    val def: GuardArchetype = GuardArchetypes.forType(type)
    val pos = Vec3(initialX, 0f, initialZ)
    var yaw: Float = initialYaw
    var scanYaw: Float = initialYaw

    var hp: Float = def.maxHp
    var state: GuardState = GuardState.PATROL
    var suspicion: Float = 0f

    var routeIndex: Int = 0
    var activePath: List<Vec3>? = null
    var pathIndex: Int = 0

    var lastSeenPos: Vec3? = null
    var radioTargetPos: Vec3? = null
    var radioTimer: Float = -1f
    var aimTimer: Float = 0f
    var searchTimer: Float = 0f
    var waitTimer: Float = 0f
    var tranqTimer: Float = 0f
    var stunTimer: Float = 0f
    var jamSuppressedTimer: Float = 0f
    var biteCooldown: Float = 0f

    // Boss Overseer phases: 0 = sweeping volley fire, 1 = vent vulnerable window
    var bossPhase: Int = 0
    var bossPhaseTimer: Float = 0f

    val isDown: Boolean get() = state == GuardState.DOWN
    val isAlerted: Boolean get() = state == GuardState.ALERTED || state == GuardState.COMBAT

    fun hear(noiseX: Float, noiseZ: Float, radius: Float): Boolean {
        if (isDown) return false
        val dist = hypot(pos.x - noiseX, pos.z - noiseZ)
        if (dist > radius * def.hearingMul) return false
        if (state == GuardState.COMBAT || state == GuardState.ALERTED) return false

        lastSeenPos = Vec3(noiseX, 0f, noiseZ)
        suspicion = max(suspicion, 65f)
        state = GuardState.INVESTIGATE
        activePath = null
        return true
    }

    fun update(
        dt: Float,
        player: Player,
        grid: VoxelGrid,
        planner: PathPlanner,
        onShootPlayer: (Guard) -> Unit,
        onRaiseAlarm: (Guard) -> Unit
    ) {
        if (isDown) return

        if (stunTimer > 0f) {
            stunTimer -= dt
            return
        }
        if (tranqTimer > 0f) {
            tranqTimer -= dt
            if (tranqTimer <= 0f) {
                state = GuardState.DOWN
                return
            }
        }
        if (jamSuppressedTimer > 0f) jamSuppressedTimer -= dt
        if (biteCooldown > 0f) biteCooldown -= dt

        // Drone rotating searchlight
        if (def.isFlying) {
            if (isAlerted && !player.isDead) {
                scanYaw = turnTowards(scanYaw, atan2(player.pos.x - pos.x, player.pos.z - pos.z), 6f * dt)
            } else {
                scanYaw += 0.9f * dt
            }
        }

        // 1. Sensory Perception (Vision, Scent, Proximity)
        sense(dt, player, grid)

        // 2. State Actions & Movement
        act(dt, player, planner, onShootPlayer, onRaiseAlarm)

        // 3. Path Following
        followPath(dt)
    }

    private fun sense(dt: Float, player: Player, grid: VoxelGrid) {
        if (player.isDead) {
            suspicion = max(0f, suspicion - 10f * dt)
            return
        }

        val dx = player.pos.x - pos.x
        val dz = player.pos.z - pos.z
        val dist = hypot(dx, dz)

        // Hound Scent: detects player through walls up to scent radius
        if (def.scentRadius > 0f && dist <= def.scentRadius) {
            suspicion = min(100f, suspicion + (if (dist < 2f) 300f else 80f) * dt)
            lastSeenPos = player.pos.copy()
        }

        // Boss Proximity Radar
        if (def.isBoss && dist < 3.5f) {
            suspicion = min(100f, suspicion + 140f * dt)
            lastSeenPos = player.pos.copy()
        }

        // Vision Cone Check
        val facing = if (def.isFlying) scanYaw else yaw
        val angleToPlayer = atan2(dx, dz)
        var diff = angleToPlayer - facing
        diff = atan2(sin(diff), cos(diff))

        val inFov = abs(diff) < (def.fovRad * 0.5f)
        val maxView = def.viewRange * (if (player.isCrouching && !def.isFlying) 0.65f else 1.0f)

        if (dist <= maxView && inFov) {
            val hasLos = grid.hasLineOfSight(pos.x, pos.z, player.pos.x, player.pos.z, lowCoverCheck = player.isCrouching && !def.isFlying)
            if (hasLos) {
                val prox = 1.0f - (dist / maxView)
                var gain = (20f + 140f * prox * prox) * dt
                if (player.isMoving && !player.isCrouching) gain *= 1.4f
                if (dist < 1.5f) gain = 400f * dt // Point blank detection

                suspicion = min(100f, suspicion + gain)
                lastSeenPos = player.pos.copy()
            } else {
                suspicion = max(0f, suspicion - 8f * dt)
            }
        } else {
            suspicion = max(0f, suspicion - 6f * dt)
        }

        // State Transitions based on suspicion
        if (suspicion >= 100f && state != GuardState.ALERTED && state != GuardState.COMBAT) {
            state = GuardState.ALERTED
            activePath = null
        } else if (suspicion >= 60f && state == GuardState.PATROL) {
            state = GuardState.INVESTIGATE
            activePath = null
            searchTimer = 6f
        } else if (suspicion >= 25f && state == GuardState.PATROL) {
            state = GuardState.CURIOUS
            activePath = null
        }
    }

    private fun act(
        dt: Float,
        player: Player,
        planner: PathPlanner,
        onShootPlayer: (Guard) -> Unit,
        onRaiseAlarm: (Guard) -> Unit
    ) {
        when (state) {
            GuardState.PATROL -> {
                if (activePath == null && route.isNotEmpty()) {
                    val wp = route[routeIndex % route.size]
                    activePath = planner.findPath(pos.x, pos.z, wp.first, wp.second)
                    pathIndex = 0
                }
            }

            GuardState.CURIOUS -> {
                lastSeenPos?.let {
                    val wantYaw = atan2(it.x - pos.x, it.z - pos.z)
                    yaw = turnTowards(yaw, wantYaw, 3f * dt)
                }
            }

            GuardState.INVESTIGATE -> {
                if (activePath == null && lastSeenPos != null) {
                    activePath = planner.findPath(pos.x, pos.z, lastSeenPos!!.x, lastSeenPos!!.z)
                    pathIndex = 0
                }
                if (activePath == null || pathIndex >= activePath!!.size) {
                    searchTimer -= dt
                    yaw += 1.2f * dt
                    if (searchTimer <= 0f) {
                        state = GuardState.PATROL
                        suspicion = 0f
                    }
                }
            }

            GuardState.ALERTED -> {
                // Drone, Hound, Sniper or Boss goes straight to combat
                if (def.isFlying || def.isMelee || def.isSniper || def.isBoss) {
                    state = GuardState.COMBAT
                    activePath = null
                    return
                }

                // Radio call countdown
                if (radioTimer < 0f) radioTimer = 0f
                radioTimer += dt
                if (radioTimer >= 2.5f) {
                    radioTimer = -1f
                    state = GuardState.COMBAT
                    onRaiseAlarm(this)
                }
            }

            GuardState.COMBAT -> {
                val dx = player.pos.x - pos.x
                val dz = player.pos.z - pos.z
                val dist = hypot(dx, dz)
                yaw = turnTowards(yaw, atan2(dx, dz), 6f * dt)

                // Melee hound attack
                if (def.isMelee) {
                    if (dist <= 1.4f && biteCooldown <= 0f) {
                        biteCooldown = 1.0f
                        player.damage(def.damage)
                    } else if (activePath == null) {
                        activePath = planner.findPath(pos.x, pos.z, player.pos.x, player.pos.z)
                        pathIndex = 0
                    }
                    return
                }

                // Boss Overseer Volley / Vent cycle
                if (def.isBoss) {
                    bossPhaseTimer += dt
                    if (bossPhase == 0 && bossPhaseTimer >= 2.4f) {
                        bossPhase = 1 // Switch to vent window
                        bossPhaseTimer = 0f
                    } else if (bossPhase == 1 && bossPhaseTimer >= 2.8f) {
                        bossPhase = 0 // Return to volley fire
                        bossPhaseTimer = 0f
                    }
                }

                if (bossPhase == 1) {
                    // Venting: cannot fire
                    return
                }

                // Ranged attack firing
                aimTimer += dt
                if (aimTimer >= def.fireDelay) {
                    aimTimer = 0f
                    onShootPlayer(this)
                }

                // Maintain tactical combat distance
                if (dist > 8f && activePath == null) {
                    activePath = planner.findPath(pos.x, pos.z, player.pos.x, player.pos.z)
                    pathIndex = 0
                }
            }

            GuardState.SEARCH -> {
                searchTimer -= dt
                yaw += 1.4f * dt
                if (searchTimer <= 0f) {
                    state = GuardState.PATROL
                    suspicion = 0f
                    activePath = null
                }
            }

            GuardState.DOWN -> {}
        }
    }

    private fun followPath(dt: Float) {
        val path = activePath ?: return
        if (pathIndex >= path.size) {
            activePath = null
            if (state == GuardState.PATROL) {
                routeIndex++
            }
            return
        }

        val target = path[pathIndex]
        val dx = target.x - pos.x
        val dz = target.z - pos.z
        val dist = hypot(dx, dz)

        if (dist < 0.35f) {
            pathIndex++
            return
        }

        val speed = if (isAlerted) def.runSpeed else def.walkSpeed
        yaw = turnTowards(yaw, atan2(dx, dz), 5f * dt)
        pos.x += (dx / dist) * speed * dt
        pos.z += (dz / dist) * speed * dt
    }

    private fun turnTowards(current: Float, target: Float, maxStep: Float): Float {
        var diff = target - current
        diff = atan2(sin(diff), cos(diff))
        if (abs(diff) <= maxStep) return target
        return current + Math.signum(diff) * maxStep
    }
}
