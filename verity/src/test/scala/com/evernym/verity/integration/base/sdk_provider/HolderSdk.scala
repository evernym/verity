package com.evernym.verity.integration.base.sdk_provider

import akka.http.scaladsl.model.{HttpResponse, StatusCode}
import akka.http.scaladsl.model.StatusCodes.OK
import com.evernym.verity.actor.ComMethodUpdated
import com.evernym.verity.util2.Status.StatusDetailException
import com.evernym.verity.actor.agent.MsgPackFormat.MPF_INDY_PACK
import com.evernym.verity.did.didcomm.v1.{Thread => MsgThread}
import com.evernym.verity.actor.wallet.{CreateCredReq, CreateMasterSecret, CreateProof, CredForProofReq, CredForProofReqCreated, CredReqCreated, CredStored, MasterSecretCreated, ProofCreated, StoreCred}
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil.{MSG_FAMILY_CONFIGS, MSG_FAMILY_V1V2MIGRATION, MSG_TYPE_DETAIL_GET_MSGS, MSG_TYPE_DETAIL_UPDATE_MSG_STATUS, MSG_TYPE_GET_UPGRADE_INFO, MSG_TYPE_UPDATE_COM_METHOD}
import com.evernym.verity.agentmsg.msgfamily.configs.UpdateComMethodReqMsg
import com.evernym.verity.agentmsg.msgfamily.pairwise.{CreateKeyReqMsg_MFV_0_6, GetMsgsReqMsg_MFV_0_6, GetMsgsRespMsg_MFV_0_6, KeyCreatedRespMsg_MFV_0_6, MsgStatusUpdatedRespMsg_MFV_0_6, UpdateMsgStatusReqMsg_MFV_0_6}
import com.evernym.verity.agentmsg.msgfamily.v1tov2migration.{GetUpgradeInfo, UpgradeInfoRespMsg_MFV_1_0}
import com.evernym.verity.agentmsg.msgpacker.{AgentMsgPackagingUtil, AgentMsgTransformer}
import com.evernym.verity.constants.Constants.NO
import com.evernym.verity.did.DidPair
import com.evernym.verity.integration.base.sdk_provider.MsgFamilyHelper.buildMsgTypeStr
import com.evernym.verity.ledger.LedgerTxnExecutor
import com.evernym.verity.did.didcomm.v1.decorators.AttachmentDescriptor.buildAttachment
import com.evernym.verity.did.didcomm.v1.messages.MsgFamily.{EVERNYM_QUALIFIER, typeStrFromMsgType}
import com.evernym.verity.did.didcomm.v1.messages.MsgId
import com.evernym.verity.constants.Constants.{MFV_0_6, MFV_1_0}
import com.evernym.verity.protocol.engine.ThreadId
import com.evernym.verity.protocol.protocols.agentprovisioning.v_0_7.AgentProvisioningMsgFamily.{AgentCreated, CreateCloudAgent, ProvisionToken, RequesterKeys}
import com.evernym.verity.protocol.protocols.connections.v_1_0.Msg
import com.evernym.verity.protocol.protocols.connections.v_1_0.Msg.{ConnRequest, ConnResponse, Connection}
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Msg.{IssueCred, OfferCred, RequestCred}
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.{CredRequested, IssueCredential}
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.{AttIds, AvailableCredentials}
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.Msg.{Presentation, RequestPresentation}
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.PresentProof.{credentialsToUse, extractAttachment}
import com.evernym.verity.protocol.protocols.relationship.v_1_0.Signal.Invitation
import com.evernym.verity.observability.metrics.NoOpMetricsWriter
import com.evernym.verity.protocol.engine.util.DIDDoc
import com.evernym.verity.testkit.util.HttpUtil
import com.evernym.verity.testkit.util.HttpUtil._
import com.evernym.verity.util.Base64Util
import com.evernym.verity.util2.Status
import com.evernym.verity.vault.KeyParam
import com.evernym.verity.vdr.{CredDef, CredDefId, MockVdrTools, Schema, SchemaId}
import com.evernym.verity.vdr.service.VdrTools
import org.json.JSONObject

import java.util.UUID
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

/**
 * contains helper methods for holder sdk side of the operations
 *
 * @param param sdk parameters
 * @param ledgerTxnExecutor ledger txn executor
 */
