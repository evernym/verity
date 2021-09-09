package com.evernym.verity.actor.persistence.supervisor.backoff.onfailure

import akka.pattern.BackoffSupervisor.{CurrentChild, GetCurrentChild, GetRestartCount, RestartCount}
import akka.testkit.EventFilter
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.persistence.supervisor.{GenerateRecoveryFailure, IgnoreSupervisorLogErrors, MockActorRecoveryFailure}
import com.evernym.verity.actor.testkit.ActorSpec
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.concurrent.Eventually

//This test confirms that if any `RuntimeException` occurs during message handling
// it will be restarted as per default supervisor strategy
// and restarting will be controlled by Backoff.onFailure supervisor

class ActorRecoveryFailureSpec
  extends ActorSpec
  with BasicSpec
  with Eventually
  with IgnoreSupervisorLogErrors {

  override def expectDeadLetters: Boolean = true

  lazy val mockSupervised = system.actorOf(MockActorRecoveryFailure.backOffOnFailureProps(appConfig, ecp.futureExecutionContext))

  "OnFailure BackoffSupervised actor" - {
    "when throws an unhandled exception during recovery" - {
      "should stop and start (not exactly a restart) as per BACKOFF strategy" in {

        //3 errors from 'handleFailure' in 'akka.actor.FaultHandling' (the default handler)
        val expectedLogEntries = 3
        EventFilter.error(pattern = "purposefully throwing exception", occurrences = expectedLogEntries) intercept {
          mockSupervised ! GenerateRecoveryFailure
          expectNoMessage()
        }

        //because 'max-nr-of-retries' is defined as 3
        mockSupervised ! GetRestartCount
        expectMsgType[RestartCount].count shouldBe 3

        //supervisor should stop restarting child once crossed 'max-nr-of-retries'
        mockSupervised ! GetCurrentChild
        expectMsgType[CurrentChild].ref shouldBe None
      }
    }
  }

  override def overrideConfig: Option[Config] = Option { ConfigFactory.parseString (
    """
       verity.persistent-actor.base.supervisor {
          enabled = true
          backoff {
            strategy = OnFailure
            min-seconds = 1
            max-seconds = 2
            random-factor = 0
            max-nr-of-retries = 3
          }
      }
      akka.test.filter-leeway = 25s   # to make the event filter run for 25 seconds
      """
  )}

  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  override def executionContextProvider: ExecutionContextProvider = ecp
}


