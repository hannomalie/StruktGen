package de.hanno.strukt.generate

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import struktgen.api.Strukt
import java.io.File
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

        visitor.resolver = resolver

        resolver.getAllFiles().toList().map {
            it.accept(visitor, Unit)
        }

        propertyDeclarationsPerClass.forEach { (classDeclaration, propertyDeclarations) ->

            val interfaceName = classDeclaration.simpleName.asString()
            val interfaceFQName = classDeclaration.qualifiedName!!.asString()
            val packageName = classDeclaration.packageName.asString()
            val implClassName = interfaceName + "Impl"
            val implClassFQName = "$packageName.$implClassName"
            val nonEmptyPackageName = nonEmptyPackageName(classDeclaration)

            val byteSizeCounter = AtomicInteger()
            val propertyDeclarationsString = propertyDeclarations.toPropertyDeclarations(byteSizeCounter, resolver)

            val printDeclaration = classDeclaration.generatePrintDeclaration(propertyDeclarations, resolver)

            // TODO: Check why the hell files are tried to be written multiple times instead of this nasty hack
            if(codeGenerator.generatedFile.toList().none { it.nameWithoutExtension == implClassName }) {
                codeGenerator.createNewFile(Dependencies.ALL_FILES, nonEmptyPackageName, implClassName, "kt").use { stream ->
                    try {
                        val companionDeclaration = """    companion object: struktgen.api.StruktType<$interfaceFQName> { 
                    |        override val sizeInBytes = $byteSizeCounter 
                    |        val $interfaceFQName.Companion.sizeInBytes get() = $byteSizeCounter
                    |        val $interfaceFQName.Companion.type: struktgen.api.StruktType<$interfaceFQName> get() = this@Companion
                    |        val $interfaceFQName.sizeInBytes get() = $byteSizeCounter
                    |        operator fun $interfaceFQName.Companion.invoke() = $implClassName()
                    |        override val factory = { $implClassName() }
                    |        
                    |        @PublishedApi internal val _slidingWindow = $implClassName()
                    |        inline fun java.nio.ByteBuffer.forEach(block: context(java.nio.ByteBuffer) ($interfaceFQName) -> Unit) { 
                    |           position(0)
                    |           while(position() + sizeInBytes <= capacity()) {
                    |               block(this, _slidingWindow)
                    |               position(position() + sizeInBytes)
                    |           }
                    |           position(0)
                    |        }
                    |        inline fun java.nio.ByteBuffer.forEachIndexed(block: context(java.nio.ByteBuffer) (kotlin.Int, $interfaceFQName) -> Unit) { 
                    |           position(0)
                    |           var counter = 0
                    |           while(position() + sizeInBytes <= capacity()) {
                    |               block(this, counter, _slidingWindow)
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
                            printDeclaration,
                            companionDeclaration
                        ))
                    } catch (e: IOException) {
                        logger.error("Cannot write to file $nonEmptyPackageName/$implClassName")
                    }
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
        printDeclaration: String,
        companionDeclaration: String
    ) = """
                    |class $implClassName: $interfaceFQName, struktgen.api.Strukt {
                    |$propertyDeclarations
                    |$printDeclaration
                    |$companionDeclaration
                    |}
                """.trimMargin().toByteArray(Charsets.UTF_8)

    sealed class Type(val fqName: String) {
        abstract fun getterCallAsString(currentByteOffset: kotlin.Int): String
        abstract fun setterCallAsString(currentByteOffset: kotlin.Int): String

        open fun propertyDeclarationAsString(isMutable: kotlin.Boolean, propertyName: String, currentByteOffset: AtomicInteger): String {
            return """context(java.nio.ByteBuffer) override ${if(isMutable) "var" else "val"} $propertyName: $fqName 
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
        object Double: Type(kotlin.Double::class.qualifiedName!!) {
            override fun getterCallAsString(currentByteOffset: kotlin.Int): String = "getDouble(position() + $currentByteOffset)"
            override fun setterCallAsString(currentByteOffset: kotlin.Int): String = "putDouble(position() + $currentByteOffset, value)"
        }
        object Long: Type(kotlin.Long::class.qualifiedName!!) {
            override fun getterCallAsString(currentByteOffset: kotlin.Int): String = "getLong(position() + $currentByteOffset)"
            override fun setterCallAsString(currentByteOffset: kotlin.Int): String = "putLong(position() + $currentByteOffset, value)"
        }
        class Enum(val declaration: KSClassDeclaration): Type(declaration.qualifiedName!!.asString()) {
            override fun getterCallAsString(currentByteOffset: kotlin.Int): String = "${declaration.qualifiedName!!.asString()}.values()[getInt(position() + $currentByteOffset)]"
            override fun setterCallAsString(currentByteOffset: kotlin.Int): String = "putInt(position() + $currentByteOffset, value.ordinal)"
        }
        class Custom(val declaration: KSClassDeclaration, internal val resolver: Resolver): Type(declaration.qualifiedName!!.asString()) {
            override fun getterCallAsString(currentByteOffset: kotlin.Int): String = throw IllegalStateException("This should never get called, remove me later")
            override fun setterCallAsString(currentByteOffset: kotlin.Int): String = throw IllegalStateException("This should never get called, remove me later")

            fun getCustomInstancesDeclarations(propertyName: String, currentByteOffset: AtomicInteger, resolver: Resolver): String {
                val propertyDeclarations = declaration.findPropertyDeclarations(resolver)
                val printDeclaration = declaration.generatePrintDeclaration(
                    propertyDeclarations,
                    resolver
                )

                return """|    private val _${propertyName} = object: ${declaration.qualifiedName!!.asString()}, struktgen.api.Strukt {
                    |${propertyDeclarations.toPropertyDeclarations(currentByteOffset, resolver)}
                    |$printDeclaration
                    |    }"""
            }

            override fun propertyDeclarationAsString(isMutable: kotlin.Boolean, propertyName: String, currentByteOffset: AtomicInteger): String {
                if(isMutable) throw IllegalStateException("var properties are not allowed for nested properties, as they don't make sense")
                return """|
                    |    ${getCustomInstancesDeclarations(propertyName, currentByteOffset, resolver)}
                    |    context(java.nio.ByteBuffer) override val $propertyName: $fqName get() { return _${propertyName} }
                    |""".trimMargin()
            }

            private val implClassHeader get() = """$implClassName: ${declaration.qualifiedName!!.asString()}"""
            private val implClassName get() = """${declaration.simpleName.asString()}Impl"""
        }
    }

    inner class FindPropertiesVisitor : KSVisitorVoid() {
        internal lateinit var resolver: Resolver

        override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
            if(classDeclaration.isStrukt) {
                propertyDeclarationsPerClass[classDeclaration] = classDeclaration.findPropertyDeclarations(resolver)
            }
        }

        override fun visitFile(file: KSFile, data: Unit) {
            file.declarations.toList().map { it.accept(this, Unit) }
        }
    }
}

internal fun StruktGenerator.Type.getSizeInBytes(resolver: Resolver): Int {
    return when (this) {
        StruktGenerator.Type.Boolean -> 4
        is StruktGenerator.Type.Custom -> declaration.getAllProperties()
            .sumOf { it.parse(resolver)?.getSizeInBytes(resolver) ?: 0 }

        StruktGenerator.Type.Float -> 4
        StruktGenerator.Type.Int -> 4
        StruktGenerator.Type.Long -> 8
        StruktGenerator.Type.Double -> 8
        is StruktGenerator.Type.Enum -> 4
    }
}
internal fun KSClassDeclaration.getSizeInBytes(resolver: Resolver): Int {
    val type = parse(resolver) ?: return 0

    return when (type) {
        StruktGenerator.Type.Boolean -> 4
        is StruktGenerator.Type.Custom -> type.declaration.getAllProperties()
            .sumBy { it.parse(resolver)?.getSizeInBytes(resolver) ?: 0 }

        StruktGenerator.Type.Float -> 4
        StruktGenerator.Type.Int -> 4
        StruktGenerator.Type.Long -> 8
        is StruktGenerator.Type.Enum -> 4
        StruktGenerator.Type.Double -> 8
    }
}

internal fun List<KSPropertyDeclaration>.toPropertyDeclarations(currentByteOffset: AtomicInteger, resolver:Resolver): String {

    return joinToString("\n") { declaration ->
        val type = declaration.parseType(resolver)

        type?.let { type ->
            "\t" + type.propertyDeclarationAsString(declaration.isMutable, declaration.simpleName.asString(), currentByteOffset).apply {
                currentByteOffset.getAndAdd(type.getSizeInBytes(resolver))
            }
        } ?: ""
    }
}

fun KSDeclaration.parse(resolver: Resolver): StruktGenerator.Type? = if(this is KSClassDeclaration) parse(resolver) else null

fun KSClassDeclaration.parse(resolver: Resolver): StruktGenerator.Type? = when(qualifiedName!!.asString()) {
    kotlin.Boolean::class.qualifiedName!!.toString() -> StruktGenerator.Type.Boolean
    kotlin.Int::class.qualifiedName!!.toString() -> StruktGenerator.Type.Int
    kotlin.Float::class.qualifiedName!!.toString() -> StruktGenerator.Type.Float
    kotlin.Long::class.qualifiedName!!.toString() -> StruktGenerator.Type.Long
    else -> {
        when {
            this.isStrukt -> StruktGenerator.Type.Custom(this, resolver)
            this.classKind == ClassKind.ENUM_CLASS -> StruktGenerator.Type.Enum(this)
            else -> null
        }
    }
}

internal fun KSPropertyDeclaration.parseType(resolver: Resolver) = type.resolve().declaration.parse(resolver)

fun KSClassDeclaration.generatePrintDeclaration(list: List<KSPropertyDeclaration>, resolver: Resolver): String {
    val s = list.joinToString(" + \", \" + ") { ksPropertyDeclaration ->
        val printCall = ksPropertyDeclaration.parseType(resolver)!!.let { type ->
            when (type) {
                is StruktGenerator.Type.Custom -> {
                    val qualifiedThis = if (classKind == ClassKind.OBJECT) "" else ksPropertyDeclaration.simpleName.asString()
                    "_$qualifiedThis.print()"
                }
                else -> ksPropertyDeclaration.simpleName.asString() + ".toString()"
            }
        }
        "\"" + ksPropertyDeclaration.simpleName.asString() + " = \"" + "+ " + printCall
    }
    return """     context(java.nio.ByteBuffer) override fun print() = "{ "+$s+" }" """
}
@OptIn(KspExperimental::class)
internal fun KSClassDeclaration.findPropertyDeclarations(resolver: Resolver): List<KSPropertyDeclaration> = if (classKind == ClassKind.INTERFACE) {
    resolver.getDeclarationsInSourceOrder(this).filterIsInstance<KSPropertyDeclaration>().toList()
} else emptyList()
