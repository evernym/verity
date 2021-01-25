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

  lazy val mockUnsupervised = system.actorOf(MockActorRecoveryFailure.props(appConfig, 1000))

  "Unsupervised actor" - {
    "when throws an unhandled exception during actor recovery" - {
      "should keep restarting as per DEFAULT strategy" taggedAs UNSAFE_IgnoreAkkaEvents in {  //UNSAFE_IgnoreAkkaEvents is to ignore the unhandled Ping message error message
        EventFilter.error(pattern = "purposefully throwing exception", occurrences = 5) intercept {
          mockUnsupervised ! GenerateRecoveryFailure
          expectNoMessage()
        }
      }
    }
  }

  override def overrideConfig: Option[Config] = Option { ConfigFactory.parseString (
    """
      akka.test.filter-leeway = 6s   # to make the event filter run for little longer time
      """
  )}
}


