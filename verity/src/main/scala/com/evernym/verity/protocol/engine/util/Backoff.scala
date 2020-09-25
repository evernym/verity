package com.evernym.verity.protocol.engine.util

import com.evernym.verity.protocol.engine.util.Backoff.MinMaxDur
import com.evernym.verity.protocol.engine.util.DbcUtil.requireNotNull

import scala.concurrent.duration.FiniteDuration
import scala.util.Try

trait DynamicDelay {
  def delay(iteration: Int): FiniteDuration
}

private class ExponentialBackoff (val minMaxDur: MinMaxDur, val power: Float) extends DynamicDelay {
  requireNotNull(minMaxDur, "minMaxDur")
  requireNotNull(minMaxDur._1, "minDur")
  requireNotNull(minMaxDur._2, "maxDur")

  def delay(iteration: Int): FiniteDuration = {
    calculateDelay(iteration, minMaxDur, power)
  }

  def calculateDelay(iteration: Int, dur: MinMaxDur, p: Float): FiniteDuration = {
    val minDur = dur._1
    val maxDur = dur._2

    val nextDur = Try(maxDur.min(minDur * math.pow(p, iteration))).getOrElse(maxDur)

    nextDur match {
      case d: FiniteDuration => d
      case _ => maxDur
    }
  }
}

object Backoff {
  type MinMaxDur = (FiniteDuration, FiniteDuration)

  val SLOW_EXP_GROWTH = 1.15f // slow for exponential

  def exponentialBackoff(minMaxDur: MinMaxDur, power: Float = SLOW_EXP_GROWTH): DynamicDelay = {
    new ExponentialBackoff(minMaxDur, power)
  }

}
