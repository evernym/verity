package com.evernym.verity.actor.persistence.supervisor

import akka.event.Logging.LogEvent
import akka.testkit.TestKitBase
import com.evernym.verity.actor.testkit.checks.ChecksAkkaEvents
import com.evernym.verity.testkit.BasicSpecBase
import org.scalatest.TestSuite

trait IgnoreSupervisorLogErrors extends ChecksAkkaEvents {
  this: TestSuite with TestKitBase with BasicSpecBase =>

  override def logEventExceptions(e: LogEvent): Boolean = {
    e.message match {
      case m: String => m.endsWith("supervised")
    }
  }
}
