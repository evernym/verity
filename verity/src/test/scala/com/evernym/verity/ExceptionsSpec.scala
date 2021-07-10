package com.evernym.verity

import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.protocol.engine.util.SimpleLogger
import com.evernym.verity.util2.Exceptions


class ExceptionsSpec extends BasicSpec {
  "Exceptions.getStackTraceString should" - {
    "convert an exception stack trace to a newline delimited string" in {
      val logger = SimpleLogger("LoggerUtilSpec")
      try {
        throw new RuntimeException("Test Exception")
      } catch {
        case e: Exception =>
          val stackTrace = Exceptions.getStackTraceAsString(e)
          logger.trace(stackTrace)
          val stackTraceLines: Array[String] = stackTrace.split("\n")
          stackTraceLines(0) shouldBe "java.lang.RuntimeException: Test Exception"
          assert(stackTraceLines(1).contains("\tat "))
      }
    }
  }
  "Exceptions.getStackTraceSingleLineString should" - {
    "convert an exception stack trace to a newline delimited string" in {
      val logger = SimpleLogger("LoggerUtilSpec")
      try {
        throw new RuntimeException("Test Exception")
      } catch {
        case e: Exception =>
          val stackTrace = Exceptions.getStackTraceAsSingleLineString(e)
          logger.trace(stackTrace)
          val stackTraceLines: Array[String] = stackTrace.split('\\')
          stackTraceLines(0) shouldBe "java.lang.RuntimeException: Test Exception"
          assert(stackTraceLines(1).contains("\tat "))
      }
    }
  }
}

