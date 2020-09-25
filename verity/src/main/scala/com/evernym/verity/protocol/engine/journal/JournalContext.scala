package com.evernym.verity.protocol.engine.journal

object JournalContext {
  def apply(str: String *): JournalContext = new JournalContext(str.toVector)
}

class JournalContext(val stack: Vector[String]) {
  def tag: String = stack.mkString(":")
  def +(str: String) = new JournalContext(stack :+ str)
  def dropLastOne = new JournalContext(stack.dropRight(1))
}