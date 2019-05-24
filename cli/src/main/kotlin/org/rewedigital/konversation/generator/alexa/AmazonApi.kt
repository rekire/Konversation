package org.rewedigital.konversation.generator.alexa

import java.awt.Desktop
import java.net.ServerSocket
import java.net.URI
import java.util.*

class AmazonApi(private var refreshToken: String? = null) {
    private val clientId = ""
    private val clientSecret = ""
    private var accessToken: String? = null
        get() = field ?: refreshToken?.let {
            khttp.post(
                url = "https://api.amazon.com/auth/o2/token",
                json = mapOf("client_id" to clientId,
                    "client_secret" to clientSecret,
                    "refresh_token" to refreshToken,
                    "grant_type" to "refresh_token"))
                .jsonObject
                .getString("access_token")
        }

    fun login() {
        val random = UUID.randomUUID().toString()
        val loginUrl = "https://www.amazon.com/ap/oa/?client_id=$clientId&scope=profile+alexa::ask:models:readwrite&response_type=code&redirect_uri=http:%2F%2Flocalhost:21337%2F&state=$random"

        // if possible open the browser with the login page
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(loginUrl));
        } else {
            println("Please open a browser and visit this url: $loginUrl")
        }

        // Open a very minimalistic webserver on port 21337 for the login result
        val server = ServerSocket(21337)
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
        writer.write("$httpVersion 200 Ok\nContent-Type: text/html\n\n".toByteArray())
        if (success) {
            writer.write("<h1>Login successful</h1><p>Please continue in your shell</p>".toByteArray())
        } else {
            writer.write("<h1>Login failed</h1>".toByteArray())
        }
        writer.flush()
        client.close()
        server.close()

        if (success) {
            // Fetch the token from Amazon
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
        } else {
            accessToken = null
            refreshToken = null
        }
    }
}