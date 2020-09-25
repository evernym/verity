package com.evernym.verity.actor.testkit.checks.specs

import ch.qos.logback.classic.Level
import com.evernym.verity.actor.testkit.checks.{CheckPreconditionException, CheckerRegistry, FailedCheckException, LogChecker, UNSAFE_IgnoreLog}
import com.typesafe.scalalogging.Logger
import com.evernym.verity.testkit._

class ChecksLogsSpec extends BasicSpec {
  "A LogChecker" - {

    val cc = new LogChecker
    val cr = new CheckerRegistry(cc)

    "in a single thread" - {
      val logger = Logger("test-logger")

      "detects errors in logs" taggedAs (UNSAFE_IgnoreLog) in {
        intercept[FailedCheckException] {
          cr.run {
            logger.error("THIS IS A FAKE LOG MESSAGE FOR TESTING")
          }
        }
      }
      "detects warnings in logs" taggedAs (UNSAFE_IgnoreLog) in {
        intercept[FailedCheckException] {
          cr.run {
            logger.warn("THIS IS A FAKE LOG MESSAGE FOR TESTING")
          }
        }
      }
      "ignores info, debug, and trace in logs" in {
        cr.run {
          logger.info("test info")
          logger.debug("test debug")
          logger.trace("test trace")
        }
      }

    }

    "detects logs in other threads" taggedAs (UNSAFE_IgnoreLog) in {

      intercept[FailedCheckException] {
        cr.run {
          runInAnotherThread {
            val logger = Logger("some-other-test-logger")
            logger.error("THIS IS A FAKE ERROR LOG MESSAGE FOR TESTING")
          }
          Thread.sleep(50)
        }
      }

      intercept[FailedCheckException] {
        cr.run {
          runInAnotherThread {
            val logger = Logger("some-other-test-logger")
            logger.warn("THIS IS A FAKE WARN LOG MESSAGE FOR TESTING")
          }
          Thread.sleep(50)
        }
      }

    }

    "requires test logging be WARN or lower" in {

      def test() = {
        intercept[CheckPreconditionException] {
          cr.run {
            val logger = Logger("test-logger")
            logger.info("test info")
          }
        }
      }

      runWithLogLevel(Level.OFF) { test() }

      runWithLogLevel(Level.ERROR) { test() }

    }
  }

}