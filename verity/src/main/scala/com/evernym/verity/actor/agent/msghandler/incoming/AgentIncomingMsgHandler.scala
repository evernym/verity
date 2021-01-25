package com.evernym.verity.actor.agent.msghandler.incoming

import java.util.UUID

import akka.actor.{ActorRef, Props}
import com.evernym.verity.actor.agent.msghandler.{AgentMsgHandler, AgentMsgProcessor, UnhandledMsg, ProcessUntypedMsgV2, StateParam}
import com.evernym.verity.actor.agent.msgrouter.InternalMsgRouteParam
import com.evernym.verity.actor.msg_tracer.progress_tracker.TrackingParam
import com.evernym.verity.actor.persistence.AgentPersistentActor
import com.evernym.verity.config.AgentAuthKeyUtil
import com.evernym.verity.protocol.engine._
import com.evernym.verity.util.ReqMsgContext
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor.agent.SpanUtil.runWithInternalSpan
import com.evernym.verity.actor.agent.SponsorRel
import com.evernym.verity.actor.agent.relationship.RelationshipTypeEnum.{ANYWISE_RELATIONSHIP, PAIRWISE_RELATIONSHIP, SELF_RELATIONSHIP}

import scala.concurrent.Future

trait AgentIncomingMsgHandler { this: AgentMsgHandler with AgentPersistentActor =>

  def agentIncomingCommonCmdReceiver[A]: Receive = {

    //edge agent -> agency routing service -> this actor
    case ppm: ProcessPackedMsg if isReadyToHandleIncomingMsg
                                          => sendToAgentMsgProcessor(ppm)

    //edge agent -> agency routing service -> this actor
    case prm: ProcessRestMsg              => sendToAgentMsgProcessor(prm)

    //edge agent -> agency routing service -> self rel actor (user agent actor) -> this actor (pairwise agent actor)
    case mfr: MsgForRelationship          => sendToAgentMsgProcessor(mfr)

    //agent-msg-processor-actor -> this actor
    case mfd: SignalMsgFromDriver         => handleSignalMsgFromDriver(mfd)

    //agent-msg-processor-actor -> this actor
    case um: UnhandledMsg                 =>
      runWithInternalSpan(s"${um.amw.msgType}", "AgentIncomingMsgHandler") {
        try {
          if (incomingMsgHandler(um.rmc).isDefinedAt(um.amw))
            incomingMsgHandler(um.rmc)(um.amw)
          else
            handleException(um.cause, sender())
        } catch protoExceptionHandler
      }
  }

  def handleSignalMsgFromDriver(smfd: SignalMsgFromDriver): Unit = {
    // flow diagram: SIG, step 5
    val sndr = sender()
    if (handleSignalMsgs.isDefinedAt(smfd)) {
      handleSignalMsgs(smfd).foreach { dmOpt =>
        dmOpt.foreach { dm =>
          dm.forRel match {
            case Some(rel) =>
              val tc = smfd.threadContextDetail
              val msgForRel = MsgForRelationship(dm.msg, smfd.threadId, selfParticipantId,
                Option(tc.msgPackFormat), Option(tc.msgTypeFormat), None)
              agentActorContext.agentMsgRouter.execute(InternalMsgRouteParam(rel, msgForRel))
            case None =>
              agentActorContext.protocolRegistry.find(smfd.protoRef).foreach { pd =>
                sendToAgentMsgProcessor(ProcessUntypedMsgV2(dm.msg, pd.protoDef, smfd.threadId), sndr)
              }
          }
        }
      }
    } else {
      throw new RuntimeException(s"[$persistenceId] msg sent by driver not handled by agent: " + smfd.signalMsg)
    }
  }

  def stateDetailsFor: Future[PartialFunction[String, Parameter]]
  def sponsorRel: Option[SponsorRel] = None

  private def userIdForResourceUsageTracking(senderVerKey: Option[VerKey]): Option[String] =
    (state.relationship.map(_.relationshipType), senderVerKey) match {
      case (Some(ANYWISE_RELATIONSHIP), _)            => senderVerKey
      case (Some(SELF_RELATIONSHIP), _)               => Option(domainId)
      case (Some(PAIRWISE_RELATIONSHIP), Some(svk))   =>
        if (state.theirAuthVerKeys.contains(svk)) state.theirDid
        else Option(domainId)
      case _                                          => senderVerKey
    }

  def sendToAgentMsgProcessor(cmd: Any): Unit = {
    val sndr = sender()
    stateDetailsFor.map { protoInitParams =>
      val param = StateParam(
        self,
        state.domainId,
        state.relationshipId,
        state.thisAgentAuthKeyReq,
        state.agentWalletIdReq,
        state.protoInstances,
        sponsorRel,
        protoInitParams,
        selfParticipantId,
        senderParticipantId,
        allowedUnauthedMsgTypes,
        allAuthedKeys,
        userIdForResourceUsageTracking,
        trackingParam
      )
      val msgProcessor =
          context.actorOf(Props(new AgentMsgProcessor(
            agentActorContext.appConfig,
            agentActorContext.walletAPI,
            agentActorContext.agentMsgRouter,
            agentActorContext.protocolRegistry,
            param
          )), UUID.randomUUID().toString)
      msgProcessor.tell(cmd, sndr)
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

  lazy val trackingParam: TrackingParam = TrackingParam(Option(domainId), relationshipId)

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
  def handleSignalMsgs: PartialFunction[SignalMsgFromDriver, Future[Option[ControlMsg]]] = PartialFunction.empty


  //TODO: there is opportunity to tight below authorization related code
  // (there seems to be more variables than it may needed)

  /**
   * list of authorized msg sender ver keys (need to be implemented by individual agent actors)
   * @return
   */
  def authedMsgSenderVerKeys: Set[VerKey]

  /**
   * list of message types which are allowed to be processed if sent by un authorized sender
   * this is required when inviter's cloud agent (eas) receives
   * invitation answer message (accepted, rejected, redirected etc) from unknown
   * (because till that moment, connection is not yet established) sender (cas cloud agent)
   * @return
   */
  def allowedUnauthedMsgTypes: Set[MsgType] = Set.empty

  /**
   * reads configured authorized key for domainId (self rel id) belonging to this agent
   * @return
   */
  def configuredAuthedKeys: Set[VerKey] = {
    AgentAuthKeyUtil.keysForSelfRelDID(agentActorContext.appConfig, domainId)
  }

  /**
   * combination of configured and other added authed keys
   */
  def allAuthedKeys: Set[VerKey] = configuredAuthedKeys ++ authedMsgSenderVerKeys
}
