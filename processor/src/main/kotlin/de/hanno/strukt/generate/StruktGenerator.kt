package de.hanno.strukt.generate

import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.isLocal
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import de.hanno.strukt.generate.StruktGenerator.Type.Companion.parse
import struktgen.api.Strukt
import java.io.IOException
import java.lang.IllegalStateException
import java.util.concurrent.atomic.AtomicInteger

private val KSClassDeclaration.isStrukt: Boolean
    get() = classKind == ClassKind.INTERFACE && superTypes.any {
        val resolvedType = it.resolve()
        resolvedType.declaration.qualifiedName!!.asString() == Strukt::class.qualifiedName!!
    }

class StruktGeneratorProvider: SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        val codeGenerator = environment.codeGenerator
        val logger = environment.logger

        return StruktGenerator(logger, codeGenerator)
    }
}

class StruktGenerator(val logger: KSPLogger, val codeGenerator: CodeGenerator) : SymbolProcessor {

    private fun nonEmptyPackageName(it: KSClassDeclaration): String {
        val packageNameOrEmpty = it.packageName.asString()
        return if(packageNameOrEmpty.isNotEmpty()) "default" else packageNameOrEmpty
    }

    val propertyDeclarationsPerClass = mutableMapOf<KSClassDeclaration, List<KSPropertyDeclaration>>()
    val visitor = FindPropertiesVisitor()
    private var invoked = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (invoked) {
            return emptyList()
        }

        resolver.getAllFiles().toList().map {
            it.accept(visitor, Unit)
        }

        codeGenerator.createNewFile(Dependencies.ALL_FILES, "default", "default", "kt").use { stream ->
            stream.write("""
                |package struktgen
                |
                |interface Strukt {
                |  fun toString(buffer: java.nio.ByteBuffer): String
                |}
                |interface StruktType<T> {
                |    val sizeInBytes: Int 
                |    val factory: () -> T
                |}
                |
                |@JvmInline
                |value class Invokable<T>(val buffer: TypedBuffer<T>) {
                |    operator fun invoke(block: java.nio.ByteBuffer.(T) -> Unit) {
                |       buffer.byteBuffer.block(buffer._slidingWindow)
                |   }
                |}
                |
                |fun <T> java.nio.ByteBuffer.typed(struktType: StruktType<T>) = TypedBuffer(this, struktType)
                |
                |data class TypedBuffer<T>(val byteBuffer: java.nio.ByteBuffer, val struktType: StruktType<T>) {
                |    @PublishedApi internal val _slidingWindow = struktType.factory()
                |    inline fun forEach(untilIndex: Int = byteBuffer.capacity()/struktType.sizeInBytes, block: java.nio.ByteBuffer.(T) -> Unit) {
                |       byteBuffer.position(0)
                |       val untilPosition = untilIndex * struktType.sizeInBytes
                |       while(byteBuffer.position() < untilPosition) {
                |           byteBuffer.block(_slidingWindow)
                |           byteBuffer.position(byteBuffer.position() + struktType.sizeInBytes)
                |       }
                |       byteBuffer.position(0)
                |    }
                |    
                |    inline fun forEachIndexed(untilIndex: Int = byteBuffer.capacity()/struktType.sizeInBytes, block: java.nio.ByteBuffer.(Int, T) -> Unit) {
                |       byteBuffer.position(0)
                |       var counter = 0
                |       val untilPosition = untilIndex * struktType.sizeInBytes
                |       while(byteBuffer.position() < untilPosition) {
                |           byteBuffer.block(counter, _slidingWindow)
                |           counter++
                |           byteBuffer.position(counter * struktType.sizeInBytes)
                |       }
                |       byteBuffer.position(0)
                |    }
                |    inline operator fun get(index: Int): Invokable<T> {
                |        byteBuffer.position(index * struktType.sizeInBytes)
                |        return Invokable(this)
                |    }
                |}
            """.trimMargin().toByteArray(Charsets.UTF_8))
        }
        propertyDeclarationsPerClass.forEach { (classDeclaration, propertyDeclarations) ->

            val interfaceName = classDeclaration.simpleName.asString()
            val interfaceFQName = classDeclaration.qualifiedName!!.asString()
            val packageName = classDeclaration.packageName.asString()
            val implClassName = interfaceName + "Impl"
            val implClassFQName = "$packageName.$implClassName"
            val nonEmptyPackageName = nonEmptyPackageName(classDeclaration)

            val byteSizeCounter = AtomicInteger()
            val propertyDeclarationsString = propertyDeclarations.toPropertyDeclarations(byteSizeCounter)

            val toStringDeclaration = classDeclaration.generateToStringDeclaration(propertyDeclarations)

            codeGenerator.createNewFile(Dependencies.ALL_FILES, nonEmptyPackageName, implClassName, "kt").use { stream ->
                try {
                    val companionDeclaration = """    companion object: struktgen.StruktType<$interfaceFQName> { 
                        |        override val sizeInBytes = $byteSizeCounter 
                        |        val $interfaceFQName.Companion.sizeInBytes get() = $byteSizeCounter
                        |        val $interfaceFQName.Companion.type: struktgen.StruktType<$interfaceFQName> get() = this@Companion
                        |        val $interfaceFQName.sizeInBytes get() = $byteSizeCounter
                        |        operator fun $interfaceFQName.Companion.invoke() = $implClassName()
                        |        override val factory = { $implClassName() }
                        |        
                        |        @PublishedApi internal val _slidingWindow = $implClassName()
                        |        inline fun java.nio.ByteBuffer.forEach(block: java.nio.ByteBuffer.($interfaceFQName) -> Unit) { 
                        |           position(0)
                        |           while(position() + sizeInBytes <= capacity()) {
                        |               block(_slidingWindow)
                        |               position(position() + sizeInBytes)
                        |           }
                        |           position(0)
                        |        }
                        |        inline fun java.nio.ByteBuffer.forEachIndexed(block: java.nio.ByteBuffer.(kotlin.Int, $interfaceFQName) -> Unit) { 
                        |           position(0)
                        |           var counter = 0
                        |           while(position() + sizeInBytes <= capacity()) {
                        |               block(counter, _slidingWindow)
                        |               counter++
                        |               position(counter * sizeInBytes)
                        |           }
                        |           position(0)
                        |        }
                        |    }
                        |""".trimMargin()
                    stream.write(generateStruktImplementationCode(
                        implClassName,
                        interfaceFQName,
                        propertyDeclarationsString,
                        toStringDeclaration,
                        companionDeclaration
                    ))
                } catch (e: IOException) {
                    logger.error("Cannot write to file $nonEmptyPackageName/$implClassName")
                }
            }
        }

