package com.rewedigital.voice.konversation

import com.rewedigital.voice.konversation.generator.Printer
import com.rewedigital.voice.konversation.generator.alexa.AlexaExporter
import com.rewedigital.voice.konversation.generator.kson.KsonExporter
import com.rewedigital.voice.konversation.parser.Parser
import java.io.File
import java.util.*
import java.util.function.Consumer
import java.util.stream.Stream
import kotlin.system.exitProcess

open class Cli {
    private lateinit var intents: MutableList<Intent>
    private var cacheEverything = true // should be not the default value
    private var countPermutations = false
    private var stats = false
    private var outputFile: String? = "result.json"
    private var limit: Long? = null
    private var prettyPrint = false
    private var ksonDir: String? = null
    private var dumpOnly = false
    private var invocationName: String? = null
    private var exportAlexa: Boolean = false

    fun parseArgs(args: Array<String>) {
        var input: String? = null
        if (args.isEmpty()) {
            println("Missing arguments! Please specify at least the kvs or grammar file you want to process.")
            println()
            help()
            exit(-1)
        } else {
            var argNo = 0
            while (argNo < args.size) {
                val arg = args[argNo]
                if (File(arg).exists()) {
                    input = arg
                } else {
                    when (arg.toLowerCase()) {
                        "help",
                        "-help",
                        "-h",
                        "/?" -> {
                            help()
                            return
                        }
                        "count",
                        "-count" -> countPermutations = true
                        "cache",
                        "-cache" -> cacheEverything = true
                        "--export-alexa" -> if (++argNo < args.size) {
                            exportAlexa = true
                            outputFile = args[argNo]
                        } else {
                            println("Target is missing")
                            exit(-1)
                        }
                        "invocation",
                        "-invocation" -> if (++argNo < args.size) {
                            invocationName = args[argNo]
                        }
                        "--export-kson" -> if (++argNo < args.size) {
                            ksonDir = args[argNo]
                        } else {
                            println("Target directory is missing")
                            exit(-1)
                        }
                        "stats",
                        "-stats" -> stats = true
                        "limit",
                        "-limit",
                        "top",
                        "-top" -> if (++argNo < args.size) {
                            try {
                                limit = args[argNo].toLong()
                            } catch (e: Throwable) {
                                println("\"${args[argNo]}\" is no valid count of utterances.")
                                exit(-1)
                            }
                        } else {
                            println("Count is missing!")
                            exit(-1)
                        }
                        "prettyprint",
                        "-prettyprint" -> prettyPrint = true
                        "dump",
                        "-dump" -> dumpOnly = true
                        //"compile",
                        //"-compile" -> ksonExport = true
                        else -> println("Unknown argument \"$arg\".")
                    }
                }
                argNo++
            }

            val inputFile = File(input.orEmpty())
            if (inputFile.isFile) {
                input?.let {
                    parseFiles(it, inputFile.parentFile)
                }
            } else if (inputFile.isDirectory) {
                inputFile.listFiles { dir: File?, name: String? ->
                    File(dir, name).isDirectory && (name == "konversation" || name?.startsWith("konversation-") == true)
                }.toList()
                    .flatMap { it.listFiles { _, name -> name.endsWith(".kvs") }.toList() }
                    .forEach {
                        val target = File(ksonDir + it.absolutePath.substring(inputFile.absolutePath.length))
                        parseFiles(it.path, target.parentFile)
                    }
                //.map { it.absolutePath.substring(inputFile.absolutePath.length) to it }
                //.map(::println)
            } else {
                println("Input file not found!")
                exit(-1)
            }
        }
    }

