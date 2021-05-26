import java.nio.ByteBuffer

annotation class Strukt

@Strukt
interface Simple {
    val ByteBuffer.a: Int
    val ByteBuffer.b: Float
    companion object
}

@Strukt
interface Nested {
    var ByteBuffer.a: Int
    val ByteBuffer.b: Int
    companion object
}

@Strukt
interface FooStrukt {
    val ByteBuffer.a: Int
    var ByteBuffer.b: Int
    val ByteBuffer.c: Float
    val ByteBuffer.d: Nested
    var ByteBuffer.e: Boolean
    companion object
}