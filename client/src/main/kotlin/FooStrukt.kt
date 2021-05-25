import java.nio.ByteBuffer

annotation class Strukt

@Strukt
interface Simple {
    val ByteBuffer.a: Int
    val ByteBuffer.b: Float
}

@Strukt
interface Nested {
    val ByteBuffer.a: Int
    val ByteBuffer.b: Int
}

@Strukt
interface FooStrukt {
    val ByteBuffer.a: Int
    val ByteBuffer.b: Int
    val ByteBuffer.c: Float
    val ByteBuffer.d: Nested
    val ByteBuffer.e: Boolean
}