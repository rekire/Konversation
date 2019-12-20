package org.rewedigital.konversation.generator.alexa

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.features.json.GsonSerializer
import io.ktor.client.features.json.JsonFeature
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.takeFrom
import kotlinx.coroutines.runBlocking
import org.rewedigital.konversation.Entities
import org.rewedigital.konversation.Intent
import org.rewedigital.konversation.generator.alexa.models.Skills
import org.rewedigital.konversation.generator.alexa.models.Vendors
import java.awt.Desktop
import java.net.ServerSocket
import java.net.URI
import java.util.*

class AmazonApi(val clientId: String, val clientSecret: String, var refreshToken: String? = null) {
    suspend inline fun <reified T> HttpClient.getOrNull(
        urlString: String,
        block: HttpRequestBuilder.() -> Unit = {}
    ): T? = try {
        get {
            url.takeFrom(urlString)
            block()
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }

    val isLoggedIn: Boolean
        get() = accessToken != null

    private val httpClient by lazy {
        HttpClient(OkHttp) {
            install(JsonFeature) {
                serializer = GsonSerializer()
                acceptContentTypes += ContentType("application", "json+hal")
            }
        }
    }

    var accessToken: String? = null
        get() = field ?: refreshToken?.let {
            khttp.post(
                url = "https://api.amazon.com/auth/o2/token",
                json = mapOf("client_id" to clientId,
                    "client_secret" to clientSecret,
                    "refresh_token" to refreshToken,
                    "grant_type" to "refresh_token"))
                .jsonObject
                .getString("access_token").also {
                    field = it
                }
        }

    fun login(serverPort: Int): Boolean {
        val random = UUID.randomUUID().toString()
        val loginUrl = "https://www.amazon.com/ap/oa/?client_id=$clientId&scope=alexa::ask:skills:readwrite+alexa::ask:models:readwrite&response_type=code&redirect_uri=http:%2F%2Flocalhost:21337%2F&state=$random"

        // if possible open the browser with the login page
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(loginUrl))
            println("Your browser should open now and view this url: $loginUrl")
        } else {
            println("Please open a browser and visit this url: $loginUrl")
        }

        // Open a very minimalistic webserver on port $serverPort for the login result
        val server = ServerSocket(serverPort)
        val client = server.accept()
        val reader = Scanner(client.getInputStream())
        val writer = client.getOutputStream()
        val req = reader.nextLine()

        // Read the interesting fields like the http request version, the code and the state
        val httpVersion = req.substringAfterLast(' ')
        val code = req.substringAfter("code=").substringBefore('&').substringBefore(' ')
        val state = req.substringAfter("state=").substringBefore('&').substringBefore(' ')
        val success = state == random && code.isNotEmpty()

        // Create a response and shutdown the server

        if (success) {
            // Fetch the token from Amazon
            try {
                val response = khttp.post(
                    url = "https://api.amazon.com/auth/o2/token",
                    json = mapOf("client_id" to clientId,
                        "client_secret" to clientSecret,
                        "code" to code,
                        "grant_type" to "authorization_code",
                        "redirect_uri" to "http://localhost:21337/"))
                    .jsonObject
                accessToken = response.getString("access_token")
                refreshToken = response.getString("refresh_token")
                writer.write("$httpVersion 200 Ok\nContent-Type: text/html\n\n".toByteArray())
                writer.write("<h1>Login successful</h1><p>Please continue in your shell</p>".toByteArray())
            } catch (e: Exception) {
                writer.write("$httpVersion 403 Forbidden\nContent-Type: text/html\n\n".toByteArray())
                writer.write("<h1>Login failed</h1><p>${e.javaClass.simpleName}: ${e.message}</p>".toByteArray())
                throw e
            } finally {
                writer.flush()
                client.close()
                server.close()
            }
        } else {
            writer.write("$httpVersion 403 Forbidden\nContent-Type: text/html\n\n".toByteArray())
            writer.write("<h1>Login failed</h1><p>Invalid state or wrong code</p>".toByteArray())
            writer.flush()
            client.close()
            server.close()
            accessToken = null
            refreshToken = null
        }
        return success
    }

    fun fetchVendors() = runBlocking {
        httpClient.getOrNull<Vendors>("https://api.amazonalexa.com/v1/vendors") {
            headers.append("Authorization", "Bearer $accessToken")
        }?.vendors
    }

    fun fetchSkills(vendor: String) = runBlocking {
        httpClient.getOrNull<Skills>("https://api.amazonalexa.com/v1/skills?vendorId=$vendor") {
            headers.append("Authorization", "Bearer $accessToken")
        }?.skills
    }

    fun loadToken(refreshToken: String) {
        this.refreshToken = refreshToken
    }

    fun uploadSchema(invocationName: String, locale: String, intents: List<Intent>, entities: List<Entities>?, skillId: String): String? {
        require(isLoggedIn) { "Failed to fetch token." + if (refreshToken == null) " No refresh token set!" else "" }
        val json = StringBuilder()
        AlexaExporter(invocationName, Int.MAX_VALUE).minified({ json.append(it) }, intents, entities)
        val response = khttp.put(
            url = "https://api.amazonalexa.com/v1/skills/$skillId/stages/development/interactionModel/locales/$locale",
            headers = mapOf("Authorization" to "Bearer $accessToken"),
            data = json.toString())
        return if (response.statusCode != 202) {
            println("Error ${response.statusCode} while updating the intent schema: " + String(response.content))
            null
        } else {
            //println("Used Authorization: Bearer $accessToken\nLocation: ${response.headers["Location"]}\n${response.text}")
            response.headers["Location"]
        }
    }

    fun checkStatus(location: String, locale: String): Pair<Status, String> =
        khttp.get(
            url = "https://api.amazonalexa.com$location",
            headers = mapOf("Authorization" to "Bearer $accessToken"))
            .jsonObject
            .getJSONObject("interactionModel")
            .getJSONObject(locale)
            .getJSONObject("lastUpdateRequest").let { details ->
                val status = Status.valueOf(details.getString("status"))
                if (status == Status.SUCCEEDED) return status to "Done"
                if (!details.has("buildDetails")) return status to "Processing"
                val steps = details.getJSONObject("buildDetails").getJSONArray("steps")
                for (i in 0 until steps.length()) {
                    val step = steps.getJSONObject(i)
                    val stepStatus = Status.valueOf(step.getString("status"))
                    val name = when (step.getString("name")) {
                        "LANGUAGE_MODEL_QUICK_BUILD" -> "Building quick model"
                        "LANGUAGE_MODEL_FULL_BUILD" -> "Training model"
                        else -> step.getString("name")
                    }
                    if (stepStatus == Status.IN_PROGRESS) {
                        return stepStatus to name
                    }
                }
                return status to "Finishing"
            }
}