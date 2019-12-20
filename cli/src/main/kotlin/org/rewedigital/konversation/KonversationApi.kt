package org.rewedigital.konversation

import org.rewedigital.konversation.generator.Exporter
import org.rewedigital.konversation.generator.Printer
import org.rewedigital.konversation.generator.alexa.AlexaExporter
import org.rewedigital.konversation.generator.alexa.AmazonApi
import org.rewedigital.konversation.generator.alexa.Status
import org.rewedigital.konversation.generator.dialogflow.DialogflowApi
import org.rewedigital.konversation.generator.dialogflow.DialogflowExporter
import org.rewedigital.konversation.generator.kson.KsonExporter
import org.rewedigital.konversation.parser.Parser
import java.io.File
import java.io.FileOutputStream

class KonversationApi(
    var amazonClientId: String? = null,
    var amazonClientSecret: String? = null,
    var dialogflowServiceAccount: File? = null) {

    val dialogflow: DialogflowApi by lazy { DialogflowApi(dialogflowServiceAccount.orThrow("Google service account not set")) }
    val alexa: AmazonApi by lazy { AmazonApi(amazonClientId.orThrow("Amazon client id not set"), amazonClientSecret.orThrow("Amazon client secret not set")) }

    private var inputFileCount = 0

    val intentDb by lazy { cache.first }
    private val entityDb by lazy { cache.second }

    private val cache: Pair<Map<String, List<Intent>>, Map<String, List<Entities>>> by lazy {
        val intents = mutableMapOf<String, MutableList<Intent>>()
        val entities = mutableMapOf<String, MutableList<Entities>>()
        inputFiles.forEach { inputFile ->
            when {
                inputFile.isFile -> {
                    inputFileCount++
                    val parser = parseFile(inputFile)
                    intents.getOrPut("") { mutableListOf() } += parser.intents
                    parser.entities?.let { it: Entities -> entities.getOrPut("") { mutableListOf() } += it }
                }
                inputFile.isDirectory -> inputFile
                    .listFiles { dir: File?, name: String? ->
                        name != null && File(dir, name).isDirectory && (name == "konversation" || name.startsWith("konversation-"))
                    }
                    .orEmpty()
                    .toList()
                    .flatMap {
                        it.listFiles { _, name ->
                            name.endsWith(".kvs") || name.endsWith(".grammar") || name.endsWith(".values")
                        }.orEmpty().toList()
                    }
                    .also {
                        inputFileCount += it.size
                    }
                    .forEach { file ->
                        val prefix = file.parentFile.absolutePath.substring(inputFile.absolutePath.length + 13).trimStart('-')
                        val parser = parseFile(file)
                        intents.getOrPut(prefix) { mutableListOf() } += parser.intents
                        parser.entities?.let { entities.getOrPut(prefix) { mutableListOf() } += it }
                    }
            }
        }
        // merge utterances (for the case that they are defined in multiple files)
        intents.forEach { (_, intents) ->
            intents.groupingBy { it.name }.eachCount().filter { it.value > 1 }.forEach { (intent, _) ->
                val all = intents.filter { it.name == intent }
                all.first().let { master ->
                    all.drop(1).forEach {
                        master.utterances += it.utterances
                        intents.remove(it)
                    }
                }
            }
        }
        Pair(intents, entities)
    }

    // shared fields
    var invocationName: String? = null // TODO should be a map for localization
    val inputFiles = mutableListOf<File>()
    var logger: LoggerFacade? = null

    fun exportPlain(targetDirectory: File) = intentDb[""]?.let { intents ->
        intents.forEach { intent ->
            if (intent.utterances.isEmpty()) {
                logger?.info("Skipping empty intent ${intent.name}...")
            } else {
                logger?.log("Dumping ${intent.name}...")
                val stream = File(targetDirectory, "${intent.name}.txt").outputStream()
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

    fun exportAlexaSchema(targetDirectory: File, prettyPrint: Boolean = false) = intentDb.forEach { (config, intents) ->
        val exporter = AlexaExporter(requireNotNull(invocationName) { "invocation name was null" })
        val stream = targetDirectory.outputStream()
        exportToStream(stream, prettyPrint, exporter, intents, config)
    }

    private fun exportToStream(stream: FileOutputStream, prettyPrint: Boolean, exporter: Exporter, intents: List<Intent>, config: String) {
        val printer: Printer = { line ->
            stream.write(line.toByteArray())
        }
        if (prettyPrint) {
            exporter.prettyPrinted(printer, intents, entityDb[config])
        } else {
            exporter.minified(printer, intents, entityDb[config])
        }
        stream.close()
    }

    fun exportDialogflow(targetDirectory: File, prettyPrint: Boolean = false) = intentDb.forEach { (config, intents) ->
        val invocationName = requireNotNull(this.invocationName) { "invocation name was null" }
        val exporter = DialogflowExporter(invocationName)
        val stream = File(targetDirectory, "${invocationName.replace(' ', '-').toLowerCase()}.zip").outputStream()
        if (prettyPrint) {
            exporter.prettyPrinted(stream, intents, entityDb[config])
        } else {
            exporter.minified(stream, intents, entityDb[config])
        }
        stream.close()
    }

    fun exportKson(targetDirectory: File, prettyPrint: Boolean = false) = intentDb.forEach { (config, intents) ->
        val targetDir = File(targetDirectory.absolutePath + File.separator + "konversation".join("-", config))
        intents.forEach { intent ->
            val exporter = KsonExporter(intent.name)
            targetDir.mkdirs()
            val stream = File(targetDir, "${intent.name}.kson").outputStream()
            exportToStream(stream, prettyPrint, exporter, intents, config)
        }
    }

    fun exportEnum(targetDirectory: File, namespace: String) {
        val enum = StringBuilder()
            .appendln("package $namespace")
            .appendln()
            .appendln("import org.rewedigital.dialog.utils.KonversationEnum")
            .appendln()
            .appendln("enum class Konversations(override val alias: String? = null): KonversationEnum {")

        fun String.cleanup() = "([A-Z]*[a-z0-9]*)[.-]?".toRegex()
            .findAll(this)
            .joinToString(separator = "") {
                it.groupValues[1].toLowerCase().capitalize()
            }
        intentDb[""]?.filter { it.prompt.isNotEmpty() }?.forEachIterator { intent ->
            val cleanName = intent.name.cleanup()
            enum.append("    $cleanName")
            if (cleanName != intent.name) {
                enum.append("(\"${intent.name}\")")
            }
            enum.appendln(if (hasNext()) "," else "")
        }
        enum.append("}")
        File(targetDirectory, "Konversations.kt").outputStream().use { it.write(enum.toString().toByteArray()) }
    }

    fun authorizeAlexa(serverPort: Int) =
        alexa.login(serverPort)

    fun updateAlexaSchema(invocationName: String, skillId: String): String? =
        intentDb[""]?.let { intents ->
            alexa.uploadSchema(invocationName, "de-DE", intents, entityDb[""], skillId)?.let { location ->
                var msg: String? = null
                for (i in 0..600) {
                    Thread.sleep(1000)
                    val (status, message) = alexa.checkStatus(location, "de-DE")
                    if (status != Status.IN_PROGRESS) return message
                    if (msg != message) {
                        println("$message...")
                        msg = message
                    }
                }
                return null
            }
        }

    fun updateDialogflowProject(project: String, invocationName: String) {
        intentDb[""]?.let { intents ->
            dialogflow.uploadIntents(invocationName, project, intents, entityDb[""])
        }
    }

    private fun parseFile(file: File) = Parser(file)
    private fun <T> T?.orThrow(msg: String): T = this ?: throw java.lang.IllegalArgumentException(msg)
}