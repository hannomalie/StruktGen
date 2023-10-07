package struktgen.api

import java.nio.ByteBuffer

interface Strukt {
    context(ByteBuffer)
    fun print(): String
}

interface StruktType<T: Strukt> {
    val sizeInBytes: Int
    val factory: () -> T
}

fun <T: Strukt> TypedBuffer(buffer: ByteBuffer, struktType: StruktType<T>) = object: TypedBuffer<T>(struktType) {
    override val byteBuffer get() = buffer
}

fun <T: Strukt> ByteBuffer.typed(struktType: StruktType<T>) = object: TypedBuffer<T>(struktType) {
    override val byteBuffer get() = this@typed
}

interface ITypedBuffer<T: Strukt> {
    val struktType: StruktType<T>
    val byteBuffer: ByteBuffer
    val _slidingWindow: T
}
abstract class TypedBuffer<T: Strukt>(override val struktType: StruktType<T>): ITypedBuffer<T> {
    override val _slidingWindow = struktType.factory()
}

val <T : Strukt> ITypedBuffer<T>.size: Int get() = byteBuffer.capacity()/struktType.sizeInBytes

inline fun <T : Strukt, R> ITypedBuffer<T>.forIndex(
    index: Int,
    block: context(ByteBuffer) (T) -> R
): R {
    byteBuffer.position(index * struktType.sizeInBytes)
    return block(byteBuffer, _slidingWindow)
}

inline operator fun <T : Strukt> ITypedBuffer<T>.get(index: Int): T {
    byteBuffer.position(index * struktType.sizeInBytes)
    return _slidingWindow
}

inline fun <T : Strukt> ITypedBuffer<T>.forEachIndexed(
    untilIndex: Int = byteBuffer.capacity() / struktType.sizeInBytes,
    block: context(ByteBuffer) (Int, T) -> Unit
) {
    byteBuffer.position(0)
    var counter = 0
    val untilPosition = untilIndex * struktType.sizeInBytes
    while(byteBuffer.position() < untilPosition) {
        block(byteBuffer, counter, _slidingWindow)
        counter++
        byteBuffer.position(counter * struktType.sizeInBytes)
    }
    byteBuffer.position(0)
}

inline fun <T : Strukt> ITypedBuffer<T>.forEach(untilIndex: Int = byteBuffer.capacity()/struktType.sizeInBytes, block: context(ByteBuffer) (T) -> Unit) {
    byteBuffer.position(0)
    val untilPosition = untilIndex * struktType.sizeInBytes
    while(byteBuffer.position() < untilPosition) {
        block(byteBuffer, _slidingWindow)
        byteBuffer.position(byteBuffer.position() + struktType.sizeInBytes)
    }
    byteBuffer.position(0)
}
