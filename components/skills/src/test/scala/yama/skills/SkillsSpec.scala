package yama.skills

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import yama.conversation.{CallId, Item, ToolName}
import yama.tool.{Tool, ToolBox}

import java.nio.file.{Files, Path}
import java.util.zip.{ZipEntry, ZipOutputStream}
import scala.util.Using

class SkillsSpec extends CatsEffectSuite:

  private def write(root: Path, rel: String, content: String): Unit =
    val file = root.resolve(rel)
    Files.createDirectories(file.getParent)
    Files.writeString(file, content): Unit

  private def skill(name: String, description: String, body: String = "Do the thing."): String =
    s"---\nname: $name\ndescription: $description\n---\n\n$body\n"

  /** project/ and user/ roots with valid, lenient-warning and skipped fixtures. */
  private val roots = FunFixture[(Path, Path)](
    _ =>
      val tmp     = Files.createTempDirectory("yama-skills")
      val project = tmp.resolve("project")
      val user    = tmp.resolve("user")
      write(project, "pdf-tools/SKILL.md", skill("pdf-tools", "Work with PDFs. Use when a task mentions PDF files.", "Read references/guide.md first."))
      write(project, "pdf-tools/references/guide.md", "# Guide")
      write(project, "pdf-tools/scripts/extract.py", "print('hi')")
      write(project, "Bad_Name/SKILL.md", skill("Bad_Name", "Lenient: invalid name still loads."))
      write(project, "colon/SKILL.md", "---\nname: colon\ndescription: Use when: the value has a colon\n---\nbody")
      write(project, "no-description/SKILL.md", "---\nname: no-description\n---\nbody")
      write(project, "broken/SKILL.md", "---\nname: [unclosed\n---\nbody")
      write(project, "node_modules/hidden/SKILL.md", skill("hidden", "Never discovered."))
      write(user, "pdf-tools/SKILL.md", skill("pdf-tools", "User-level copy, shadowed."))
      write(user, "notes/SKILL.md", skill("notes", "User-level skill."))
      (project, user)
    ,
    _ => ()
  )

  private def discover(project: Path, user: Path): IO[Skills[IO]] =
    Skills.discover[IO](List(Skills.Root(project, Scope.Project), Skills.Root(user, Scope.User)))

  roots.test("discovers valid skills, loads lenient ones, skips broken ones") { (project, user) =>
    discover(project, user).map { skills =>
      assertEquals(skills.catalog.map(_.name).sorted, List("Bad_Name", "colon", "notes", "pdf-tools"))
      val messages = skills.diagnostics.map(d => d.location.getParent.getFileName.toString -> d.skipped)
      assert(messages.contains("Bad_Name" -> false), messages)       // warning, still loaded
      assert(messages.contains("no-description" -> true), messages)  // skipped
      assert(messages.contains("broken" -> true), messages)          // unparseable YAML: skipped
      assertEquals(skills.catalog.find(_.name == "colon").map(_.description), Some("Use when: the value has a colon"))
    }
  }

  roots.test("project-level skills beat user-level ones") { (project, user) =>
    discover(project, user).map { skills =>
      val pdf = skills.catalog.find(_.name == "pdf-tools").get
      assertEquals(pdf.scope, Scope.Project)
      assert(skills.diagnostics.exists(_.message.contains("shadowed")))
    }
  }

  roots.test("catalog prompt lists name and description only") { (project, user) =>
    discover(project, user).map { skills =>
      assert(skills.catalogPrompt.contains("<available_skills>"))
      assert(skills.catalogPrompt.contains("<name>pdf-tools</name>"))
      assert(!skills.catalogPrompt.contains("Read references/guide.md first."))
    }
  }

  roots.test("activate_skill: enum schema, <skill_content> with resources listed, de-duplicated") { (project, user) =>
    for
      skills <- discover(project, user)
      box     = ToolBox[IO](skills.tools)
      tool    = box.find(ToolName("activate_skill")).get
      first  <- box.execute(Item.ToolCall(CallId("1"), ToolName("activate_skill"), """{"name":"pdf-tools"}"""))
      again  <- skills.activate("pdf-tools")
    yield
      val names = tool.schema.hcursor.downField("properties").downField("name").get[List[String]]("enum")
      assertEquals(names.map(_.sorted), Right(skills.catalog.map(_.name).sorted))
      val content = first.output.asString.get
      assert(content.startsWith("""<skill_content name="pdf-tools">"""), content)
      assert(content.contains("Read references/guide.md first."))
      assert(content.contains("<file>references/guide.md</file>"))
      assert(content.contains("<file>scripts/extract.py</file>"))
      assert(!content.contains("description:"), "frontmatter must be stripped")
      assert(again.exists(_.contains("already active")))
  }

  roots.test("read_skill_resource reads inside the skill and refuses path traversal") { (project, user) =>
    for
      skills  <- discover(project, user)
      guide   <- skills.readResource("pdf-tools", "references/guide.md")
      escape  <- skills.readResource("pdf-tools", "../notes/SKILL.md")
      absolut <- skills.readResource("pdf-tools", "/etc/passwd")
      viaTool <- ToolBox[IO](skills.tools).execute(
        Item.ToolCall(CallId("2"), ToolName("read_skill_resource"), Json.obj("name" -> "pdf-tools".asJson, "path" -> "../../x".asJson).noSpaces)
      )
    yield
      assertEquals(guide, Right("# Guide"))
      assert(escape.left.exists(_.startsWith("Refused")))
      assert(absolut.left.exists(_.startsWith("Refused")))
      assert(Tool.isError(viaTool.output))
  }

  test("no skills: no catalog and no tools") {
    Skills.discover[IO](List(Skills.Root(Files.createTempDirectory("empty"), Scope.Project))).map { skills =>
      assertEquals(skills.catalogPrompt, "")
      assertEquals(skills.tools, Nil)
    }
  }

  test("the bundled markdown-summary skill is valid") {
    val root = Path.of(sys.props("yama.root")).resolve("skills")
    Skills.discover[IO](List(Skills.Root(root, Scope.Project))).map { skills =>
      assertEquals(skills.catalog.map(_.name), List("markdown-summary"))
      assertEquals(skills.diagnostics, Nil)
    }
  }

  test("built-in skills come from the classpath, below user-level ones") {
    Skills.builtinRoot[IO].use(builtin => for
      user     = Files.createTempDirectory("yama-user")
      _        = write(user, "markdown-summary/SKILL.md", skill("markdown-summary", "User override."))
      shadowed <- Skills.discover[IO](Skills.defaultRoots(Files.createTempDirectory("p"), user, user, Nil, trustProject = false, builtin))
      alone    <- Skills.discover[IO](builtin.toList.map(Skills.Root(_, Scope.Builtin)))
      body     <- alone.activate("markdown-summary")
    yield
      assert(builtin.isDefined, "yama-skills/ is on the classpath")
      assertEquals(alone.catalog.map(s => s.name -> s.scope), List("markdown-summary" -> Scope.Builtin))
      assert(body.exists(_.contains("built into yama")), body)
      assertEquals(shadowed.catalog.find(_.name == "markdown-summary").map(_.scope), Some(Scope.User))
    )
  }

  test("built-in skills load from a jar with a launcher script before the zip, like the fat jar") {
    val jar = Files.createTempFile("yama", ".jar")
    Using.resource(Files.newOutputStream(jar)) { out =>
      out.write("#!/usr/bin/env sh\nexec java -jar \"$0\" \"$@\"\n".getBytes)
      Using.resource(new ZipOutputStream(out)) { zip =>
        for (name, content) <- List(".root" -> "", "markdown-summary/SKILL.md" -> skill("markdown-summary", "Built in."), "markdown-summary/ref.md" -> "ref")
        do
          zip.putNextEntry(new ZipEntry(s"${Skills.BuiltinResource}/$name"))
          zip.write(content.getBytes)
          zip.closeEntry()
      }
    }
    for
      (dir, names, resource) <- Skills.fromJar[IO](jar).use { dir =>
        Skills.discover[IO](List(Skills.Root(dir, Scope.Builtin))).flatMap { skills =>
          skills.readResource("markdown-summary", "ref.md").map(r => (dir, skills.catalog.map(_.name), r))
        }
      }
    yield
      assertEquals(names, List("markdown-summary"))
      assertEquals(resource, Right("ref"))
      assert(!Files.exists(dir), "the extracted skills are removed on release")
  }
