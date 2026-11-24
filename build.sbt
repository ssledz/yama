// yama: Yet Another Micro Agent — Polylith workspace on sbt.
//
//   components/<name>  interface = package yama.<name>, implementation = yama.<name>.internal (private[<name>])
//   bases/<name>       entry points (cli: the only place IO appears)
//   projects/<name>    no code: one base + components -> artifact
//   development/       root project: aggregates every brick for one IDE/REPL session
//
// Rules: components depend only on component interfaces; nothing depends on a base or a project; no cycles.

ThisBuild / organization := "io.github.ssledz"
// version: see version.sbt, owned by sbt-release (`sbt release`)
ThisBuild / scalaVersion := "3.10.1"

ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Wunused:all",
  "-Wvalue-discard"
)

// ---------------------------------------------------------------------------
// Dependencies
// ---------------------------------------------------------------------------

lazy val V = new {
  val catsEffect      = "3.7.1"
  val fs2             = "3.16.0"
  val circe           = "0.14.17"
  val circeYaml       = "1.15.0"
  val http4s          = "0.23.39"
  val catsRetry       = "4.0.0"
  val decline         = "2.6.2"
  val log4cats        = "2.8.0"
  val logback         = "1.6.8"
  val ciris           = "3.15.2"
  val mcp             = "2.2.0"
  val munit           = "1.3.6"
  val munitCatsEffect = "2.2.1"
}

lazy val D = new {
  val catsEffect      = "org.typelevel"           %% "cats-effect"         % V.catsEffect
  val fs2Io           = "co.fs2"                  %% "fs2-io"              % V.fs2
  val circeCore       = "io.circe"                %% "circe-core"          % V.circe
  val circeParser     = "io.circe"                %% "circe-parser"        % V.circe
  val circeYaml       = "io.circe"                %% "circe-yaml"          % V.circeYaml
  val http4sClient    = "org.http4s"              %% "http4s-client"       % V.http4s
  val http4sEmber     = "org.http4s"              %% "http4s-ember-client" % V.http4s
  val http4sCirce     = "org.http4s"              %% "http4s-circe"        % V.http4s
  val http4sDsl       = "org.http4s"              %% "http4s-dsl"          % V.http4s          % Test
  val catsRetry       = "com.github.cb372"        %% "cats-retry"          % V.catsRetry
  val declineEffect   = "com.monovore"            %% "decline-effect"      % V.decline
  val log4catsCore    = "org.typelevel"           %% "log4cats-core"       % V.log4cats
  val log4catsSlf4j   = "org.typelevel"           %% "log4cats-slf4j"      % V.log4cats
  val logback         = "ch.qos.logback"           % "logback-classic"     % V.logback
  val ciris           = "is.cir"                  %% "ciris"               % V.ciris
  val cirisCirce      = "is.cir"                  %% "ciris-circe"         % V.ciris
  val mcpSdk          = "io.modelcontextprotocol.sdk" % "mcp"              % V.mcp
  val munit           = "org.scalameta"           %% "munit"               % V.munit           % Test
  val munitCatsEffect = "org.typelevel"           %% "munit-cats-effect"   % V.munitCatsEffect % Test
  val log4catsNoop    = "org.typelevel"           %% "log4cats-noop"       % V.log4cats        % Test
}

// ---------------------------------------------------------------------------
// Brick helpers
// ---------------------------------------------------------------------------

lazy val commonSettings = Seq(
  libraryDependencies ++= Seq(D.catsEffect, D.munit, D.munitCatsEffect),
  Test / fork := true,
  // integration tests (mcp-client spawns mcp/files-mcp) need the workspace root
  Test / javaOptions += s"-Dyama.root=${(ThisBuild / baseDirectory).value}"
)

def component(name: String): Project =
  Project(name, file(s"components/$name")).settings(commonSettings)

def base(name: String): Project =
  Project(name, file(s"bases/$name")).settings(commonSettings)

def artifactProject(name: String): Project =
  Project(name, file(s"projects/$name"))

// ---------------------------------------------------------------------------
// Components
// ---------------------------------------------------------------------------

lazy val conversation = component("conversation")
  .settings(libraryDependencies ++= Seq(D.circeCore))

