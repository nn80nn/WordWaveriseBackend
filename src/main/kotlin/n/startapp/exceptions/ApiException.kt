  package n.startapp.exceptions

import io.ktor.http.*

/**
 * Base exception class for API errors
 * @property message Error message
 * @property statusCode HTTP status code (default: 500 Internal Server Error)
 */
open class ApiException(
    override val message: String,
    val statusCode: HttpStatusCode = HttpStatusCode.InternalServerError
) : Exception(message)

/**
 * Exception for resource not found (404)
 */
class NotFoundException(message: String = "Resource not found") :
    ApiException(message, HttpStatusCode.NotFound)

/**
 * Exception for bad requests (400)
 */
class BadRequestException(message: String = "Bad request") :
    ApiException(message, HttpStatusCode.BadRequest)

/**
 * Exception for unauthorized access (401)
 */
class UnauthorizedException(message: String = "Unauthorized") :
    ApiException(message, HttpStatusCode.Unauthorized)

/**
 * Exception for forbidden access (403)
 */
class ForbiddenException(message: String = "Forbidden") :
    ApiException(message, HttpStatusCode.Forbidden)
