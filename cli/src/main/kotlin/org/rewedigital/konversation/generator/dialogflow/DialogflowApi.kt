package org.rewedigital.konversation.generator.dialogflow

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import org.rewedigital.konversation.Entities
import org.rewedigital.konversation.Intent
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.*

class DialogflowApi(private val credentials: File) {
    val accessToken: String
        get() {
            val credential = GoogleCredential.fromStream(FileInputStream(credentials))
                .createScoped(listOf("https://www.googleapis.com/auth/dialogflow"))
            if (credential.refreshToken())
                return credential.accessToken
            throw IllegalStateException("Something went wrong while fetching the access token")
        }

    fun uploadIntents(invocationName: String, project: String, intents: List<Intent>, entities: List<Entities>?): Boolean {
        val output = ByteArrayOutputStream(1024 * 1024 * 5) // 5MB
        DialogflowExporter(invocationName).minified(output, intents, entities)
        val zipFile = String(Base64.getEncoder().encode(output.toByteArray()))
        val response = khttp.post(
            url = "https://dialogflow.googleapis.com/v2/projects/$project/agent:import",
            headers = mapOf("Authorization" to "Bearer $accessToken"),
            data = """{"agentContent":"$zipFile"}""")
        if (response.statusCode != 200) {
            println("Error while updating the intent schema: " + String(response.content))
        }
        println("Done")
        return response.statusCode == 200
    }
}