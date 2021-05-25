package de.hanno.strukt.generate

import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import de.hanno.strukt.generate.StruktGenerator.Type.Companion.parse
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

    val propertyDeclarations = mutableMapOf<KSClassDeclaration, List<KSPropertyDeclaration>>()
    val visitor = FindPropertiesVisitor()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        resolver.getAllFiles().toList().map {
            it.accept(visitor, Unit)
        }

        propertyDeclarations.entries.forEach { (classDeclaration, propertyDeclarationsX) ->

            val interfaceName = classDeclaration.simpleName.asString()
            val implClassName = interfaceName + "Impl"
            val nonEmptyPackageName = nonEmptyPackageName(classDeclaration)

            val propertyDeclarations = propertyDeclarationsX.toPropertyDeclarations(AtomicInteger())

            codeGenerator.createNewFile(Dependencies.ALL_FILES, nonEmptyPackageName, implClassName, "kt").use { stream ->
                stream.write(generateStruktImplementationCode(implClassName, interfaceName, propertyDeclarations))
            }
        }

        return emptyList()
    }

    private fun generateStruktImplementationCode(
        implClassName: String,
        interfaceName: String,
        propertyDeclarations: String
    ) = """
                    |class $implClassName: $interfaceName {
                    |$propertyDeclarations
                    |}
                """.trimMargin().toByteArray(Charsets.UTF_8)

    sealed class Type(val fqName: String) {
        abstract fun getterCallAsString(currentByteOffset: kotlin.Int): String
        open fun getterDeclarationAsString(propertyName: String, currentByteOffset: AtomicInteger): String {
            return """override val java.nio.ByteBuffer.$propertyName: $fqName get() { return ${getterCallAsString(currentByteOffset.get())} }"""
        }

        object Boolean: Type(kotlin.Boolean::class.qualifiedName!!) {
            override fun getterCallAsString(currentByteOffset: kotlin.Int): String = "getInt($currentByteOffset) == 0"
        }

        object Int: Type(kotlin.Int::class.qualifiedName!!) {
            override fun getterCallAsString(currentByteOffset: kotlin.Int): String = "getInt($currentByteOffset)"
        }
        object Float: Type(kotlin.Float::class.qualifiedName!!) {
            override fun getterCallAsString(currentByteOffset: kotlin.Int): String = "getFloat($currentByteOffset)"
        }
        class Custom(val declaration: KSClassDeclaration): Type(declaration.qualifiedName!!.asString()) {
            override fun getterCallAsString(currentByteOffset: kotlin.Int): String = throw IllegalStateException("This should never get called, remove me later")

            fun getCustomInstancesDeclarations(propertyName: String, currentByteOffset: AtomicInteger): String {
                return """|    private val _${propertyName} = object: ${declaration.qualifiedName!!.asString()} {
                    |${declaration.findPropertyDeclarations().toPropertyDeclarations(currentByteOffset)}
                    |    }"""
            }

            override fun getterDeclarationAsString(propertyName: String, currentByteOffset: AtomicInteger): String {
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

            fun KSClassDeclaration.parse(): Type? {
                return when(qualifiedName!!.asString()) {
                    kotlin.Boolean::class.qualifiedName!!.toString() -> Boolean
                    kotlin.Int::class.qualifiedName!!.toString() -> Int
                    kotlin.Float::class.qualifiedName!!.toString() -> Float
                    else -> if(this.isStrukt) Custom(this) else null
                }
            }
        }
    }


    inner class FindPropertiesVisitor : KSVisitorVoid() {

        override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
            if(classDeclaration.classKind == ClassKind.INTERFACE && classDeclaration.annotations.any { it.shortName.asString() == "Strukt" }) {
                propertyDeclarations[classDeclaration] = classDeclaration.findPropertyDeclarations()
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
            "\t" + type.getterDeclarationAsString(declaration.simpleName.asString(), currentByteOffset).apply {
                currentByteOffset.getAndAdd(type.sizeInBytes)
            }
        } ?: ""
    }
}