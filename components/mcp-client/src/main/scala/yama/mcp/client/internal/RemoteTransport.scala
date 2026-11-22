package yama.mcp.client.internal

import cats.effect.{Resource, Sync}
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer
import io.modelcontextprotocol.spec.McpClientTransport
import yama.mcp.client.McpServer

import java.net.URI

/** The SDK's streamable-HTTP client transport (JDK HttpClient), with our headers (e.g. `Authorization`) on every request. */
private[client] object RemoteTransport:
  def make[F[_]: Sync](server: McpServer.Remote): Resource[F, McpClientTransport] =
    Resource.eval(Sync[F].delay {
      val uri      = URI.create(server.url)
      val base     = s"${uri.getScheme}://${uri.getRawAuthority}"
      val endpoint = Option(uri.getRawPath).filter(_.nonEmpty).getOrElse("/") + Option(uri.getRawQuery).fold("")("?" + _)
      val addHeaders: McpSyncHttpClientRequestCustomizer =
        (builder, _, _, _, _) => server.headers.foreach((name, value) => builder.header(name, value))
      HttpClientStreamableHttpTransport.builder(base).endpoint(endpoint).addHttpRequestCustomizer(addHeaders).build()
    })
