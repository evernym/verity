package com.evernym.verity.actor.agent

import kamon.Kamon

/**
 * utility functions to record metrics spans which helps
 * finding out which functions/features are taking more time and where
 */
object SpanUtil {

  def runWithInternalSpan[T](opName: String, componentName: String)(op: => T): T = {
    Kamon.runWithSpan(Kamon.internalSpanBuilder(opName, componentName).start())(op)
  }

  def runWithClientSpan[T](opName: String, componentName: String)(op: => T): T = {
    Kamon.runWithSpan(Kamon.clientSpanBuilder(opName, componentName).start())(op)
  }
}