case class HolderSdk(override val ec: ExecutionContext,
                     param: SdkParam,
                     vdrTools: Option[VdrTools],
                     ledgerTxnExecutor: Option[LedgerTxnExecutor],
                     oauthParam: Option[OAuthParam]=None,
                     isMultiLedgerSupported: Boolean = true)
  extends SdkBase(param, ec) {

  implicit val executionContext: ExecutionContext = ec

  def registerWebhook(updateComMethod: UpdateComMethodReqMsg): ComMethodUpdated = {
    val typeStr = typeStrFromMsgType(EVERNYM_QUALIFIER, MSG_FAMILY_CONFIGS, MFV_0_6, MSG_TYPE_UPDATE_COM_METHOD)
    val updateComMethodJson = JsonMsgUtil.createJsonString(typeStr, updateComMethod)
    val routedPackedMsg = packForMyVerityAgent(updateComMethodJson)
    parseAndUnpackResponse[ComMethodUpdated](checkOKResponse(sendPOST(routedPackedMsg))).msg
  }

  def getUpgradeInfo(gui: GetUpgradeInfo): UpgradeInfoRespMsg_MFV_1_0 = {
    val typeStr = typeStrFromMsgType(EVERNYM_QUALIFIER, MSG_FAMILY_V1V2MIGRATION, MFV_1_0, MSG_TYPE_GET_UPGRADE_INFO)
    val getUpgradeInfoJson = JsonMsgUtil.createJsonString(typeStr, gui)
    val routedPackedMsg = packForMyVerityAgent(getUpgradeInfoJson)
    parseAndUnpackResponse[UpgradeInfoRespMsg_MFV_1_0](checkOKResponse(sendPOST(routedPackedMsg))).msg
  }

  def provisionVerityCloudAgent(provToken: ProvisionToken): AgentCreated = {
    fetchAgencyKey()
    provisionVerityCloudAgent(Option(provToken))
  }

  def provisionVerityCloudAgent(provToken: Option[ProvisionToken] = None): AgentCreated = {
    val reqKeys = RequesterKeys(localAgentDidPair.did, localAgentDidPair.verKey)
    provisionVerityAgentBase(CreateCloudAgent(reqKeys, provToken))
  }

  def sendCreateNewKey(connId: String): PairwiseRel = {
    val myPairwiseKey = createNewKey()
    val createKey = CreateKeyReqMsg_MFV_0_6(myPairwiseKey.did, myPairwiseKey.verKey)
    val routedPackedMsg = packForMyVerityAgent(JsonMsgBuilder(createKey).jsonMsg)
    val receivedMsg = parseAndUnpackResponse[KeyCreatedRespMsg_MFV_0_6](checkOKResponse(sendPOST(routedPackedMsg)))
    val createdMsg = receivedMsg.msg
    val verityAgentDIDPair = DidPair(createdMsg.withPairwiseDID, createdMsg.withPairwiseDIDVerKey)
    updatePairwiseAgentDidPair(connId, myPairwiseKey, verityAgentDIDPair)
  }

  //expects an unused invitation
  def sendConnReqForInvitation(connId: String, invitation: Invitation): Unit = {
    val (httpResp, threadId) = sendConnReqBase(connId, invitation)
    checkOKResponse(httpResp)
    val receivedMsg = downloadMsg[ConnResponse](
      statusCodes = Option(List(Status.MSG_STATUS_RECEIVED.statusCode)),
      connId = Option(connId)
    )
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
    val httpResp = HttpUtil.sendBinaryReqToUrl(packedMsg, updatedPairwiseRel.theirServiceEndpoint)
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
    checkResponse(HttpUtil.sendBinaryReqToUrl(packedMsg, myPairwiseRel.theirServiceEndpoint), expectedRespStatus)
  }

  def sendCredRequest(connId: String,
                      offerCred: OfferCred,
                      thread: Option[MsgThread]): Unit = {
    val credOfferJsonString = IssueCredential.extractCredOfferJson(offerCred)
    val credOfferJson =  new JSONObject(credOfferJsonString)
    val credDefId = credOfferJson.getString("cred_def_id")
    val credDefJson = getCredDefJson(credDefId)

    val credReqCreated = createCredRequest(connId, credDefId, credDefJson, credOfferJsonString)
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
    val credForProofReq = Try(testWalletAPI.executeSync[CredForProofReqCreated](CredForProofReq(proofRequestJson)))
    val availableCredentials = credForProofReq.map(_.cred).map(DefaultMsgCodec.fromJson[AvailableCredentials](_))
    val (credentialsUsedJson, ids) = credentialsToUse(availableCredentials, selfAttestedAttrs)
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

  private def createCredRequest(connId: String,
                                credDefId: CredDefId,
                                credDefJson: String,
                                credOfferJson: String): CredReqCreated = {
    val pairwiseRel = myPairwiseRelationships(connId)
    testWalletAPI.executeSync[CredReqCreated](
      CreateCredReq(credDefId, pairwiseRel.myPairwiseDID, credDefJson, credOfferJson, masterSecretId)
    )
  }

  private def doSchemaAndCredDefRetrieval(ids: Set[(SchemaId,CredDefId)],
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

  private def getCredDefJson(credDefId: CredDefId): String = {
    val credDef = awaitLedgerReq(getCredDefFromLedger(credDefId))
    credDef.json
  }

  private def doSchemaRetrieval(schemaIds: Set[SchemaId]): String = {
    val schemas = schemaIds.map(id => (id, awaitLedgerReq(getSchemaFromLedger(id))))
    schemas.map { case (id, schema) =>
      s""""$id": ${schema.json}"""
    }.mkString("{", ",", "}")
  }


  private def doCredDefRetrieval(credDefIds: Set[CredDefId]): String = {
    val credDefs = credDefIds.map(id => (id, awaitLedgerReq(getCredDefFromLedger(id))))
    credDefs.map { case (id, credDef) =>
      s""""$id": ${credDef.json}"""
    }.mkString("{", ",", "}")
  }

  private def getCredDefFromLedger(credDefId: CredDefId): Future[CredDef] = {
    if (isMultiLedgerSupported) vdrTools.get.resolveCredDef(credDefId).map(CredDef(credDefId, "", _))
    else vdrTools.get.asInstanceOf[MockVdrTools].getCredDef(credDefId).map(CredDef(credDefId, "", _))
  }

  private def getSchemaFromLedger(schemaId: SchemaId): Future[Schema] = {
    if (isMultiLedgerSupported) vdrTools.get.resolveSchema(schemaId).map(Schema(schemaId, _))
    else vdrTools.get.asInstanceOf[MockVdrTools].getSchema(schemaId).map(Schema(schemaId, _))
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
      fwdMsgType,
      None
    )(
      new AgentMsgTransformer(
        testWalletAPI,
        testAppConfig,
        executionContext
      ),
      walletAPIParam,
      NoOpMetricsWriter(),
      executionContext
    )
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

  private def sendUpdateMsgStatusAsReviewedForConn(msgId: MsgId, connId: Option[String]): Unit = {
    updateMsgStatusOnConn(msgId, "MS-106", connId)
  }

  def updateMsgStatusOnConn(msgId: MsgId, statusCode: String, connId: Option[String]): Unit = {
    val updateMsgStatus = UpdateMsgStatusReqMsg_MFV_0_6(statusCode, List(msgId))
    val updateMsgStatusJson = JsonMsgUtil.createJsonString(MSG_TYPE_DETAIL_UPDATE_MSG_STATUS, updateMsgStatus)
    val routedPackedMsg = {
      connId match {
        case Some(cId) => packForMyPairwiseRel(cId, updateMsgStatusJson)
        case None      => packForMyVerityAgent(updateMsgStatusJson)
      }
    }
    parseAndUnpackResponse[MsgStatusUpdatedRespMsg_MFV_0_6](checkOKResponse(sendPOST(routedPackedMsg)))
  }

  //this function/logic will only work for registered protocols (and not for legacy message types)
  def downloadMsg[T: ClassTag](connId: String): ReceivedMsgParam[T] = {
    val msgType = buildMsgTypeStr
    downloadMsg(msgType, Option(NO), Option(List(Status.MSG_STATUS_RECEIVED.statusCode)), connId = Option(connId))
  }

  //this function/logic will only work for registered protocols (and not for legacy message types)
  def downloadMsg[T: ClassTag](excludePayload: Option[String] = Option(NO),
                               statusCodes: Option[List[String]] = Option(List(Status.MSG_STATUS_RECEIVED.statusCode)),
                               connId: Option[String]=None): ReceivedMsgParam[T] = {
    val msgType = buildMsgTypeStr
    downloadMsg(msgType, excludePayload, statusCodes, connId)
  }

  //this function/logic should work based on given msgTypeStr
  def downloadMsg[T: ClassTag](msgTypeStr: String,
                               excludePayload: Option[String],
                               statusCodes: Option[List[String]],
                               connId: Option[String]): ReceivedMsgParam[T] = {
    var unexpectedMsgs = Map.empty[MsgId, String]
    for (tryCount <- 1 to 30) {
      val getMsgs = GetMsgsReqMsg_MFV_0_6(excludePayload = excludePayload, statusCodes = statusCodes)
      val getMsgsJson = JsonMsgUtil.createJsonString(MSG_TYPE_DETAIL_GET_MSGS, getMsgs)
      val routedPackedMsg = {
        connId match {
          case Some(cId) => packForMyPairwiseRel(cId, getMsgsJson)
          case None      => packForMyVerityAgent(getMsgsJson)
        }
      }
      val result = parseAndUnpackResponse[GetMsgsRespMsg_MFV_0_6](checkOKResponse(sendPOST(routedPackedMsg))).msg.msgs
      val msg = result.find(m => m.`type` == msgTypeStr && statusCodes.forall(scs => scs.contains(m.statusCode)))
      msg match {
        case Some(m) if excludePayload.contains(NO) && m.payload.isDefined =>
          val unpackedMsg: ReceivedMsgParam[T] = unpackMsg(m.payload.get).copy(msgIdOpt = Option(m.uid))
          sendUpdateMsgStatusAsReviewedForConn(m.uid, connId)
          return unpackedMsg
        case Some(m) if excludePayload.contains(NO) =>
          throw new RuntimeException("expected message found without payload: " + m)
        case msg =>
          msg.foreach{ m => unexpectedMsgs = unexpectedMsgs + (m.uid -> m.toString)}
          Thread.sleep(tryCount * 50)
      }
    }
    throw new RuntimeException("expected message not found: " + msgTypeStr + s"(unexpectedMsgsFound = ${unexpectedMsgs.mkString(", ")})")
  }

  val masterSecretId: String = UUID.randomUUID().toString
  var credExchangeStatus = Map.empty[ThreadId, CredExchangeStatus]
  val vdrMultiLedgerSupportEnabled = true
  val vdrUnqualifiedLedgerPrefix = "did:indy:sovrin"

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