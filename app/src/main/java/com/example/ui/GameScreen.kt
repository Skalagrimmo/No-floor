package com.example.ui

import android.annotation.SuppressLint
import android.opengl.GLSurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.engine.thermal.PowerProfile
import com.example.engine.thermal.ThermalTier
import com.example.game.GameEngine
import com.example.game.GamePhase
import com.example.game.GameRenderer
import com.example.game.entities.OperativeKit
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

@SuppressLint("ClickableViewAccessibility")
@Composable
fun GameScreen(engine: GameEngine) {
    val phase by engine.phase.collectAsState()
    val telemetry by engine.thermalGovernor.telemetry.collectAsState()
    val prompt by engine.interactionPrompt.collectAsState()
    val objective by engine.objectiveText.collectAsState()
    val distance by engine.objectiveDistance.collectAsState()
    val scoreState by engine.scoreState.collectAsState()

    var moveInputX by remember { mutableFloatStateOf(0f) }
    var moveInputZ by remember { mutableFloatStateOf(0f) }
    var aimYaw by remember { mutableStateOf<Float?>(null) }
    var crouchToggle by remember { mutableStateOf(false) }
    var showTelemetryDetail by remember { mutableStateOf(false) }

    // Game loop tick for continuous state updates
    LaunchedEffect(phase) {
        var lastTickNs = System.nanoTime()
        while (true) {
            val now = System.nanoTime()
            val dt = ((now - lastTickNs) / 1_000_000_000.0f).coerceIn(0.005f, 0.05f)
            lastTickNs = now

            if (phase == GamePhase.PLAYING) {
                engine.update(
                    dt = dt,
                    inputX = moveInputX,
                    inputZ = moveInputZ,
                    aimYaw = aimYaw,
                    crouch = crouchToggle
                )
            }
            delay(16)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(TacticalNavy)) {
        // 1. OpenGL ES Surface View
        AndroidView(
            modifier = Modifier.fillMaxSize().testTag("gl_surface_view"),
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    val act = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                    val configInfo = act?.deviceConfigurationInfo
                    val reqVersion = if ((configInfo?.reqGlEsVersion ?: 0) >= 0x00030000) 3 else 2
                    setEGLContextClientVersion(reqVersion)
                    setRenderer(GameRenderer(engine))
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                    engine.thermalGovernor.attachSurfaceView(this)
                }
            }
        )

        // 2. HUD Overlays during gameplay
        if (phase == GamePhase.PLAYING) {
            // Top Bar: Vitals (Left), Objective (Center), Minimap & Thermal (Right)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 28.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                // Vitals Panel
                VitalsPanel(engine = engine)

                // Objective Banner
                ObjectiveBanner(text = objective, distanceMeters = distance)

                // Minimap & Thermal Telemetry
                Column(horizontalAlignment = Alignment.End) {
                    MinimapView(engine = engine)
                    Spacer(modifier = Modifier.height(6.dp))
                    ThermalGovernorBadge(
                        telemetry = telemetry,
                        onClick = { showTelemetryDetail = !showTelemetryDetail }
                    )
                }
            }

            // Prompt Popup
            prompt?.let { pr ->
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(y = 60.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(TacticalSurface.copy(alpha = 0.9f))
                        .border(1.dp, NeonCyan, RoundedCornerShape(6.dp))
                        .clickable { engine.triggerInteraction() }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("interaction_prompt_button")
                ) {
                    Text(
                        text = "E · $pr",
                        color = NeonCyan,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Bottom Bar: Left Joystick, Equipment Slots, Action Buttons
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 20.dp)
            ) {
                // Left Virtual Move Joystick
                VirtualJoystick(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .testTag("move_joystick"),
                    onMove = { x, y ->
                        moveInputX = x
                        moveInputZ = y
                    }
                )

                // Center: Equipment Hotbar
                EquipmentHotbar(
                    engine = engine,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )

                // Right: Combat & Action Controls
                ActionControls(
                    engine = engine,
                    crouch = crouchToggle,
                    onToggleCrouch = { crouchToggle = !crouchToggle },
                    onAim = { yaw -> aimYaw = yaw },
                    modifier = Modifier.align(Alignment.BottomEnd)
                )
            }
        }

        // 3. Telemetry Detail Modal
        AnimatedVisibility(
            visible = showTelemetryDetail,
            modifier = Modifier.align(Alignment.Center)
        ) {
            ThermalOptimizerDialog(
                telemetry = telemetry,
                onSelectProfile = { engine.thermalGovernor.setPowerProfile(it) },
                onDismiss = { showTelemetryDetail = false }
            )
        }

        // 4. Title / Kit Selection Screen
        if (phase == GamePhase.TITLE) {
            TitleScreen(onStart = { kit -> engine.startMission(kit) })
        }

        // 5. Augment Selection Modal
        if (phase == GamePhase.AUGMENT_SELECT) {
            AugmentSelectDialog(
                onSelect = { augmentId ->
                    engine.player.applyAugment(augmentId)
                    engine.audio.playTerminalBeep(true)
                }
            )
        }

        // 6. Game Over / Victory Debriefing
        if (phase == GamePhase.GAME_OVER || phase == GamePhase.MISSION_COMPLETE) {
            ScoreDebriefDialog(
                score = scoreState,
                victory = (phase == GamePhase.MISSION_COMPLETE),
                onRestart = { engine.startMission(engine.player.kit) }
            )
        }
    }
}

