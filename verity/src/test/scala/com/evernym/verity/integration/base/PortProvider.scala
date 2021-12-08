package com.evernym.verity.integration.base

import java.util.concurrent.atomic.AtomicInteger

object PortProvider {

  //global singleton object for test to make sure it doesn't allocate
  // already allocated/used port to avoid "port already used" error.

  private val port: AtomicInteger = new AtomicInteger(2000)

  def getFreePort: Int = port.getAndIncrement
}
