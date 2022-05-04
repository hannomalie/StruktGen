package de.hanno.struktgen.benchmark

import FooStrukt
import FooStruktImpl
import FooStruktImpl.Companion.forEach
import FooStruktImpl.Companion.forEachIndexed
import FooStruktImpl.Companion.sizeInBytes
import Nested
import org.lwjgl.system.MemoryUtil
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import org.openjdk.jmh.runner.Runner
import org.openjdk.jmh.runner.options.OptionsBuilder
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

val iterationCount = 1000

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

    @State(Scope.Thread)
    open class SimpleState(
        val strukt: VanillaStruktImpl = VanillaStruktImpl(),
        val buffer: ByteBuffer = ByteBuffer.allocateDirect(FooStruktImpl.sizeInBytes)
    )
    @State(Scope.Thread)
    open class StruktState(
        val strukt: FooStruktImpl = FooStruktImpl(),
        val buffer: ByteBuffer = ByteBuffer.allocateDirect(FooStruktImpl.sizeInBytes)
    )
    @State(Scope.Thread)
    open class UnsafeStruktState(
        val strukt: FooStruktImpl = FooStruktImpl(),
        val buffer: ByteBuffer = MemoryUtil.memAlloc(FooStruktImpl.sizeInBytes)
    ) {
        @TearDown(Level.Trial)
        fun tearDown() {
            MemoryUtil.memFree(buffer)
        }
    }

    @State(Scope.Thread)
    open class IterationStruktState(
        val buffer: ByteBuffer = ByteBuffer.allocateDirect(FooStrukt.sizeInBytes * iterationCount)
    )
    @State(Scope.Thread)
    open class IterationVanillaState(
        val buffer: ByteBuffer = ByteBuffer.allocateDirect(FooStrukt.sizeInBytes * iterationCount),
        val instances: List<VanillaStruktImpl> = (0 until iterationCount).map { VanillaStruktImpl() }
    )
    @State(Scope.Thread)
    open class UnsafeIterationStruktState(
        val buffer: ByteBuffer = MemoryUtil.memAlloc(FooStrukt.sizeInBytes * iterationCount)
    ) {
        @TearDown(Level.Trial)
        fun tearDown() {
            MemoryUtil.memFree(buffer)
        }
    }

    @Benchmark
    fun getPropertyVanilla(blackHole: Blackhole, state: SimpleState): Int {
        val b = state.buffer.run { state.strukt.b }
        blackHole.consume(b)
        return b
    }
    @Benchmark
    fun getPropertyStrukt(blackHole: Blackhole, state: StruktState): Int {
        val b = state.buffer.run { state.strukt.b }
        blackHole.consume(b)
        return b
    }
    @Benchmark
    fun unsafe_getPropertyStrukt(blackHole: Blackhole, state: UnsafeStruktState): Int {
        val b = state.buffer.run { state.strukt.b }
        blackHole.consume(b)
        return b
    }

    @Benchmark
    fun setPropertyVanilla(blackHole: Blackhole, state: SimpleState): Int {
        val da = state.buffer.run {
            state.strukt.run {
                d.a = 2
                d.a
            }
        }
        blackHole.consume(da)
        return da
    }

    @Benchmark
    fun setPropertyStrukt(blackHole: Blackhole, state: StruktState): Int {
        val da = state.buffer.run {
            state.strukt.run {
                d.a = 2
                d.a
            }
        }
        blackHole.consume(da)
        return da
    }

    @Benchmark
    fun unsafe_setPropertyStrukt(blackHole: Blackhole, state: UnsafeStruktState): Int {
        val da = state.buffer.run {
            state.strukt.run {
                d.a = 2
                d.a
            }
        }
        blackHole.consume(da)
        return da
    }

//    @Benchmark
    fun iterate_getPropertyStrukt(blackHole: Blackhole, state: IterationStruktState) {
        state.buffer.forEach { strukt ->
            val b = strukt.b
            blackHole.consume(b)
        }
    }
//    @Benchmark
    fun iterate_getPropertyVanilla(blackHole: Blackhole, state: IterationVanillaState) {
        state.instances.forEach { strukt ->
            val b = state.buffer.run { strukt.b }
            blackHole.consume(b)
        }
    }

//    @Benchmark
    fun unsafe_iterate_getPropertyStrukt(blackHole: Blackhole, state: UnsafeIterationStruktState) {
        state.buffer.forEach { strukt ->
            val b = strukt.b
            blackHole.consume(b)
        }
    }
//    @Benchmark
    fun iterateindexed_getPropertyStrukt(blackHole: Blackhole, state: IterationStruktState) {
        state.buffer.forEachIndexed { index, strukt ->
            val b = strukt.b
            blackHole.consume(b)
        }
    }

    class VanillaStruktImpl : FooStrukt {

        context(ByteBuffer)
        override val a: Int
            get() = 0
        var _b = 0

        context(ByteBuffer)
        override var b: Int
            get() = _b
            set(value) {
                _b = value
            }

        context(ByteBuffer)
        override val c: Float
            get() = 0f
        val _d = object : Nested {
            var _a = 0

            context(ByteBuffer)
            override var a: Int
                get() = _a
                set(value) {
                    _a = value
                }
            context(ByteBuffer)
            override val b: Int
                get() = 0

            context(ByteBuffer)
            override fun print(): String {
                TODO("Not implemented")
            }

        }
        context(ByteBuffer)
        override val d: Nested
            get() = _d
        context(ByteBuffer)
        override var e: Boolean
            get() = true
            set(value) {}

        context(ByteBuffer)
        override fun print(): String {
            TODO("Not implemented")
        }
    }
}

fun main() {

    val options = OptionsBuilder()
        .include(StruktBenchmark::class.java.simpleName)
        .output("benchmark_strukt.log")
        .build()
    Runner(options).run()
}