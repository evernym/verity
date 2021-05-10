package com.evernym.verity.integration.base.sdk_provider

import akka.http.scaladsl.model.{HttpResponse, StatusCode}
import akka.http.scaladsl.model.StatusCodes.OK
import com.evernym.verity.Status
import com.evernym.verity.actor.Platform
import com.evernym.verity.actor.agent.DidPair
import com.evernym.verity.actor.agent.MsgPackFormat.MPF_INDY_PACK
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil.MSG_TYPE_DETAIL_GET_MSGS
import com.evernym.verity.agentmsg.msgfamily.pairwise.{CreateKeyReqMsg_MFV_0_6, GetMsgsReqMsg_MFV_0_6, GetMsgsRespMsg_MFV_0_6, KeyCreatedRespMsg_MFV_0_6}
import com.evernym.verity.agentmsg.msgpacker.{AgentMsgPackagingUtil, AgentMsgTransformer}
import com.evernym.verity.constants.Constants.NO
import com.evernym.verity.protocol.engine.{DIDDoc, MsgFamily, ThreadId}
import com.evernym.verity.protocol.protocols.agentprovisioning.v_0_7.AgentProvisioningMsgFamily.{AgentCreated, CreateCloudAgent, RequesterKeys}
import com.evernym.verity.protocol.protocols.connecting.v_0_6.ConnectingMsgFamily
import com.evernym.verity.protocol.protocols.connections.v_1_0.{ConnectionsMsgFamily, Msg}
import com.evernym.verity.protocol.protocols.connections.v_1_0.Msg.{ConnRequest, ConnResponse, Connection}
import com.evernym.verity.protocol.protocols.relationship.v_1_0.Signal.Invitation
import com.evernym.verity.util.Util
import com.evernym.verity.vault.KeyParam

import java.util.UUID
import scala.reflect.ClassTag

/**
 * contains helper methods for holder sdk side of the operations
 *
 * @param myVerityPlatform cloud agent provider platform
 */