    open fun parseFiles(input: String, targetDir: File) {
        intents = Parser(input).intents
        println("Parsing finished. Found ${intents.size} intents.")

        if (countPermutations) {
            fun Long.formatted() = String.format(Locale.getDefault(), "%,d", this)

            var total = 0L
            intents.forEach { intent: Intent ->
                var count = 0L
                intent.utterances.forEach { it -> count += it.permutationCount }
                if (stats) println("${intent.name} has ${intent.utterances.size} utterances which have in total ${count.formatted()} permutations")
                total += count
            }
            if (stats) println("That are in total ${total.formatted()} permutations!")
        }

        val all = intents.sumBy { intent ->
            val permutations = intent.utterances.sumBy { utterance -> utterance.permutations.size }
            if (stats) println("${intent.name} has now $permutations sample utterances")
            permutations
        }
        if (stats) println("Generated in total $all Utterances")
        //println("- " + all.sorted().joinToString(separator = "\n- "))

        if (dumpOnly) {
            outputFile = null
            intents.forEach { intent ->
                if (intent.utterances.isEmpty()) {
                    println("Skipping empty intent ${intent.name}...")
                } else {
                    println("Dumping ${intent.name}...")
                    val stream = File("${intent.name}.txt").outputStream()
                    intent.utterances.forEach { utterance ->
                        utterance.permutations.forEach { permutation ->
                            stream.write(permutation.toByteArray())
                            stream.write(13)
                            stream.write(10)
                        }
                    }
                    stream.close()
                }
            }
        }

        // FIXME or remove me
        if (!input.endsWith(".grammar") && dumpOnly) {
            intents.forEach { intent ->
                println("Response of ${intent.name}")
                //intent.prompt.create().runIfNotNullOrEmpty(::println)
                println("---")
            }
        }

        //println(intents[1].prompt.create())

        ksonDir?.let {
            intents.forEach { intent ->
                val exporter = KsonExporter(intent.name)
                targetDir.mkdirs()
                val stream = File(targetDir,"${intent.name}.kson").outputStream()
                val printer: Printer = { line ->
                    stream.write(line.toByteArray())
                }
                if (prettyPrint) {
                    exporter.prettyPrinted(printer, intents)
                } else {
                    exporter.minified(printer, intents)
                }
            }
        }

        if (exportAlexa) {
            outputFile?.let {
                invocationName?.let { skillName ->
                    val exporter = AlexaExporter(skillName, targetDir, limit ?: Long.MAX_VALUE)
                    val stream = File(outputFile).outputStream()
                    val printer: Printer = { line ->
                        stream.write(line.toByteArray())
                    }
                    if (prettyPrint) {
                        exporter.prettyPrinted(printer, intents)
                    } else {
                        exporter.minified(printer, intents)
                    }
                } ?: run {
                    println("Invocation name is missing! Please specify the invocation name with the parameter -invocation <name>.")
                    exit(-1)
                }
            }
        }
    }

    private fun help() {
        println("Arguments for konversation:")
        println("[-help]                     Print this help")
        println("[-count]                    Count the permutations and print this to the console")
        println("[-stats]                    Print out some statistics while generation")
        println("[-cache]                    Cache everything even if an utterance has just a single permutation")
        println("[--export-alexa <OUTFILE>]  Write the resulting json to OUTFILE instead of result.json")
        println("[-invocation <NAME>]        Define the invocation name for the Alexa export")
        println("[-limit <COUNT>]            While pretty printing the json to the output file limit the utterances count per intent")
        println("[--export-kson <OUTDIR>]    Compiles the kvs file to kson resource files which are required for the runtime")
        println("[-dump]                     Dump out all intents to its own txt file")
        println("[-prettyprint]              Generate a well formatted json for easier debugging")
        println("<FILE>                      The grammar or kvs file to parse")
        println()
    }

    open fun exit(status: Int) {
        exitProcess(status)
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            Cli().parseArgs(args)
        }
    }
}

fun <T> Iterable<T>.forEachIterator(block: Iterator<T>.(element: T) -> Unit) {
    val iterator = iterator()
    while (iterator.hasNext()) block(iterator, iterator.next())
}

fun <T> Stream<T>.forEachIterator(block: Iterator<T>.(element: T) -> Unit) {
    val iterator = iterator()
    while (iterator.hasNext()) block(iterator, iterator.next())
}

fun <T> Iterable<T>.forEachBreakable(block: BreakableIterator<T>.(element: T) -> Unit) {
    val iterator = BreakableIterator(iterator())
    while (iterator.hasNext()) block(iterator, iterator.next())
}

class BreakableIterator<T>(private val inner: Iterator<T>) : Iterator<T> {
    private var resume = true

    override fun forEachRemaining(action: Consumer<in T>) =
        inner.forEachRemaining(action)

    override fun hasNext() = resume && inner.hasNext()

    override fun next() = inner.next()

    fun stop() {
        resume = false
    }
}

fun String?.runIfNotNullOrEmpty(callback: (String) -> Unit) = this?.let { if (this.isNotBlank()) callback.invoke(this) }