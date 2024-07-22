package example.com

import example.com.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.utils.EmptyContent.status
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.Test
import kotlin.test.*

class ApplicationTest {
    @Test
    fun testRoot() = testApplication {
        client.get("/").apply {
            assertEquals(HttpStatusCode.OK, status)
            assertEquals("Hello World!", bodyAsText())
        }
    }

//    @Test
//    fun testCreateUserProfileWithValidToken() {
//        withTestApplication(Application::module) {
//            handleRequest(HttpMethod.Post, "/createUserProfile") {
//                addHeader(HttpHeaders.ContentType, ContentType.Application.Json.toString())
//                addHeader(HttpHeaders.Authorization, "Bearer validFirebaseToken")
//                setBody("""{"name": "John Doe"}""")
//            }.apply {
//                assertEquals(HttpStatusCode.OK, response.status())
//                assertEquals("Profile created for John Doe", response.content)
//            }
//        }
//    }
}
