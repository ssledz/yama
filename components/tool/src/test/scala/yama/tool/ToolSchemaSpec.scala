package yama.tool

import io.circe.Json
import io.circe.parser.parse
import munit.FunSuite

class ToolSchemaSpec extends FunSuite:

  case class WgetArgs(url: String, outputPath: String) derives ToolSchema
  case class SearchArgs(query: String, limit: Option[Int], tags: List[String], exact: Boolean) derives ToolSchema

  private def json(s: String): Json = parse(s).fold(throw _, identity)

  test("derives an object schema from a case class") {
    assertEquals(
      ToolSchema[WgetArgs].json,
      json("""{
        "type": "object",
        "properties": { "url": { "type": "string" }, "outputPath": { "type": "string" } },
        "required": ["url", "outputPath"],
        "additionalProperties": false
      }""")
    )
    assert(ToolSchema[WgetArgs].allRequired)
  }

  test("Option fields are not required; lists and primitives map to JSON Schema types") {
    val schema = ToolSchema[SearchArgs]
    assertEquals(schema.json.hcursor.downField("required").as[List[String]], Right(List("query", "tags", "exact")))
    assertEquals(schema.json.hcursor.downField("properties").downField("limit").get[String]("type"), Right("integer"))
    assertEquals(schema.json.hcursor.downField("properties").downField("tags").downField("items").get[String]("type"), Right("string"))
    assertEquals(schema.json.hcursor.downField("properties").downField("exact").get[String]("type"), Right("boolean"))
    assert(!schema.allRequired)
  }

  test("describe adds descriptions to fields") {
    val described = ToolSchema[WgetArgs].describe("url" -> "what to download")
    assertEquals(described.hcursor.downField("properties").downField("url").get[String]("description"), Right("what to download"))
  }
