package eu.rekisoft.voice.konversation.parts

import eu.rekisoft.voice.konversation.Part
import eu.rekisoft.voice.konversation.SwapingHashedList
import java.io.File
import java.text.ParseException
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

class Utterance(val line: String, val name: String) : Part {
    private var cache: SwapingHashedList? = null

    val permutations: SwapingHashedList
        get() = cache ?: generatePermutations()

    val slotTypes = mutableListOf<String>()

    val permutationCount: Long
        get() {
            var total: Long = 1
            val slots: List<String> = validate().flatMap {
                it.split("|").also {
                    total *= it.size
                }
            }
            slotTypes.addAll(slots.filter { it.startsWith('{') && it.endsWith('}') }.map { it.substring(1, it.length - 2) })
            return total
        }

    private fun validate(): MutableList<String> {
        // Parse the line to make sure that there is no syntax error. Regex would not work for cases like {{Foo}|{Bar}}
        var start = 0
        var counter = 0
        val slots = mutableListOf<String>()
        var lastWasMasked = false
        line.forEachIndexed { i, c ->
            when (c) {
                '\\' -> lastWasMasked = true
                '{' -> {
                    if (!lastWasMasked) {
                        when (counter) {
                            0 -> start = i + 1
                            1 -> {
                                // we found a slot type, that is fine
                            }
                            else -> throw ParseException("This line has a syntax error: $line", i)
                        }
                        counter++
                    }
                    lastWasMasked = false
                }
                '}' -> {
                    if (!lastWasMasked) {
                        when (counter) {
                            1 -> {
                                // we found the end of the slot
                                slots.add(line.substring(start, i))
                            }
                            2 -> {
                                // we found the end of a slot type, that is fine
                            }
                            else -> throw ParseException("This line has a syntax error: $line", i)
                        }
                        counter--
                    }
                    lastWasMasked = false
                }
                else -> lastWasMasked = false
            }
        }
        if (counter != 0) throw ParseException("This line has a syntax error: $line", line.length)

        return slots
    }

    private fun generatePermutations(): SwapingHashedList {
        val slots = validate()

        var total: Long = 1
        slots.map {
            it.split("|").also {
                total *= it.size
            }
        }
        println("Generating about $total permutation for ${slots.size} slots") // (${stats.joinToString()})

        // we know now all slots, let's fill them up with content
        File("cache").run {
            if (!exists()) mkdirs()
        }
        val cacheFile = "cache/$name-${String.format("%08x", line.hashCode())}"
        val storage = cache ?: SwapingHashedList(cacheFile).also { cache = it }
        if (!storage.isCached()) {
            //runBlocking {
            insertPermutations(line, slots, 0, storage)
            //}
            storage.flush()
        }
        return storage
    }

    private /*suspend*/ fun insertPermutations(line: String, slots: MutableList<String>, offset: Int, storage: SwapingHashedList) {
        if (slots.size == offset) {
            val count = counter.incrementAndGet()
            if ((count % 100000) == 0) println(count)

            storage.add(line.trim().replace(" +".toRegex(), " "))
            return
        }
        val placeholder = UUID.randomUUID().toString() // could be replaced by something faster
        val replacement = line.replaceFirst(slots[offset], placeholder)
        slots[offset].let {
            it.substring(1, it.length - 1)
        }.split("|").map {
            insertPermutations(replacement.replace(placeholder, it), slots, offset + 1, storage)
        }
    }

    companion object {
        val counter = AtomicInteger(0)
    }
}