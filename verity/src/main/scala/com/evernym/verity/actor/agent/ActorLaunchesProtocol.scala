package com.evernym.verity.actor.agent

import akka.actor.{Actor, ActorRef, ActorSystem}
import akka.pattern.ask
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor.ForIdentifier
import com.evernym.verity.actor.persistence.HasActorResponseTimeout
import com.evernym.verity.protocol.actor.{ActorDriverGenParam, _}
import com.evernym.verity.protocol.engine._

import scala.concurrent.Future


trait ActorLaunchesProtocol extends LaunchesProtocol {

  this: Actor with HasActorResponseTimeout with HasLogger =>

  def entityId: String
  def agentWalletIdReq: String

  override type ControllerProviderInputType = ActorDriverGenParam

  def agentActorContext: AgentActorContext
  protected implicit lazy val protocolRegistry: ProtocolRegistry[ControllerProviderInputType] = agentActorContext.protocolRegistry
  implicit val system: ActorSystem = context.system

  def stateDetailsFor: Future[PartialFunction[String, Parameter]]

  def handleInitProtocolReq(ipr: InitProtocolReq): Unit = {
    logger.debug(s"about to get values for init params:" + ipr.stateKeys)
    val sndr = sender()
    try {
      stateDetailsFor.map { paramMapper =>
        logger.debug(s"init params received")
        val parameters = ipr.stateKeys.map(paramMapper)
        sndr ! InitProtocol(domainId, parameters)
        logger.debug(s"init params sent")
      }.recover {
        case e: MatchError =>
          logger.error(s"init param not found: " + e.getMessage, e)
          throw e
        case e: RuntimeException =>
          logger.error(s"init params construction failed: " + e.getMessage, e)
          throw e
      }
    } catch {
      case e: RuntimeException =>
        logger.error(s"init params retrieval/preparation/sending failed: "  + e.getMessage, e)
        throw e
    }
  }

  private def buildProtocolActorCmd[A](pinstId: PinstId,
                                       threadContextDetail: ThreadContextDetail,
                                       msgEnvelope: MsgEnvelope[A]): Any = {
    val cmd = ProtocolCmd(msgEnvelope, ProtocolMetadata(threadContextDetail, agentWalletIdReq, self))
    ForIdentifier(pinstId, cmd)
  }

  def tellProtocol[A](pinstIdPair: PinstIdPair,
                      threadContextDetail: ThreadContextDetail,
                      msgEnvelope: MsgEnvelope[A],
                      sndr: ActorRef = sender()): Any = {
    // flow diagram: ctl + proto, step 15 -- Message given to protocol subsystem.
    val cmd = buildProtocolActorCmd(pinstIdPair.id, threadContextDetail, msgEnvelope)
    ActorProtocol(pinstIdPair.protoDef).region.tell(cmd, sndr)
  }

  def askProtocols[T,A](relationshipId: Option[RelationshipId],
                        threadId: ThreadId,
                        msgEnvelope: MsgEnvelope[A],
                        threadContextDetail: ThreadContextDetail): Option[Future[T]] = {
    pinstIdForMsg(msgEnvelope, relationshipId, threadId).map { x =>
      askProtocolDirectly(x.id, threadContextDetail, msgEnvelope)(x.protoDef)
    }.asInstanceOf[Option[Future[T]]] //TODO this seems really brittle!
  }

  def askProtocolDirectly[A](pinstId: PinstId,
                             threadContextDetail: ThreadContextDetail,
                             msgEnvelope: MsgEnvelope[A])(protoDef: ProtoDef): Future[Any] = {
    val cmd = buildProtocolActorCmd(pinstId, threadContextDetail, msgEnvelope)
    ActorProtocol(protoDef).region ? cmd
  }

}
