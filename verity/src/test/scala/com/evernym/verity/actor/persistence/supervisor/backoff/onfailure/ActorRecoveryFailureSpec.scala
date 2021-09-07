package com.evernym.verity.actor.persistence.supervisor.backoff.onfailure

import akka.pattern.BackoffSupervisor.{CurrentChild, GetCurrentChild}
import akka.testkit.EventFilter
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.persistence.supervisor.{GenerateRecoveryFailure, IgnoreSupervisorLogErrors, MockActorRecoveryFailure}
import com.evernym.verity.actor.testkit.ActorSpec
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.concurrent.Eventually

//This test will exercise the `Restart` strategy: https://github.com/akka/akka/blob/622d8af0ef9f685ee1e91b04177926ca938376ac/akka-actor/src/main/scala/akka/actor/FaultHandling.scala#L208
// with handling `Restart` strategy as this: https://github.com/akka/akka/blob/031886a7b32530228f34176cd41bba9d344f43bd/akka-actor/src/main/scala/akka/pattern/internal/BackoffOnRestartSupervisor.scala#L45

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

        //will test this Restart strategy: https://github.com/akka/akka/blob/622d8af0ef9f685ee1e91b04177926ca938376ac/akka-actor/src/main/scala/akka/actor/FaultHandling.scala#L211


        //4 from 'handleFailure' in 'akka.actor.FaultHandling' (the default handler)
        val expectedLogEntries = 3
        EventFilter.error(pattern = "purposefully throwing exception", occurrences = expectedLogEntries) intercept {
          mockSupervised ! GenerateRecoveryFailure
          expectNoMessage()
        }

        // Supervisor should stop restarting child
        mockSupervised ! GetCurrentChild
        expectMsgType[CurrentChild].ref shouldBe None
      }
    }
  }

  override def overrideConfig: Option[Config] = Option { ConfigFactory.parseString (
    """
       verity.persistent-actor.base.supervisor {
          enabled = true
          strategy = OnFailure
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


