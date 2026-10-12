package com.example.game.entities

import com.example.engine.math.Vec3
import com.example.game.voxel.VoxelGrid
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class Weapon(
    val name: String,
    val dmg: Float,
    val sedate: Boolean,
    val noise: Float,
    val clipSize: Int,
    var clipLeft: Int,
    var reserve: Int,
    val reloadTime: Float
)

data class Gadget(
    val name: String,
    val kind: String, // "noise", "emp", "thermite"
    var charges: Int
)

enum class OperativeKit(
    val title: String,
    val desc: String
) {
    GHOST("Ghost", "Tranquilizer pistol + noise emitters. Silent and patient."),
    HACKER("Hacker", "Suppressed pistol + EMP pucks. Controls network systems."),
    BREACHER("Breacher", "Heavy sidearm + thermite charges. Makes new doors.")
}

/**
 * Operative Player character physics, weapons, gadgets, and stealth states.
 */
class Player(val kit: OperativeKit = OperativeKit.GHOST) {

    val pos = Vec3(20f, 0f, 20f)
    val vel = Vec3(0f, 0f, 0f)
    var yaw: Float = 0f

    var hp: Float = 100f
    var maxArmor: Int = 3
    var armor: Int = if (kit == OperativeKit.BREACHER) 2 else 0
    var focus: Float = 100f
    var isFocusing: Boolean = false

    val weapon: Weapon = when (kit) {
        OperativeKit.GHOST -> Weapon("Tranq Pistol", dmg = 0f, sedate = true, noise = 2.5f, clipSize = 4, clipLeft = 4, reserve = 8, reloadTime = 1.6f)
        OperativeKit.HACKER -> Weapon("Suppressed Pistol", dmg = 26f, sedate = false, noise = 5.0f, clipSize = 6, clipLeft = 6, reserve = 12, reloadTime = 1.4f)
        OperativeKit.BREACHER -> Weapon("Heavy Sidearm", dmg = 35f, sedate = false, noise = 7.0f, clipSize = 5, clipLeft = 5, reserve = 10, reloadTime = 1.5f)
    }

    val gadget: Gadget = when (kit) {
        OperativeKit.GHOST -> Gadget("Noise Emitter", "noise", charges = 3)
        OperativeKit.HACKER -> Gadget("EMP Puck", "emp", charges = 3)
        OperativeKit.BREACHER -> Gadget("Thermite Charge", "thermite", charges = 2)
    }

    var medkits: Int = 1
    var smokeBombs: Int = 2
    var hasCore: Boolean = false
    var intelCollected: Int = 0

    // Augment multipliers
    val augments = mutableListOf<String>()
    var hackMultiplier: Float = if (kit == OperativeKit.HACKER) 0.6f else 1.0f
    var noiseMultiplier: Float = 1.0f
    var focusRegenMultiplier: Float = 1.0f
    var isJamProof: Boolean = false

    // Action timers
    var isCrouching: Boolean = false
    var isMoving: Boolean = false
    var isDead: Boolean = false
    var invulnTimer: Float = 0f
    var actionLock: Float = 0f
    var reloadTimer: Float = -1f
    var dashTimer: Float = -1f
    var dodgeTimer: Float = -1f
    var meleeCooldown: Float = 0f
    var noiseRadius: Float = 0f
    var hurtFlash: Float = 0f
    var isJammed: Boolean = false

    private var dashDirX: Float = 0f
    private var dashDirZ: Float = 0f

    fun applyAugment(id: String) {
        if (augments.contains(id)) return
        augments.add(id)
        when (id) {
            "silent_hack" -> hackMultiplier *= 0.5f
            "focus_flow" -> focusRegenMultiplier *= 1.5f
            "dark_veil" -> noiseMultiplier *= 0.8f
            "extra_charge" -> gadget.charges += 2
            "soft_soles" -> noiseMultiplier *= 0.65f
            "plating" -> armor = min(maxArmor, armor + 2)
            "faraday" -> isJamProof = true
        }
    }

    fun damage(amount: Float) {
        if (isDead || invulnTimer > 0f) return
        var remaining = amount
        while (remaining > 0f && armor > 0) {
            armor--
            remaining -= 15f
        }
        if (remaining > 0f) {
            hp = max(0f, hp - remaining)
        }
        hurtFlash = 1.0f
        if (hp <= 0f) {
            isDead = true
        }
    }

