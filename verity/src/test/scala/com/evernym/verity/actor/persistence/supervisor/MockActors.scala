package com.evernym.verity.actor.persistence.supervisor

import akka.actor.Props
import com.evernym.verity.actor.persistence.{BasePersistentActor, DefaultPersistenceEncryption}
import com.evernym.verity.actor.{ActorMessage, KeyCreated, TestJournal}
import com.evernym.verity.config.AppConfig

import scala.concurrent.Future

object MockActorCreationFailure {
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

  throw new RuntimeException("purposefully throwing exception")

}

//-------------------------

object MockActorRecoveryFailure {
  def props(appConfig: AppConfig, exceptionSleepTimeInMillis: Int = 0): Props =
    Props(new MockActorRecoveryFailure(appConfig, exceptionSleepTimeInMillis))
}

class MockActorRecoveryFailure(val appConfig: AppConfig, exceptionSleepTimeInMillis: Int = 0)
  extends BasePersistentActor
    with DefaultPersistenceEncryption {


  override def receiveCmd: Receive = {
    case GenerateRecoveryFailure => //nothing to do
  }

  override def receiveEvent: Receive = ???

  override def postActorRecoveryCompleted(): List[Future[Any]] = {
    Thread.sleep(exceptionSleepTimeInMillis) //to control the exception throw flow to be able to better test occurrences of failures
    throw new RuntimeException("purposefully throwing exception")
  }
}

case object GenerateRecoveryFailure extends ActorMessage


//-------------------------
object MockActorRecoverySuccess {
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

object MockActorMsgHandlerFailure {
  def props(appConfig: AppConfig): Props =
    Props(new MockActorMsgHandlerFailure(appConfig))
}

class MockActorMsgHandlerFailure(val appConfig: AppConfig)
  extends BasePersistentActor
    with DefaultPersistenceEncryption {

  override def receiveCmd: Receive = {
    case ThrowException => throw new RuntimeException("purposefully throwing exception")
  }

  override def receiveEvent: Receive = ???
}

case object ThrowException extends ActorMessage


//-------------------------

object MockActorPersistenceFailure {
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

  override def receiveEvent: Receive = ???
}

case object GeneratePersistenceFailure extends ActorMessage

class GeneratePersistenceFailureJournal extends TestJournal {

  override def asyncWriteMessages(messages: _root_.scala.collection.immutable.Seq[_root_.akka.persistence.AtomicWrite]):
  _root_.scala.concurrent.Future[_root_.scala.collection.immutable.Seq[_root_.scala.util.Try[Unit]]] = {
    Future.failed(new RuntimeException("purposefully throwing exception"))
  }
}
