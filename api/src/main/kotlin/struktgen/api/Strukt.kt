package struktgen.api

import java.nio.ByteBuffer

interface Strukt {
    context(ByteBuffer)
    fun print(): String
}

interface StruktType<T> {
    val sizeInBytes: Int
    val factory: () -> T
}
