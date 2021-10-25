package com.evernym.integrationtests.e2e.util

object PortProvider {

  var lastPortFrom2000: Int = 2000

  def firstFreePortFrom2000: Int = {
    val p = lastPortFrom2000
    lastPortFrom2000 += 1
    p
  }

}