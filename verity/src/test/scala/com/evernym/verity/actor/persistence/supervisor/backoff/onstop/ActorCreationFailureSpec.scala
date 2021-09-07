package com.evernym.verity.actor.persistence.supervisor.backoff.onstop

import akka.testkit.EventFilter
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.base.Ping
import com.evernym.verity.actor.persistence.supervisor.MockActorCreationFailure
import com.evernym.verity.actor.testkit.ActorSpec
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.concurrent.Eventually

//This test will exercise the `Stop` strategy: https://github.com/akka/akka/blob/622d8af0ef9f685ee1e91b04177926ca938376ac/akka-actor/src/main/scala/akka/actor/FaultHandling.scala#L208

class ActorCreationFailureSpec
  extends ActorSpec
    with BasicSpec
    with Eventually {

  lazy val mockSupervised = system.actorOf(MockActorCreationFailure.backOffOnStopProps(appConfig, ecp.futureExecutionContext))

  override def expectDeadLetters: Boolean = true

  "OnStop BackoffSupervised actor" - {
    "when throws an unhandled exception" - {
      "should be stopped and started as per back off strategy" in {
        EventFilter.error(pattern = "purposefully throwing exception", occurrences = 3) intercept {
          mockSupervised ! Ping(sendAck = true)
          expectNoMessage()
        }
      }
    }
  }

  override def overrideConfig: Option[Config] = Option { ConfigFactory.parseString (
    """
       verity.persistent-actor.base.supervisor {
          enabled = true
          strategy = OnStop
          min-seconds = 1
          max-seconds = 2
          random-factor = 0
          max-nr-of-retries = 3
      }
      akka.test.filter-leeway = 25s   # to make the event filter run for 25 seconds
      """
  )}

  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  override def executionContextProvider: ExecutionContextProvider = ecp
}

