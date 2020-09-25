package com.evernym.verity.testkit.util

object AssertionUtil {
  def expectMsgType[T](resp: Any): T = {
    resp.asInstanceOf[T]
  }
}
