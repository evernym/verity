package com.evernym.verity.integration.base.sdk_provider

import akka.http.scaladsl.model.{HttpResponse, StatusCode}
import akka.http.scaladsl.model.StatusCodes.OK
import com.evernym.verity.Status
import com.evernym.verity.Status.StatusDetailException
import com.evernym.verity.actor.agent.DidPair
import com.evernym.verity.actor.agent.MsgPackFormat.MPF_INDY_PACK
import com.evernym.verity.actor.agent.{Thread => MsgThread}
import com.evernym.verity.actor.wallet.{CreateCredReq, CreateMasterSecret, CreateProof, CredForProofReq, CredForProofReqCreated, CredReqCreated, CredStored, MasterSecretCreated, ProofCreated, StoreCred}
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil.{MSG_TYPE_DETAIL_GET_MSGS, MSG_TYPE_DETAIL_GET_MSGS_BY_CONNS, MSG_TYPE_DETAIL_UPDATE_MSG_STATUS}
import com.evernym.verity.agentmsg.msgfamily.pairwise.{CreateKeyReqMsg_MFV_0_6, GetMsgsByConnsReqMsg_MFV_0_6, GetMsgsByConnsRespMsg_MFV_0_6, GetMsgsReqMsg_MFV_0_6, GetMsgsRespMsg_MFV_0_6, KeyCreatedRespMsg_MFV_0_6, MsgStatusUpdatedRespMsg_MFV_0_6, UpdateMsgStatusReqMsg_MFV_0_6}
import com.evernym.verity.agentmsg.msgpacker.{AgentMsgPackagingUtil, AgentMsgTransformer}
import com.evernym.verity.constants.Constants.NO
import com.evernym.verity.ledger.{GetCredDefResp, GetSchemaResp, LedgerTxnExecutor, Submitter}
import com.evernym.verity.protocol.didcomm.decorators.AttachmentDescriptor.buildAttachment
import com.evernym.verity.protocol.engine.{DID, DIDDoc, MsgFamily, MsgId, ThreadId}
import com.evernym.verity.protocol.protocols.agentprovisioning.v_0_7.AgentProvisioningMsgFamily.{AgentCreated, CreateCloudAgent, RequesterKeys}
import com.evernym.verity.protocol.protocols.connections.v_1_0.Msg
import com.evernym.verity.protocol.protocols.connections.v_1_0.Msg.{ConnRequest, ConnResponse, Connection}
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.{CredRequested, IssueCredential}
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Msg.{IssueCred, OfferCred, RequestCred}
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.{AttIds, AvailableCredentials}
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.Msg.{Presentation, RequestPresentation}
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.PresentProof.{credentialsToUse, extractAttachment}
import com.evernym.verity.protocol.protocols.relationship.v_1_0.Signal.Invitation
import com.evernym.verity.util.Base64Util
import com.evernym.verity.vault.KeyParam
import org.json.JSONObject

import java.util.UUID
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

/**
 * contains helper methods for holder sdk side of the operations
 *
 * @param param sdk parameters
 */
