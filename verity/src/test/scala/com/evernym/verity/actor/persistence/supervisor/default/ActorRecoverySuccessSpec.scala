package com.evernym.verity.actor.persistence.supervisor.default

import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.persistence.{GetPersistentActorDetail, PersistentActorDetail}
import com.evernym.verity.actor.persistence.supervisor.MockActorRecoverySuccess
import com.evernym.verity.actor.{ForIdentifier, ShardUtil}
import com.evernym.verity.actor.testkit.ActorSpec
import com.evernym.verity.testkit.BasicSpec
import org.scalatest.concurrent.Eventually

import scala.language.postfixOps


class ActorRecoverySuccessSpec
  extends ActorSpec
  with BasicSpec
  with Eventually
  with ShardUtil {

  lazy val mockUnsupervised = createPersistentRegion("MockActor", MockActorRecoverySuccess.props(appConfig, ecp.futureExecutionContext))

  "Unsupervised actor" - {
    "when asked for actor detail" - {
      "should respond with expected detail" in {
        mockUnsupervised ! ForIdentifier("1", GetPersistentActorDetail)
        val ad = expectMsgType[PersistentActorDetail]
        ad.persistenceId shouldBe "MockActor-1"
      }
    }
  }
  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  override def executionContextProvider: ExecutionContextProvider = ecp
}


