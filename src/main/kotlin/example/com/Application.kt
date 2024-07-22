package example.com

import example.com.plugins.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*

fun main(args: Array<String>) {
    val arrayListOfEnv = EnvLoader.arrayOfEnvs("C:\\Users\\dacos\\Documents\\autoseersservice\\.env")
    arrayListOfEnv.forEach {
        System.setProperty(it.first, it.second)
    }
    val port = System.getenv("PORT")?.toInt() ?: 8080
    embeddedServer(
        Netty,
        port,
        watchPaths = listOf("build"),
        module = Application::module
    ).start(true)
}

fun Application.module() {
    FirebaseAdmin.initialize()
    configureSerialization()
    configureMonitoring()
    configureRouting()
}
