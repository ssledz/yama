package yama.mcp.tools

import cats.Functor
import cats.syntax.all.*
import yama.mcp.client.McpClient
import yama.tool.Tool

/** Each remote MCP tool adapted to the same `Tool[F]` trait: the loop and the model can't tell native from MCP. */
object McpTools:
  def from[F[_]: Functor](client: McpClient[F]): F[List[Tool[F]]] =
    client.listTools.map(_.map { mcp =>
      Tool[F](
        name = mcp.name,
        description = mcp.description,
        schema = mcp.inputSchema.mapObject(_.remove("$schema")),
        origin = s"mcp:${client.server}"
      )(args => client.callTool(mcp.name, args))
    })
