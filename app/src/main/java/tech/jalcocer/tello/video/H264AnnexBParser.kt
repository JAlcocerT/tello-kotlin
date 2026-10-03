package tech.jalcocer.tello.video

data class NalUnit(val bytes: ByteArray) {
    val type: Int
        get() {
            val offset = when {
                bytes.size >= 5 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() &&
                    bytes[2] == 0.toByte() && bytes[3] == 1.toByte() -> 4
                bytes.size >= 4 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() && bytes[2] == 1.toByte() -> 3
                else -> return -1
            }
            return bytes[offset].toInt() and 0x1f
        }
}

/** Splits an arbitrary UDP byte stream into complete Annex-B NAL units. */
class H264AnnexBParser {
    private var pending = ByteArray(0)

    fun append(chunk: ByteArray, length: Int = chunk.size): List<NalUnit> {
        if (length <= 0) return emptyList()
        val combined = ByteArray(pending.size + length)
        pending.copyInto(combined)
        chunk.copyInto(combined, pending.size, 0, length)
        val starts = findStartCodes(combined)
        if (starts.size < 2) {
            pending = if (combined.size <= MAX_PENDING) combined else combined.copyOfRange(combined.size - 4, combined.size)
            return emptyList()
        }
        val output = starts.zipWithNext().mapNotNull { (start, end) ->
            if (end > start) NalUnit(combined.copyOfRange(start, end)) else null
        }
        pending = combined.copyOfRange(starts.last(), combined.size)
        return output
    }

    fun reset() {
        pending = ByteArray(0)
    }

    private fun findStartCodes(data: ByteArray): List<Int> {
        val starts = mutableListOf<Int>()
        var index = 0
        while (index <= data.size - 3) {
            val three = data[index] == 0.toByte() && data[index + 1] == 0.toByte() && data[index + 2] == 1.toByte()
            val four = index <= data.size - 4 && data[index] == 0.toByte() && data[index + 1] == 0.toByte() &&
                data[index + 2] == 0.toByte() && data[index + 3] == 1.toByte()
            if (four || three) {
                starts += index
                index += if (four) 4 else 3
            } else index++
        }
        return starts
    }

    companion object {
        private const val MAX_PENDING = 2 * 1024 * 1024
    }
}
