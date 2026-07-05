package n.startapp

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureHTTP() {
    install(CORS) {
        // Разрешенные HTTP методы
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Head)

        // Разрешенные заголовки
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Accept)
        allowHeader("X-Admin-Secret")

        // Разрешить credentials (cookies, authorization headers)
        allowCredentials = true

        // Разрешенные хосты для разработки и production
        // Frontend development server (Vite/React default port)
        allowHost("localhost:5173", schemes = listOf("http"))
        allowHost("127.0.0.1:5173", schemes = listOf("http"))

        // Для мобильного доступа в локальной сети (опционально)
        // allowHost("192.168.1.100:5173", schemes = listOf("http"))

        // Production frontend
        allowHost("wordwaverise.com", schemes = listOf("https"))
        allowHost("www.wordwaverise.com", schemes = listOf("https"))
        allowHost("app.wordwaverise.com", schemes = listOf("https"))

        // Для тестирования можно временно разрешить все хосты
        // anyHost() // WARNING: Remove in production!
    }
}
