package com.calypsan.listenup.server.openapi

import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Serve the generated OpenAPI document at /api/openapi.json and a Swagger UI page at
 * /api/docs that loads it. Swagger UI is served as a tiny CDN-backed HTML host so we
 * need no bundled UI assets; the spec itself is generated in-process (see OpenApiDocument).
 */
fun Route.openApiRoutes() {
    get("/api/openapi.json") {
        call.respondText(buildOpenApiDocument().toString(), ContentType.Application.Json)
    }
    get("/api/docs") {
        call.respondText(SWAGGER_UI_HTML, ContentType.Text.Html)
    }
}

private val SWAGGER_UI_HTML =
    """
    <!doctype html>
    <html>
      <head>
        <title>ListenUp API — Swagger UI</title>
        <link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist/swagger-ui.css">
      </head>
      <body>
        <div id="swagger"></div>
        <script src="https://unpkg.com/swagger-ui-dist/swagger-ui-bundle.js"></script>
        <script>
          window.ui = SwaggerUIBundle({ url: '/api/openapi.json', dom_id: '#swagger' });
        </script>
      </body>
    </html>
    """.trimIndent()
