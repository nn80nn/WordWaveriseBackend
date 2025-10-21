package n.startapp

import io.ktor.server.application.*

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    configureLogging()
    configureSerialization()
    configureExceptionHandling()
    configureHTTP()
    configureRouting()
}
