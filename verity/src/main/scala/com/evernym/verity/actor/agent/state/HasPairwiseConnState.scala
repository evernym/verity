package com.evernym.verity.actor.agent.state

import akka.actor.Actor.Receive
import com.evernym.verity.Exceptions.InvalidValueException
import com.evernym.verity.Status.MSG_STATUS_ACCEPTED
import com.evernym.verity.actor.agent.msghandler.outgoing.PayloadMetadata
import com.evernym.verity.actor.agent.MsgPackVersion.MPV_INDY_PACK
import com.evernym.verity.actor.agent.relationship.RelationshipTypeEnum.PAIRWISE_RELATIONSHIP
import com.evernym.verity.actor.agent.relationship._
import com.evernym.verity.actor.agent.state.base.{AgentStatePairwiseInterface, LegacyAgentPairwiseStateUpdateImpl}
import com.evernym.verity.actor.agent.{ConnectionStatus, EncryptionParamBuilder, MsgPackVersion, WalletVerKeyCacheHelper}
import com.evernym.verity.actor.{ConnectionCompleted, ConnectionStatusUpdated, TheirDidDocDetail, TheirProvisionalDidDocDetail}
import com.evernym.verity.agentmsg.msgpacker._
import com.evernym.verity.constants.Constants.GET_AGENCY_VER_KEY_FROM_POOL
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.protocols.connecting.common.{LegacyRoutingDetail, RoutingDetail, TheirRoutingParam}
import com.evernym.verity.vault.{EncryptParam, KeyInfo, SealParam, WalletAccessParam}

import scala.util.Left

/**
 * base class for handling a pairwise connection related functions
 * for example: updating connection status, their did doc etc
 */
trait PairwiseConnStateBase {

  type StateType <: AgentStatePairwiseInterface
  def state: StateType

  implicit def relationshipUtilParam: RelUtilParam
  def ownerDIDReq: DID

  def relationshipState: Relationship = state.relationshipReq

  def updateRelationshipBase(rel: Relationship): Unit =
    if (rel.relationshipType != PAIRWISE_RELATIONSHIP) {
      throw new IllegalArgumentException("Can not update to a non-pairwise relationship")
    } else {
      updateRelationship(rel)
    }

  def updateRelationship(rel: Relationship): Unit

  def updateConnectionStatus(reqReceived: Boolean, answerStatusCode: String = MSG_STATUS_ACCEPTED.statusCode): Unit

  def updateLegacyRelationshipState(relScopeDID: DID, lrd: LegacyRoutingDetail): Unit = {
    val theirDidDoc = RelationshipUtil.prepareTheirDidDoc(relScopeDID, lrd.agentKeyDID, Option(Left(lrd)))
    updateRelAndConnection(theirDidDoc)
  }

  def updateRelationshipState(relScopeDID: DID, agentKeyDID: DID, rd: RoutingDetail): Unit = {
    val theirDidDoc = RelationshipUtil.prepareTheirDidDoc(relScopeDID, agentKeyDID, Option(Right(rd)))
    updateRelAndConnection(theirDidDoc)
  }

  private def updateRelAndConnection(updatedTheirDidDoc: DidDoc): Unit = {
    val updatedRel = relationshipState.update(_.thoseDidDocs := Seq(updatedTheirDidDoc))
    updateRelationshipBase(updatedRel)
    updateConnectionStatus(reqReceived = true, MSG_STATUS_ACCEPTED.statusCode)
  }

  def pairwiseConnReceiver: Receive = {
    case cc: ConnectionStatusUpdated =>
      (cc.theirDidDocDetail, cc.theirProvisionalDidDocDetail) match {
        case (Some(tdd: TheirDidDocDetail), None) =>
          val lrd = LegacyRoutingDetail(tdd.agencyDID, tdd.agentKeyDID, tdd.agentVerKey, tdd.agentKeyDlgProofSignature)
          updateLegacyRelationshipState(tdd.pairwiseDID, lrd)
        case (None, Some(pdd: TheirProvisionalDidDocDetail)) =>
          val rd = RoutingDetail(pdd.verKey, pdd.endpoint, pdd.routingKeys.toVector)
          updateRelationshipState(pdd.did, pdd.did, rd)
        case _ =>
          updateConnectionStatus(reqReceived = true)
      }

    case cc: ConnectionCompleted =>
      val lrd = LegacyRoutingDetail(
        cc.theirAgencyDID,
        cc.theirAgentDID,
        cc.theirAgentDIDVerKey,
        cc.theirAgentKeyDlgProofSignature)
      updateLegacyRelationshipState(cc.theirEdgeDID, lrd)
  }

  def wap: WalletAccessParam
  def walletVerKeyCacheHelper: WalletVerKeyCacheHelper
  def agentMsgTransformer: AgentMsgTransformer
  def encParamBuilder: EncryptionParamBuilder = new EncryptionParamBuilder(walletVerKeyCacheHelper)

  def isTheirAgentVerKey(key: VerKey): Boolean = state.theirAgentAuthKey.exists(_.verKeyOpt.contains(key))

  def isMyPairwiseVerKey(verKey: VerKey): Boolean = {
    val userPairwiseVerKey = walletVerKeyCacheHelper.getVerKeyReqViaCache(state.myDid_!)
    verKey == userPairwiseVerKey
  }

