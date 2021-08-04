package com.evernym.verity.actor.agent.msghandler

import com.evernym.verity.constants.Constants.UNKNOWN_SENDER_PARTICIPANT_ID
import com.evernym.verity.actor._
import com.evernym.verity.actor.agent.msghandler.incoming.AgentIncomingMsgHandler
import com.evernym.verity.actor.agent.msghandler.outgoing.{AgentOutgoingMsgHandler, OutgoingMsgParam}
import com.evernym.verity.actor.agent._
import com.evernym.verity.did.didcomm.v1.Thread
import com.evernym.verity.actor.persistence.AgentPersistentActor
import com.evernym.verity.agentmsg.msgfamily.pairwise.MsgExtractor
import com.evernym.verity.did.{DidStr, VerKeyStr}
import com.evernym.verity.msg_tracer.resp_time_tracker.MsgRespTimeTracker
import com.evernym.verity.protocol.engine._
import com.evernym.verity.vault.KeyParam

import scala.util.Left

/**
 * handles incoming and outgoing messages
 */

trait AgentMsgHandler
  extends AgentCommon
    with ProtocolEngineExceptionHandler
    with AgentIncomingMsgHandler
    with AgentOutgoingMsgHandler
    with MsgRespTimeTracker
    with AgentStateCleanupHelper
    with HasLogger {

  this: AgentPersistentActor =>

  final override def receiveActorInitSpecificCmd: Receive =
    receiveAgentInitCmd orElse receiveAgentSpecificInitCmd

  def receiveAgentSpecificInitCmd: Receive

  override final def receiveCmd: Receive =
    agentCommonCmdReceiver orElse
      agentIncomingCommonCmdReceiver orElse
      agentOutgoingCommonCmdReceiver orElse
      receiveAgentCmd orElse
      cleanupCmdHandler orElse {

      case m: ActorMessage => try {
        //these are the untyped incoming messages:
        // a. for example get invite message sent by invite acceptor (connect.me)
        // b. control messages sent by agent actors (in response to a signal message handling)
        //      (search for 'sendUntypedMsgToProtocol' method in UserAgent.scala to see these messages)
        //      (few others are like GetMsgs, UpdateMsgExpirationTime etc)
        sendToAgentMsgProcessor(ProcessUntypedMsgV1(m, relationshipId, DEFAULT_THREAD_ID, UNKNOWN_SENDER_PARTICIPANT_ID))
      } catch protoExceptionHandler
  }

  def senderParticipantId(senderVerKey: Option[VerKeyStr]): ParticipantId
  def selfParticipantId: ParticipantId

  /**
   * key info belonging to "this" agent (edge/cloud)
   * @return
   */
  lazy val thisAgentKeyParam: KeyParam = KeyParam(Left(state.thisAgentVerKeyReq))
  lazy val msgExtractor: MsgExtractor = new MsgExtractor(thisAgentKeyParam, agentActorContext.walletAPI, futureExecutionContext)

  def receiveAgentEvent: Receive
  def receiveAgentCmd: Receive

  def relationshipId: Option[RelationshipId] = state.myDid
  def theirRelationshipId: Option[RelationshipId] = state.theirDid

  //NOTE: this tells if this actor is ready to handle incoming messages or not
  //this was only required so that agency agent doesn't start unpacking messages
  //before it's setup process is completed (meaning agency agent key is created and its endpoint is written to the ledger)
  def isReadyToHandleIncomingMsg: Boolean = true

  override final def receiveEvent: Receive =
    cleanupEventReceiver orElse
    agentCommonEventReceiver orElse
      receiveAgentEvent

  def storeOutgoingMsg(omp: OutgoingMsgParam,
                       msgId:MsgId,
                       msgName: MsgName,
                       senderDID: DidStr,
                       threadOpt: Option[Thread]): Unit = {
    logger.debug("default implementation of storeOutgoingMsg")
  }

  def sendMsgToMyDomain(omp: OutgoingMsgParam, msgId: MsgId, msgName: MsgName): Unit = {
    logger.debug("default implementation of sendMsgToMyDomain")
  }

  def sendMsgToTheirDomain(omp: OutgoingMsgParam,
                           msgId: MsgId,
                           msgName: MsgName,
                           thread: Option[Thread]=None): Unit = {
    logger.debug("default implementation of sendMsgToTheirDomain")
  }
}
