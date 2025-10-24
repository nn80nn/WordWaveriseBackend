package n.startapp

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import n.startapp.utils.EnvConfig

fun Application.configureAuthentication() {
    install(Authentication) {
        jwt("auth-jwt") {
            realm = EnvConfig.jwtRealm

            verifier(
                JWT
                    .require(Algorithm.HMAC256(EnvConfig.jwtSecret))
                    .withAudience(EnvConfig.jwtAudience)
                    .withIssuer(EnvConfig.jwtIssuer)
                    .build()
            )

            validate { credential ->
                if (credential.payload.audience.contains(EnvConfig.jwtAudience) &&
                    credential.payload.getClaim("userId").asInt() != null) {
                    JWTPrincipal(credential.payload)
                } else {
                    null
                }
            }
        }
    }
}