case class HolderSdk(myVerityPlatform: Platform) extends SdkBase(myVerityPlatform) {

  def provisionVerityCloudAgent(): AgentCreated = {
    val reqKeys = RequesterKeys(localAgentDidPair.DID, localAgentDidPair.verKey)
    provisionVerityAgentBase(CreateCloudAgent(reqKeys, None))
  }

  def sendCreateNewKey(connId: String): PairwiseRel = {
    val myPairwiseKey = createNewKey()
    val createKey = CreateKeyReqMsg_MFV_0_6(myPairwiseKey.DID, myPairwiseKey.verKey)
    val createKeyJson = createJsonString(createKey, ConnectingMsgFamily)
    val routedPackedMsg = packForMyVerityAgent(createKeyJson)
    val receivedMsg = parseAndUnpackResponse[KeyCreatedRespMsg_MFV_0_6](checkOKResponse(sendPOST(routedPackedMsg)))
    val createdMsg = receivedMsg.msg
    val verityAgentDIDPair = DidPair(createdMsg.withPairwiseDID, createdMsg.withPairwiseDIDVerKey)
    storeTheirKey(verityAgentDIDPair)
    val pairwiseRel = PairwiseRel(Option(myPairwiseKey), Option(verityAgentDIDPair))
    myPairwiseRelationships += (connId -> pairwiseRel)
    pairwiseRel
  }

  def sendConnReqForUnacceptedInvitation(connId: String, invitation: Invitation): Unit = {
    val (httpResp, threadId) = sendConnReqBase(connId, invitation)
    checkOKResponse(httpResp)
    val receivedMsg = expectMsgOnConn[ConnResponse](
      connId,
      statusCodes = Option(List(Status.MSG_STATUS_RECEIVED.statusCode)))
    //TODO: verify the connection response signature
    updateTheirDidDoc(connId, receivedMsg.msg)

    sendConnRespReceivedAck(connId, threadId)
  }

  def sendConnReqForAcceptedInvitation(connId: String, invitation: Invitation): HttpResponse = {
    sendConnReqBase(connId, invitation)._1
  }

  private def sendConnReqBase(connId: String, invitation: Invitation): (HttpResponse, ThreadId) = {
    val threadId = UUID.randomUUID().toString
    val updatedPairwiseRel = updateTheirDidDoc(connId, invitation)
    val connReq = ConnRequest(label = connId, createConnectionObject(updatedPairwiseRel))
    val connReqJson = withThreadIdAdded(createJsonString(connReq, ConnectionsMsgFamily), threadId)
    val packedMsg = packForTheirVerityAgent(connId, connReqJson, "conn-req")
    val httpResp = sendBinaryReqToUrl(packedMsg, updatedPairwiseRel.theirServiceEndpoint)
    (httpResp, threadId)
  }

  private def sendConnRespReceivedAck(connId: String, threadId: ThreadId): Unit = {
    val ack = Msg.Ack(status = true)
    sendProtoMsgToTheirAgent(connId, ack, Option(threadId))
  }

  def sendProtoMsgToTheirAgent(connId: String,
                                       msg: Any,
                                       threadIdOpt: Option[ThreadId] = None,
                                       expectedRespStatus: StatusCode = OK): Unit = {
    val msgFamily = getMsgFamily(msg)
    val threadId = threadIdOpt.getOrElse(UUID.randomUUID().toString)
    val myPairwiseRel = myPairwiseRelationships(connId)
    val msgType = msgFamily.msgType(msg.getClass)
    val msgJson = withThreadIdAdded(createJsonString(msg, msgFamily), threadId)
    val packedMsg = packForTheirVerityAgent(connId: String, msgJson, msgType.msgName)
    checkResponse(sendBinaryReqToUrl(packedMsg, myPairwiseRel.theirServiceEndpoint), expectedRespStatus)
  }

  //----------------------

  private def packForTheirVerityAgent(connId: String,
                                      msg: String,
                                      fwdMsgType: String): Array[Byte] = {
    val pairwiseRel = myPairwiseRelationships(connId)
    val packedMsg = packMsg(
      msg,
      Set(KeyParam.fromVerKey(pairwiseRel.theirAgentVerKey)),
      Option(KeyParam.fromVerKey(pairwiseRel.myPairwiseVerKey))
    )
    val routingKeys = AgentMsgPackagingUtil.buildRoutingKeys(pairwiseRel.theirAgentVerKey, pairwiseRel.theirRoutingKeys)
    val future = AgentMsgPackagingUtil.packMsgForRoutingKeys(
      MPF_INDY_PACK,
      packedMsg,
      routingKeys,
      fwdMsgType
    )(new AgentMsgTransformer(testWalletAPI), walletAPIParam)
    awaitFut(future).msg
  }

  private def updateTheirDidDoc(connId: String, invitation: Invitation): PairwiseRel = {
    val myPairwiseRel = myPairwiseRelationships(connId)
    val updatedPairwiseRel = myPairwiseRel.withProvisionalTheirDidDoc(invitation)
    myPairwiseRelationships += (connId -> updatedPairwiseRel)
    updatedPairwiseRel
  }

  private def updateTheirDidDoc(connId: String, connResp: ConnResponse): PairwiseRel = {
    val myPairwiseRel = myPairwiseRelationships(connId)
    val updatedPairwiseRel = myPairwiseRel.withFinalTheirDidDoc(connResp)
    myPairwiseRelationships += (connId -> updatedPairwiseRel)
    updatedPairwiseRel
  }

  private def createConnectionObject(myPairwiseRel: PairwiseRel): Connection = {
    Connection(
      myPairwiseRel.myPairwiseDID,
      DIDDoc(
        myPairwiseRel.myPairwiseDID,
        myPairwiseRel.myPairwiseVerKey,
        Util.buildAgencyEndpoint(myVerityPlatform.appConfig).url,
        Vector(myPairwiseRel.myVerityAgentVerKey, agencyVerKey)
      ).toDIDDocFormatted
    )
  }

  //this function/logic will only work for protocol messages (and not for legacy message types)
  def expectMsgOnConn[T: ClassTag](connId: String,
                                   excludePayload: Option[String] = Option(NO),
                                   statusCodes: Option[List[String]] = Option(List(Status.MSG_STATUS_RECEIVED.statusCode)),
                                   tryCount: Int = 1): ReceivedMsgParam[T] = {
    val msgType = buildMsgTypeStr
    expectMsgOnConn(connId, msgType, excludePayload, statusCodes, tryCount)
  }

  def expectMsgOnConn[T: ClassTag](connId: String,
                                   msgTypeStr: String,
                                   excludePayload: Option[String],
                                   statusCodes: Option[List[String]],
                                   tryCount: Int): ReceivedMsgParam[T] = {
    val getMsgs = GetMsgsReqMsg_MFV_0_6(excludePayload = excludePayload, statusCodes = statusCodes)
    val getMsgsJson = createJsonString(MSG_TYPE_DETAIL_GET_MSGS, getMsgs)
    val routedPackedMsg = packForMyPairwiseRel(connId, getMsgsJson)
    val result = parseAndUnpackResponse[GetMsgsRespMsg_MFV_0_6](checkOKResponse(sendPOST(routedPackedMsg))).msg.msgs
    val msg = result.find(m => m.`type` == msgTypeStr && statusCodes.forall(scs => scs.contains(m.statusCode)))
    msg match {
      case Some(m) if excludePayload.contains(NO) && m.payload.isDefined =>
        unpackMsg(m.payload.get)
      case Some(m) if excludePayload.contains(NO) =>
        throw new RuntimeException("expected message found without payload: " + m)
      case None if tryCount < 5 =>
        Thread.sleep(tryCount*1000)
        expectMsgOnConn(connId, excludePayload, statusCodes, tryCount+1)
      case None => throw new RuntimeException("expected message not found: ")
    }
  }

  private def buildMsgTypeStr[T: ClassTag]: String = {
    val clazz = implicitly[ClassTag[T]].runtimeClass
    val msgType = getMsgFamilyOpt.map(_.msgType(clazz))
    msgType.map(MsgFamily.typeStrFromMsgType)
      .getOrElse(throw new RuntimeException("message type not found in any registered protocol: " + clazz.getClass.getSimpleName))
  }

  private def packForMyPairwiseRel(connId: String, msg: String): Array[Byte] = {
    val pairwiseRel = myPairwiseRelationships(connId)
    val verityAgentPackedMsg = packFromMyPairwiseKey(connId, msg, Set(KeyParam.fromVerKey(pairwiseRel.myVerityAgentVerKey)))
    prepareFwdMsg(agencyDID, pairwiseRel.myPairwiseDID, verityAgentPackedMsg)
  }

  private def packFromMyPairwiseKey(connId: String, msg: String, recipVerKeyParams: Set[KeyParam]): Array[Byte] = {
    val relationship = myPairwiseRelationships(connId)
    packMsg(msg, recipVerKeyParams, Option(KeyParam.fromVerKey(relationship.myPairwiseVerKey)))
  }
}