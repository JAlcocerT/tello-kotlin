package tech.jalcocer.tello.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TelemetryTest {
    @Test
    fun parsesTelloStatePacket() {
        val value = parseTelemetry("bat:77;h:42;vgx:30;vgy:40;templ:60;temph:64;wifi:90;")
        assertEquals(77, value.battery)
        assertEquals(42, value.heightCm)
        assertEquals(5.0, value.speedMps!!, 0.001)
        assertEquals(62, value.temperatureC)
        assertEquals(90, value.wifiPercent)
    }
}
