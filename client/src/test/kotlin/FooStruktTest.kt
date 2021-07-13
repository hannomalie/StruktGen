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
import struktgen.TypedBuffer
import struktgen.typed
import java.nio.ByteBuffer

class FooStruktTest {

    @Test
    fun `simple strukt is generated correctly`() {
        val simple = Simple()
        assertThat(SimpleImpl.sizeInBytes).isEqualTo(8)
        val buffer = ByteBuffer.allocate(SimpleImpl.sizeInBytes)
        simple.run {
            assertThat(buffer.a).isEqualTo(0)
            assertThat(buffer.b).isEqualTo(0.0f)
        }
    }

    @Test
    fun `complex strukt is generated correctly`() {
        val simple = FooStrukt()
        assertThat(FooStrukt.sizeInBytes).isEqualTo(24)
        val buffer = ByteBuffer.allocate(FooStrukt.sizeInBytes)
        simple.run {
            buffer.run {
                assertThat(a).isEqualTo(0)
                assertThat(b).isEqualTo(0)
                assertThat(c).isEqualTo(0f)
                assertThat(d.run { a }).isEqualTo(0)
                assertThat(d.run { b }).isEqualTo(0)
                assertThat(e).isFalse()
            }
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
        simple.run {
            buffer.run {
                assertThat(a).isEqualTo(6)
                assertThrows<IndexOutOfBoundsException> {
                    b
                }
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
        simple.run {
            buffer.run {
                assertThat(a).isEqualTo(5)
                assertThat(b).isCloseTo(6f, offset(0.0000001f))
            }
        }
    }

    @Test
    fun `strukt properties can be set correctly through instance`() {
        val simple = FooStrukt()
        val buffer = ByteBuffer.allocate(FooStrukt.sizeInBytes)
        simple.run {
            buffer.run {
                assertThat(b).isEqualTo(0)
                b = 2
                assertThat(b).isEqualTo(2)

                assertThat(e).isFalse()
                e = true
                assertThat(e).isTrue()

                assertThat(d.run { a }).isEqualTo(0)
                d.run { a = 3 }
                assertThat(d.run { a }).isEqualTo(3)
            }
        }
    }

    @Test
    fun `strukt properties can be set correctly through instance when manually iterating`() {
        val simple = FooStrukt()
        val arraySize = 10
        val buffer = ByteBuffer.allocate(FooStrukt.sizeInBytes * arraySize)

        for(i in 0 until arraySize) {
            buffer.position(i * FooStrukt.sizeInBytes)

            simple.run {
                buffer.run {
                    assertThat(b).isEqualTo(0)
                    b = i
                    assertThat(b).isEqualTo(i)

                    assertThat(e).isFalse()
                    e = true
                    assertThat(e).isTrue()

                    assertThat(d.run { a }).isEqualTo(0)
                    d.run { a = i }
                    assertThat(d.run { a }).isEqualTo(i)
                }
            }
        }
    }

    @Test
    fun `strukt properties can be set correctly through instance when iterating`() {
        val arraySize = 10
        val buffer = ByteBuffer.allocate(FooStrukt.sizeInBytes * arraySize)

        var counter = 0
        buffer.forEach { simple ->
            simple.run {
                assertThat(b).isEqualTo(0)
                b = counter
                assertThat(b).isEqualTo(counter)

                assertThat(e).isFalse()
                e = true
                assertThat(e).isTrue()

                assertThat(d.run { a }).isEqualTo(0)
                d.run { a = counter }
                assertThat(d.run { a }).isEqualTo(counter)
            }
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

            simple.run {
                assertThat(b).isEqualTo(0)
                b = index
                assertThat(b).isEqualTo(index)

                assertThat(e).isFalse()
                e = true
                assertThat(e).isTrue()

                assertThat(d.run { a }).isEqualTo(0)
                d.run { a = index }
                assertThat(d.run { a }).isEqualTo(index)
            }
            counter++
        }

        assertThat(counter).isEqualTo(10)
    }

    @Test
    fun `typed buffer iteration works`() {
        val buffer = ByteBuffer.allocate(FooStrukt.sizeInBytes * 10)
        val typedBuffer = TypedBuffer(buffer, FooStrukt.type)

        typedBuffer.forEach {
            it.run {
                assertThat(b).isEqualTo(0)
                b = 1
                assertThat(b).isEqualTo(1)
            }
        }
    }
    @Test
    fun `typed buffer iteration with index works`() {
        val typedBuffer = ByteBuffer.allocate(FooStrukt.sizeInBytes * 10).typed(FooStrukt.type)

        typedBuffer.forEachIndexed { index, it ->
            it.run {
                assertThat(b).isEqualTo(0)
                b = index
                assertThat(b).isEqualTo(index)
            }
        }
    }

    @Test
    fun `typed buffer index access works`() {
        val typedBuffer = ByteBuffer.allocate(FooStrukt.sizeInBytes * 10).typed(FooStrukt.type)

        typedBuffer[0] {
            it.run {
                assertThat(b).isEqualTo(0)
                b = 5
                assertThat(b).isEqualTo(5)
            }
        }
    }
}