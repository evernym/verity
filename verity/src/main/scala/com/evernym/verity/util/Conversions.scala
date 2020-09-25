package com.evernym.verity.util

import scala.language.implicitConversions
import scala.util.Try

object Conversions {
  implicit def T2OptionT[T](x: T): Option[T] = Option(x)

  def str2OptionLong(x: String): Option[Long] = Try(x.toLong).toOption
}

