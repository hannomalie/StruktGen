# StruktGen

I wasn't too satisfied with the benchmark results of my [no-code-generation kotlin struct library](https://github.com/hannomalie/kotlin-structs),
so I had to test another approach that utilizes code generation.

### What does it do, how to use?
With the help of [ksp](https://github.com/google/ksp), I implemented a code generator that takes your annotated interfaces
and generates implementations for them that can be used as struct-like constructs I call strukts (you know, k for Kotlin...).
Unlike in languages that compile to native code, we can't just use arbitrary backing memory (easily) on the JVM, but something similar: ByteBuffers.
So the idea is to have a struct-like instance that refers to a ByteBuffer allocated elsewhere (hence all properties are ByteBuffer extension properties).
So to say a Strukt instance is just a buffer view that enables regular kotlin syntax.

Code tells more than words

```kotlin

interface Nested: struktgen.api.Strukt {
    var ByteBuffer.a: Int
    val ByteBuffer.b: Int
    companion object
}

interface FooStrukt: struktgen.api.Strukt {
    val ByteBuffer.a: Int
    var ByteBuffer.b: Int
    val ByteBuffer.c: Float
    val ByteBuffer.d: Nested
    var ByteBuffer.e: Boolean
    companion object
}

[...]

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
```

The implementation of the structs is rather simple, as the properties defined on the interface use the correct byte offset
and directly access a given ByteBuffer instance.

### Iteration
First how easy it is:

```kotlin
val buffer = ByteBuffer.allocate(FooStrukt.sizeInBytes * arraySize)

buffer.forEach { strukt ->
    strukt.run {
        assertThat(b).isEqualTo(0)
        b = counter
        assertThat(b).isEqualTo(counter)
    }
}
buffer.forEachIndexed { index, strukt ->
    strukt.run {
        assertThat(b).isEqualTo(0) 
    }
}
```
Note how the buffer is already the receiver of the passed lambda, which eliminates the need of one scoping function
to bring it in scope.

My last library didn't make use of ByteBuffer positions.
The advantage was that I always used absolute offsets to fetch and write data to and from a buffer, and the user didn't have to deal
with any state.
The disadvantage was, that the state needed to reside somewhere, for example in case of iteration, the iterator had
to keep track of the current position.
With my new implementation, the backing buffer is a dependency of all struct properties - it needs to contain the current buffer offset
already, because it is the only dependency I have, and the only one I want to urge my user to provide.

### StruktType<T>

Sadly, Kotlin doesn't have type classes, so it's not easily possible to abstract over static properties of a type.
Nonetheless, I added an interface StruktType<T> that implements things like sizeInBytes or a factory.
The companion objects of a strukt implementation implement the interface and define an extension property _type_
on the companion of the strukt interface's companion.

### TypedBuffer<T>

```kotlin
val typedBuffer = ByteBuffer.allocate(FooStrukt.sizeInBytes * 10).typed(FooStrukt.type) // StruktType in action

typedBuffer.forEachIndexed { index, it ->
    it.run {
        assertThat(b).isEqualTo(0)
        b = index
        assertThat(b).isEqualTo(index)
    }
}
```
A typed buffer is handy when you don't want to pass around naked ByteBuffer instances, as you
might often have buffers that are only used as storage for a single strukt type.
Iteration functions are implemented directly on the typed buffer class, so you can't accidentally import
a wrong one from a companion.

#### index access into a typed buffer
Using index access on a typed buffer might be easy, but as the returned instance is just a shared sliding window,
one might forget about the backing buffer and the state it keeps (the position), so it's best to
not assign instances at all, but just use them in place.
I created some (not yet optimized) syntax that lets you index into a typed buffer, passing a lambda,
and immediately have the buffer as a receiver.

```kotlin
typedBuffer[0] {
    it.run {
        assertThat(b).isEqualTo(0)
        b = 5
        assertThat(b).isEqualTo(5)
    }
}
```

### More details
For the given example, the processor generates the following code, just formatted a little bit uglier
```kotlin
class FooStruktImpl : FooStrukt {
    override val java.nio.ByteBuffer.a: kotlin.Int
        get() = getInt(position() + 0)

    override var java.nio.ByteBuffer.b: kotlin.Int
        get() = getInt(position() + 4)
        set(value) { putInt(position() + 4, value) }
    override val java.nio.ByteBuffer.c: kotlin.Float
        get() = getFloat(position() + 8)


    private val _d = object : Nested {
        override var java.nio.ByteBuffer.a: kotlin.Int
            get() = getInt(position() + 12)
            set(value) { putInt(position() + 12, value) }
        override val java.nio.ByteBuffer.b: kotlin.Int
            get() = getInt(position() + 16)

    }
    override val java.nio.ByteBuffer.d: Nested
        get() = _d

    override var java.nio.ByteBuffer.e: kotlin.Boolean
        get() = getInt(position() + 20) == 1
        set(value) { putInt(position() + 20, if (value) 1 else 0) }

    companion object {
        val sizeInBytes = 24
        val FooStrukt.Companion.sizeInBytes get() = 24
        val FooStrukt.sizeInBytes get() = 24
        operator fun FooStrukt.Companion.invoke() = FooStruktImpl()
    }

}
```
The simple properties of the strukt class don't need much explanation.
For nested structs, only val properties are allowed, because it doesn't really make
a lot of sense to change the reference to the nested property, as the offsets
are somewhat private to the surrounding class and would need to be fulfilled magically
by the instance someone would theoretically assign - doesn't make much sense and use cases are rare,
better to just copy buffer contents.

The companion object is for convenience - you always want to know the size of your objects,
so that you can provide buffer storage with correct bounds, for example for iteration.

### Current syntax limitations
#### Dispatch/extension receiver ugliness
Currently, it's not possible to call extension properties of an instance with the extension receiver in scope and
an explicit dispatch receiver - this makes the syntax currently a bit ugly, because one needs to use scoping functions,
even though the ByteBuffer is already in scope.
The problem is already tracked in a [Kotlin ticket](https://youtrack.jetbrains.com/issue/KT-42626) and is probably solved
as a subtask of one of the next upcoming Kotlin features called [multiple receivers](https://youtrack.jetbrains.com/issue/KT-10468).

#### ByteBuffer extension properties
In my previous library, I made the backing buffer instances a property on the struct (I didn't use 'k' back then) itself.
That enabled direct access in the strukt's properties without the need to provide it on the callsite.
But it was ugly because it didn't really belong there and struct instances rarely where owner of the backing buffer.
Additionally, there was some state management and lazy initialization that made implementation as well as usage of the lib
more complicated.

#### toString
The current version doesn't require annotations anymore, instead there is an api module that is used as a dependency which contains
a base interface that provides a toString method for your strukts.
The special thing about that is, that this very method needs to get a ByteBuffer instance just like all the properties
passed into, which makes it incompatible with javas default toString usage, for example for println.
Another problem is that it's not possible to define fun ByteBuffer.toString(): String in the implementing class,
because the member declaration from Any/Object shadows it.
The buffer is therefore a regular parameter, which makes my api a bit more lame than it should be.
Having a string representation is nonetheless a nice thing, you can get output like this:

```kotlin

buffer[0] {
    println(it.toString(this))
}
// { diffuse = { x = 0.0, y = 0.0, z = 0.0 }, metallic = 0.0, materialType = FOLIAGE, uvScale = { x = 0.0, y = 0.0 } }
```

### Performance

Keep in mind that my benchmarks are really just a first attempt to get a feeling about the performance implications
the implementation has.
It can be flawed, incomplete and may not tell an answer to what I ask.
The benchmark code can be found in the client module.

| Benchmark        | Mode           | Units  |
| ------------- |:-------------:| -----:|
| StruktBenchmark.getPropertyStrukt      | 1019144,783 | ops/ms |
| StruktBenchmark.getPropertyVanilla      | 1336160,070 | ops/ms |
| StruktBenchmark.unsafe_getPropertyStrukt      | 985404,194 | ops/ms |

This benchmark shows difference between reading properties from a direct buffer and regular properties
on an instance that just return some static primitive data.
It can be seen that read access to Struct objects has a performance penalty, compared to classic heap objects,
which could be seen as expected.
Using UNSAFE doesn't make a noticeable difference.

| Benchmark        | Mode           | Units  |
| ------------- |:-------------:| -----:|
| StruktBenchmark.setPropertyStrukt      | 822914,029 | ops/ms |
| StruktBenchmark.setPropertyVanilla      | 1308137,271 | ops/ms |
| StruktBenchmark.unsafe_setPropertyStrukt      | 843925,271 | ops/ms |

Some as above, but for write access.
It can be seen that there is also a performance penalty for writing to a direct buffer instead of accessing heap data,
which could also be seen as expected.
Using UNSAFE doesn't make a noticeable difference.

| Benchmark        | Mode           | Units  |
| ------------- |:-------------:| -----:|
| StruktBenchmark.iterate_getPropertyStrukt      | 1180,447 | ops/ms |
| StruktBenchmark.iterate_getPropertyVanilla        | 1287,677 | ops/ms
| StruktBenchmark.iterateindexed_getPropertyStrukt      | 1061,277 | ops/ms |
| StruktBenchmark.unsafe_iterate_getPropertyStrukt  | 1247,887 | ops/ms

These benchmarks iterate over a buffer with 1000 instances of a struct, or in case of the vanilla one,
iterate an ArrayList of 1000 instances.
It's very interesting how small the difference between buffer and regular heap access already is.
With direct buffers using UNSAFE, the gap is even closer.

