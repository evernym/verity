package com.evernym.verity.actor.agent.state

import akka.actor.Actor.Receive
import com.evernym.verity.util2.Exceptions.InvalidValueException
import com.evernym.verity.util2.Status.MSG_STATUS_ACCEPTED
import com.evernym.verity.util2.HasExecutionContextProvider
import com.evernym.verity.actor.agent.MsgPackFormat.MPF_INDY_PACK
import com.evernym.verity.actor.agent.relationship.RelationshipTypeEnum.PAIRWISE_RELATIONSHIP
import com.evernym.verity.actor.agent.relationship._
import com.evernym.verity.actor.agent.state.base.AgentStatePairwiseInterface
import com.evernym.verity.actor.agent.{EncryptionParamBuilder, MsgPackFormat}
import com.evernym.verity.actor.{ConnectionCompleted, ConnectionStatusUpdated, TheirDidDocDetail, TheirProvisionalDidDocDetail, TheirRoutingUpdated}
import com.evernym.verity.agentmsg.msgpacker._
import com.evernym.verity.constants.Constants.GET_AGENCY_VER_KEY_FROM_POOL
import com.evernym.verity.actor.agent.PayloadMetadata
import com.evernym.verity.actor.agent.relationship.Tags.{AGENT_KEY_TAG, EDGE_AGENT_KEY}
import com.evernym.verity.protocol.protocols.connecting.common.{LegacyRoutingDetail, RoutingDetail, TheirRoutingParam}
import com.evernym.verity.actor.wallet.PackedMsg
import com.evernym.verity.did.{DidStr, VerKeyStr}
import com.evernym.verity.observability.metrics.MetricsWriter
import com.evernym.verity.vault.{EncryptParam, KeyParam, SealParam, WalletAPIParam}

import scala.concurrent.{ExecutionContext, Future}

/**
 * base class for handling a pairwise connection related functions
 * for example: updating connection status, their did doc etc
 */
