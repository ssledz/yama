package yama.log

/** Minimal ANSI colouring for on-stage readability. */
object Ansi:
  private def wrap(code: String)(text: String): String = s"\u001b[${code}m$text\u001b[0m"

  def bold(text: String): String    = wrap("1")(text)
  def dim(text: String): String     = wrap("2")(text)
  def red(text: String): String     = wrap("31")(text)
  def green(text: String): String   = wrap("32")(text)
  def yellow(text: String): String  = wrap("33")(text)
  def blue(text: String): String    = wrap("34")(text)
  def magenta(text: String): String = wrap("35")(text)
  def cyan(text: String): String    = wrap("36")(text)
  def bgBlue(text: String): String  = wrap("44;37")(text)
  def bgGreen(text: String): String = wrap("42;30")(text)
