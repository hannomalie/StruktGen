package struktgen.api

import java.nio.ByteBuffer

interface Strukt {
    fun toString(buffer: ByteBuffer): String
}