package org.rewedigital.konversation

class Shell(conversation: String) {
    init {
        val konversation = Konversation(conversation, Environment("bla", "de_DE", false))
        for (i in 0..10) {
            //println(konversation.create(mutableMapOf()))
        }
    }

    // Entry point to test the jvm implementation
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
                Shell("test")
        }
    }
}