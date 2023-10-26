import struktgen.api.Order
import struktgen.api.Strukt
import java.nio.ByteBuffer

interface Simple: Strukt {
    context(ByteBuffer) val a: Int
    context(ByteBuffer) val b: Float
    companion object
}

interface Nested: Strukt {
    context(ByteBuffer) var a: Int
    context(ByteBuffer) val b: Int
    companion object
}

interface FooStrukt: Strukt {
    context(ByteBuffer) val a: Int
    context(ByteBuffer) var b: Int
    context(ByteBuffer) val c: Float
    context(ByteBuffer) val d: Nested
    context(ByteBuffer) var e: Boolean
    companion object
}

interface ReorderedStrukt: Strukt {
    context(ByteBuffer) @Order(1) var a: Int
    context(ByteBuffer) @Order(0) var b: Int
    context(ByteBuffer) var c: Int
    context(ByteBuffer) var d: Int
    companion object
}