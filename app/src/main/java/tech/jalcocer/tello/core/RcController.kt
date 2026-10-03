package tech.jalcocer.tello.core

class RcController(
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val pressed = mutableMapOf<Direction, Long>()
    private var lastInputMs = clockMs()

    @Synchronized
    fun press(direction: Direction) {
        val now = clockMs()
        pressed.putIfAbsent(direction, now)
        lastInputMs = now
    }

    @Synchronized
    fun release(direction: Direction) {
        pressed.remove(direction)
        lastInputMs = clockMs()
    }

    @Synchronized
    fun heartbeat() {
        lastInputMs = clockMs()
    }

    @Synchronized
    fun clear() {
        pressed.clear()
        lastInputMs = clockMs()
    }

    @Synchronized
    fun values(fastMode: Boolean): IntArray {
        val now = clockMs()
        if (pressed.isNotEmpty() && now - lastInputMs > STALE_INPUT_MS) pressed.clear()
        val result = IntArray(4)
        pressed.forEach { (direction, startedAt) ->
            val speed = if (fastMode || now - startedAt >= ACCELERATION_MS) MAX_SPEED else BASE_SPEED
            val vector = when (direction) {
                Direction.LEFT -> intArrayOf(-1, 0, 0, 0)
                Direction.RIGHT -> intArrayOf(1, 0, 0, 0)
                Direction.FORWARD -> intArrayOf(0, 1, 0, 0)
                Direction.BACK -> intArrayOf(0, -1, 0, 0)
                Direction.UP -> intArrayOf(0, 0, 1, 0)
                Direction.DOWN -> intArrayOf(0, 0, -1, 0)
                Direction.YAW_LEFT -> intArrayOf(0, 0, 0, -1)
                Direction.YAW_RIGHT -> intArrayOf(0, 0, 0, 1)
            }
            result.indices.forEach { index ->
                result[index] = (result[index] + vector[index] * speed).coerceIn(-100, 100)
            }
        }
        return result
    }

    companion object {
        const val BASE_SPEED = 30
        const val MAX_SPEED = 100
        const val ACCELERATION_MS = 2_000L
        const val STALE_INPUT_MS = 750L
    }
}