  /**
   * we support two types of routing, one is called legacy (based on connecting 0.5 and 0.6 protocols)
   * another one is the recent one (based on connections 1.0 protocol)
   * @return
   */
  def theirRoutingDetail: Option[Either[LegacyRoutingDetail, RoutingDetail]] = {
    state.theirDidDoc.flatMap(_.endpoints_!.filterByKeyIds(state.theirAgentKeyDIDReq).headOption).map(_.endpointADTX) map {
      case le: LegacyRoutingServiceEndpoint =>
        Left(LegacyRoutingDetail(le.agencyDID, le.agentKeyDID, le.agentVerKey, le.agentKeyDlgProofSignature))
      case e: RoutingServiceEndpoint        =>
        Right(RoutingDetail(state.theirAgentAuthKeyReq.verKey, e.value, e.routingKeys))
      case _                                =>
        throw new MatchError("unsupported endpoint matched")
    }
  }

  /**
   * an unique string to identify different routing targets,
   * internal to the agency msg delivery system (no role in actual agent messages)
   * @return
   */
  def theirRoutingTarget: String =
    state
      .theirDidDoc
      .flatMap(_.endpoints_!.filterByKeyIds(state.theirAgentKeyDIDReq).headOption)
      .map(_.endpointADTX) match {
    case Some(lrse: LegacyRoutingServiceEndpoint) => lrse.agencyDID
    case Some(rse: RoutingServiceEndpoint)        => rse.value
    case x                                        => throw new RuntimeException("unsupported condition while preparing their routing target: " + x)
  }

  /**
   * Constructs RoutingParam to be used in sending a message to appropriate routes
   * @return
   */
  def theirRoutingParam: TheirRoutingParam = theirRoutingDetail match {
    case Some(Left(lrd: LegacyRoutingDetail)) => TheirRoutingParam(Left(lrd.agencyDID))
    case Some(Right(rd: RoutingDetail))       => TheirRoutingParam(Right(rd.endpoint))
    case x                                    => throw new RuntimeException("unsupported condition while preparing routing param: " + x)
  }

  def encParamBasedOnMsgSender(senderVerKeyOpt: Option[VerKey]): EncryptParam = {
    senderVerKeyOpt match {
      case Some(verKey) =>
        if (isMyPairwiseVerKey(verKey))
          encParamBuilder
            .withRecipDID(ownerDIDReq)
            .withSenderVerKey(state.thisAgentVerKeyReq)
            .encryptParam
        else if (isTheirAgentVerKey(verKey))
          encParamBuilder
            .withRecipVerKey(state.theirAgentVerKeyReq)
            .withSenderVerKey(state.thisAgentVerKeyReq)
            .encryptParam
        else
          encParamBuilder
            .withRecipVerKey(verKey)
            .withSenderVerKey(state.thisAgentVerKeyReq)
            .encryptParam
      case None => throw new InvalidValueException(Option("no sender ver key found"))
    }
  }

  def buildReqMsgForTheirRoutingService(msgPackVersion: MsgPackVersion,
                                        agentMsgs: List[Any],
                                        wrapInBundledMsgs: Boolean,
                                        msgType: String
                                       ): PackedMsg = {
    theirRoutingDetail match {
      case Some(Left(_: LegacyRoutingDetail)) =>
        val encryptParam =
          encParamBuilder
            .withRecipVerKey(state.theirAgentVerKeyReq)
            .withSenderVerKey(state.thisAgentVerKeyReq)
            .encryptParam
        val packMsgParam = PackMsgParam(encryptParam, agentMsgs, wrapInBundledMsgs)
        val packedMsg = AgentMsgPackagingUtil.buildAgentMsg(msgPackVersion, packMsgParam)(agentMsgTransformer, wap)
        buildRoutedPackedMsgForTheirRoutingService(msgPackVersion, packedMsg.msg, msgType)
      case x => throw new RuntimeException("unsupported routing detail (for unpacked msg): " + x)
    }
  }

  def buildRoutedPackedMsgForTheirRoutingService(msgPackVersion: MsgPackVersion, packedMsg: Array[Byte], msgType: String): PackedMsg = {
    theirRoutingDetail match {
      case Some(Left(ld: LegacyRoutingDetail)) =>
        val theirAgencySealParam = SealParam(KeyInfo(Left(walletVerKeyCacheHelper.getVerKeyReqViaCache(
          ld.agencyDID, getKeyFromPool = GET_AGENCY_VER_KEY_FROM_POOL))))
        val fwdRouteForAgentPairwiseActor = FwdRouteMsg(ld.agentKeyDID, Left(theirAgencySealParam))
        AgentMsgPackagingUtil.buildRoutedAgentMsg(msgPackVersion, PackedMsg(packedMsg, Option(PayloadMetadata(msgType, msgPackVersion))),
          List(fwdRouteForAgentPairwiseActor))(agentMsgTransformer, wap)
      case Some(Right(rd: RoutingDetail)) =>
        val routingKeys = if (rd.routingKeys.nonEmpty) Vector(rd.verKey) ++ rd.routingKeys else rd.routingKeys
        AgentMsgPackagingUtil.packMsgForRoutingKeys(MPV_INDY_PACK, packedMsg, routingKeys, msgType)(agentMsgTransformer, wap)
      case x => throw new RuntimeException("unsupported routing detail (for packed msg): " + x)
    }
  }
}


/**
 * base class for handling a pairwise connection related functions
 * for example: updating connection status, their did doc etc
 */
trait LegacyPairwiseConnState extends PairwiseConnStateBase with LegacyAgentPairwiseStateUpdateImpl {

  def updateRelationship(rel: Relationship): Unit =
      state.updateRelationship(rel)

  def updateConnectionStatus(reqReceived: Boolean, answerStatusCode: String = MSG_STATUS_ACCEPTED.statusCode): Unit = {
    setConnectionStatus(ConnectionStatus(reqReceived, answerStatusCode))
  }

}

/**
 * base class for handling a pairwise connection related functions
 * for example: updating connection status, their did doc etc
 */
trait PairwiseConnState extends PairwiseConnStateBase