package com.evernym.verity

import ch.qos.logback.classic.Logger
import ch.qos.logback.core.spi.FilterReply
import com.evernym.verity.testkit.BasicSpec
import org.slf4j.LoggerFactory


class IgnoreLoggingFilterSpec extends BasicSpec {
  "Should run without configuration" in {
    var f = new IgnoreLoggerFilter()
    f.decide(null, null, null, null, null, null)
  }

  "Should run with null parameters" in {
    var f = new IgnoreLoggerFilter()
    f.setLoggerNameContains("foo")
    f.decide(null, null, null, null, null, null)
  }

  "Should match name" in {
    var f = new IgnoreLoggerFilter()
    f.setLoggerNameContains("foo")
    val l = LoggerFactory.getLogger("foo").asInstanceOf[Logger]
    f.decide(null, l, null, null, null, null) shouldBe FilterReply.DENY

    val l2 = LoggerFactory.getLogger("bar").asInstanceOf[Logger]
    f.decide(null, l2, null, null, null, null) shouldBe FilterReply.NEUTRAL

    f.decide(null, null, null, null, null, null) shouldBe FilterReply.NEUTRAL
  }

}
