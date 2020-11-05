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
  val blue: ColorTag = ColorTag(BLUE)
  val black: ColorTag = ColorTag(BLACK)
  val red: ColorTag = ColorTag(RED)
  val green: ColorTag = ColorTag(GREEN)
  val yellow: ColorTag = ColorTag(YELLOW)
  val magenta: ColorTag = ColorTag(MAGENTA)
  val cyan: ColorTag = ColorTag(CYAN)
  val white: ColorTag = ColorTag(WHITE)
  val underline: AttribTag = AttribTag(UNDERLINE)
  val bold: AttribTag = AttribTag(INTENSITY_BOLD)
}

