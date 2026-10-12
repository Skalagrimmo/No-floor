package com.example

import com.example.game.entities.Guard
import com.example.game.entities.GuardArchetypes
import com.example.game.entities.GuardState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GuardAiUnitTest {

    @Test
    fun testGuardArchetypeAttributes() {
        val patrol = GuardArchetypes.PATROL
        assertEquals(50f, patrol.maxHp, 1e-4f)
        assertEquals(13f, patrol.viewRange, 1e-4f)

        val heavy = GuardArchetypes.HEAVY
        assertEquals(160f, heavy.maxHp, 1e-4f)

        val sniper = GuardArchetypes.SNIPER
        assertTrue(sniper.isSniper)
        assertEquals(24f, sniper.viewRange, 1e-4f)

        val hound = GuardArchetypes.HOUND
        assertTrue(hound.isMelee)
        assertEquals(7f, hound.scentRadius, 1e-4f)
    }

    @Test
    fun testGuardHearingEscalation() {
        val guard = Guard(
            id = "g_test",
            type = "patrol",
            initialX = 10f,
            initialZ = 10f,
            initialYaw = 0f,
            route = emptyList()
        )

        assertEquals(GuardState.PATROL, guard.state)

        // Noise outside hearing range should be ignored
        val heardFar = guard.hear(noiseX = 50f, noiseZ = 50f, radius = 5f)
        assertEquals(false, heardFar)
        assertEquals(GuardState.PATROL, guard.state)

        // Noise within hearing range should trigger investigation
        val heardNear = guard.hear(noiseX = 12f, noiseZ = 12f, radius = 10f)
        assertEquals(true, heardNear)
        assertEquals(GuardState.INVESTIGATE, guard.state)
        assertTrue(guard.suspicion >= 65f)
        assertTrue(guard.lastSeenPos != null)
    }
}
