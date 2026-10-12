package com.example.game

import android.content.Context
import com.example.engine.math.Vec3
import com.example.engine.thermal.ThermalGovernor
import com.example.game.audio.GameAudio
import com.example.game.entities.Guard
import com.example.game.entities.GuardArchetypes
import com.example.game.entities.GuardState
import com.example.game.entities.OperativeKit
import com.example.game.entities.Player
import com.example.game.entities.SmokeCloud
import com.example.game.entities.ThrownGadget
import com.example.game.voxel.ComplexGen
import com.example.game.voxel.DoorDef
import com.example.game.voxel.LevelData
import com.example.game.voxel.PathPlanner
import com.example.game.voxel.TerminalDef
import com.example.game.voxel.VoxelGrid
import com.example.game.voxel.VoxelTypes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class GamePhase {
    TITLE,
    PLAYING,
    AUGMENT_SELECT,
    GAME_OVER,
    MISSION_COMPLETE
}

data class Tracer(
    val start: Vec3,
    val end: Vec3,
    val color: FloatArray,
    var life: Float = 0.15f
)

data class InteractionTarget(
    val prompt: String,
    val action: () -> Unit
)

data class GameScore(
    val coreRecovered: Boolean = false,
    val intelCount: Int = 0,
    val takedowns: Int = 0,
    val alarmsRaised: Int = 0,
    val totalTimeSeconds: Float = 0f,
    val finalScore: Int = 0
)

/**
 * GameEngine coordinates the simulation loop, entity updates, audio,
 * procedural levels, and scoring logic.
 */
