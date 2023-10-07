import FooStruktImpl.Companion.forEach
import FooStruktImpl.Companion.forEachIndexed
import FooStruktImpl.Companion.invoke
import FooStruktImpl.Companion.sizeInBytes
import FooStruktImpl.Companion.type
import NestedImpl.Companion.invoke
import SimpleImpl.Companion.invoke
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset.offset
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import struktgen.api.*
import java.nio.ByteBuffer

class FooStruktTest {

    @Test
    fun `simple strukt is generated correctly`() {
        val simple = Simple()
        assertThat(SimpleImpl.sizeInBytes).isEqualTo(8)
        val buffer = ByteBuffer.allocate(SimpleImpl.sizeInBytes)
        buffer.run {
            assertThat(simple.a).isEqualTo(0)
            assertThat(simple.b).isEqualTo(0.0f)
        }
    }

    @Test
    fun `complex strukt is generated correctly`() {
        val simple = FooStrukt()
        assertThat(FooStrukt.sizeInBytes).isEqualTo(24)
        val buffer = ByteBuffer.allocate(FooStrukt.sizeInBytes)
        buffer.run {
            assertThat(simple.a).isEqualTo(0)
            assertThat(simple.b).isEqualTo(0)
            assertThat(simple.c).isEqualTo(0f)
            assertThat(simple.d.a).isEqualTo(0)
            assertThat(simple.d.b).isEqualTo(0)
            assertThat(simple.e).isFalse()
        }
    }

    @Test
    fun `buffer position is respected`() {
        val simple = Nested()
        val buffer = ByteBuffer.allocate(4 * 2).apply {
            putInt(0, 5)
            putInt(4, 6)
            position(4)
        }
        assertThat(buffer.position()).isEqualTo(4)
        buffer.run {
            assertThat(simple.a).isEqualTo(6)
            assertThrows<IndexOutOfBoundsException> {
                simple.b
            }
        }
    }

    @Test
    fun `strukt properties can be set correctly through buffer`() {
        val simple = Simple()
        val buffer = ByteBuffer.allocate(4 * 2).apply {
            putInt(0, 5)
            putFloat(4, 6f)
            rewind()
        }
        buffer.run {
            assertThat(simple.a).isEqualTo(5)
            assertThat(simple.b).isCloseTo(6f, offset(0.0000001f))
        }
    }

    @Test
    fun `strukt properties can be set correctly through instance`() {
        val simple = FooStrukt()
        val buffer = ByteBuffer.allocate(FooStrukt.sizeInBytes)
        buffer.run {
            assertThat(simple.b).isEqualTo(0)
            simple.b = 2
            assertThat(simple.b).isEqualTo(2)

            assertThat(simple.e).isFalse()
            simple.e = true
            assertThat(simple.e).isTrue()

            assertThat(simple.d.a).isEqualTo(0)
            simple.d.a = 3
            assertThat(simple.d.a).isEqualTo(3)
        }
    }

    @Test
    fun `strukt properties can be set correctly through instance when manually iterating`() {
        val simple = FooStrukt()
        val arraySize = 10
        val buffer = ByteBuffer.allocate(FooStrukt.sizeInBytes * arraySize)

        for(i in 0 until arraySize) {
            buffer.position(i * FooStrukt.sizeInBytes)

            buffer.run {
                assertThat(simple.b).isEqualTo(0)
                simple.b = i
                assertThat(simple.b).isEqualTo(i)

                assertThat(simple.e).isFalse()
                simple.e = true
                assertThat(simple.e).isTrue()

                assertThat(simple.d.a).isEqualTo(0)
                simple.d.a = i
                assertThat(simple.d.a).isEqualTo(i)
            }
        }
    }

    @Test
    fun `strukt properties can be set correctly through instance when iterating`() {
        val arraySize = 10
        val buffer = ByteBuffer.allocate(FooStrukt.sizeInBytes * arraySize)

        var counter = 0
        buffer.forEach { simple ->
            assertThat(simple.b).isEqualTo(0)
            simple.b = counter
            assertThat(simple.b).isEqualTo(counter)

            assertThat(simple.e).isFalse()
            simple.e = true
            assertThat(simple.e).isTrue()

            assertThat(simple.d.a).isEqualTo(0)
            simple.d.a = counter
            assertThat(simple.d.a).isEqualTo(counter)
            counter++
        }
        assertThat(counter).isEqualTo(10)
    }
    @Test
    fun `strukt properties can be set correctly through instance when iterating with index`() {
        val arraySize = 10
        val buffer = ByteBuffer.allocate(FooStrukt.sizeInBytes * arraySize)

        var counter = 0
        buffer.forEachIndexed { index, simple ->

            assertThat(simple.b).isEqualTo(0)
            simple.b = index
            assertThat(simple.b).isEqualTo(index)

            assertThat(simple.e).isFalse()
            simple.e = true
            assertThat(simple.e).isTrue()

            assertThat(simple.d.a).isEqualTo(0)
            simple.d.a = index
            assertThat(simple.d.a).isEqualTo(index)
            counter++
        }

        assertThat(counter).isEqualTo(10)
    }

    @Test
    fun `typed buffer iteration works`() {
        val buffer = ByteBuffer.allocate(FooStrukt.sizeInBytes * 10)
        val typedBuffer = TypedBuffer(buffer, FooStrukt.type)

        typedBuffer.forEach { foo ->
            assertThat(foo.b).isEqualTo(0)
            foo.b = 1
            assertThat(foo.b).isEqualTo(1)
        }
    }
    @Test
    fun `typed buffer iteration with index works`() {
        val typedBuffer = ByteBuffer.allocate(FooStrukt.sizeInBytes * 10).typed(FooStrukt.type)

        typedBuffer.forEachIndexed { index, foo ->
            assertThat(foo.b).isEqualTo(0)
            foo.b = index
            assertThat(foo.b).isEqualTo(index)
        }
    }

    @Test
    fun `typed buffer index access works`() {
        val typedBuffer: TypedBuffer<FooStrukt> = ByteBuffer.allocate(FooStrukt.sizeInBytes * 10).typed(FooStrukt.type)

        val foo = typedBuffer[0]
        typedBuffer.byteBuffer.run {
            assertThat(foo.b).isEqualTo(0)
            foo.b = 5
            assertThat(foo.b).isEqualTo(5)
        }
    }

    @Test
    fun `typed buffer index access with block works`() {
        val typedBuffer: TypedBuffer<FooStrukt> = ByteBuffer.allocate(FooStrukt.sizeInBytes * 10).typed(FooStrukt.type)

        typedBuffer.forIndex(0) {
            assertThat(it.b).isEqualTo(0)
            it.b = 5
            assertThat(it.b).isEqualTo(5)
        }
    }

    @Test
    fun `print function works`() {
        val typedBuffer: TypedBuffer<FooStrukt> = ByteBuffer.allocate(FooStrukt.sizeInBytes * 1).typed(FooStrukt.type)

        typedBuffer.forIndex(0) {
            assertThat(it.print()).isEqualTo("asd")
        }
    }
}