    fun heal(): Boolean {
        if (medkits <= 0 || hp >= 100f) return false
        medkits--
        hp = min(100f, hp + 50f)
        return true
    }

    fun tryDash(dirX: Float, dirZ: Float): Boolean {
        if (dashTimer >= 0f || dodgeTimer >= 0f) return false
        val len = hypot(dirX, dirZ)
        if (len < 0.01f) return false
        dashDirX = dirX / len
        dashDirZ = dirZ / len
        dashTimer = 0.22f
        return true
    }

    fun tryDodge(dirX: Float, dirZ: Float): Boolean {
        if (dodgeTimer >= 0f || dashTimer >= 0f) return false
        val len = hypot(dirX, dirZ)
        if (len < 0.01f) {
            dashDirX = -sin(yaw)
            dashDirZ = -cos(yaw)
        } else {
            dashDirX = dirX / len
            dashDirZ = dirZ / len
        }
        dodgeTimer = 0.35f
        invulnTimer = 0.40f
        actionLock = max(actionLock, 0.35f)
        return true
    }

    fun update(
        dt: Float,
        inputX: Float,
        inputZ: Float,
        camYaw: Float,
        aimYaw: Float?,
        crouchInput: Boolean,
        grid: VoxelGrid
    ): Boolean { // Returns true if a footstep sound was produced
        if (isDead) return false

        actionLock = max(0f, actionLock - dt)
        meleeCooldown = max(0f, meleeCooldown - dt)
        invulnTimer = max(0f, invulnTimer - dt)
        hurtFlash = max(0f, hurtFlash - dt * 2.5f)

        // Focus bar
        if (isJammed && !isJamProof) {
            focus = max(0f, focus - 25f * dt)
            if (focus <= 1f) isFocusing = false
        } else if (isFocusing) {
            focus = max(0f, focus - 30f * dt)
            if (focus <= 0f) isFocusing = false
        } else {
            focus = min(100f, focus + 15f * focusRegenMultiplier * dt)
        }

        // Weapon Reload
        if (reloadTimer >= 0f) {
            reloadTimer -= dt
            if (reloadTimer < 0f) {
                val needed = weapon.clipSize - weapon.clipLeft
                val take = min(needed, weapon.reserve)
                weapon.clipLeft += take
                weapon.reserve -= take
            }
        }

        if (actionLock > 0f && dodgeTimer < 0f) return false

        isCrouching = crouchInput

        // Cardinal translation based on camera orientation
        var moveX = 0f
        var moveZ = 0f
        val len = hypot(inputX, inputZ)
        if (len > 0.05f) {
            val nx = inputX / max(1f, len)
            val nz = inputZ / max(1f, len)
            val cosCam = cos(camYaw)
            val sinCam = sin(camYaw)
            moveX = nx * cosCam - nz * sinCam
            moveZ = nx * sinCam + nz * cosCam
        }

        var speed = if (isCrouching) 2.6f else 4.8f

        if (dodgeTimer >= 0f) {
            dodgeTimer -= dt
            moveX = dashDirX
            moveZ = dashDirZ
            speed = 7.2f
        } else if (dashTimer >= 0f) {
            dashTimer -= dt
            moveX = dashDirX
            moveZ = dashDirZ
            speed = 12.0f
        }

        val stepDist = speed * dt
        val nextX = pos.x + moveX * stepDist
        val nextZ = pos.z + moveZ * stepDist

        // Axis-separated collision against voxel grid
        var resolvedX = nextX
        var resolvedZ = nextZ
        val r = 0.32f

        if (grid.isBlocked(resolvedX, pos.z, r)) {
            resolvedX = pos.x
        }
        if (grid.isBlocked(resolvedX, resolvedZ, r)) {
            resolvedZ = pos.z
        }

        val movedDist = hypot(resolvedX - pos.x, resolvedZ - pos.z)
        isMoving = movedDist > 0.005f

        pos.x = resolvedX
        pos.z = resolvedZ

        // Aim or Movement Yaw
        if (aimYaw != null) {
            yaw = aimYaw
        } else if (isMoving) {
            yaw = atan2(moveX, moveZ)
        }

        // Footstep noise
        if (isMoving) {
            noiseRadius = (if (isCrouching) 1.5f else 4.5f) * noiseMultiplier
        } else {
            noiseRadius = 0f
        }

        return isMoving
    }
}
