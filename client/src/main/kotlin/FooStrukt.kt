import struktgen.api.Strukt
import java.nio.ByteBuffer

interface Simple: Strukt {
    val ByteBuffer.a: Int
    val ByteBuffer.b: Float
    companion object
}

interface Nested: Strukt {
    var ByteBuffer.a: Int
    val ByteBuffer.b: Int
    companion object
}

interface FooStrukt: Strukt {
    val ByteBuffer.a: Int
    var ByteBuffer.b: Int
    val ByteBuffer.c: Float
    val ByteBuffer.d: Nested
    var ByteBuffer.e: Boolean
    companion object
}
