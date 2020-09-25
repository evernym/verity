package com.evernym.verity.protocol.engine

import ch.qos.logback.classic.Level
import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreLog
import com.evernym.verity.logging.LoggingUtil.getLoggerByName
import com.evernym.verity.protocol.engine.journal.{Journal, JournalContext, JournalLogging}
import com.evernym.verity.testkit.{BasicSpec, runWithLogLevel}
import com.typesafe.scalalogging.Logger
import org.scalatest.TestSuite

class JournalLoggingSpec extends BasicSpec {

  "Detailed Logging" - {
    "has indentation" - {
      "that matches nested calls" taggedAs (UNSAFE_IgnoreLog) in {

        Journal.shouldIndent = true
        Journal.overrideLogger = Some(getLoggerByName("AV"))

        class X extends JournalLogging {

          override protected def logger: Logger = getLoggerByName("logging-tests")

          override protected val journalContext: JournalContext = JournalContext("A", "x", "1")

          def go(): Unit = {
            Journal.depth shouldBe 0
            withLog("description 1") {
              Journal.depth shouldBe 1
              withLog("description 2") {
                Journal.depth shouldBe 2
                record("description 3")
                Journal.depth shouldBe 2
              }
              Journal.depth shouldBe 1
            }
            Journal.depth shouldBe 0
          }

        }

        val x = new X
        runWithLogLevel(Level.DEBUG) {
          x.go()
        }

      }
    }
  }

  "has syntax highlighting" - {
    pending
  }

}

/**
  * Mix this in to a test suite to get log messages formatted in a way to aid in debugging.
  */
trait DebugProtocols {
  this: TestSuite =>
  //TODO add ability to turn on debugging programmatically, and only do it for the Protocol marker
  Journal.shouldIndent = true
  Journal.overrideLogger = Some(getLoggerByName("AV"))
}
