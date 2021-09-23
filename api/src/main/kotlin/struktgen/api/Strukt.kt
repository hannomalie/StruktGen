package struktgen.api

import java.nio.ByteBuffer

interface Strukt {
    fun toString(buffer: ByteBuffer): String
}

interface StruktType<T> {
    val sizeInBytes: Int
    val factory: () -> T
}