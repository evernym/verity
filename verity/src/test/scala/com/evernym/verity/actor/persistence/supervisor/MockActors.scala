package com.evernym.verity.actor.persistence.supervisor

import akka.actor.Props
import akka.persistence.AtomicWrite
import com.evernym.verity.actor.persistence.{BasePersistentActor, DefaultPersistenceEncryption, SupervisorUtil}
import com.evernym.verity.actor.{ActorMessage, KeyCreated, TestJournal}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.CommonConfig.PERSISTENT_ACTOR_BASE

import scala.collection.immutable
import scala.concurrent.Future
import scala.util.Try

object MockActorCreationFailure extends PropsProvider {
  def props(appConfig: AppConfig): Props =
    Props(new MockActorCreationFailure(appConfig))
}

class MockActorCreationFailure(val appConfig: AppConfig)
  extends BasePersistentActor
    with DefaultPersistenceEncryption {

  override def receiveCmd: Receive = {
    case "unhandled" => //nothing to do
  }

  override def receiveEvent: Receive = ???

  throw new RuntimeException("purposefully throwing exception d")

}

//-------------------------

object MockActorRecoveryFailure extends PropsProvider {
  def props(appConfig: AppConfig): Props =
    Props(new MockActorRecoveryFailure(appConfig))
}

class MockActorRecoveryFailure(val appConfig: AppConfig)
  extends BasePersistentActor
    with DefaultPersistenceEncryption {

  lazy val exceptionSleepTimeInMillis = appConfig.getConfigIntOption("akka.mock.actor.exceptionSleepTimeInMillis").getOrElse(0)

  override def receiveCmd: Receive = {
    case GenerateRecoveryFailure => //nothing to do
  }

  override def receiveEvent: Receive = ???

  override def postActorRecoveryCompleted(): List[Future[Any]] = {
    //to control the exception throw flow to be able to accurately test occurrences of failures
    if (exceptionSleepTimeInMillis > 0)
      Thread.sleep(exceptionSleepTimeInMillis)
    throw new RuntimeException("purposefully throwing exception s")
  }
}

case object GenerateRecoveryFailure extends ActorMessage


//-------------------------
object MockActorRecoverySuccess extends PropsProvider {
  def props(appConfig: AppConfig): Props =
    Props(new MockActorRecoverySuccess(appConfig))
}

class MockActorRecoverySuccess(val appConfig: AppConfig)
  extends BasePersistentActor
    with DefaultPersistenceEncryption {

  override def receiveCmd: Receive = {
    case "unhandled" => //nothing to do
  }

  override def receiveEvent: Receive = ???
}

//-------------------------

object MockActorMsgHandlerFailure extends PropsProvider {
  def props(appConfig: AppConfig): Props =
    Props(new MockActorMsgHandlerFailure(appConfig))
}

class MockActorMsgHandlerFailure(val appConfig: AppConfig)
  extends BasePersistentActor
    with DefaultPersistenceEncryption {

  override def receiveCmd: Receive = {
    case ThrowException => throw new RuntimeException("purposefully throwing exception a")
  }

  override def receiveEvent: Receive = ???

  supervisorStrategy
}

case object ThrowException extends ActorMessage


//-------------------------

object MockActorPersistenceFailure extends PropsProvider {
  def props(appConfig: AppConfig): Props =
    Props(new MockActorPersistenceFailure(appConfig))
}

class MockActorPersistenceFailure(val appConfig: AppConfig)
  extends BasePersistentActor
    with DefaultPersistenceEncryption {

  override def receiveCmd: Receive = {
    case GeneratePersistenceFailure =>
      writeAndApply(KeyCreated("123"))
  }

  override def receiveEvent: Receive = {
    case _ => //nothing to do
  }
}

case object GeneratePersistenceFailure extends ActorMessage

class GeneratePersistenceFailureJournal extends TestJournal {

  override def asyncWriteMessages(messages: immutable.Seq[AtomicWrite]):
  Future[immutable.Seq[Try[Unit]]] = {
    Future.failed(new RuntimeException("purposefully throwing exception"))
  }
}


trait PropsProvider {
  def props(appConfig: AppConfig): Props

  def backOffOnStopProps(appConfig: AppConfig): Props =
    SupervisorUtil.onStopSupervisorProps(
      appConfig,
      PERSISTENT_ACTOR_BASE,
      "MockSupervisor",
      props(appConfig)).get

  def backOffOnFailureProps(appConfig: AppConfig): Props =
    SupervisorUtil.onFailureSupervisorProps(
      appConfig,
      PERSISTENT_ACTOR_BASE,
      "MockSupervisor",
      props(appConfig)).get
}