class GameEngine(
    val context: Context? = null,
    val thermalGovernor: ThermalGovernor = ThermalGovernor(context)
) {
    val audio = GameAudio(context)

    private val _phase = MutableStateFlow(GamePhase.TITLE)
    val phase: StateFlow<GamePhase> = _phase.asStateFlow()

    private val _scoreState = MutableStateFlow(GameScore())
    val scoreState: StateFlow<GameScore> = _scoreState.asStateFlow()

    private val _interactionPrompt = MutableStateFlow<String?>(null)
    val interactionPrompt: StateFlow<String?> = _interactionPrompt.asStateFlow()

    private val _objectiveText = MutableStateFlow("Enter facility")
    val objectiveText: StateFlow<String> = _objectiveText.asStateFlow()

    private val _objectiveDistance = MutableStateFlow(0f)
    val objectiveDistance: StateFlow<Float> = _objectiveDistance.asStateFlow()

    var activeLevelIndex: Int = 0
    lateinit var levels: List<LevelData>
    lateinit var activeLevel: LevelData
    lateinit var grid: VoxelGrid
    lateinit var planner: PathPlanner
    lateinit var player: Player

    val guards = mutableListOf<Guard>()
    val gadgets = mutableListOf<ThrownGadget>()
    val smokeClouds = mutableListOf<SmokeCloud>()
    val tracers = mutableListOf<Tracer>()

    var alertLevel: Int = 0
    var isLockdown: Boolean = false
    var nodesHacked: Int = 0
    var selectedSlot: Int = 0 // 0=Weapon, 1=Gadget, 2=Medkit, 3=Smoke

    var cameraAzimuth: Float = 0f
    var runStartTimeMs: Long = 0L
    var pendingInteraction: InteractionTarget? = null

    // Callbacks to request mesh updates from GL thread
    var onMeshNeedsRebuild: (() -> Unit)? = null

    init {
        initGame(System.currentTimeMillis())
    }

    fun initGame(seed: Long) {
        levels = ComplexGen.generateComplex(seed, levelCount = 3)
        loadLevel(0, OperativeKit.GHOST)
    }

    fun startMission(kit: OperativeKit) {
        loadLevel(0, kit)
        runStartTimeMs = System.currentTimeMillis()
        _phase.value = GamePhase.PLAYING
        updateObjective()
    }

    fun loadLevel(levelIndex: Int, kit: OperativeKit = player.kit) {
        activeLevelIndex = levelIndex
        activeLevel = levels[levelIndex]
        grid = VoxelGrid()
        grid.rasterize(activeLevel)
        planner = PathPlanner(grid)

        player = Player(kit)
        player.pos.set(activeLevel.spawnX, 0f, activeLevel.spawnZ)

        guards.clear()
        for (gDef in activeLevel.guards) {
            guards.add(
                Guard(
                    id = gDef.id,
                    type = gDef.type,
                    initialX = gDef.x,
                    initialZ = gDef.z,
                    initialYaw = gDef.yaw,
                    route = gDef.route
                )
            )
        }

        gadgets.clear()
        smokeClouds.clear()
        tracers.clear()
        onMeshNeedsRebuild?.invoke()
    }

    fun update(dt: Float, inputX: Float, inputZ: Float, aimYaw: Float?, crouch: Boolean) {
        if (_phase.value != GamePhase.PLAYING) return

        // 1. Update Player
        val stepped = player.update(dt, inputX, inputZ, cameraAzimuth, aimYaw, crouch, grid)

        // 2. Check Jamming aura
        var inJamField = false
        for (g in guards) {
            if (!g.isDown && g.def.jamRadius > 0f && g.jamSuppressedTimer <= 0f) {
                if (g.pos.distance(player.pos) <= g.def.jamRadius) {
                    inJamField = true
                    break
                }
            }
        }
        player.isJammed = inJamField

        // 3. Emit Footstep Noise
        if (stepped && player.noiseRadius > 0f) {
            emitNoise(player.pos.x, player.pos.z, player.noiseRadius)
        }

        // 4. Update Guards
        for (g in guards) {
            g.update(
                dt = dt,
                player = player,
                grid = grid,
                planner = planner,
                onShootPlayer = { shooter ->
                    val y = 1.1f
                    tracers.add(
                        Tracer(
                            start = Vec3(shooter.pos.x, y, shooter.pos.z),
                            end = Vec3(player.pos.x, y, player.pos.z),
                            color = floatArrayOf(0.9f, 0.2f, 0.2f, 1f)
                        )
                    )
                    audio.playHeavyShot()
                    emitNoise(shooter.pos.x, shooter.pos.z, 16f)

                    // Accuracy check
                    val dist = shooter.pos.distance(player.pos)
                    val hitChance = if (shooter.def.isSniper) 0.85f else (0.75f - dist * 0.025f)
                    if (Math.random() < hitChance) {
                        player.damage(shooter.def.damage)
                        if (player.isDead) {
                            endMission(victory = false)
                        }
                    }
                },
                onRaiseAlarm = {
                    alertLevel = min(3, alertLevel + 1)
                    audio.playAlarm()
                }
            )
        }

        // 5. Update Thrown Gadgets
        for (i in gadgets.indices.reversed()) {
            val gad = gadgets[i]
            gad.progress += dt / gad.duration
            if (gad.progress >= 1.0f) {
                gadgets.removeAt(i)
                landGadget(gad)
            }
        }

        // 6. Update Smoke Clouds
        for (i in smokeClouds.indices.reversed()) {
            val sc = smokeClouds[i]
            sc.remainingTime -= dt
            if (sc.remainingTime <= 0f) {
                smokeClouds.removeAt(i)
            }
        }

        // 7. Update Tracers
        for (i in tracers.indices.reversed()) {
            val tr = tracers[i]
            tr.life -= dt
            if (tr.life <= 0f) {
                tracers.removeAt(i)
            }
        }

        // 8. Evaluate Proximity Interaction
        evaluateInteractions()

        // 9. Update Objective Distance
        updateObjective()
    }

    fun fireEquipped() {
        if (_phase.value != GamePhase.PLAYING || player.isDead) return

        when (selectedSlot) {
            0 -> fireWeapon()
            1 -> throwSelectedGadget(player.gadget.kind)
            2 -> {
                if (player.heal()) {
                    audio.playTerminalBeep(true)
                } else {
                    audio.playTerminalBeep(false)
                }
            }
            3 -> {
                if (player.smokeBombs > 0) {
                    player.smokeBombs--
                    throwSelectedGadget("smoke")
                } else {
                    audio.playTerminalBeep(false)
                }
            }
        }
    }

    private fun fireWeapon() {
        val w = player.weapon
        if (player.reloadTimer >= 0f || player.actionLock > 0f) return
        if (w.clipLeft <= 0) {
            audio.playTerminalBeep(false)
            return
        }

        w.clipLeft--
        if (w.sedate) {
            audio.playSuppressedShot()
        } else {
            audio.playSuppressedShot()
        }

        emitNoise(player.pos.x, player.pos.z, w.noise)

        // Hitscan raycast along yaw
        val dx = sin(player.yaw)
        val dz = cos(player.yaw)
        val range = 24f

        var hitGuard: Guard? = null
        var hitDist = range

        for (g in guards) {
            if (g.isDown) continue
            val gx = g.pos.x - player.pos.x
            val gz = g.pos.z - player.pos.z
            val t = gx * dx + gz * dz
            if (t > 0.4f && t < hitDist) {
                val perp = Math.abs(gx * dz - gz * dx)
                if (perp < 0.65f) {
                    hitGuard = g
                    hitDist = t
                }
            }
        }

        val hitPos = Vec3(player.pos.x + dx * hitDist, 1.1f, player.pos.z + dz * hitDist)
        tracers.add(
            Tracer(
                start = Vec3(player.pos.x, 1.1f, player.pos.z),
                end = hitPos,
                color = floatArrayOf(0.37f, 0.82f, 0.79f, 1f)
            )
        )

        hitGuard?.let { g ->
            if (w.sedate) {
                if (g.def.isFlying || g.def.isBoss) {
                    audio.playTerminalBeep(false)
                } else {
                    g.tranqTimer = 1.6f
                    audio.playTerminalBeep(true)
                }
            } else {
                g.hp -= w.dmg * (if (g.def.isBoss && g.bossPhase == 1) 2.0f else 1.0f)
                if (g.hp <= 0f) {
                    g.state = GuardState.DOWN
                    audio.playTakedown()
                } else {
                    g.suspicion = 100f
                    g.state = GuardState.COMBAT
                    g.lastSeenPos = player.pos.copy()
                }
            }
        }
    }

    fun meleeAttack(): Boolean {
        if (_phase.value != GamePhase.PLAYING || player.meleeCooldown > 0f) return false
        player.meleeCooldown = 0.5f

        var nearestGuard: Guard? = null
        var nearestDist = 1.8f
        for (g in guards) {
            if (!g.isDown && !g.def.isFlying) {
                val d = g.pos.distance(player.pos)
                if (d < nearestDist) {
                    nearestDist = d
                    nearestGuard = g
                }
            }
        }

        nearestGuard?.let { g ->
            player.yaw = atan2(g.pos.x - player.pos.x, g.pos.z - player.pos.z)
            audio.playTakedown()
            emitNoise(player.pos.x, player.pos.z, 3.0f)

            g.hp -= if (g.def.isBoss) 15f else 35f
            if (g.hp <= 0f) {
                g.state = GuardState.DOWN
            } else {
                g.suspicion = 100f
                g.state = GuardState.COMBAT
                g.lastSeenPos = player.pos.copy()
            }
            return true
        }
        return false
    }

    private fun throwSelectedGadget(kind: String) {
        if (kind != "smoke") {
            if (player.gadget.charges <= 0) {
                audio.playTerminalBeep(false)
                return
            }
            player.gadget.charges--
        }

        val targetX = player.pos.x + sin(player.yaw) * 8f
        val targetZ = player.pos.z + cos(player.yaw) * 8f

        gadgets.add(
            ThrownGadget(
                kind = kind,
                startPos = player.pos.copy(),
                targetPos = Vec3(targetX, 0f, targetZ)
            )
        )
        audio.playSuppressedShot()
    }

    private fun landGadget(gad: ThrownGadget) {
        val dest = gad.targetPos
        when (gad.kind) {
            "noise" -> {
                audio.playTerminalBeep(true)
                emitNoise(dest.x, dest.z, 14f)
            }
            "smoke" -> {
                audio.playSuppressedShot()
                smokeClouds.add(SmokeCloud(dest.copy(), radius = 4f, remainingTime = 12f))
            }
            "emp" -> {
                audio.playEmpBlast()
                // Disable guards/drones in radius
                for (g in guards) {
                    if (g.pos.distance(dest) <= 6f && !g.isDown) {
                        if (g.def.isFlying) {
                            g.state = GuardState.DOWN
                        } else if (g.def.isBoss) {
                            g.stunTimer = 3.5f
                        } else if (g.def.jamRadius > 0f) {
                            g.jamSuppressedTimer = 20f
                        }
                    }
                }
            }
        }
    }

    fun emitNoise(x: Float, z: Float, radius: Float) {
        for (g in guards) {
            g.hear(x, z, radius)
        }
    }

    private fun evaluateInteractions() {
        val p = player.pos
        var cand: InteractionTarget? = null

        // 1. Silent Takedown (unaware guards)
        for (g in guards) {
            if (!g.isDown && !g.def.isFlying && !g.def.isBoss) {
                if (g.pos.distance(p) <= 1.6f && g.state <= GuardState.CURIOUS) {
                    cand = InteractionTarget("SILENT TAKEDOWN") {
                        g.state = GuardState.DOWN
                        audio.playTakedown()
                        emitNoise(p.x, p.z, 2.0f)
                    }
                    break
                }
            }
        }

        // 2. Terminals (Relays, Nodes, Augments)
        if (cand == null) {
            for (t in activeLevel.terminals) {
                val tPos = Vec3(t.x, 0f, t.z)
                if (tPos.distance(p) <= 1.8f) {
                    cand = when (t.kind) {
                        "relay" -> InteractionTarget("DISABLE RELAY") {
                            alertLevel = max(0, alertLevel - 1)
                            audio.playTerminalBeep(true)
                        }
                        "node" -> InteractionTarget("HACK SECURITY NODE") {
                            nodesHacked++
                            audio.playTerminalBeep(true)
                        }
                        "augment" -> InteractionTarget("AUGMENT TERMINAL") {
                            audio.playTerminalBeep(true)
                            _phase.value = GamePhase.AUGMENT_SELECT
                        }
                        else -> null
                    }
                    if (cand != null) break
                }
            }
        }

        // 3. Vault Core extraction
        if (cand == null && activeLevel.vaultX != null && activeLevel.vaultZ != null && !player.hasCore) {
            val vPos = Vec3(activeLevel.vaultX!!, 0f, activeLevel.vaultZ!!)
            if (vPos.distance(p) <= 2.2f) {
                if (nodesHacked >= 2) {
                    cand = InteractionTarget("EXTRACT THE CORE") {
                        player.hasCore = true
                        isLockdown = true
                        alertLevel = 3
                        audio.playAlarm()
                    }
                } else {
                    cand = InteractionTarget("CORE LOCKED (HACK 2 NODES)") {
                        audio.playTerminalBeep(false)
                    }
                }
            }
        }

        // 4. Lift / Floor Transition
        if (cand == null) {
            val liftPos = Vec3(activeLevel.liftX, 0f, activeLevel.liftZ)
            if (liftPos.distance(p) <= 2.0f) {
                cand = if (player.hasCore && activeLevelIndex == 0) {
                    InteractionTarget("EXTRACTION LIFT - ESCAPE") {
                        endMission(victory = true)
                    }
                } else if (!player.hasCore && activeLevelIndex < levels.size - 1) {
                    InteractionTarget("DESCEND TO SUBLEVEL B${activeLevelIndex + 2}") {
                        loadLevel(activeLevelIndex + 1)
                    }
                } else if (player.hasCore && activeLevelIndex > 0) {
                    InteractionTarget("ASCEND TO SUBLEVEL B${activeLevelIndex}") {
                        loadLevel(activeLevelIndex - 1)
                    }
                } else null
            }
        }

        // 5. Breachable Walls
        if (cand == null && player.gadget.kind == "thermite" && player.gadget.charges > 0) {
            for (b in activeLevel.breaches) {
                val bPos = Vec3(b.cells[0].first * VoxelTypes.VOX_SIZE, 0f, b.cells[0].second * VoxelTypes.VOX_SIZE)
                if (bPos.distance(p) <= 2.4f) {
                    cand = InteractionTarget("PLANT THERMITE BREACH") {
                        player.gadget.charges--
                        grid.detonateBreach(b)
                        audio.playBreachExplosion()
                        emitNoise(p.x, p.z, 22f)
                        onMeshNeedsRebuild?.invoke()
                    }
                    break
                }
            }
        }

        pendingInteraction = cand
        _interactionPrompt.value = cand?.prompt
    }

    fun triggerInteraction() {
        pendingInteraction?.action?.invoke()
    }

    private fun updateObjective() {
        if (player.hasCore) {
            _objectiveText.value = "ESCAPE WITH CORE TO EXTRACTION LIFT"
            val lift = Vec3(levels[0].liftX, 0f, levels[0].liftZ)
            _objectiveDistance.value = player.pos.distance(lift)
        } else if (activeLevel.vaultX != null) {
            if (nodesHacked < 2) {
                _objectiveText.value = "HACK SECURITY NODES ($nodesHacked/2)"
            } else {
                _objectiveText.value = "STEAL CLASSIFIED CORE IN VAULT"
            }
            val vault = Vec3(activeLevel.vaultX!!, 0f, activeLevel.vaultZ!!)
            _objectiveDistance.value = player.pos.distance(vault)
        } else {
            _objectiveText.value = "FIND LIFT TO DEEP SECTORS"
            val lift = Vec3(activeLevel.liftX, 0f, activeLevel.liftZ)
            _objectiveDistance.value = player.pos.distance(lift)
        }
    }

    fun endMission(victory: Boolean) {
        val elapsedSec = (System.currentTimeMillis() - runStartTimeMs) / 1000f
        var score = 0
        if (victory) score += 1000
        score += player.intelCollected * 150
        score -= alertLevel * 100
        score = max(0, score)

        _scoreState.value = GameScore(
            coreRecovered = player.hasCore,
            intelCount = player.intelCollected,
            takedowns = 0,
            alarmsRaised = alertLevel,
            totalTimeSeconds = elapsedSec,
            finalScore = score
        )

        _phase.value = if (victory) GamePhase.MISSION_COMPLETE else GamePhase.GAME_OVER
    }
}
