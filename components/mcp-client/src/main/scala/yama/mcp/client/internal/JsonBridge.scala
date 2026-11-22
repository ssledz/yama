package yama.mcp.client.internal

import cats.effect.Sync
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.parse
import io.modelcontextprotocol.json.{McpJsonDefaults, TypeRef}

/** Jackson ↔ circe at the boundary: serialise to a string and parse. Simple, and fast enough for tool payloads. */
private[client] object JsonBridge:
  private val mapper = McpJsonDefaults.getMapper()

  def toCirce[F[_]: Sync](value: Object): F[Json] =
    Sync[F].delay(mapper.writeValueAsString(value)).flatMap(s => parse(s).liftTo[F])

  def toJavaMap[F[_]: Sync](json: Json): F[java.util.Map[String, Object]] =
    Sync[F].delay(mapper.readValue(json.noSpaces, new TypeRef[java.util.Map[String, Object]] {}))
