package yama.config

import cats.effect.IO
import io.circe.parser.decode
import munit.CatsEffectSuite

import java.nio.file.{Files, Path}

class ConfigSpec extends CatsEffectSuite:

  private val repoConfig = Path.of(sys.props("yama.root")).resolve("config.json")

  test("the repo's config.json loads") {
    Config.load[IO](repoConfig, Overrides()).map { config =>
      assert(config.providers.keySet.contains(config.chat.provider))
      assertEquals(config.mcpServers.keySet, Set("files", "tavily"))
      assertEquals(config.subagents("research_agent").tools, List("tavily_search", "tavily_extract"))
    }
  }

  test("CLI flags override the file") {
    val overrides = Overrides(provider = Some("openrouter"), model = Some("x/y"), maxRounds = Some(3), skillsPaths = List("extra"))
    Config.load[IO](repoConfig, overrides).map { config =>
      assertEquals(config.chat.provider, "openrouter")
      assertEquals(config.chat.model, "x/y")
      assertEquals(config.agent.maxRounds, 3)
      assert(config.skills.paths.contains("extra"))
    }
  }

  test("an unknown provider is a config error") {
    Config.load[IO](repoConfig, Overrides(provider = Some("nope"))).attempt.map { result =>
      assert(result.left.exists(_.getMessage.contains("unknown provider 'nope'")), result)
    }
  }

  test("a missing file is a config error") {
    Config.load[IO](Path.of("does-not-exist.json"), Overrides()).attempt.map(r => assert(r.isLeft))
  }

  test("MCP servers: no type means a local process, type http means remote") {
    val json =
      """{ "files": { "command": "npx", "args": ["tsx", "src/index.ts"], "cwd": "mcp/files-mcp" },
           "tavily": { "type": "http", "url": "https://mcp.tavily.com/mcp/", "apiKeyEnv": "TAVILY_API_KEY" } }"""
    assertEquals(
      decode[Map[String, McpServerSettings]](json),
      Right(
        Map(
          "files"  -> McpServerSettings.Local("npx", List("tsx", "src/index.ts"), Map.empty, Some("mcp/files-mcp")),
          "tavily" -> McpServerSettings.Http("https://mcp.tavily.com/mcp/", Some("TAVILY_API_KEY"), Map.empty)
        )
      )
    )
    assert(decode[McpServerSettings]("""{"type":"sse","url":"x"}""").isLeft)
  }

  test("an optional API key gets a dummy default, a required one fails when unset") {
    val dir = Files.createTempDirectory("yama-config")
    val file = dir.resolve("config.json")
    Files.writeString(
      file,
      """{ "chat": { "provider": "local", "model": "m" },
           "providers": { "local": { "baseUrl": "http://localhost:11434/v1", "apiKeyEnv": "YAMA_TEST_UNSET_KEY", "apiKeyOptional": true },
                          "remote": { "baseUrl": "https://x", "apiKeyEnv": "YAMA_TEST_UNSET_KEY" } },
           "agent": { "instructions": "hi" }, "sandbox": {} }"""
    )
    for
      local  <- Config.load[IO](file, Overrides()).flatMap(Secrets.load[IO])
      remote <- Config.load[IO](file, Overrides(provider = Some("remote"))).flatMap(Secrets.load[IO]).attempt
    yield
      assertEquals(local.chatApiKey.value, "unused")
      assert(remote.isLeft)
  }
