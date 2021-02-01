package com.evernym.verity.actor.persistence.supervisor.default

import akka.testkit.EventFilter
import com.evernym.verity.actor.persistence.supervisor.{GenerateRecoveryFailure, MockActorRecoveryFailure}
import com.evernym.verity.actor.testkit.ActorSpec
import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreAkkaEvents
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.concurrent.Eventually

import scala.language.postfixOps


class ActorRecoveryFailureSpec
  extends ActorSpec
  with BasicSpec
  with Eventually {

  lazy val mockUnsupervised = system.actorOf(MockActorRecoveryFailure.props(appConfig))

  "Unsupervised actor" - {
    "when throws an unhandled exception during actor recovery" - {
      "should restart actor as per DEFAULT strategy" taggedAs UNSAFE_IgnoreAkkaEvents in {  //UNSAFE_IgnoreAkkaEvents is to ignore the unhandled GenerateRecoveryFailure message error message
        //5 from 'handleFailure' in 'akka.actor.FaultHandling' (the default handler) and
        // 5 from overridden 'preRestart' method in CoreActor
        val expectedLogEntries = 10
        EventFilter.error(pattern = "purposefully throwing exception", occurrences = expectedLogEntries) intercept {
          mockUnsupervised ! GenerateRecoveryFailure
          expectNoMessage()
        }
      }
    }
  }

  override def overrideConfig: Option[Config] = Option { ConfigFactory.parseString (
    """
      akka.test.filter-leeway = 6s   # to make the event filter run for little longer time
      akka.mock.actor.exceptionSleepTimeInMillis = 1000
      """
  )}
}


