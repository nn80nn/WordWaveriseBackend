package n.startapp

import io.ktor.server.application.*
import n.startapp.database.DatabaseFactory

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    // Initialize database
    DatabaseFactory.init()

    // Configure application
    configureLogging()
    configureSerialization()
    configureExceptionHandling()
    configureHTTP()
    configureAuthentication()
    configureRouting()
}