trait PairwiseConnStateBase
  extends HasExecutionContextProvider {
  private implicit def executionContext: ExecutionContext = futureExecutionContext

  type StateType <: AgentStatePairwiseInterface
  def state: StateType

  implicit def didDocBuilderParam: DidDocBuilderParam
  def ownerDIDReq: DidStr

  def relationshipState: Relationship = state.relationshipReq

  def updateRelationshipBase(rel: Relationship): Unit =
    if (rel.relationshipType != PAIRWISE_RELATIONSHIP) {
      throw new IllegalArgumentException("Can not update to a non-pairwise relationship")
    } else {
      updateRelationship(rel)
    }

  def updateRelationship(rel: Relationship): Unit

  def updateConnectionStatus(reqReceived: Boolean, answerStatusCode: String = MSG_STATUS_ACCEPTED.statusCode): Unit

  /**
   * used to update the relationship object with their DID doc information (with legacy routing details)
   * and also it updates connection status
   * @param relScopeDID
   * @param lrd
   */
  def updateLegacyRelationshipState(relScopeDID: DidStr,
                                    relScopeDIDVerKey: VerKeyStr,
                                    lrd: LegacyRoutingDetail): Unit = {
    val theirDidDoc =
      DidDocBuilder(futureExecutionContext)
        .withDid(relScopeDID)
        .withAuthKey(relScopeDID, relScopeDIDVerKey, Set(EDGE_AGENT_KEY))
        .withAuthKeyAndEndpointDetail(lrd.agentKeyDID, lrd.agentVerKey, Set(AGENT_KEY_TAG), Left(lrd))
        .didDoc
    updateRelAndConnection(theirDidDoc)
  }

  /**
   * used to update the relationship object with their DID doc information (with standard routing details)
   * and also it updates connection status
   * @param relScopeDID
   * @param rd
   */
  def updateRelationshipState(relScopeDID: DidStr,
                              relScopeDIDVerKey: VerKeyStr,
                              rd: RoutingDetail): Unit = {
    val theirDidDoc =
      DidDocBuilder(futureExecutionContext)
        .withDid(relScopeDID)
        .withAuthKeyAndEndpointDetail(relScopeDID, relScopeDIDVerKey, Set(AGENT_KEY_TAG), Right(rd))
        .didDoc
    updateRelAndConnection(theirDidDoc)
  }

  private def updateRelAndConnection(updatedTheirDidDoc: DidDoc): Unit = {
    val updatedRel = relationshipState.update(_.thoseDidDocs := Seq(updatedTheirDidDoc))
    updateRelationshipBase(updatedRel)
    updateConnectionStatus(reqReceived = true, MSG_STATUS_ACCEPTED.statusCode)
  }

  def pairwiseConnEventReceiver: Receive = {
    case cc: ConnectionStatusUpdated =>
      (cc.theirDidDocDetail, cc.theirProvisionalDidDocDetail) match {
        case (Some(tdd: TheirDidDocDetail), None) =>
          val lrd = LegacyRoutingDetail(tdd.agencyDID, tdd.agentKeyDID, tdd.agentVerKey, tdd.agentKeyDlgProofSignature)
          updateLegacyRelationshipState(tdd.pairwiseDID, tdd.pairwiseDIDVerKey, lrd)
          initTheirRoutingUpdateStatus(
            TheirRouting(
              tdd.agentKeyDID,
              tdd.agentVerKey,
              tdd.agencyDID
            )
          )
        case (None, Some(pdd: TheirProvisionalDidDocDetail)) =>
          val rd = RoutingDetail(pdd.verKey, pdd.endpoint, pdd.routingKeys.toVector)
          updateRelationshipState(pdd.did, pdd.verKey, rd)
        case _ =>
          updateConnectionStatus(reqReceived = true)
      }

      //legacy event
    case cc: ConnectionCompleted =>
      val lrd = LegacyRoutingDetail(
        cc.theirAgencyDID,
        cc.theirAgentDID,
        cc.theirAgentDIDVerKey,
        cc.theirAgentKeyDlgProofSignature)
      initTheirRoutingUpdateStatus(
        TheirRouting(
          cc.theirAgentDID,
          cc.theirAgentDIDVerKey,
          cc.theirAgencyDID
        )
      )
      updateLegacyRelationshipState(cc.theirEdgeDID, "", lrd)

      //below event is related to v1 to v2 migration only
    case tru: TheirRoutingUpdated =>
      val updatedDidDoc = state.relationship.flatMap(_.theirDidDoc.map { tdd =>
        val updatedEndpointSeq: Seq[EndpointADT] = tdd.endpoints_!.endpoints.map(_.endpointADTX).map {
          case lep: LegacyRoutingServiceEndpoint =>
            lep.copy(
              agencyDID = tru.agencyDID,
              agentKeyDID = tru.routingPairwiseDID,
              agentVerKey = tru.routingPairwiseVerKey,
              authKeyIds = Seq(tru.routingPairwiseDID)
            )
          case ep: RoutingServiceEndpoint => ep
          case _ => throw new MatchError("unsupported endpoint matched")
        }.map(EndpointADT.apply)
        val updatedEndpoints = Endpoints(updatedEndpointSeq)

        if (tdd.getAuthorizedKeys.keys.exists(_.keyId == tru.routingPairwiseDID)) {
          //migration use case
          val updatedKeys = Seq(AuthorizedKey(tru.routingPairwiseDID, tru.routingPairwiseVerKey, Set(EDGE_AGENT_KEY, AGENT_KEY_TAG)))
          tdd
            .update(_.endpoints := updatedEndpoints)
            .update(_.authorizedKeys := AuthorizedKeys(updatedKeys))
        } else {
          //migration undo use case
          val (otherKeys, toBeUpdated) =
          tdd
            .getAuthorizedKeys
            .keys
            .filter(_.keyId != tru.routingPairwiseDID)
            .partition(ak => ak.tags != Set(EDGE_AGENT_KEY, AGENT_KEY_TAG))
          val updatedKeys = toBeUpdated.map(_.copy(tags = Set(EDGE_AGENT_KEY)))
          val newKeys = Seq(AuthorizedKey(tru.routingPairwiseDID, tru.routingPairwiseVerKey, Set(AGENT_KEY_TAG)))
          val finalUpdatedAuthKeys = otherKeys ++ updatedKeys ++ newKeys
          tdd
            .update(_.authorizedKeys := AuthorizedKeys(finalUpdatedAuthKeys))
            .update(_.endpoints := updatedEndpoints)
        }
      })
      updateRelationshipBase(relationshipState.update(_.thoseDidDocs.setIfDefined(updatedDidDoc.map(Seq(_)))))
      updateTheirRoutingStatus(
        TheirRouting(
          tru.routingPairwiseDID,
          tru.routingPairwiseVerKey,
          tru.agencyDID
        )
      )
  }

  def wap: WalletAPIParam
  def agentMsgTransformer: AgentMsgTransformer
  def encParamBuilder: EncryptionParamBuilder = EncryptionParamBuilder()

  def isTheirAgentVerKey(key: VerKeyStr): Boolean =
    state.theirAgentAuthKey.exists(_.verKeyOpt.contains(key))

  def isMyPairwiseVerKey(verKey: VerKeyStr): Boolean =
    state.myDidAuthKey.exists(_.verKeyOpt.contains(verKey))

  /**
   * we support two types of routing, one is called legacy (based on connecting 0.5 and 0.6 protocols)
   * another one is the recent one (based on connections 1.0 protocol)
   * @return
   */
  def theirRoutingDetail: Option[Either[LegacyRoutingDetail, RoutingDetail]] = {
    state
      .theirDidDoc
      .flatMap(_.endpoints_!.filterByKeyIds(state.theirAgentKeyDIDReq).headOption)
      .map(_.endpointADTX)
      .map {
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

  def encParamBasedOnMsgSender(senderVerKeyOpt: Option[VerKeyStr]): EncryptParam = {
    val encBuilderWithRecip = senderVerKeyOpt match {
      case Some(verKey) if isMyPairwiseVerKey(verKey) => encParamBuilder.withRecipDID(ownerDIDReq)
      case Some(verKey) if isTheirAgentVerKey(verKey) => encParamBuilder.withRecipVerKey(state.theirAgentVerKeyReq)
      case Some(verKey)                               => encParamBuilder.withRecipVerKey(verKey)
      case None                                       => throw new InvalidValueException(Option("no sender ver key found"))
    }
    encBuilderWithRecip
      .withSenderVerKey(state.thisAgentVerKeyReq)
      .encryptParam
  }

  def buildReqMsgForTheirRoutingService(msgPackFormat: MsgPackFormat,
                                        agentMsgs: List[Any],
                                        wrapInBundledMsgs: Boolean,
                                        msgType: String,
                                        msgSender: Option[String],
                                        mw: MetricsWriter): Future[PackedMsg] = {
    theirRoutingDetail match {
      case Some(Left(_: LegacyRoutingDetail)) =>
        val encryptParam =
          encParamBuilder
            .withRecipVerKey(state.theirAgentVerKeyReq)
            .withSenderVerKey(state.thisAgentVerKeyReq)
            .encryptParam
        val packMsgParam = PackMsgParam(encryptParam, agentMsgs, wrapInBundledMsgs)
        AgentMsgPackagingUtil.buildAgentMsg(
          msgPackFormat,
          packMsgParam
        )(agentMsgTransformer, wap, mw).flatMap { pm =>
          buildRoutedPackedMsgForTheirRoutingService(msgPackFormat, pm.msg, msgType, msgSender, mw)
        }
      case x => throw new RuntimeException("unsupported routing detail (for unpacked msg): " + x)
    }
  }

  def buildRoutedPackedMsgForTheirRoutingService(msgPackFormat: MsgPackFormat,
                                                 packedMsg: Array[Byte],
                                                 msgType: String,
                                                 msgSender: Option[String],
                                                 mw: MetricsWriter):
  Future[PackedMsg] = {
    theirRoutingDetail match {
      case Some(Left(ld: LegacyRoutingDetail)) =>
        val theirAgencySealParam = SealParam(KeyParam.fromDID(ld.agencyDID, GET_AGENCY_VER_KEY_FROM_POOL))
        val fwdRouteForAgentPairwiseActor = FwdRouteMsg(ld.agentKeyDID, Left(theirAgencySealParam))
        AgentMsgPackagingUtil.buildRoutedAgentMsg(
          msgPackFormat,
          PackedMsg(packedMsg, Option(PayloadMetadata(msgType, msgPackFormat))),
          List(fwdRouteForAgentPairwiseActor)
        )(agentMsgTransformer, wap, mw, futureExecutionContext)
      case Some(Right(rd: RoutingDetail)) =>
        val routingKeys = AgentMsgPackagingUtil.buildRoutingKeys(rd.verKey, rd.routingKeys)
        AgentMsgPackagingUtil.packMsgForRoutingKeys(
          MPF_INDY_PACK,
          packedMsg,
          routingKeys,
          msgType,
          msgSender
        )(agentMsgTransformer, wap, mw, futureExecutionContext)
      case x => throw new RuntimeException("unsupported routing detail (for packed msg): " + x)
    }
  }

  def initTheirRoutingUpdateStatus(routing: TheirRouting): Unit = {
    theirRoutingStatus = TheirRoutingStatus(None, Option(routing))
  }

  private def updateTheirRoutingStatus(newRoute: TheirRouting): Unit = {
    theirRoutingStatus =
      (theirRoutingStatus.original, theirRoutingStatus.latest) match {

        //migrate the routing (to point to VAS)
        case (None,       Some(latest)) if latest != newRoute =>
          TheirRoutingStatus(Some(latest), Option(newRoute))

        //undo routing changes (to point back to EAS)
        case (Some(_), Some(latest)) if latest != newRoute =>
          theirRoutingStatus.copy(latest = Option(newRoute))

        case _  =>
          theirRoutingStatus
    }
  }

  var theirRoutingStatus: TheirRoutingStatus = TheirRoutingStatus(None, None)
}


/**
 * base class for handling a pairwise connection related functions
 * for example: updating connection status, their did doc etc
 */
trait PairwiseConnState extends PairwiseConnStateBase

case class TheirRoutingStatus(original: Option[TheirRouting],
                              latest: Option[TheirRouting]) {
  def isOrigAndLatestSame: Boolean = original == latest
}

case class TheirRouting(routingPairwiseDID: DidStr,
                        routingPairwiseVerKey: VerKeyStr,
                        agencyDID: DidStr)