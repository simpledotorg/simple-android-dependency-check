package org.simple.sadc

import kotlinx.ast.common.AstSource
import kotlinx.ast.common.ast.Ast
import kotlinx.ast.common.ast.DefaultAstNode
import kotlinx.ast.common.klass.KlassDeclaration
import kotlinx.ast.common.klass.KlassIdentifier
import kotlinx.ast.grammar.kotlin.common.summary
import kotlinx.ast.grammar.kotlin.target.antlr.kotlin.KotlinGrammarAntlrKotlinParser
import java.io.File

fun main(args: Array<String>) {
    val sourceFolder = File(args[0], "app/src/main")
    val records = sourceFolder
        .walkTopDown()
        .filter { it.isFile && it.name.endsWith("Controller.kt") }
        .map { it.nameWithoutExtension to AstSource.String(it.readText()) }
        .map { (name, source) -> name to KotlinGrammarAntlrKotlinParser.parseKotlinFile(source) }
        .map { (name, source) -> name to source.summary().get() }
        .filter { (name, astList) -> astList.klassDeclaration(name).isActualController() }
        .map { (name, astList) -> Record.from(name, astList) }
        .onEach { println("Processed ${it.name}...") }
        .toList()

    val classesComplexityCsv = records
        .sortedByDescending(Record::complexity)
        .joinToString(
            separator = "\n",
            prefix = "Name,Dependencies,Rx Streams,Overall complexity\n",
            transform = Record::toCsvRow
        )

    val overallComplexity = records.sumBy { it.complexity } / records.size.toFloat()

    val overallComplexityCsv =
        "$classesComplexityCsv\n\nOverall Complexity (Sum of complexity / Number of classes)\n$overallComplexity"

    val file = File("results.csv").apply { writeText(overallComplexityCsv) }

    println("Wrote results to ${file.absolutePath}")
}

private val File.nameWithoutExtension
    get() = this.name.split('.').first()

private fun KlassDeclaration.isActualController(): Boolean {
    return inheritance.find { it.children.any(Ast::isRxTransformer) } != null
}

private fun KlassDeclaration.findNumberOfRxStreams(): Int {
    val node = this.expressions.find { it.description == "classBody" } as DefaultAstNode?
    return node
        ?.children
        ?.filterIsInstance(KlassDeclaration::class.java)
        ?.count { it.type?.rawName in setOf("Observable<UiChange>", "ObservableSource<UiChange>") && it.identifier?.rawName != "apply" }
        ?: 0
}

private fun KlassDeclaration.findNumberOfDependencies(): Int {
    return children
        .filter { it is KlassDeclaration && it.keyword == "val" }
        .count()
}

private fun Ast.isRxTransformer(): Boolean {
    return this is KlassIdentifier && rawName.equals(
        "ObservableTransformer<UiEvent, UiChange>",
        ignoreCase = true
    )
}

private fun KlassDeclaration.konstructorDeclaration(): KlassDeclaration {
    return children.find { it.description == "KlassDeclaration(constructor)" } as KlassDeclaration
}

private fun Iterable<Ast>.klassDeclaration(name: String): KlassDeclaration {
    val classDescriptor = "KlassDeclaration(class $name)"

    return find { it is KlassDeclaration && it.description == classDescriptor } as KlassDeclaration
}

private data class Record(
    val name: String,
    val numberOfDependencies: Int,
    val numberOfRxStreams: Int
) {
    companion object {
        fun from(name: String, astList: List<Ast>): Record {
            val klass = astList.klassDeclaration(name)
            val konstructor = klass.konstructorDeclaration()

            return Record(
                name = name,
                numberOfDependencies = konstructor.findNumberOfDependencies(),
                numberOfRxStreams = klass.findNumberOfRxStreams()
            )
        }
    }

    val complexity: Int
        get() = numberOfRxStreams.coerceAtLeast(1) * numberOfDependencies.coerceAtLeast(1)

    fun toCsvRow(): String = "$name,$numberOfDependencies,$numberOfRxStreams,$complexity"
}
