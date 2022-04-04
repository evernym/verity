package com.evernym.verity.actor.agent.msghandler.incoming

import java.util.UUID
import akka.actor.{ActorRef, Props}
import com.evernym.verity.actor.agent.msghandler.{AgentMsgHandler, AgentMsgProcessor, ProcessUntypedMsgV2, SendToProtocolActor, StateParam, UnhandledMsg}
import com.evernym.verity.actor.agent.msgrouter.InternalMsgRouteParam
import com.evernym.verity.actor.persistence.AgentPersistentActor
import com.evernym.verity.config.AgentAuthKeyUtil
import com.evernym.verity.protocol.engine._
import com.evernym.verity.util.ReqMsgContext
import com.evernym.verity.actor.agent.SponsorRel
import com.evernym.verity.actor.msg_tracer.progress_tracker.MsgEvent
import com.evernym.verity.actor.resourceusagethrottling.{COUNTERPARTY_ID_PREFIX, OWNER_ID_PREFIX, UserId}
import com.evernym.verity.did.VerKeyStr
import com.evernym.verity.did.didcomm.v1.messages.MsgType
import com.evernym.verity.observability.metrics.InternalSpan

import scala.concurrent.{ExecutionContext, Future}

trait AgentIncomingMsgHandler { this: AgentMsgHandler with AgentPersistentActor =>

  private implicit val executionContext: ExecutionContext = futureExecutionContext

  def agentIncomingCommonCmdReceiver[A]: Receive = {

    //edge agent -> agency routing service -> this actor
    // flow diagram: fwd + ctl + proto + legacy, step 6 -- Received by agent actor.
    case ppm: ProcessPackedMsg if isReadyToHandleIncomingMsg
                                          => sendToAgentMsgProcessor(ppm)

    //edge agent -> agency routing service -> this actor
    case prm: ProcessRestMsg              => sendToAgentMsgProcessor(prm)

    //edge agent -> agency routing service -> self rel actor (user agent actor) -> this actor (pairwise agent actor)
    case mfr: MsgForRelationship          => sendToAgentMsgProcessor(mfr)

    //agent-msg-processor-actor -> this actor
    case psm: ProcessSignalMsg            => handleSignalMsgFromDriver(psm)

    case stpa: SendToProtocolActor        => sendToAgentMsgProcessor(stpa)

    //agent-msg-processor-actor -> this actor
    case um: UnhandledMsg                 =>
      metricsWriter.runWithSpan(s"${um.amw.msgType}", "AgentIncomingMsgHandler", InternalSpan) {
        try {
          if (incomingMsgHandler(um.rmc).isDefinedAt(um.amw)) {
            recordInMsgEvent(um.rmc.id,
              MsgEvent(
                s"${um.rmc.id}",
                um.amw.headAgentMsg.msgFamilyDetail.msgType.toString
              ))
            incomingMsgHandler(um.rmc)(um.amw)
          } else {
            handleException(um.cause, sender())
            recordInMsgEvent(um.rmc.id, MsgEvent.withTypeAndDetail(
              um.amw.headAgentMsg.msgFamilyDetail.msgType.toString, s"FAILED: unhandled message (${um.cause.getMessage})"))
          }
        } catch protoExceptionHandler
      }
  }

  def handleSignalMsgFromDriver(psm: ProcessSignalMsg): Unit = {
    // flow diagram: SIG, step 5
    val sndr = sender()
    if (handleSignalMsgs.isDefinedAt(psm.smp)) {
      handleSignalMsgs(psm.smp)
        .recover({
          case e: Exception =>
            logger.error(s"An error occurred while handling signal msg(${psm.smp.signalMsg.getClass.getName})")
            throw e
        })
        .foreach { dmOpt =>
          dmOpt.foreach { dm =>
            dm.forRel match {
              case Some(rel) =>
                val tc = psm.threadContextDetail
                val msgForRel = MsgForRelationship(domainId, dm.msg, psm.threadId, selfParticipantId,
                  Option(tc.msgPackFormat), Option(tc.msgTypeFormat), None)
                agentActorContext.agentMsgRouter.execute(InternalMsgRouteParam(rel, msgForRel))
              case None =>
                agentActorContext.protocolRegistry.find(psm.protoRef).foreach { pd =>
                  sendToAgentMsgProcessor(ProcessUntypedMsgV2(dm.msg, pd.protoDef, psm.threadId), sndr)
                }
            }
          }
        }
    } else {
      throw new RuntimeException(s"[$persistenceId] msg sent by driver not handled by agent: " + psm.smp.signalMsg)
    }
  }