@Composable
fun VitalsPanel(engine: GameEngine) {
    val player = engine.player
    Surface(
        color = TacticalNavy.copy(alpha = 0.85f),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, TacticalBorder),
        modifier = Modifier.width(180.dp).testTag("vitals_panel")
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            // Health Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("VITAL", color = AlertRed, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text("${player.hp.toInt()}%", color = PureWhite, fontSize = 10.sp)
            }
            LinearProgressIndicator(
                progress = { (player.hp / 100f).coerceIn(0f, 1f) },
                color = AlertRed,
                trackColor = TacticalSurfaceVariant,
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Armor Pips
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("ARMOR ", color = SteelGray, fontSize = 9.sp)
                for (i in 0 until player.maxArmor) {
                    Box(
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .size(width = 16.dp, height = 6.dp)
                            .background(
                                if (i < player.armor) SteelGray else TacticalSurfaceVariant,
                                RoundedCornerShape(2.dp)
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Tactical Focus Meter
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("FOCUS", color = NeonCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text("${player.focus.toInt()}%", color = NeonCyan, fontSize = 10.sp)
            }
            LinearProgressIndicator(
                progress = { (player.focus / 100f).coerceIn(0f, 1f) },
                color = NeonCyan,
                trackColor = TacticalSurfaceVariant,
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
            )

            // Noise Meter
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("NOISE", color = NeonAmber, fontSize = 9.sp)
                Text(if (player.isCrouching) "CROUCH" else "STAND", color = DimText, fontSize = 9.sp)
            }
        }
    }
}

