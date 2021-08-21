package com.evernym.integrationtests.e2e

import scala.concurrent.duration.Duration

object TestConstants {
  val defaultTimeout: Duration = Duration("25 sec")
  val defaultWaitTime: Long =  defaultTimeout.toMillis
}
