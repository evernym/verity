package com.evernym.verity.actor.agent

import akka.actor.{Actor, ActorRef, ActorSystem}
import com.evernym.verity.util2.HasExecutionContextProvider
import com.evernym.verity.actor.ForIdentifier
import com.evernym.verity.actor.persistence.HasActorResponseTimeout
import com.evernym.verity.protocol.container.actor.{ActorDriverGenParam, _}
import com.evernym.verity.protocol.engine._

import scala.concurrent.{ExecutionContext, Future}


trait ActorLaunchesProtocol
  extends LaunchesProtocol
  with HasExecutionContextProvider {

  this: Actor with HasActorResponseTimeout with HasLogger =>

  private implicit val executionContext: ExecutionContext = futureExecutionContext

  def entityId: String
  def agentWalletIdReq: String

  override type ControllerProviderInputType = ActorDriverGenParam

  def registeredProtocols: ProtocolRegistry[ActorDriverGenParam]
  protected implicit lazy val protocolRegistry: ProtocolRegistry[ControllerProviderInputType] = registeredProtocols
  implicit val system: ActorSystem = context.system

  def stateDetailsFor(protoRef: ProtoRef): Future[PartialFunction[String, Parameter]]

  def handleInitProtocolReq(ipr: InitProtocolReq, sponsorRel: Option[SponsorRel]): Unit = {
    logger.debug(s"about to get values for init params:" + ipr.stateKeys)
    val sndr = sender()
    try {
      stateDetailsFor(ipr.protoRef).map { paramMapper =>
        logger.debug(s"init params received")
        val parameters = ipr.stateKeys.map(paramMapper)
        sndr ! ProtocolCmd(InitProtocol(domainId, parameters, sponsorRel), None)
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

  def tellProtocol(pinstIdPair: PinstIdPair,
                   threadContextDetail: ThreadContextDetail,
                   msgEnvelope: MsgEnvelope,
                   sndr: ActorRef = sender()): Unit = {

    val cmd = ProtocolCmd(
      msgEnvelope,
      Some(ProtocolMetadata(self, agentWalletIdReq, threadContextDetail))
    )
    ActorProtocol(pinstIdPair.protoDef)
      .region
      .tell(
        ForIdentifier(pinstIdPair.id, cmd),
        sndr
      )
  }

  /*
  Intended for actor specific messages and NOT for protocol messages (control and/or protocol messages)
  Basically, non-enveloped messages.
   */
  def tellProtocolActor(pinstIdPair: PinstIdPair,
                        msgEnvelope: Any,
                        sndr: ActorRef = sender()): Unit = {
    val cmd = ProtocolCmd(
      msgEnvelope,
      None
    )
    ActorProtocol(pinstIdPair.protoDef)
      .region
      .tell(
        ForIdentifier(pinstIdPair.id, cmd),
        sndr
      )
  }
}
