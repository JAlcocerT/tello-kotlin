package tech.jalcocer.tello.video

import org.junit.Assert.assertEquals
import org.junit.Test

class H264AnnexBParserTest {
    @Test
    fun parsesAcrossUdpPacketBoundaries() {
        val parser = H264AnnexBParser()
        assertEquals(0, parser.append(byteArrayOf(9, 0, 0)).size)
        val first = parser.append(byteArrayOf(1, 0x67, 1, 2, 0, 0, 0, 1, 0x68, 3))
        assertEquals(1, first.size)
        assertEquals(7, first.single().type)
        val second = parser.append(byteArrayOf(4, 0, 0, 1, 0x65, 5))
        assertEquals(1, second.size)
        assertEquals(8, second.single().type)
    }
}
