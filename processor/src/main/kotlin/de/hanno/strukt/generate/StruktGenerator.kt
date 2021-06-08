package de.hanno.strukt.generate

import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import de.hanno.strukt.generate.StruktGenerator.Type.Companion.parse
import java.io.IOException
import java.lang.IllegalStateException
import java.util.concurrent.atomic.AtomicInteger

private val KSClassDeclaration.isStrukt: Boolean
    get() = classKind == ClassKind.INTERFACE && this.annotations.any { it.shortName.asString() == "Strukt" }

class StruktGeneratorProvider: SymbolProcessorProvider {
    override fun create(
        options: Map<String, String>,
        kotlinVersion: KotlinVersion,
        codeGenerator: CodeGenerator,
        logger: KSPLogger
    ): SymbolProcessor {
        return StruktGenerator(logger, codeGenerator)
    }
}

class StruktGenerator(val logger: KSPLogger, val codeGenerator: CodeGenerator) : SymbolProcessor {

//    override fun process(resolver: Resolver): List<KSAnnotated> {
//        val interfaces = resolver.getSymbolsWithAnnotation("Strukt").filterIsInstance<KSClassDeclaration>().filter { it.classKind == ClassKind.INTERFACE }.toList()
//
//        interfaces.forEach {
//            val implClassName = it.simpleName.asString() + "Impl"
//            val nonEmptyPackageName = nonEmptyPackageName(it)
//            val file = codeGenerator
//                .createNewFile(Dependencies.ALL_FILES, nonEmptyPackageName, implClassName, "kt")
//            val propertyDeclarations = it.getDeclaredProperties().joinToString("") { declaration ->
//                """val ${declaration.simpleName.asString()}\n\tget() { return "foo" }"""
//            }
//            file.write("""
//                class $implClassName {
//                    $propertyDeclarations
//                }
//            """.trimIndent().toByteArray(Charsets.UTF_8))
//            file.close()
//        }
//        return interfaces
//    }

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
            val implClassName = interfaceName + "Impl"
            val nonEmptyPackageName = nonEmptyPackageName(classDeclaration)

            val byteSizeCounter = AtomicInteger()
            val propertyDeclarations = propertyDeclarations.toPropertyDeclarations(byteSizeCounter)

            codeGenerator.createNewFile(Dependencies.ALL_FILES, nonEmptyPackageName, implClassName, "kt").use { stream ->
                try {
                    val companionDeclaration = """    companion object { 
                        |        val sizeInBytes = $byteSizeCounter 
                        |        val $interfaceName.Companion.sizeInBytes get() = $byteSizeCounter
                        |        val $interfaceName.sizeInBytes get() = $byteSizeCounter
                        |        operator fun $interfaceName.Companion.invoke() = $implClassName()
                        |        
                        |        @PublishedApi internal val _slidingWindow = $implClassName()
                        |        inline fun java.nio.ByteBuffer.forEach(block: java.nio.ByteBuffer.($interfaceName) -> Unit) { 
                        |           position(0)
                        |           while(position() + sizeInBytes <= capacity()) {
                        |               block(_slidingWindow)
                        |               position(position() + sizeInBytes)
                        |           }
                        |           position(0)
                        |        }
                        |        inline fun java.nio.ByteBuffer.forEachIndexed(block: java.nio.ByteBuffer.(kotlin.Int, $interfaceName) -> Unit) { 
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
                    stream.write(generateStruktImplementationCode(implClassName, interfaceName, propertyDeclarations, companionDeclaration))
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
        interfaceName: String,
        propertyDeclarations: String,
        companionDeclaration: String
    ) = """
                    |class $implClassName: $interfaceName {
                    |$propertyDeclarations
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
        class Custom(val declaration: KSClassDeclaration): Type(declaration.qualifiedName!!.asString()) {
            override fun getterCallAsString(currentByteOffset: kotlin.Int): String = throw IllegalStateException("This should never get called, remove me later")
            override fun setterCallAsString(currentByteOffset: kotlin.Int): String = throw IllegalStateException("This should never get called, remove me later")

            fun getCustomInstancesDeclarations(propertyName: String, currentByteOffset: AtomicInteger): String {
                return """|    private val _${propertyName} = object: ${declaration.qualifiedName!!.asString()} {
                    |${declaration.findPropertyDeclarations().toPropertyDeclarations(currentByteOffset)}
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
                else -> if(this.isStrukt) Custom(this) else null
            }
        }
    }


    inner class FindPropertiesVisitor : KSVisitorVoid() {

        override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
            if(classDeclaration.classKind == ClassKind.INTERFACE && classDeclaration.annotations.any { it.shortName.asString() == "Strukt" }) {
                propertyDeclarationsPerClass[classDeclaration] = classDeclaration.findPropertyDeclarations()
            }
        }

        override fun visitFile(file: KSFile, data: Unit) {
            file.declarations.toList().map { it.accept(this, Unit) }
        }
    }
}
private val StruktGenerator.Type.sizeInBytes: Int
    get() {
        return when(this) {
            StruktGenerator.Type.Boolean -> 4
            is StruktGenerator.Type.Custom -> declaration.getAllProperties().sumBy { it.parse()?.sizeInBytes ?: 0 }
            StruktGenerator.Type.Float -> 4
            StruktGenerator.Type.Int -> 4
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
        }
    }

private fun KSClassDeclaration.findPropertyDeclarations(): List<KSPropertyDeclaration> = if (classKind == ClassKind.INTERFACE) {
    getDeclaredProperties().toList()
} else emptyList()

private fun List<KSPropertyDeclaration>.toPropertyDeclarations(currentByteOffset: AtomicInteger): String {

    return joinToString("\n") { declaration ->
        val type = declaration.type.resolve().declaration.parse()

        type?.let { type ->
            "\t" + type.propertyDeclarationAsString(declaration.isMutable, declaration.simpleName.asString(), currentByteOffset).apply {
                currentByteOffset.getAndAdd(type.sizeInBytes)
            }
        } ?: ""
    }
}