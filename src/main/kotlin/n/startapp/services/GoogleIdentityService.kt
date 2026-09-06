package n.startapp.services

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import n.startapp.exceptions.BadRequestException
import n.startapp.exceptions.UnauthorizedException
import n.startapp.utils.EnvConfig

/** Who Google says the bearer of an id token is. */
data class GoogleIdentity(val email: String, val googleId: String)

/**
 * The one place an id token from Google is turned into an identity.
 *
 * Sign-in was not the only thing that needed this: an account created through Google carries no
 * password, so every operation that re-asks "is this really you" — deleting the account, changing
 * the address it is reached at — has to accept a fresh Google token instead. Two copies of these
 * checks would be two places to forget `email_verified` in.
 */
object GoogleIdentityService {

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json() }
    }

    suspend fun verify(idToken: String): GoogleIdentity {
        if (idToken.isBlank()) throw BadRequestException("idToken is required")

        val response = try {
            httpClient.get("https://oauth2.googleapis.com/tokeninfo?id_token=$idToken")
        } catch (e: Exception) {
            throw BadRequestException("Failed to verify Google token")
        }
        if (response.status.value != 200) throw UnauthorizedException("Invalid Google token")

        val json = Json.parseToJsonElement(response.body<String>()).jsonObject
        val email = json["email"]?.jsonPrimitive?.content
            ?: throw BadRequestException("Email not found in Google token")
        val googleId = json["sub"]?.jsonPrimitive?.content
            ?: throw BadRequestException("Subject not found in Google token")

        val clientId = EnvConfig.googleClientId
        if (clientId.isNotBlank()) {
            val aud = json["aud"]?.jsonPrimitive?.content
            if (aud != clientId) throw UnauthorizedException("Token audience mismatch")
        }

        // Совпадение адреса — это то, что склеивает вход по паролю и вход через Google в один
        // аккаунт, поэтому неподтверждённый адрес принимать нельзя: тогда чужой Google-аккаунт,
        // заведённый на наш email, забрал бы себе учётку с паролем.
        // tokeninfo отдаёт поле строкой, а не булевым.
        if (json["email_verified"]?.jsonPrimitive?.content != "true") {
            throw UnauthorizedException("Google account email is not verified")
        }

        return GoogleIdentity(email = email, googleId = googleId)
    }
}
