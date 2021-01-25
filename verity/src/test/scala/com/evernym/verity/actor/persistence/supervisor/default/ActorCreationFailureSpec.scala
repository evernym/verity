package com.evernym.verity.actor.persistence.supervisor.default

import akka.testkit.EventFilter
import com.evernym.verity.actor.base.Ping
import com.evernym.verity.actor.persistence.supervisor.MockActorCreationFailure
import com.evernym.verity.actor.testkit.ActorSpec
import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreAkkaEvents
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.concurrent.Eventually

import scala.language.postfixOps

class ActorCreationFailureSpec
  extends ActorSpec
  with BasicSpec
  with Eventually {

  lazy val mockUnsupervised = system.actorOf(MockActorCreationFailure.props(appConfig))

  "Unsupervised actor" - {
    "when throws an unhandled exception during actor creation" - {
      "should be stopped" taggedAs UNSAFE_IgnoreAkkaEvents in {   //UNSAFE_IgnoreAkkaEvents is to ignore the unhandled Ping message error message
        EventFilter.error(pattern = "purposefully throwing exception", occurrences = 1) intercept {
          mockUnsupervised ! Ping(sendBackConfirmation = true)
          expectNoMessage()
        }
      }
    }
  }

  override def overrideConfig: Option[Config] = Option { ConfigFactory.parseString (
    """
      akka.test.filter-leeway = 15s   # to make the event filter run for little longer time
      """
  )}
}