// The repo's config.json is bundled as yama/default-config.json: written to ~/.yama/config.json on first run.
// yama/files-mcp.package holds "<name>@<version>" and the bin name from mcp/files-mcp/package.json: what first run
// points npx at.
lazy val config = component("config")
  .settings(
    libraryDependencies ++= Seq(D.circeCore, D.circeParser, D.ciris, D.cirisCirce),
    Compile / resourceGenerators += Def.task {
      val root    = (ThisBuild / baseDirectory).value
      val out     = (Compile / resourceManaged).value / "yama"
      val pkg     = IO.read(root / "mcp" / "files-mcp" / "package.json")
      def field(name: String) =
        ("\"" + name + "\"" + """\s*:\s*"([^"]+)"""").r.findFirstMatchIn(pkg).map(_.group(1)).getOrElse(sys.error(s"no $name in files-mcp/package.json"))
      IO.copyFile(root / "config.json", out / "default-config.json")
      val bin     = """"bin"\s*:\s*\{\s*"([^"]+)"""".r.findFirstMatchIn(pkg).map(_.group(1)).getOrElse(sys.error("no bin in files-mcp/package.json"))
      IO.write(out / "files-mcp.package", s"${field("name")}@${field("version")}\n$bin\n")
      Seq(out / "default-config.json", out / "files-mcp.package")
    }.taskValue
  )

lazy val log = component("log")
  .dependsOn(conversation)
  .settings(libraryDependencies ++= Seq(D.log4catsCore))

lazy val chat = component("chat")
  .dependsOn(conversation)
  .settings(libraryDependencies ++= Seq(D.circeCore, D.catsRetry, D.log4catsCore, D.log4catsNoop))

lazy val chatOpenAiCompat = component("chat-openai-compat")
  .dependsOn(chat, conversation)
  .settings(
    libraryDependencies ++= Seq(D.http4sClient, D.http4sCirce, D.circeParser, D.ciris, D.http4sDsl)
  )

lazy val tool = component("tool")
  .dependsOn(conversation)
  .settings(libraryDependencies ++= Seq(D.circeCore, D.circeParser))

lazy val wget = component("wget")
  .dependsOn(tool)
  .settings(libraryDependencies ++= Seq(D.http4sClient, D.http4sDsl, D.fs2Io, D.log4catsCore, D.log4catsNoop))

// No yama dependencies: reusable on its own. MCP Java SDK (Reactor, Jackson) never leaks out.
lazy val mcpClient = component("mcp-client")
  .settings(libraryDependencies ++= Seq(D.mcpSdk, D.fs2Io, D.circeCore, D.circeParser, D.log4catsCore, D.log4catsNoop))

lazy val mcpTools = component("mcp-tools")
  .dependsOn(mcpClient, tool)

// The repo's skills/ folder is bundled as classpath resources under yama-skills/ (see Skills.builtinRoot),
// so the fat jar carries its built-in skills. The .root marker lets the folder be found inside a jar.
lazy val skills = component("skills")
  .dependsOn(tool)
  .settings(
    libraryDependencies ++= Seq(D.circeYaml),
    Compile / resourceGenerators += Def.task {
      val src = (ThisBuild / baseDirectory).value / "skills"
      val out = (Compile / resourceManaged).value / "yama-skills"
      IO.delete(out)
      val copies = (src ** "*").get.filter(_.isFile).flatMap(f => IO.relativize(src, f).map(rel => f -> out / rel))
      IO.copy(copies)
      IO.write(out / ".root", "")
      copies.map(_._2) :+ (out / ".root")
    }.taskValue
  )

lazy val agent = component("agent")
  .dependsOn(conversation, chat % "compile->compile;test->test", tool, log)
  .settings(libraryDependencies ++= Seq(D.log4catsCore, D.log4catsNoop))

lazy val subagent = component("subagent")
  .dependsOn(agent, chat % "compile->compile;test->test", tool, log)
  .settings(libraryDependencies ++= Seq(D.log4catsCore, D.log4catsNoop))

lazy val components: Seq[Project] = Seq(
  conversation, config, log, chat, chatOpenAiCompat, tool, wget, mcpClient, mcpTools, skills, agent, subagent
)

// ---------------------------------------------------------------------------
// Bases
// ---------------------------------------------------------------------------

lazy val cli = base("cli")
  .dependsOn(components.map(p => p: ClasspathDep[ProjectReference]) *)
  .settings(
    libraryDependencies ++= Seq(D.declineEffect, D.http4sEmber, D.log4catsSlf4j, D.logback),
    // the version from version.sbt, for `yama --version` and the banner
    Compile / resourceGenerators += Def.task {
      val out = (Compile / resourceManaged).value / "yama" / "version"
      IO.write(out, version.value)
      Seq(out)
    }.taskValue
  )

// ---------------------------------------------------------------------------
// Projects (no code: base + components -> artifact)
// ---------------------------------------------------------------------------

lazy val yamaCli = artifactProject("yama-cli")
  .dependsOn(cli)
  .settings(
    Compile / mainClass       := Some("yama.cli.Main"),
    run / fork                := true,
    run / javaOptions         += "--sun-misc-unsafe-memory-access=allow", // JDK 24+: silence Scala 3 LazyVals warning
    run / connectInput        := true,
    run / baseDirectory       := (ThisBuild / baseDirectory).value,
    assembly / mainClass      := Some("yama.cli.Main"),
    assembly / assemblyJarName := "yama.jar",
    // a launcher script in front of the zip: `./yama.jar` runs it (sh or cmd), `java -jar yama.jar` still works
    assembly / assemblyPrependShellScript := Some(AssemblyPlugin.defaultUniversalScript(shebang = false)),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "versions", _, "module-info.class") => MergeStrategy.discard
      case "module-info.class"                                      => MergeStrategy.discard
      case PathList("META-INF", "services", _*)                     => MergeStrategy.concat
      case x =>
        val old = (assembly / assemblyMergeStrategy).value
        old(x)
    }
  )