case class HolderSdk(param: SdkParam, ledgerTxnExecutor: LedgerTxnExecutor) extends SdkBase(param) {

  def provisionVerityCloudAgent(): AgentCreated = {
    val reqKeys = RequesterKeys(localAgentDidPair.DID, localAgentDidPair.verKey)
    provisionVerityAgentBase(CreateCloudAgent(reqKeys, None))
  }

  def sendCreateNewKey(connId: String): PairwiseRel = {
    val myPairwiseKey = createNewKey()
    val createKey = CreateKeyReqMsg_MFV_0_6(myPairwiseKey.DID, myPairwiseKey.verKey)
    val routedPackedMsg = packForMyVerityAgent(JsonMsgBuilder(createKey).jsonMsg)
    val receivedMsg = parseAndUnpackResponse[KeyCreatedRespMsg_MFV_0_6](checkOKResponse(sendPOST(routedPackedMsg)))
    val createdMsg = receivedMsg.msg
    val verityAgentDIDPair = DidPair(createdMsg.withPairwiseDID, createdMsg.withPairwiseDIDVerKey)
    storeTheirKey(verityAgentDIDPair)
    val pairwiseRel = PairwiseRel(Option(myPairwiseKey), Option(verityAgentDIDPair))
    myPairwiseRelationships += (connId -> pairwiseRel)
    pairwiseRel
  }

  //expects an unused invitation
  def sendConnReqForInvitation(connId: String, invitation: Invitation): Unit = {
    val (httpResp, threadId) = sendConnReqBase(connId, invitation)
    checkOKResponse(httpResp)
    val receivedMsg = expectMsgFromConn[ConnResponse](
      connId,
      statusCodes = Option(List(Status.MSG_STATUS_RECEIVED.statusCode)))
    //TODO: verify the connection response signature
    updateTheirDidDoc(connId, receivedMsg.msg)

    sendConnRespReceivedAck(connId, threadId)
  }

  private def sendConnRespReceivedAck(connId: String, thread: MsgThread): Unit = {
    val ack = Msg.Ack(status = true)
    sendProtoMsgToTheirAgent(connId, ack, Option(thread))
  }

  def sendConnReqForAcceptedInvitation(connId: String, invitation: Invitation): HttpResponse = {
    sendConnReqBase(connId, invitation)._1
  }

  private def sendConnReqBase(connId: String, invitation: Invitation): (HttpResponse, MsgThread) = {
    val updatedPairwiseRel = updateTheirDidDoc(connId, invitation)
    val connReq = ConnRequest(label = connId, createConnectionObject(updatedPairwiseRel))
    val jsonMsgBuilder = JsonMsgBuilder(connReq)
    val packedMsg = packForTheirVerityAgent(connId, jsonMsgBuilder.jsonMsg, "conn-req")
    val httpResp = sendBinaryReqToUrl(packedMsg, updatedPairwiseRel.theirServiceEndpoint)
    (httpResp, jsonMsgBuilder.thread)
  }

  //the packed message will be directly sent to 'their' agent (on EAS/VAS)
  // this doesn't have to do anything with holder cloud agent (on CAS)
  def sendProtoMsgToTheirAgent(connId: String,
                               msg: Any,
                               threadOpt: Option[MsgThread] = None,
                               expectedRespStatus: StatusCode = OK): Unit = {
    val myPairwiseRel = myPairwiseRelationships(connId)
    val jsonMsgBuilder = JsonMsgBuilder(msg, threadOpt)
    val msgType = jsonMsgBuilder.msgFamily.msgType(msg.getClass)
    val packedMsg = packForTheirVerityAgent(connId: String, jsonMsgBuilder.jsonMsg, msgType.msgName)
    checkResponse(sendBinaryReqToUrl(packedMsg, myPairwiseRel.theirServiceEndpoint), expectedRespStatus)
  }

  def sendCredRequest(connId: String,
                      credDefId: String,
                      offerCred: OfferCred,
                      thread: Option[MsgThread]): Unit = {
    val credDefJson = getCredDefJson(credDefId)
    val credOfferJson = IssueCredential.extractCredOfferJson(offerCred)
    val credReqCreated = createCredRequest(connId, credDefId, credDefJson, credOfferJson)
    val attachment = buildAttachment(Some("libindy-cred-req-0"), payload = credReqCreated.credReqJson)
    val attachmentEventObject = IssueCredential.toAttachmentObject(attachment)
    val credRequested = CredRequested(Seq(attachmentEventObject))
    val rc = RequestCred(Vector(attachment), Option(credRequested.comment))
    credExchangeStatus += thread.flatMap(_.thid).get -> CredExchangeStatus(connId, credDefId, credDefJson, offerCred, credReqCreated)
    sendProtoMsgToTheirAgent(connId, rc, thread)
  }

  def storeCred(issueCred: IssueCred,
                thread: Option[MsgThread]): CredStored = {
    val exchangeStatus = credExchangeStatus(thread.flatMap(_.thid).get)
    val attachedCred = new JSONObject(Base64Util.decodeToStr(issueCred.`credentials~attach`.head.data.base64))
    val revRegDefJson: String = null

    testWalletAPI.executeSync[CredStored](StoreCred(
      UUID.randomUUID().toString,
      exchangeStatus.credDefJson,
      exchangeStatus.credReqCreated.credReqMetadataJson,
      attachedCred.toString,
      revRegDefJson))
  }

  def acceptProofReq(connId: String,
                     proofReq: RequestPresentation,
                     selfAttestedAttrs: Map[String, String],
                     thread: Option[MsgThread]): Unit = {
    val proofRequestJson = extractAttachment(AttIds.request0, proofReq.`request_presentations~attach`).get
    val credCreated = Try(testWalletAPI.executeSync[CredForProofReqCreated](CredForProofReq(proofRequestJson)))
    val credentialsNeeded = credCreated.map(_.cred).map(DefaultMsgCodec.fromJson[AvailableCredentials](_))
    val (credentialsUsedJson, ids) = credentialsToUse(credentialsNeeded, selfAttestedAttrs)
    val (schemaJson, credDefJson) = doSchemaAndCredDefRetrieval(ids, allowsAllSelfAttested = false)
    val proofCreated = crateProof(
      proofRequestJson,
      credentialsUsedJson.get,
      schemaJson,
      credDefJson,
      "{}"
    )
    val payload = buildAttachment(Some(AttIds.presentation0), proofCreated.proof)
    val msg = Presentation("", Seq(payload))
    sendProtoMsgToTheirAgent(connId, msg, thread)
  }

  private def crateProof(proofReq: String,
                         reqCreds: String,
                         schemaJson: String,
                         credDefJson: String,
                         revStates: String
                        ): ProofCreated = {
    testWalletAPI.executeSync[ProofCreated](
      CreateProof(
        proofReq,
        reqCreds,
        schemaJson,
        credDefJson,
        masterSecretId,
        revStates
      )
    )
  }

  def setupMasterSecret(): Unit = {
    testWalletAPI.executeSync[MasterSecretCreated](CreateMasterSecret(masterSecretId))
  }

  private def awaitLedgerReq[T](fut: Future[T]): T = {
    val result = Await.ready(fut, 5.seconds).value.get
    result match {
      case Success(r) => r
      case Failure(StatusDetailException(sd)) =>
        throw new RuntimeException("error while executing ledger operation: " + sd)
      case Failure(exception) => throw exception
    }
  }

  private def getCredDefJson(credDefId: String): String = {
    val credDefResp = awaitLedgerReq(ledgerTxnExecutor.getCredDef(Submitter(), credDefId))
    DefaultMsgCodec.toJson(credDefResp.credDef.get)
  }


  private def createCredRequest(connId: String,
                                credDefId: String,
                                credDefJson: String,
                                credOfferJson: String): CredReqCreated = {
    val pairwiseRel = myPairwiseRelationships(connId)
    testWalletAPI.executeSync[CredReqCreated](
      CreateCredReq(credDefId, pairwiseRel.myPairwiseDID, credDefJson, credOfferJson, masterSecretId)
    )
  }

  private def doSchemaAndCredDefRetrieval(ids: Set[(String,String)],
                                          allowsAllSelfAttested: Boolean): (String, String) = {
    ids.size match {
      case 0 if !allowsAllSelfAttested =>
        throw new Exception("No ledger identifiers were included with the Presentation")
      case _ =>
        val schemaJson = doSchemaRetrieval(ids.map(_._1))
        val credDefJson = doCredDefRetrieval(ids.map(_._2))
        (schemaJson, credDefJson)
    }
  }

  private def doSchemaRetrieval(ids: Set[String]): String = {
    val schemas = ids.map(id => (id, awaitLedgerReq[GetSchemaResp](ledgerTxnExecutor.getSchema(Submitter(), id))))
    schemas.map { case (id, getSchemaResp) =>
      val schemaJson = DefaultMsgCodec.toJson(getSchemaResp.schema)
      s""""$id": $schemaJson"""
    }.mkString("{", ",", "}")
  }


  private def doCredDefRetrieval(credDefIds: Set[String]): String = {

    val credDefs = credDefIds.map(id => (id, awaitLedgerReq[GetCredDefResp](ledgerTxnExecutor.getCredDef(Submitter(), id))))
    credDefs.map { case (id, getCredDefResp) =>
      val credDefJson = DefaultMsgCodec.toJson(getCredDefResp.credDef)
      s""""$id": $credDefJson"""
    }.mkString("{", ",", "}")
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
        param.verityPackedMsgUrl,
        Vector(myPairwiseRel.myVerityAgentVerKey, agencyVerKey)
      ).toDIDDocFormatted
    )
  }

  def sendUpdateMsgStatusAsReviewedForConn(connId: String, msgId: MsgId): Unit = {
    updateMsgStatusOnConn(connId, msgId, "MS-106")
  }

  def updateMsgStatusOnConn(connId: String, msgId: MsgId, statusCode: String): Unit = {
    val updateMsgStatus = UpdateMsgStatusReqMsg_MFV_0_6(statusCode, List(msgId))
    val updateMsgStatusJson = JsonMsgUtil.createJsonString(MSG_TYPE_DETAIL_UPDATE_MSG_STATUS, updateMsgStatus)
    val routedPackedMsg = packForMyPairwiseRel(connId, updateMsgStatusJson)
    parseAndUnpackResponse[MsgStatusUpdatedRespMsg_MFV_0_6](checkOKResponse(sendPOST(routedPackedMsg)))
  }

  //this function/logic will only work for registered protocols (and not for legacy message types)
  def expectMsgFromConn[T: ClassTag](connId: String,
                                     excludePayload: Option[String] = Option(NO),
                                     statusCodes: Option[List[String]] = Option(List(Status.MSG_STATUS_RECEIVED.statusCode)),
                                     tryCount: Int = 1): ReceivedMsgParam[T] = {
    val msgType = buildMsgTypeStr
    expectMsgFromConn(connId, msgType, excludePayload, statusCodes, tryCount)
  }

  def expectMsgFromConn[T: ClassTag](connId: String,
                                     msgTypeStr: String,
                                     excludePayload: Option[String],
                                     statusCodes: Option[List[String]],
                                     tryCount: Int): ReceivedMsgParam[T] = {
    val getMsgs = GetMsgsReqMsg_MFV_0_6(excludePayload = excludePayload, statusCodes = statusCodes)
    val getMsgsJson = JsonMsgUtil.createJsonString(MSG_TYPE_DETAIL_GET_MSGS, getMsgs)
    val routedPackedMsg = packForMyPairwiseRel(connId, getMsgsJson)
    val result = parseAndUnpackResponse[GetMsgsRespMsg_MFV_0_6](checkOKResponse(sendPOST(routedPackedMsg))).msg.msgs
    val msg = result.find(m => m.`type` == msgTypeStr && statusCodes.forall(scs => scs.contains(m.statusCode)))
    msg match {
      case Some(m) if excludePayload.contains(NO) && m.payload.isDefined =>
        unpackMsg(m.payload.get).copy(msgIdOpt = Option(m.uid))
      case Some(m) if excludePayload.contains(NO) =>
        throw new RuntimeException("expected message found without payload: " + m)
      case None if tryCount < 5 =>
        Thread.sleep(tryCount*1000)
        expectMsgFromConn(connId, msgTypeStr, excludePayload, statusCodes, tryCount+1)
      case None => throw new RuntimeException("expected message not found: ")
    }
  }

  //this function/logic will only work for registered protocols (and not for legacy message types)
  def expectMsg[T: ClassTag](pairwiseDIDs: Option[List[DID]] = None,
                             msgIds: Option[List[MsgId]] = None,
                             excludePayload: Option[String] = Option(NO),
                             statusCodes: Option[List[String]] = Option(List(Status.MSG_STATUS_RECEIVED.statusCode)),
                             tryCount: Int = 1): ReceivedMsgParam[T] = {
    val msgType = buildMsgTypeStr
    expectMsg(msgType, pairwiseDIDs, msgIds, excludePayload, statusCodes, tryCount)
  }

  def expectMsg[T: ClassTag](msgTypeStr: String,
                             pairwiseDIDs: Option[List[DID]],
                             msgIds: Option[List[MsgId]],
                             excludePayload: Option[String],
                             statusCodes: Option[List[String]],
                             tryCount: Int): ReceivedMsgParam[T] = {
    val getMsgs = GetMsgsByConnsReqMsg_MFV_0_6(pairwiseDIDs, msgIds, excludePayload, statusCodes)
    val getMsgsJson = JsonMsgUtil.createJsonString(MSG_TYPE_DETAIL_GET_MSGS_BY_CONNS, getMsgs)
    val routedPackedMsg = packForMyVerityAgent(getMsgsJson)
    val result = parseAndUnpackResponse[GetMsgsByConnsRespMsg_MFV_0_6](checkOKResponse(sendPOST(routedPackedMsg))).msg.msgsByConns
    val allMsgs = result.flatMap(_.msgs)
    val msg = allMsgs.find(m => m.`type` == msgTypeStr && statusCodes.forall(scs => scs.contains(m.statusCode)))
    msg match {
      case Some(m) if excludePayload.contains(NO) && m.payload.isDefined =>
        unpackMsg(m.payload.get).copy(msgIdOpt = Option(m.uid))
      case Some(m) if excludePayload.contains(NO) =>
        throw new RuntimeException("expected message found without payload: " + m)
      case None if tryCount < 5 =>
        Thread.sleep(tryCount*1000)
        expectMsgFromConn(msgTypeStr, excludePayload, statusCodes, tryCount+1)
      case None => throw new RuntimeException("expected message not found: ")
    }
  }

  private def buildMsgTypeStr[T: ClassTag]: String = {
    val clazz = implicitly[ClassTag[T]].runtimeClass
    val msgType = MsgFamilyHelper.getMsgFamilyOpt.map(_.msgType(clazz))
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

  val masterSecretId: String = UUID.randomUUID().toString
  var credExchangeStatus = Map.empty[ThreadId, CredExchangeStatus]

  setupMasterSecret()
}

object CredExchangeStatus {
  def apply(connId: String,
            credDefId: String,
            credDefJson: String,
            offerCred: OfferCred,
            credReqCreated: CredReqCreated): CredExchangeStatus = {
    CredExchangeStatus(connId, credDefId, credDefJson, offerCred, Option(credReqCreated))
  }
}

case class CredExchangeStatus(connId: String,
                              credDefId: String,
                              credDefJson: String,
                              credOffer: OfferCred,
                              credReqCreatedOpt: Option[CredReqCreated] = None) {

  def credOfferJson: String = IssueCredential.extractCredOfferJson(credOffer)
  def credReqCreated: CredReqCreated = credReqCreatedOpt.getOrElse(throw new RuntimeException("cred req created not available"))

  def withCredReqCreated(crc: CredReqCreated): CredExchangeStatus = {
    copy(credReqCreatedOpt = Option(crc))
  }
}