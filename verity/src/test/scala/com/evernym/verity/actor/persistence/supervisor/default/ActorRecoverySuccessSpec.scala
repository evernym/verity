package com.evernym.verity.actor.persistence.supervisor.default

import akka.actor.Props
import com.evernym.verity.actor.persistence.supervisor.MockActorRecoverySuccess
import com.evernym.verity.actor.{ForIdentifier, ShardUtil}
import com.evernym.verity.actor.persistence.{ActorDetail, BasePersistentActor, DefaultPersistenceEncryption, GetActorDetail}
import com.evernym.verity.actor.testkit.ActorSpec
import com.evernym.verity.config.AppConfig
import com.evernym.verity.testkit.BasicSpec
import org.scalatest.concurrent.Eventually

import scala.language.postfixOps


class ActorRecoverySuccessSpec
  extends ActorSpec
  with BasicSpec
  with Eventually
  with ShardUtil {

  lazy val mockUnsupervised = createPersistentRegion("MockActor", MockActorRecoverySuccess.props(appConfig))

  "Unsupervised actor" - {
    "when asked for actor detail" - {
      "should respond with expected detail" in {
        mockUnsupervised ! ForIdentifier("1", GetActorDetail)
        val ad = expectMsgType[ActorDetail]
        ad.persistenceId shouldBe "MockActor-1"
      }
    }
  }

}