// ---------------------------------------------------------------------------
// Development (workspace root): every brick in one session + development/src scratch code
// ---------------------------------------------------------------------------

lazy val development = (project in file("."))
  .settings(commonSettings)
  .settings(
    name                     := "yama",
    Compile / scalaSource    := baseDirectory.value / "development" / "src" / "main" / "scala",
    Compile / resourceDirectory := baseDirectory.value / "development" / "src" / "main" / "resources",
    Test / scalaSource       := baseDirectory.value / "development" / "src" / "test" / "scala",
    publish / skip           := true,
    // `sbt release` (on develop) prepares a release; merging its PR into main publishes it (see releaseFlow below).
    // Semver X.Y.Z; the next version bumps the minor (0.1.0 -> 0.2.0-SNAPSHOT). Override per release:
    // `sbt "release release-version 1.0.0 next-version 1.1.0-SNAPSHOT"`.
    releaseVersionBump := sbtrelease.Version.Bump.Minor,
    releaseProcess     := releaseFlow
  )
  .aggregate((components :+ cli :+ yamaCli).map(p => p: ProjectReference) *)
  .dependsOn((components :+ cli).map(p => p: ClasspathDep[ProjectReference]) *)

// ---------------------------------------------------------------------------
// Release flow: develop -> PR -> main
// ---------------------------------------------------------------------------
//
// `sbt release` on develop: test, commit X.Y.Z to version.sbt on a new branch release/vX.Y.Z, bump develop to the
// next snapshot, push both, open a PR release/vX.Y.Z -> main. No tag here: merging the PR makes
// .github/workflows/release.yml build the fat jar, tag vX.Y.Z and publish the GitHub release.

def git(args: String*): String = scala.sys.process.Process("git" +: args).!!.trim

def releaseBranch(st: State): String =
  "release/v" + st.get(sbtrelease.ReleasePlugin.autoImport.ReleaseKeys.versions).get._1

lazy val onDevelop = ReleaseStep { st: State =>
  val branch = git("rev-parse", "--abbrev-ref", "HEAD")
  if (branch != "develop") sys.error(s"release from develop, not '$branch'")
  st
}

lazy val branchRelease = ReleaseStep { st: State =>
  val branch = releaseBranch(st)
  git("branch", branch) // at the release commit; develop moves on to the next snapshot
  st.log.info(s"created $branch")
  st
}

lazy val pushAndOpenPr = ReleaseStep { st: State =>
  val branch = releaseBranch(st)
  git("push", "origin", "develop", branch)
  val title = "Release " + branch.stripPrefix("release/")
  val hasGh = scala.util.Try(scala.sys.process.Process(Seq("gh", "--version")).!!).isSuccess
  if (hasGh)
    scala.sys.process.Process(Seq("gh", "pr", "create", "--base", "main", "--head", branch, "--title", title,
      "--body", s"Merging publishes $title: tag and fat jar on GitHub releases.")).!
  else {
    val repo = git("remote", "get-url", "origin").replaceFirst("^git@github.com:", "https://github.com/").stripSuffix(".git")
    st.log.info(s"open the release PR: $repo/compare/main...$branch?expand=1")
  }
  st
}

lazy val releaseFlow: Seq[ReleaseStep] = {
  import sbtrelease.ReleaseStateTransformations._
  Seq[ReleaseStep](
    onDevelop,
    checkSnapshotDependencies,
    inquireVersions,
    runClean,
    runTest,
    setReleaseVersion,
    commitReleaseVersion,
    branchRelease,
    setNextVersion,
    commitNextVersion,
    pushAndOpenPr
  )
}
