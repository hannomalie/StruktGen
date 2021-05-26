package foo

import FooStrukt
import FooStruktImpl
import Nested
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import org.openjdk.jmh.runner.Runner
import org.openjdk.jmh.runner.options.OptionsBuilder
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * Created by tony on 2018-12-10.
 */
@BenchmarkMode(Mode.Throughput) // Benchmark mode, using overall throughput mode
@Warmup(iterations = 2) // Preheating times
@Measurement(
    iterations = 3,
    time = 5,
    timeUnit = TimeUnit.SECONDS
) // test parameter, iterations = 10 means 10 rounds of testing
@Threads(8) // number of test threads per process
@Fork(2)  // The number of forks is executed, indicating that JMH will fork out two processes for testing.
@OutputTimeUnit(TimeUnit.MILLISECONDS) // Time type of benchmark results
open class StruktBenchmark {

    @State(Scope.Benchmark)
    open class SimpleState(
        val strukt: StruktImpl = StruktImpl(),
        val buffer: ByteBuffer = ByteBuffer.allocateDirect(FooStruktImpl.sizeInBytes)
    )

    @State(Scope.Benchmark)
    open class StruktState(
        val strukt: FooStruktImpl = FooStruktImpl(),
        val buffer: ByteBuffer = ByteBuffer.allocateDirect(FooStruktImpl.sizeInBytes)
    )


    class StruktImpl : FooStrukt {
        override val ByteBuffer.a: Int
            get() = 0
        var _b = 0
        override var ByteBuffer.b: Int
            get() = _b
            set(value) {
                _b = value
            }
        override val ByteBuffer.c: Float
            get() = 0f
        val _d = object : Nested {
            var _a = 0
            override var ByteBuffer.a: Int
                get() = _a
                set(value) {
                    _a = value
                }
            override val ByteBuffer.b: Int
                get() = 0

        }
        override val ByteBuffer.d: Nested
            get() = _d
        override var ByteBuffer.e: Boolean
            get() = true
            set(value) {}
    }

    @Benchmark
    fun getPropertyVanilla(blackHole: Blackhole, state: SimpleState): Int {
        val b = state.buffer.run { state.strukt.run { b } }
        blackHole.consume(b)
        return b
    }

    @Benchmark
    fun getPropertyStrukt(blackHole: Blackhole, state: StruktState): Int {
        val b = state.buffer.run { state.strukt.run { b } }
        blackHole.consume(b)
        return b
    }

    @Benchmark
    fun setPropertyVanilla(blackHole: Blackhole, state: SimpleState): Int {
        val da = state.buffer.run {
            state.strukt.run {
                d.run { a = 2 }
                d.run { a }
            }
        }
        blackHole.consume(da)
        return da
    }

    @Benchmark
    fun setPropertyStrukt(blackHole: Blackhole, state: StruktState): Int {
        val da = state.buffer.run {
            state.strukt.run {
                d.run { a = 2 }
                d.run { a }
            }
        }
        blackHole.consume(da)
        return da
    }
}

fun main() {

    val options = OptionsBuilder()
        .include(StruktBenchmark::class.java.simpleName)
        .output("benchmark_strukt.log")
        .build()
    Runner(options).run()
}