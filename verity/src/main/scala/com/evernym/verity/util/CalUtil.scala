package com.evernym.verity.util

object CalUtil {
  def percentDifference[N](val1: N, val2: N)(implicit numeric: Numeric[N]): Double = {
    val val1f = implicitly[Numeric[N]].toDouble(val1)
    val val2f = implicitly[Numeric[N]].toDouble(val2)

    require(val1f > 0, "Val1 must be a positive number greater than zero")
    require(val2f > 0, "Val2 must be a positive number greater than zero")

    val numerator= val1f - val2f
    val denominator = (val1f + val2f) / 2
    numerator / denominator
  }
}
