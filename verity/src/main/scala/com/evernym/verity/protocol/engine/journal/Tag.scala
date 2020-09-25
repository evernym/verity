package com.evernym.verity.protocol.engine.journal

import org.fusesource.jansi.Ansi._
import org.fusesource.jansi.Ansi.Color._
import org.fusesource.jansi.Ansi.Attribute._


sealed trait Tag {
  def apply(a: Any): String
}

case class ColorTag(color: Color) extends Tag {
  def apply(a: Any): String = {
    ansi().fg(color).a(a.toString).reset().toString
  }
}

case class AttribTag(attrib: Attribute) extends Tag {
  def apply(a: Any): String = {
    ansi().a(attrib).a(a.toString).reset().toString
  }
}

object Tag {
  val blue = ColorTag(BLUE)
  val black = ColorTag(BLACK)
  val red = ColorTag(RED)
  val green = ColorTag(GREEN)
  val yellow = ColorTag(YELLOW)
  val magenta = ColorTag(MAGENTA)
  val cyan = ColorTag(CYAN)
  val white = ColorTag(WHITE)
  val underline = AttribTag(UNDERLINE)
  val bold = AttribTag(INTENSITY_BOLD)
}

