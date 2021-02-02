package com.evernym.verity.actor.entityidentifier.base

import akka.actor.Props
import com.evernym.verity.actor.base.{ActorDetail, CoreActorExtended}
import com.evernym.verity.actor.persistence.{BasePersistentActor, PersistentActorDetail}
import com.evernym.verity.actor.testkit.ActorSpec
import com.evernym.verity.config.AppConfig
import com.evernym.verity.testkit.BasicSpec

trait EntityIdentifierBaseSpec
  extends ActorSpec
    with BasicSpec {

  def assertActorDetail(actualDetail: ActorDetail,
                        expectedDetail: ActorDetail): Unit = {
    actualDetail shouldBe expectedDetail
  }

  def assertPersistentActorDetail(actualDetail: PersistentActorDetail,
                                  expectedDetail: PersistentActorDetail): Unit = {
    actualDetail shouldBe expectedDetail
  }
}

class MockNonPersistentActor
  extends CoreActorExtended {

  override def receiveCmd: Receive = {
    case x => sender ! x
  }
}

object MockNonPersistentActor {
  def props: Props = Props(new MockNonPersistentActor)
}

class MockPersistentActor(val appConfig: AppConfig)
  extends BasePersistentActor {
  override def receiveEvent: Receive = PartialFunction.empty
  override def receiveCmd: Receive = PartialFunction.empty
  override def persistenceEncryptionKey: String = "mock"
}

object MockPersistentActor {
  def props(appConfig: AppConfig): Props = Props(new MockPersistentActor(appConfig))
}