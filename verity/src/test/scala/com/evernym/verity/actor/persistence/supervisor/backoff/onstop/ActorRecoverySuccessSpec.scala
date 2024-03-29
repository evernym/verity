package com.evernym.verity.actor.persistence.supervisor.backoff.onstop

import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.persistence.supervisor.MockActorRecoverySuccess
import com.evernym.verity.actor.persistence.{GetPersistentActorDetail, PersistentActorDetail}
import com.evernym.verity.actor.testkit.ActorSpec
import com.evernym.verity.actor.{ForIdentifier, ShardUtil}
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.concurrent.Eventually

import scala.language.postfixOps

//This test confirms that successful recovery works fine with any supervisor strategy
class ActorRecoverySuccessSpec
  extends ActorSpec
    with BasicSpec
    with Eventually
    with ShardUtil {

  lazy val mockSupervised = createPersistentRegion("MockActor", MockActorRecoverySuccess.props(appConfig, ecp.futureExecutionContext))

  "OnStop BackoffSupervised actor" - {
    "when asked for actor detail" - {
      "should respond with expected detail" in {
        mockSupervised.path
        mockSupervised ! ForIdentifier("1", GetPersistentActorDetail)
        val ad = expectMsgType[PersistentActorDetail]
        //this confirms that introducing supervisor actor shouldn't change 'persistenceId'
        // else that won't be backward compatible
        ad.persistenceId shouldBe "MockActor-1"
      }
    }
  }

  override def overrideConfig: Option[Config] = Option { ConfigFactory.parseString (
    """
       verity.persistent-actor.base.supervisor {
          enabled = true
          backoff {
            strategy = OnStop
            min-seconds = 3
            max-seconds = 20
            random-factor = 0
          }
      }
      """
  )}

  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  override def executionContextProvider: ExecutionContextProvider = ecp
}

