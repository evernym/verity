package com.evernym.verity.protocol.engine.util

object DbcUtil {

  def errMsg(arg: String): String = s"missing argName: $arg"

  def requireNotNull[T](arg: T, argName: String = "AGR"): T =
    if (arg == null) throw new IllegalArgumentException(errMsg(argName))
    else arg

  def requireNotEmpty[T](arg: Iterable[T], argName: String = "AGR"): Iterable[T] =
    if (arg.nonEmpty) arg
    else throw new IllegalArgumentException(errMsg(argName))
}