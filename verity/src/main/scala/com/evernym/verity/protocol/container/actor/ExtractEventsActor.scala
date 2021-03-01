package com.evernym.verity.protocol.container.actor

import akka.actor.{ActorRef, Props}
import com.evernym.verity.actor.base.Stop
import com.evernym.verity.actor.persistence.{BasePersistentActor, DefaultPersistenceEncryption}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.protocol.engine.PinstId

import scala.concurrent.Future

case class ExtractedEvent(event: Any)
case class ExtractionComplete()

class ExtractEventsActor(override val appConfig: AppConfig,
                         val actorTypeName: String,
                         val pinstId: PinstId,
                         val dest: ActorRef)
  extends BasePersistentActor
  with DefaultPersistenceEncryption {

  override lazy val entityType: String = actorTypeName
  override lazy val entityId: String = pinstId

  override def receiveCmd: Receive = {
    case _ => throw new IllegalAccessException(s"${this.getClass.getSimpleName} actor can not receive messages")
  }

  override def receiveEvent: Receive =  {
    case event => dest ! ProtocolCmd(ExtractedEvent(event), None)
  }


  override def postActorRecoveryCompleted(): Future[Any] = {
    dest ! ProtocolCmd(ExtractionComplete(), None)
    self ! Stop()
    Future.successful("Done")
  }

  //We don't want this read only actor to write/persist any state/event
  // hence override these functions to throw exception if at all accidentally used by this actor
  override def writeAndApply(evt: Any): Unit =
    throw new IllegalAccessException(s"${this.getClass.getSimpleName} actor can not write events")
  override def writeWithoutApply(evt: Any): Unit =
    throw new IllegalAccessException(s"${this.getClass.getSimpleName} actor can not write events")
  override def asyncWriteAndApply(evt: Any): Unit =
    throw new IllegalAccessException(s"${this.getClass.getSimpleName} actor can not write events")
  override def asyncWriteWithoutApply(evt: Any): Unit =
    throw new IllegalAccessException(s"${this.getClass.getSimpleName} actor can not write events")
}

object ExtractEventsActor {
  def prop(appConfig: AppConfig,
           actorTypeName: String,
           pinstId: PinstId,
           dest: ActorRef
          ): Props =
    Props(new ExtractEventsActor(appConfig, actorTypeName, pinstId, dest))
}