package yama.cli

import cats.effect.{ExitCode, IO}
import com.monovore.decline.Opts
import com.monovore.decline.effect.CommandIOApp
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory

/** The only place `IO` appears: everything else is polymorphic in `F[_]`. */
object Main extends CommandIOApp(name = "yama", header = "yama: Yet Another Micro Agent", version = Version.current):
  private given LoggerFactory[IO] = Slf4jFactory.create[IO]

  def main: Opts[IO[ExitCode]] = Cli.opts.map(Wiring.run[IO])
