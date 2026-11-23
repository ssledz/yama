package yama.config

import munit.FunSuite

import java.nio.file.Path

class LocationsSpec extends FunSuite:
  private val home = Path.of("/home/me")

  test("defaults to ~/.yama") {
    val l = Locations.resolve(None, None, None, home)
    assertEquals(l.configDir, Path.of("/home/me/.yama"))
    assertEquals(l.configFile, Path.of("/home/me/.yama/config.json"))
    assertEquals(l.skillsDir, Path.of("/home/me/.yama/skills"))
  }

  test("--config-dir beats YAMA_CONFIG_DIR, which beats ~/.yama; a blank env var is ignored") {
    assertEquals(Locations.resolve(Some(Path.of("/flag")), None, Some("/env"), home).configDir, Path.of("/flag"))
    assertEquals(Locations.resolve(None, None, Some("/env"), home).configFile, Path.of("/env/config.json"))
    assertEquals(Locations.resolve(None, None, Some("  "), home).configDir, Path.of("/home/me/.yama"))
  }

  test("--config moves only the file: skills and sessions stay in the config dir") {
    val l = Locations.resolve(None, Some(Path.of("/repo/config.json")), None, home)
    assertEquals(l.configFile, Path.of("/repo/config.json"))
    assertEquals(l.skillsDir, Path.of("/home/me/.yama/skills"))
  }

  test("a session folder is <sessions>/<encoded cwd>/<id>") {
    val l = Locations.resolve(None, None, None, home)
    assertEquals(l.sessionDir(Path.of("/Users/me/git/my yama"), "s1"), Path.of("/home/me/.yama/sessions/-Users-me-git-my-yama/s1"))
  }

  test("only the default config file may be bootstrapped") {
    assert(Locations.resolve(None, None, None, home).bootstrap)
    assert(Locations.resolve(Some(Path.of("/flag")), None, None, home).bootstrap)
    assert(!Locations.resolve(None, Some(Path.of("/repo/config.json")), None, home).bootstrap)
  }
