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
    context(ByteBuffer)
    var a: Int
    context(ByteBuffer)
    val b: Int
    companion object
}

interface FooStrukt: struktgen.api.Strukt {
    context(ByteBuffer)
    val a: Int
    context(ByteBuffer)
    var b: Int
    context(ByteBuffer)
    val c: Float
    context(ByteBuffer)
    val d: Nested
    context(ByteBuffer)
    var e: Boolean
    companion object
}

[...]

val simple = FooStrukt()
assertThat(FooStrukt.sizeInBytes).isEqualTo(24)
val buffer = ByteBuffer.allocate(FooStrukt.sizeInBytes)
buffer.run {
    assertThat(simmple.a).isEqualTo(0)
    assertThat(simmple.b).isEqualTo(0)
    assertThat(simmple.c).isEqualTo(0f)
    assertThat(simmple.d.a).isEqualTo(0)
    assertThat(simmple.d.b).isEqualTo(0)
    assertThat(simmple.e).isFalse()
}
```

The implementation of the structs is rather simple, as the properties defined on the interface use the correct byte offset
and directly access a given ByteBuffer instance.

### Iteration
First how easy it is:

```kotlin
val buffer = ByteBuffer.allocate(FooStrukt.sizeInBytes * arraySize)

buffer.forEach { strukt ->
    assertThat(strukt.b).isEqualTo(0)
    strukt.b = counter
    assertThat(strukt.b).isEqualTo(counter)
}
buffer.forEachIndexed { index, strukt ->
    assertThat(strukt.b).isEqualTo(0) 
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
    assertThat(it.b).isEqualTo(0)
    it.b = index
    assertThat(it.b).isEqualTo(index)
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
typedBuffer.forIndex(0) {
    assertThat(it.b).isEqualTo(0)
    it.b = 5
    assertThat(it.b).isEqualTo(5)
}
```

### More details
For the given example, the processor generates the following code, just formatted a little bit uglier
```kotlin
class FooStruktImpl : FooStrukt {
    context(java.nio.ByteBuffer)
    override val a: kotlin.Int
        get() = getInt(position() + 0)

    context(java.nio.ByteBuffer)
    override var b: kotlin.Int
        get() = getInt(position() + 4)
        set(value) { putInt(position() + 4, value) }
    context(java.nio.ByteBuffer)
    override val c: kotlin.Float
        get() = getFloat(position() + 8)


    private val _d = object : Nested {
        context(java.nio.ByteBuffer)
        override var a: kotlin.Int
            get() = getInt(position() + 12)
            set(value) { putInt(position() + 12, value) }
        context(java.nio.ByteBuffer)
        override val b: kotlin.Int
            get() = getInt(position() + 16)

    }
    context(java.nio.ByteBuffer)
    override val d: Nested
        get() = _d

    context(java.nio.ByteBuffer)
    override var e: kotlin.Boolean
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
#### Dispatch/extension receiver mild ugliness
Context receivers are currently a preview feature in Kotlin, but I nonetheless added support for it and made it
the only codegen strategy because it works reasonably well.
So you must enable it in your project in order to be able to use the code generated by StruktGen.
The current state of the language feature doesn't give you any new mechanism to bring context receivers
into scope, which means you need to use the classic scope functions (with, apply, run ...) and bring
a ByteBuffer into scope first, before you can use a Strukt's properties.
This is a lot better than what I achieved without context receivers, as one needed to nest multiple run-calls
before, but let's all forget about that :)

#### ByteBuffer context properties
In my previous library, I made the backing buffer instances a property on the struct (I didn't use 'k' back then) itself.
That enabled direct access in the strukt's properties without the need to provide it on the callsite.
But it was ugly because it didn't really belong there and struct instances rarely where owner of the backing buffer.
Additionally, there was some state management and lazy initialization that made implementation as well as usage of the lib
more complicated.

#### toString / print
The current version doesn't require annotations anymore, instead there is an api module that is used as a dependency which contains
a base interface that provides a print method for your strukts.
The special thing about that is, that this very method needs to get a ByteBuffer instance just like all the properties
passed into, which makes it incompatible with javas default toString usage, for example for println.
Another problem is that it's not possible to define fun ´ByteBuffer.toString(): String´ in the implementing class,
because the member declaration from Any/Object shadows it.
The same goes for a member function context(ByteBuffer) toString(): String, because its signature clashes with Java's
toString.
The method is therefore named `print` and uses a ByteBuffer context, which makes its usage a bit uglier than it could be, but
having a string representation is nonetheless a useful thing, you can get output like this:

```kotlin

buffer.forIndex(0) {
    println(it.print())
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