@Composable
fun ObjectiveBanner(text: String, distanceMeters: Float) {
    Surface(
        color = TacticalNavy.copy(alpha = 0.85f),
        shape = RoundedCornerShape(6.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, NeonCyan.copy(alpha = 0.4f)),
        modifier = Modifier.testTag("objective_banner")
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Text(
                text = text.uppercase(),
                color = PureWhite,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "${distanceMeters.toInt()}m TO OBJECTIVE",
                color = NeonCyan,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
fun MinimapView(engine: GameEngine) {
    Surface(
        color = TacticalNavy.copy(alpha = 0.9f),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, TacticalBorder),
        modifier = Modifier.size(110.dp).testTag("minimap_view")
    ) {
        val player = engine.player
        val level = engine.activeLevel

        Canvas(modifier = Modifier.fillMaxSize().padding(4.dp)) {
            val scale = size.width / 64f // 64m bounds mapped to canvas

            // Draw rooms
            for (r in level.rooms) {
                drawRect(
                    color = Color(0xFF1E2A34),
                    topLeft = Offset(r.x * scale, r.z * scale),
                    size = Size(r.w * scale, r.d * scale)
                )
            }

            // Draw lift / objective
            drawCircle(
                color = NeonAmber,
                radius = 3.dp.toPx(),
                center = Offset(level.liftX * scale, level.liftZ * scale)
            )

            // Draw guards (if alerted or in focus)
            for (g in engine.guards) {
                if (!g.isDown && (player.isFocusing || g.isAlerted)) {
                    drawCircle(
                        color = if (g.isAlerted) AlertRed else NeonAmber,
                        radius = 2.5.dp.toPx(),
                        center = Offset(g.pos.x * scale, g.pos.z * scale)
                    )
                }
            }

            // Draw player arrow
            val px = player.pos.x * scale
            val pz = player.pos.z * scale
            rotate(degrees = Math.toDegrees(player.yaw.toDouble()).toFloat(), pivot = Offset(px, pz)) {
                drawCircle(color = NeonCyan, radius = 3.5.dp.toPx(), center = Offset(px, pz))
                drawLine(
                    color = NeonCyan,
                    start = Offset(px, pz),
                    end = Offset(px, pz + 6.dp.toPx()),
                    strokeWidth = 2.dp.toPx()
                )
            }
        }
    }
}

@Composable
fun ThermalGovernorBadge(
    telemetry: com.example.engine.thermal.ThermalTelemetry,
    onClick: () -> Unit
) {
    val tierColor = when (telemetry.tier) {
        ThermalTier.NORMAL -> NeonCyan
        ThermalTier.LIGHT -> NeonAmber
        ThermalTier.MODERATE -> Color(0xFFFF9800)
        ThermalTier.SEVERE, ThermalTier.CRITICAL -> AlertRed
    }

    Surface(
        color = TacticalNavy.copy(alpha = 0.85f),
        shape = RoundedCornerShape(6.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, tierColor.copy(alpha = 0.5f)),
        modifier = Modifier.clickable { onClick() }.testTag("thermal_governor_badge")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(tierColor, CircleShape)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "${telemetry.currentFps.toInt()} FPS | ${telemetry.batteryTempC.toInt()}°C" + if (telemetry.isHighHeat) " [THRM]" else "",
                color = PureWhite,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
fun VirtualJoystick(
    modifier: Modifier = Modifier,
    onMove: (Float, Float) -> Unit
) {
    var knobOffset by remember { mutableStateOf(Offset.Zero) }
    val radius = 55.dp

    Box(
        modifier = modifier
            .size(radius * 2)
            .background(TacticalNavy.copy(alpha = 0.5f), CircleShape)
            .border(2.dp, NeonCyan.copy(alpha = 0.4f), CircleShape)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = {
                        knobOffset = Offset.Zero
                        onMove(0f, 0f)
                    },
                    onDragCancel = {
                        knobOffset = Offset.Zero
                        onMove(0f, 0f)
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val newOffset = knobOffset + dragAmount
                        val maxDist = 50f
                        val dist = hypot(newOffset.x, newOffset.y)
                        knobOffset = if (dist > maxDist) {
                            Offset(newOffset.x / dist * maxDist, newOffset.y / dist * maxDist)
                        } else {
                            newOffset
                        }
                        onMove(knobOffset.x / maxDist, knobOffset.y / maxDist)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .offset(knobOffset.x.dp / 2f, knobOffset.y.dp / 2f)
                .size(38.dp)
                .background(NeonCyan.copy(alpha = 0.6f), CircleShape)
        )
    }
}

@Composable
fun EquipmentHotbar(
    engine: GameEngine,
    modifier: Modifier = Modifier
) {
    val player = engine.player
    val selected = engine.selectedSlot

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(TacticalNavy.copy(alpha = 0.9f))
            .border(1.dp, TacticalBorder, RoundedCornerShape(8.dp))
            .padding(4.dp)
            .testTag("equipment_hotbar"),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val slots = listOf(
            Triple("1", player.weapon.name, "${player.weapon.clipLeft}/${player.weapon.reserve}"),
            Triple("2", player.gadget.name, "×${player.gadget.charges}"),
            Triple("3", "Medkit", "×${player.medkits}"),
            Triple("4", "Smoke", "×${player.smokeBombs}")
        )

        slots.forEachIndexed { idx, item ->
            val isSel = (idx == selected)
            Surface(
                color = if (isSel) TacticalSurfaceVariant else TacticalNavy,
                shape = RoundedCornerShape(4.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isSel) NeonCyan else TacticalBorder
                ),
                modifier = Modifier
                    .width(62.dp)
                    .height(48.dp)
                    .clickable { engine.selectedSlot = idx }
            ) {
                Column(
                    modifier = Modifier.padding(3.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(item.first, color = DimText, fontSize = 8.sp)
                    Text(item.second, color = PureWhite, fontSize = 9.sp, maxLines = 1)
                    Text(item.third, color = NeonAmber, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun ActionControls(
    engine: GameEngine,
    crouch: Boolean,
    onToggleCrouch: () -> Unit,
    onAim: (Float?) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Melee (F)
            FloatingActionButton(
                onClick = { engine.meleeAttack() },
                containerColor = TacticalNavy,
                contentColor = NeonCyan,
                shape = CircleShape,
                modifier = Modifier.size(52.dp).testTag("melee_button")
            ) {
                Text("MELEE", fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }

            // Use / Interact (E)
            FloatingActionButton(
                onClick = { engine.triggerInteraction() },
                containerColor = TacticalNavy,
                contentColor = NeonAmber,
                shape = CircleShape,
                modifier = Modifier.size(52.dp).testTag("interact_button")
            ) {
                Text("USE", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Crouch Toggle
            FloatingActionButton(
                onClick = { onToggleCrouch() },
                containerColor = if (crouch) NeonCyan else TacticalNavy,
                contentColor = if (crouch) TacticalNavy else PureWhite,
                shape = CircleShape,
                modifier = Modifier.size(52.dp).testTag("crouch_button")
            ) {
                Text(if (crouch) "CRCH" else "STND", fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }

            // Focus (Slow Mo)
            FloatingActionButton(
                onClick = {
                    engine.player.isFocusing = !engine.player.isFocusing
                },
                containerColor = if (engine.player.isFocusing) NeonCyan else TacticalNavy,
                contentColor = if (engine.player.isFocusing) TacticalNavy else NeonCyan,
                shape = CircleShape,
                modifier = Modifier.size(52.dp).testTag("focus_button")
            ) {
                Text("FOCUS", fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }

            // Fire / Use Tool Button
            FloatingActionButton(
                onClick = { engine.fireEquipped() },
                containerColor = AlertRed,
                contentColor = PureWhite,
                shape = CircleShape,
                modifier = Modifier.size(58.dp).testTag("fire_button")
            ) {
                Text("FIRE", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun ThermalOptimizerDialog(
    telemetry: com.example.engine.thermal.ThermalTelemetry,
    onSelectProfile: (PowerProfile) -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        color = TacticalNavy,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, NeonCyan),
        modifier = Modifier.width(320.dp).padding(16.dp).testTag("thermal_optimizer_dialog")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("MALI-400 THERMAL GOVERNOR", color = NeonCyan, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text("Hardware-aware energy regulation", color = DimText, fontSize = 11.sp)

            Spacer(modifier = Modifier.height(12.dp))

            Text("Current FPS: ${telemetry.currentFps.toInt()} (Target: ${telemetry.targetFps})", color = PureWhite, fontSize = 12.sp)
            Text("Frame Time: ${telemetry.frameTimeMs} ms (Budget: ${(1000f / telemetry.targetFps.coerceAtLeast(15)).toInt()} ms)", color = PureWhite, fontSize = 12.sp)
            Text("Battery/SoC Temp: ${telemetry.batteryTempC}°C", color = PureWhite, fontSize = 12.sp)
            Text("Thermal Status: ${telemetry.tier.displayName} (${telemetry.tier.description})", color = NeonAmber, fontSize = 12.sp)
            Text("GPU Draw Calls: ${telemetry.drawCalls} calls", color = PureWhite, fontSize = 12.sp)

            Spacer(modifier = Modifier.height(8.dp))
            Text("OCCLUSION CULLING (WALL OBSTRUCTION):", color = DimText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("Culling Mode: ${telemetry.occlusionMode}", color = NeonCyan, fontSize = 11.sp)
            Text("Hidden by Walls: ${telemetry.culledChunks} chunks, ${telemetry.culledGuards} guards", color = PureWhite, fontSize = 11.sp)
            Text("Saved GPU Load: ~${telemetry.drawCallsSaved} draw calls / ~${telemetry.verticesSaved} verts", color = NeonAmber, fontSize = 11.sp)

            Spacer(modifier = Modifier.height(8.dp))
            Text("GEOMETRY BATCHING (STATIC MESH MERGE):", color = DimText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("Batch Mode: ${telemetry.batchingMode}", color = NeonCyan, fontSize = 11.sp)
            Text("Batched Draw Calls: ${telemetry.batchedDrawCalls} (Saved: ${telemetry.batchingDrawCallsSaved} calls)", color = NeonAmber, fontSize = 11.sp)
            Text("Merged Static Vertices: ${telemetry.batchedVertices} verts across materials", color = PureWhite, fontSize = 11.sp)

            Spacer(modifier = Modifier.height(8.dp))
            Text("FRAME BUDGET & PASS REGULATION:", color = DimText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("Budget Usage: ${telemetry.budgetUsagePercent.toInt()}%", color = if (telemetry.budgetUsagePercent > 100f) AlertRed else NeonCyan, fontSize = 11.sp)
            Text("Texture Detail: ${telemetry.textureDetail}", color = NeonAmber, fontSize = 11.sp)
            Text("Post-Process Pass: ${if (telemetry.postProcessEnabled) "ACTIVE" else "DISABLED (Thermal Save)"}", color = if (telemetry.postProcessEnabled) PureWhite else AlertRed, fontSize = 11.sp)
            Text("Shadow Depth Pass: ${if (telemetry.shadowsEnabled) "ACTIVE" else "DISABLED (Thermal Save)"}", color = if (telemetry.shadowsEnabled) PureWhite else AlertRed, fontSize = 11.sp)

            Spacer(modifier = Modifier.height(14.dp))
            Text("POWER PROFILES:", color = DimText, fontSize = 11.sp, fontWeight = FontWeight.Bold)

            for (prof in PowerProfile.values()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectProfile(prof) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = (telemetry.profile == prof),
                        onClick = { onSelectProfile(prof) },
                        colors = RadioButtonDefaults.colors(selectedColor = NeonCyan)
                    )
                    Text(prof.displayName, color = PureWhite, fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = TacticalSurfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("CLOSE", color = NeonCyan)
            }
        }
    }
}

@Composable
fun TitleScreen(onStart: (OperativeKit) -> Unit) {
    var selectedKit by remember { mutableStateOf(OperativeKit.GHOST) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TacticalNavy.copy(alpha = 0.94f))
            .padding(24.dp)
            .testTag("title_screen"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = 480.dp)
        ) {
            Text(
                text = "NO FLOOR PLAN",
                color = NeonCyan,
                fontSize = 32.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 3.sp
            )
            Text(
                text = "INFILTRATE · STEAL THE CORE · VANISH",
                color = SteelGray,
                fontSize = 12.sp,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(24.dp))
            Text("SELECT OPERATIVE CLASS:", color = PureWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))

            for (kit in OperativeKit.values()) {
                val isSelected = (kit == selectedKit)
                Surface(
                    color = if (isSelected) TacticalSurfaceVariant else TacticalSurface,
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isSelected) NeonCyan else TacticalBorder
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { selectedKit = kit }
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(kit.title, color = if (isSelected) NeonCyan else PureWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text(kit.desc, color = DimText, fontSize = 11.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = { onStart(selectedKit) },
                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                modifier = Modifier.fillMaxWidth().height(48.dp).testTag("start_mission_button")
            ) {
                Text("COMMENCE INFILTRATION", color = TacticalNavy, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun AugmentSelectDialog(onSelect: (String) -> Unit) {
    val augments = listOf(
        Pair("silent_hack", "Silent Intrusion: Hacking speed increased by 50%."),
        Pair("focus_flow", "Focus Flow: Tactical slow-motion meter recovers 50% faster."),
        Pair("soft_soles", "Soft Soles: Running noise reduced by 35%."),
        Pair("plating", "Composite Plating: Grants +2 instant armor pips.")
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TacticalNavy.copy(alpha = 0.9f))
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = TacticalSurface,
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, NeonCyan),
            modifier = Modifier.widthIn(max = 400.dp)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text("AUGMENT TERMINAL", color = NeonCyan, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Install permanent upgrade for this run:", color = DimText, fontSize = 12.sp)

                Spacer(modifier = Modifier.height(14.dp))

                for ((id, desc) in augments) {
                    Button(
                        onClick = { onSelect(id) },
                        colors = ButtonDefaults.buttonColors(containerColor = TacticalSurfaceVariant),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Text(desc, color = PureWhite, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun ScoreDebriefDialog(
    score: com.example.game.GameScore,
    victory: Boolean,
    onRestart: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TacticalNavy.copy(alpha = 0.95f))
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = TacticalSurface,
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, if (victory) NeonCyan else AlertRed),
            modifier = Modifier.widthIn(max = 380.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (victory) "MISSION SUCCESS - EXTRACTED" else "OPERATIVE DOWN",
                    color = if (victory) NeonCyan else AlertRed,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black
                )

                Spacer(modifier = Modifier.height(14.dp))

                Text("FINAL SCORE: ${score.finalScore}", color = NeonAmber, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Time: ${score.totalTimeSeconds.toInt()}s | Alarms: ${score.alarmsRaised}", color = DimText, fontSize = 12.sp)

                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = onRestart,
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("RETRY FACILITY", color = TacticalNavy, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
