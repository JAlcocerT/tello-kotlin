package tech.jalcocer.tello.core

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class RcControllerTest {
    @Test
    fun opposingInputsCancel() {
        var now = 1_000L
        val controller = RcController { now }
        controller.press(Direction.LEFT)
        controller.press(Direction.RIGHT)
        assertArrayEquals(intArrayOf(0, 0, 0, 0), controller.values(false))
    }

    @Test
    fun heldInputAcceleratesAndStaleInputStops() {
        var now = 1_000L
        val controller = RcController { now }
        controller.press(Direction.FORWARD)
        assertArrayEquals(intArrayOf(0, 30, 0, 0), controller.values(false))
        now += 2_000
        controller.heartbeat()
        assertArrayEquals(intArrayOf(0, 100, 0, 0), controller.values(false))
        now += 751
        assertArrayEquals(intArrayOf(0, 0, 0, 0), controller.values(false))
    }
}
