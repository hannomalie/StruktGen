import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class FooStruktTest {

    @Test
    fun `simple strukt is generated correctly`() {
        val simple = SimpleImpl()
        val buffer = ByteBuffer.allocate(4 * 2)
        simple.run {
            Assertions.assertThat(buffer.a).isEqualTo(0)
            Assertions.assertThat(buffer.b).isEqualTo(0)
        }
    }

    @Test
    fun `strukt properties can be set correctly through buffer`() {
        val simple = SimpleImpl()
        val buffer = ByteBuffer.allocate(4 * 2).apply {
            putInt(0, 5)
            putInt(1, 6)
        }
        simple.run {
            Assertions.assertThat(buffer.a).isEqualTo(5)
            Assertions.assertThat(buffer.b).isEqualTo(6)
        }
    }
}