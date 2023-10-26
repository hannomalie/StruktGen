package de.hanno.strukt.generate

import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import de.hanno.strukt.generate.StruktGenerator.Type.Companion.parse
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
            val propertyDeclarationsString = propertyDeclarations.toPropertyDeclarations(byteSizeCounter)

            val printDeclaration = classDeclaration.generatePrintDeclaration(propertyDeclarations)

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
        class Custom(val declaration: KSClassDeclaration): Type(declaration.qualifiedName!!.asString()) {
            override fun getterCallAsString(currentByteOffset: kotlin.Int): String = throw IllegalStateException("This should never get called, remove me later")
            override fun setterCallAsString(currentByteOffset: kotlin.Int): String = throw IllegalStateException("This should never get called, remove me later")

            fun getCustomInstancesDeclarations(propertyName: String, currentByteOffset: AtomicInteger): String {
                val unorderedPropertyDeclarations = declaration.findPropertyDeclarations()
                val printDeclaration = declaration.generatePrintDeclaration(unorderedPropertyDeclarations)

                val propertyDeclarations = declaration.containingFile?.filePath?.let { filePath ->
                    val fileText = File(filePath).readText()
                    unorderedPropertyDeclarations.sortedBy { fileText.indexOf(it.simpleName.asString()) }
                } ?: run {
                    unorderedPropertyDeclarations
                }
                return """|    private val _${propertyName} = object: ${declaration.qualifiedName!!.asString()}, struktgen.api.Strukt {
                    |${propertyDeclarations.toPropertyDeclarations(currentByteOffset)}
                    |$printDeclaration
                    |    }"""
            }

            override fun propertyDeclarationAsString(isMutable: kotlin.Boolean, propertyName: String, currentByteOffset: AtomicInteger): String {
                if(isMutable) throw IllegalStateException("var properties are not allowed for nested properties, as they don't make sense")
                return """|
                    |    ${getCustomInstancesDeclarations(propertyName, currentByteOffset)}
                    |    context(java.nio.ByteBuffer) override val $propertyName: $fqName get() { return _${propertyName} }
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
                kotlin.Double::class.qualifiedName!!.toString() -> Double
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

fun KSClassDeclaration.generatePrintDeclaration(list: List<KSPropertyDeclaration>): String {
    val s = list.joinToString(" + \", \" + ") { ksPropertyDeclaration ->
        val printCall = ksPropertyDeclaration.parseType()!!.let { type ->
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
private val StruktGenerator.Type.sizeInBytes: Int
    get() {
        return when(this) {
            StruktGenerator.Type.Boolean -> 4
            is StruktGenerator.Type.Custom -> declaration.getAllProperties().sumBy { it.parse()?.sizeInBytes ?: 0 }
            StruktGenerator.Type.Float -> 4
            StruktGenerator.Type.Int -> 4
            StruktGenerator.Type.Long -> 8
            is StruktGenerator.Type.Enum -> 4
            StruktGenerator.Type.Double -> 8
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
            StruktGenerator.Type.Double -> 8
        }
    }

private fun KSClassDeclaration.findPropertyDeclarations(): List<KSPropertyDeclaration> = if (classKind == ClassKind.INTERFACE) {
    // TODO: This is super hacky but when the order of properties is not the same as in source code, we have a problem.
    // No other way to enforce the order, as it seems. I already asked here https://github.com/google/ksp/issues/250#issuecomment-1781205812
    getDeclaredProperties().toList().sortedBy {
        it.annotations.firstOrNull { annotation ->
            annotation.shortName.asString() == "Order"
    }?.arguments?.firstOrNull()?.value?.toString()?.toInt() ?: Int.MAX_VALUE }
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