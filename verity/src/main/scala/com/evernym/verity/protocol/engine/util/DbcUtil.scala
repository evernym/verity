package com.evernym.verity.protocol.engine.util

import scala.collection.IterableLike

object DbcUtil {

  def errMsg(arg: String): String = s"missing argName: $arg"

  def requireNotNull[T](arg: T, argName: String = "AGR"): T =
    if (arg == null) throw new IllegalArgumentException(errMsg(argName))
    else arg

  def requireNotEmpty[T, R](arg: IterableLike[T, R], argName: String = "AGR"): IterableLike[T, R] =
    if (arg.nonEmpty) arg
    else throw new IllegalArgumentException(errMsg(argName))
}