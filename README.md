# WordWaveriseBackend

This project was created using the [Ktor Project Generator](https://start.ktor.io).

Here are some useful links to get you started:

- [Ktor Documentation](https://ktor.io/docs/home.html)
- [Ktor GitHub page](https://github.com/ktorio/ktor)
- The [Ktor Slack chat](https://app.slack.com/client/T09229ZC6/C0A974TJ9). You'll need
  to [request an invite](https://surveys.jetbrains.com/s3/kotlin-slack-sign-up) to join.

## Features

Here's a list of features included in this project:

| Name                                               | Description                                                 |
| ----------------------------------------------------|------------------------------------------------------------- |
| [CORS](https://start.ktor.io/p/cors)               | Enables Cross-Origin Resource Sharing (CORS)                |
| [Routing](https://start.ktor.io/p/routing-default) | Allows to define structured routes and associated handlers. |
| [Content Negotiation](https://ktor.io/docs/serialization.html) | JSON serialization with kotlinx.serialization |
| [Status Pages](https://ktor.io/docs/status-pages.html) | Global exception handling |
| [Call Logging](https://ktor.io/docs/call-logging.html) | Request/response logging |

## API Features

- **Generic API Response Wrapper**: All endpoints return standardized `ApiResponse<T>` format
- **Exception Handling**: Automatic conversion of exceptions to API responses with appropriate HTTP status codes
- **Request Logging**: All requests are logged with method, URI, status, and user agent
- **CORS Support**: Cross-origin requests enabled (configured for all hosts in development)

## Building & Running

To build or run the project, use one of the following tasks:

| Task                                    | Description                                                          |
| -----------------------------------------|---------------------------------------------------------------------- |
| `./gradlew test`                        | Run the tests                                                        |
| `./gradlew build`                       | Build everything                                                     |
| `./gradlew buildFatJar`                 | Build an executable JAR of the server with all dependencies included |
| `./gradlew buildImage`                  | Build the docker image to use with the fat JAR                       |
| `./gradlew publishImageToLocalRegistry` | Publish the docker image locally                                     |
| `./gradlew run`                         | Run the server                                                       |
| `./gradlew runDocker`                   | Run using the local docker image                                     |

If the server starts successfully, you'll see the following output:

```
2024-12-04 14:32:45.584 [main] INFO  Application - Application started in 0.303 seconds.
2024-12-04 14:32:45.682 [main] INFO  Application - Responding at http://0.0.0.0:8080
```

## API Endpoints

### Health Check
```bash
GET /api/health
```

**Response:**
```json
{
  "status": "ok",
  "data": {
    "status": "ok",
    "version": "1.0.0"
  }
}
```

### Error Response Example
When an endpoint is not found or an error occurs:
```json
{
  "status": "error",
  "message": "Endpoint not found: /api/nonexistent"
}
```

## API Response Format

All API responses follow this structure:
```kotlin
{
  "status": "ok" | "error",
  "data": T?,              // Response data (null on error)
  "message": String?       // Optional message (typically used for errors)
}
```

