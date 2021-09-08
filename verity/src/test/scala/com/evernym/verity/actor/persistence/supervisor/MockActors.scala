package com.evernym.verity.actor.persistence.supervisor

import akka.actor.Props
import akka.persistence.AtomicWrite
import com.evernym.verity.actor.persistence.{BasePersistentActor, DefaultPersistenceEncryption, SupervisorUtil}
import com.evernym.verity.actor.{ActorMessage, KeyCreated, TestJournal}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.ConfigConstants.PERSISTENT_ACTOR_BASE

import scala.collection.immutable
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

object MockActorCreationFailure extends PropsProvider {
  def props(appConfig: AppConfig, executionContext: ExecutionContext): Props =
    Props(new MockActorCreationFailure(appConfig, executionContext))
}

class MockActorCreationFailure(val appConfig: AppConfig, ec: ExecutionContext)
  extends BasePersistentActor
    with DefaultPersistenceEncryption {

  override def receiveCmd: Receive = {
    case "unhandled" => //nothing to do
  }

  override def receiveEvent: Receive = ???

  //to mimic failure during actor creation
  throw new RuntimeException("purposefully throwing exception during construction of Actor")

  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = ec
}

//-------------------------

object MockActorRecoveryFailure extends PropsProvider {
  def props(appConfig: AppConfig, executionContext: ExecutionContext): Props =
    Props(new MockActorRecoveryFailure(appConfig, executionContext))
}

class MockActorRecoveryFailure(val appConfig: AppConfig, ec: ExecutionContext)
  extends BasePersistentActor
    with DefaultPersistenceEncryption {

  lazy val exceptionSleepTimeInMillis = appConfig.getIntOption("akka.mock.actor.exceptionSleepTimeInMillis").getOrElse(0)

  override def receiveCmd: Receive = {
    case GenerateRecoveryFailure => //nothing to do
  }

  override def receiveEvent: Receive = ???

  override def postActorRecoveryCompleted(): Future[Any] = {
    //to control the exception throw flow to be able to accurately test occurrences of failures
    if (exceptionSleepTimeInMillis > 0)
      Thread.sleep(exceptionSleepTimeInMillis)
    throw new RuntimeException("purposefully throwing exception post recovery completed")
  }

  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = ec
}

case object GenerateRecoveryFailure extends ActorMessage


//-------------------------
object MockActorRecoverySuccess extends PropsProvider {
  def props(appConfig: AppConfig, executionContext: ExecutionContext): Props =
    Props(new MockActorRecoverySuccess(appConfig, executionContext))
}

class MockActorRecoverySuccess(val appConfig: AppConfig, ec: ExecutionContext)
  extends BasePersistentActor
    with DefaultPersistenceEncryption {

  override def receiveCmd: Receive = {
    case "unhandled" => //nothing to do
  }

  override def receiveEvent: Receive = ???

  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = ec
}

//-------------------------

object MockActorMsgHandlerFailure extends PropsProvider {
  def props(appConfig: AppConfig, executionContext: ExecutionContext): Props =
    Props(new MockActorMsgHandlerFailure(appConfig, executionContext))
}

class MockActorMsgHandlerFailure(val appConfig: AppConfig, ec: ExecutionContext)
  extends BasePersistentActor
    with DefaultPersistenceEncryption {

  override def receiveCmd: Receive = {
    case ThrowException => throw new RuntimeException("purposefully throwing exception when processing message")
  }

  override def receiveEvent: Receive = ???

  supervisorStrategy

  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = ec
}

case object ThrowException extends ActorMessage


//-------------------------

object MockActorPersistenceFailure extends PropsProvider {
  def props(appConfig: AppConfig, executionContext: ExecutionContext): Props =
    Props(new MockActorPersistenceFailure(appConfig, executionContext))
}

class MockActorPersistenceFailure(val appConfig: AppConfig, executionContext: ExecutionContext)
  extends BasePersistentActor
    with DefaultPersistenceEncryption {

  override def receiveCmd: Receive = {
    case GeneratePersistenceFailure =>
      writeAndApply(KeyCreated("123"))
  }

  override def receiveEvent: Receive = {
    case _ => //nothing to do
  }

  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = executionContext
}

case object GeneratePersistenceFailure extends ActorMessage

class GeneratePersistenceFailureJournal extends TestJournal {

  override def asyncWriteMessages(messages: immutable.Seq[AtomicWrite]):
  Future[immutable.Seq[Try[Unit]]] = {
    Future.failed(new RuntimeException("purposefully throwing exception during persistence write"))
  }
}


trait PropsProvider {
  def props(appConfig: AppConfig, executionContext: ExecutionContext): Props

  def backOffOnStopProps(appConfig: AppConfig, executionContext: ExecutionContext): Props = {
    val defaultProps = props(appConfig, executionContext)
    SupervisorUtil
      .onStopSupervisorProps(
        appConfig,
        PERSISTENT_ACTOR_BASE,
        "MockSupervisor",
        defaultProps)
      .getOrElse(defaultProps)
  }

  def backOffOnFailureProps(appConfig: AppConfig, executionContext: ExecutionContext): Props = {
    val defaultProps = props(appConfig, executionContext)
    SupervisorUtil
      .onFailureSupervisorProps(
        appConfig,
        PERSISTENT_ACTOR_BASE,
        "MockSupervisor",
        defaultProps)
      .getOrElse(
        defaultProps
      )
  }
}