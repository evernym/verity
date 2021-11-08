package com.evernym.integrationtests.e2e.util

import java.util.concurrent.atomic.AtomicInteger

object PortProvider {

  var incrementalPort: AtomicInteger = new AtomicInteger(2000)

  def getFreePort: Int = incrementalPort.getAndIncrement

}