        invoked = true
        return emptyList()
    }

    private fun generateStruktImplementationCode(
        implClassName: String,
        interfaceFQName: String,
        propertyDeclarations: String,
        toStringDeclaration: String,
        companionDeclaration: String
    ) = """
                    |class $implClassName: $interfaceFQName, struktgen.Strukt {
                    |$propertyDeclarations
                    |$toStringDeclaration
                    |$companionDeclaration
                    |}
                """.trimMargin().toByteArray(Charsets.UTF_8)

    sealed class Type(val fqName: String) {
        abstract fun getterCallAsString(currentByteOffset: kotlin.Int): String
        abstract fun setterCallAsString(currentByteOffset: kotlin.Int): String

        open fun propertyDeclarationAsString(isMutable: kotlin.Boolean, propertyName: String, currentByteOffset: AtomicInteger): String {
            return """override ${if(isMutable) "var" else "val"} java.nio.ByteBuffer.$propertyName: $fqName 
                |   get() { return ${getterCallAsString(currentByteOffset.get())} }
                |   ${if(isMutable) "set(value) { ${setterCallAsString(currentByteOffset.get())} }" else ""}""".trimMargin()
        }

        object Boolean: Type(kotlin.Boolean::class.qualifiedName!!) {
            override fun getterCallAsString(currentByteOffset: kotlin.Int): String = "getInt(position() + $currentByteOffset) == 1"
            override fun setterCallAsString(currentByteOffset: kotlin.Int): String = "putInt(position() + $currentByteOffset, if(value) 1 else 0)"
        }

        object Int: Type(kotlin.Int::class.qualifiedName!!) {
            override fun getterCallAsString(currentByteOffset: kotlin.Int): String = "getInt(position() + $currentByteOffset)"
            override fun setterCallAsString(currentByteOffset: kotlin.Int): String = "putInt(position() + $currentByteOffset, value)"
        }
        object Float: Type(kotlin.Float::class.qualifiedName!!) {
            override fun getterCallAsString(currentByteOffset: kotlin.Int): String = "getFloat(position() + $currentByteOffset)"
            override fun setterCallAsString(currentByteOffset: kotlin.Int): String = "putFloat(position() + $currentByteOffset, value)"
        }
        object Long: Type(kotlin.Long::class.qualifiedName!!) {
            override fun getterCallAsString(currentByteOffset: kotlin.Int): String = "getLong(position() + $currentByteOffset)"
            override fun setterCallAsString(currentByteOffset: kotlin.Int): String = "putLong(position() + $currentByteOffset, value)"
        }
        class Enum(val declaration: KSClassDeclaration): Type(declaration.qualifiedName!!.asString()) {
            override fun getterCallAsString(currentByteOffset: kotlin.Int): String = "${declaration.qualifiedName!!.asString()}.values()[getInt(position() + $currentByteOffset)]"
            override fun setterCallAsString(currentByteOffset: kotlin.Int): String = "putInt(position() + $currentByteOffset, value.ordinal)"
        }
        class Custom(val declaration: KSClassDeclaration): Type(declaration.qualifiedName!!.asString()) {
            override fun getterCallAsString(currentByteOffset: kotlin.Int): String = throw IllegalStateException("This should never get called, remove me later")
            override fun setterCallAsString(currentByteOffset: kotlin.Int): String = throw IllegalStateException("This should never get called, remove me later")

            fun getCustomInstancesDeclarations(propertyName: String, currentByteOffset: AtomicInteger): String {
                val propertyDeclarations = declaration.findPropertyDeclarations()
                val toStringDeclaration = declaration.generateToStringDeclaration(propertyDeclarations)

                return """|    private val _${propertyName} = object: ${declaration.qualifiedName!!.asString()}, struktgen.Strukt {
                    |${propertyDeclarations.toPropertyDeclarations(currentByteOffset)}
                    |$toStringDeclaration
                    |    }"""
            }

            override fun propertyDeclarationAsString(isMutable: kotlin.Boolean, propertyName: String, currentByteOffset: AtomicInteger): String {
                if(isMutable) throw IllegalStateException("var properties are not allowed for nested properties, as they don't make sense")
                return """|
                    |    ${getCustomInstancesDeclarations(propertyName, currentByteOffset)}
                    |    override val java.nio.ByteBuffer.$propertyName: $fqName get() { return _${propertyName} }
                    |""".trimMargin()
            }

            private val implClassHeader get() = """$implClassName: ${declaration.qualifiedName!!.asString()}"""
            private val implClassName get() = """${declaration.simpleName.asString()}Impl"""
        }
        companion object {
            fun KSDeclaration.parse(): Type? = if(this is KSClassDeclaration) parse() else null

            fun KSClassDeclaration.parse(): Type? = when(qualifiedName!!.asString()) {
                kotlin.Boolean::class.qualifiedName!!.toString() -> Boolean
                kotlin.Int::class.qualifiedName!!.toString() -> Int
                kotlin.Float::class.qualifiedName!!.toString() -> Float
                kotlin.Long::class.qualifiedName!!.toString() -> Long
                else -> {
                    when {
                        this.isStrukt -> Custom(this)
                        this.classKind == ClassKind.ENUM_CLASS -> Enum(this)
                        else -> null
                    }
                }
            }
        }
    }

    inner class FindPropertiesVisitor : KSVisitorVoid() {

        override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
            if(classDeclaration.isStrukt) {
                propertyDeclarationsPerClass[classDeclaration] = classDeclaration.findPropertyDeclarations()
            }
        }

        override fun visitFile(file: KSFile, data: Unit) {
            file.declarations.toList().map { it.accept(this, Unit) }
        }
    }
}

