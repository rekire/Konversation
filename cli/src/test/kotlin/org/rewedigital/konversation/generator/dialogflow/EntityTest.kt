package org.rewedigital.konversation.generator.dialogflow

import org.junit.Test
import org.rewedigital.konversation.assertEqualsIgnoringLineBreaks

class EntityTest {
    @Test
    fun exportZeroSynonyms() {
        val sut = Entity(null, "master", listOf())
        val sb = StringBuilder()
        sut.prettyPrinted { str -> sb.append(str) }
        assertEqualsIgnoringLineBreaks(zeroSynonyms.prettyPrinted, sb.toString())
        sb.clear()
        sut.minified { str -> sb.append(str) }
        assertEqualsIgnoringLineBreaks(zeroSynonyms.minified, sb.toString())
    }

    @Test
    fun exportOneSynonym() {
        val sut = Entity(null, "master", listOf("synonym1"))
        val sb = StringBuilder()
        sut.prettyPrinted { str -> sb.append(str) }
        assertEqualsIgnoringLineBreaks(oneSynonym.prettyPrinted, sb.toString())
        sb.clear()
        sut.minified { str -> sb.append(str) }
        assertEqualsIgnoringLineBreaks(oneSynonym.minified, sb.toString())
    }

    @Test
    fun exportTwoSynonyms() {
        val sut = Entity(null, "master", listOf("synonym1", "synonym2"))
        val sb = StringBuilder()
        sut.prettyPrinted { str -> sb.append(str) }
        assertEqualsIgnoringLineBreaks(twoSynonyms.prettyPrinted, sb.toString())
        sb.clear()
        sut.minified { str -> sb.append(str) }
        assertEqualsIgnoringLineBreaks(twoSynonyms.minified, sb.toString())
    }

    data class Result(val minified: String, val prettyPrinted: String)

    companion object {
        val zeroSynonyms = Result("{\"value\":\"master\",\"synonyms\":[\"master\"]}", """  {
    "value": "master",
    "synonyms": [
      "master"
    ]
  }""")
        val oneSynonym = Result("{\"value\":\"master\",\"synonyms\":[\"master\",\"synonym1\"]}", """  {
    "value": "master",
    "synonyms": [
      "master",
      "synonym1"
    ]
  }""")
        val twoSynonyms = Result("{\"value\":\"master\",\"synonyms\":[\"master\",\"synonym1\",\"synonym2\"]}", """  {
    "value": "master",
    "synonyms": [
      "master",
      "synonym1",
      "synonym2"
    ]
  }""")
    }
}