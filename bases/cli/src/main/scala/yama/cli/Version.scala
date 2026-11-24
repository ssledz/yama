package yama.cli

import scala.io.Source
import scala.util.Using

/** yama's version, from version.sbt (written to the classpath by the build): `0.2.0-SNAPSHOT` on develop. */
object Version:
  val current: String =
    Option(getClass.getClassLoader.getResourceAsStream("yama/version"))
      .flatMap(in => Using(Source.fromInputStream(in, "UTF-8"))(_.mkString.trim).toOption)
      .filter(_.nonEmpty)
      .getOrElse("unknown")
