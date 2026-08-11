package n.startapp.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import n.startapp.models.ApiResponse
import n.startapp.utils.EnvConfig

/**
 * Gate for every handler under `/api/admin`.
 *
 * Fail-closed: an unset ADMIN_SECRET rejects everything rather than admitting everyone, so
 * forgetting the variable cannot silently publish the panel.
 *
 * @return true when the call was rejected and already answered — the handler must return at once.
 */
suspend fun ApplicationCall.rejectedAsNonAdmin(): Boolean {
    val expected = EnvConfig.adminSecret
    if (expected.isNotBlank() && request.headers["X-Admin-Secret"] == expected) return false
    respond(HttpStatusCode.Unauthorized, ApiResponse.error<Nothing>("Unauthorized"))
    return true
}