fun KSClassDeclaration.generateToStringDeclaration(list: List<KSPropertyDeclaration>): String {
    val s = list.joinToString(" + \", \" + ") { ksPropertyDeclaration ->
        val toStringCall = ksPropertyDeclaration.parseType()!!.let { type ->
            when (type) {
                is StruktGenerator.Type.Custom -> {
                    val qualifiedThis = if (classKind == ClassKind.OBJECT) "" else ksPropertyDeclaration.simpleName.asString()
                    "_$qualifiedThis.toString(this)"
                }
                else -> ksPropertyDeclaration.simpleName.asString() + ".toString()"
            }
        }
        "\"" + ksPropertyDeclaration.simpleName.asString() + " = \"" + "+ " + toStringCall
    }
    return """     override fun toString(buffer: java.nio.ByteBuffer) = buffer.run {"{ "+$s+" }"} """
}
private val StruktGenerator.Type.sizeInBytes: Int
    get() {
        return when(this) {
            StruktGenerator.Type.Boolean -> 4
            is StruktGenerator.Type.Custom -> declaration.getAllProperties().sumBy { it.parse()?.sizeInBytes ?: 0 }
            StruktGenerator.Type.Float -> 4
            StruktGenerator.Type.Int -> 4
            StruktGenerator.Type.Long -> 8
            is StruktGenerator.Type.Enum -> 4
        }
    }
private val KSClassDeclaration.sizeInBytes: Int
    get() {
        val type = parse() ?: return 0

        return when(type) {
            StruktGenerator.Type.Boolean -> 4
            is StruktGenerator.Type.Custom -> type.declaration.getAllProperties().sumBy { it.parse()?.sizeInBytes ?: 0 }
            StruktGenerator.Type.Float -> 4
            StruktGenerator.Type.Int -> 4
            StruktGenerator.Type.Long -> 8
            is StruktGenerator.Type.Enum -> 4
        }
    }

private fun KSClassDeclaration.findPropertyDeclarations(): List<KSPropertyDeclaration> = if (classKind == ClassKind.INTERFACE) {
    getDeclaredProperties().toList()
} else emptyList()

private fun List<KSPropertyDeclaration>.toPropertyDeclarations(currentByteOffset: AtomicInteger): String {

    return joinToString("\n") { declaration ->
        val type = declaration.parseType()

        type?.let { type ->
            "\t" + type.propertyDeclarationAsString(declaration.isMutable, declaration.simpleName.asString(), currentByteOffset).apply {
                currentByteOffset.getAndAdd(type.sizeInBytes)
            }
        } ?: ""
    }
}

private fun KSPropertyDeclaration.parseType() = type.resolve().declaration.parse()