  def stateDetailsFor: Future[ProtoRef => PartialFunction[String, Parameter]]
  def sponsorRel: Option[SponsorRel] = None

  def userIdForResourceUsageTracking(senderVerKey: Option[VerKeyStr]): Option[UserId] = {
    val myDomainAuthedKeys = state.myAuthVerKeys ++ configuredAuthedKeys
    senderVerKey match {
      case Some(svk) =>
        if (myDomainAuthedKeys.contains(svk)) Option(domainId).map(OWNER_ID_PREFIX + _)
        else Some(COUNTERPARTY_ID_PREFIX + state.theirDid.getOrElse(svk))
      case None => None
    }
  }

  def sendToAgentMsgProcessor(cmd: Any): Unit = {
    val sndr = sender()
    stateDetailsFor.map { protoInitParams =>
      val param = StateParam(
        self,
        state.domainId,
        state.relationshipId,
        state.thisAgentAuthKeyReq,
        state.theirDidAuthKey,
        state.agentWalletIdReq,
        state.protoInstances,
        sponsorRel,
        protoInitParams,
        selfParticipantId,
        senderParticipantId,
        allowedUnAuthedMsgTypes,
        allAuthedKeys,
        userIdForResourceUsageTracking,
        trackingIdParam
      )
      val msgProcessor =
          context.actorOf(Props(new AgentMsgProcessor(
            agentActorContext.appConfig,
            agentActorContext.walletAPI,
            agentActorContext.agentMsgRouter,
            agentActorContext.protocolRegistry,
            param,
            agentActorContext.futureExecutionContext
          )), "amp-" + UUID.randomUUID().toString)
      msgProcessor.tell(cmd, sndr)
    }.recover {
      case e: RuntimeException =>
        handleException(e, sndr)
    }
  }

  /**
   * for those messages which is sent by controller/driver as a response to a signal message
   * specially if original message has yet to be responded synchronously
   * since we don't know which is the case, for now, sending these messages to original
   * agent message processor (so there is an possibility to fix this in future)
   *
   * @param cmd
   * @param agentMsgProcessor
   */
  def sendToAgentMsgProcessor(cmd: Any, agentMsgProcessor: ActorRef): Unit = {
    agentMsgProcessor.tell(cmd, sender())
  }

  /**
   * all/some agent actors (agency agent, agency agent pairwise, user agent and user agent pairwise)
   * do have legacy message handler logic written in those actors itself (non protocol message handlers).
   * this function will help in deciding if the incoming message is the legacy one which is handled
   * by those actors locally or they should be checked against installed/registered protocols to see if they handle it.
   * @param reqMsgContext request message context
   * @return
   */
  def incomingMsgHandler(implicit reqMsgContext: ReqMsgContext): PartialFunction[Any, Any] = Map.empty

  /**
   * handles signal messages sent from driver
   * and returns optional control message which would be then sent back
   * to the protocol instance which sent the signal
   * @return
   */
  def handleSignalMsgs: PartialFunction[SignalMsgParam, Future[Option[ControlMsg]]] = PartialFunction.empty


  //TODO: there is opportunity to tight below authorization related code
  // (there seems to be more variables than it may needed)

  /**
   * list of authorized msg sender ver keys (need to be implemented by individual agent actors)
   * @return
   */
  def authedMsgSenderVerKeys: Set[VerKeyStr]

  /**
   * list of message types which are allowed to be processed if sent by un authorized sender
   * this is required when inviter's cloud agent (eas) receives
   * invitation answer message (accepted, rejected, redirected etc) from unknown
   * (because till that moment, connection is not yet established) sender (cas cloud agent)
   * @return
   */
  def allowedUnAuthedMsgTypes: Set[MsgType] = Set.empty

  /**
   * reads configured authorized key for domainId (self rel id) belonging to this agent
   * @return
   */
  def configuredAuthedKeys: Set[VerKeyStr] = {
    AgentAuthKeyUtil.keysForSelfRelDID(agentActorContext.appConfig, domainId)
  }

  /**
   * combination of configured and other added authed keys
   */
  def allAuthedKeys: Set[VerKeyStr] = configuredAuthedKeys ++ authedMsgSenderVerKeys
}
