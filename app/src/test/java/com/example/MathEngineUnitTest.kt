package com.example

import com.example.engine.math.Mat4
import com.example.engine.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MathEngineUnitTest {

    @Test
    fun testVectorOperations() {
        val v1 = Vec3(1f, 2f, 3f)
        val v2 = Vec3(4f, 5f, 6f)

        val sum = v1.add(v2)
        assertEquals(5f, sum.x, 1e-4f)
        assertEquals(7f, sum.y, 1e-4f)
        assertEquals(9f, sum.z, 1e-4f)

        val diff = v2.sub(v1)
        assertEquals(3f, diff.x, 1e-4f)
        assertEquals(3f, diff.y, 1e-4f)
        assertEquals(3f, diff.z, 1e-4f)

        val scaled = v1.scale(2f)
        assertEquals(2f, scaled.x, 1e-4f)
        assertEquals(4f, scaled.y, 1e-4f)
        assertEquals(6f, scaled.z, 1e-4f)

        val dot = v1.dot(v2) // 1*4 + 2*5 + 3*6 = 4 + 10 + 18 = 32
        assertEquals(32f, dot, 1e-4f)

        val norm = Vec3(3f, 0f, 4f).normalize()
        assertEquals(1.0f, norm.length(), 1e-4f)
        assertEquals(0.6f, norm.x, 1e-4f)
        assertEquals(0.8f, norm.z, 1e-4f)
    }

    @Test
    fun testMatrixCreationAndMultiplication() {
        val m1 = Mat4()
        // Identity diagonal elements should be 1
        assertEquals(1f, m1.values[0], 1e-4f)
        assertEquals(1f, m1.values[5], 1e-4f)
        assertEquals(1f, m1.values[10], 1e-4f)
        assertEquals(1f, m1.values[15], 1e-4f)

        val translated = Mat4().translate(5f, 10f, 15f)
        assertEquals(5f, translated.values[12], 1e-4f)
        assertEquals(10f, translated.values[13], 1e-4f)
        assertEquals(15f, translated.values[14], 1e-4f)

        val ortho = Mat4.ortho(-10f, 10f, -10f, 10f, 0.1f, 100f)
        assertTrue(ortho.values[0] != 0f)
        assertTrue(ortho.values[5] != 0f)
    }
}
