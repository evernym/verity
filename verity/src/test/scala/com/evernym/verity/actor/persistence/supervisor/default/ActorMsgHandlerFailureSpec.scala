package com.evernym.verity.actor.persistence.supervisor.default

import akka.testkit.EventFilter
import com.evernym.verity.actor.persistence.supervisor.{MockActorMsgHandlerFailure, ThrowException}
import com.evernym.verity.actor.testkit.ActorSpec
import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreAkkaEvents
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.concurrent.Eventually


class ActorMsgHandlerFailureSpec
  extends ActorSpec
  with BasicSpec
  with Eventually {

  lazy val mockUnsupervised = system.actorOf(MockActorMsgHandlerFailure.props(appConfig))

  "Unsupervised actor" - {

    "when throws an unhandled exception during msg handling" - {
      //TODO: if we remove 'UNSAFE_IgnoreAkkaEvents', then it fails intermittently
      // need to find out why
      "should restart once" taggedAs UNSAFE_IgnoreAkkaEvents in {
        //TODO: test it restarts only once
        EventFilter.error(pattern = "purposefully throwing exception", occurrences = 1) intercept {
          mockUnsupervised ! ThrowException
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