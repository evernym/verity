package com.evernym.verity.protocol.engine.util

import com.evernym.verity.testkit.BasicSpec


import scala.concurrent.duration._
import scala.language.postfixOps

class BackoffSpec extends BasicSpec {
  "exponentialBackoff" - {
    "should handle null Durations" in {
      an [IllegalArgumentException] should be thrownBy {
        Backoff.exponentialBackoff(null)
      }

      an [IllegalArgumentException] should be thrownBy {
        Backoff.exponentialBackoff((10 seconds, null))
      }

      an [IllegalArgumentException] should be thrownBy {
        Backoff.exponentialBackoff((null, 10 seconds))
      }
    }

    "should handle simple Durations" in {
      val b = Backoff.exponentialBackoff((1 seconds, 2 minutes), 1.5f)
      b.delay(1) should be (1.5 seconds)
      b.delay(2) should be (2.25 seconds)
      b.delay(3) should be (3.375 seconds)
    }
  }

}