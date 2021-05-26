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
// Coming soon ...

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

### Performance

// Coming soon ...