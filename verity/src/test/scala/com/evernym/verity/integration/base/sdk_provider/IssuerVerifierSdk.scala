package com.evernym.verity.integration.base.sdk_provider

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest, HttpResponse, StatusCode}
import akka.http.scaladsl.model.StatusCodes.{Accepted, OK}
import akka.http.scaladsl.model.headers.RawHeader
import com.evernym.verity.actor.ComMethodUpdated
import com.evernym.verity.actor.agent.DidPair
import com.evernym.verity.actor.agent.MsgPackFormat.{MPF_INDY_PACK, MPF_PLAIN}
import com.evernym.verity.actor.wallet.{SignMsg, SignedMsg}
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil.{MSG_FAMILY_CONFIGS, MSG_TYPE_UPDATE_COM_METHOD}
import com.evernym.verity.agentmsg.msgfamily.configs.{ComMethod, ComMethodPackaging, UpdateComMethodReqMsg, UpdateConfigReqMsg}
import com.evernym.verity.constants.Constants.COM_METHOD_TYPE_HTTP_ENDPOINT
import com.evernym.verity.integration.base.sdk_provider.msg_listener.{MsgListenerBase, PackedMsgListener, PlainMsgListener}
import com.evernym.verity.protocol.engine.Constants.MFV_0_6
import com.evernym.verity.protocol.engine.{MsgFamily, ThreadId, VerKey}
import com.evernym.verity.protocol.engine.MsgFamily.{EVERNYM_QUALIFIER, typeStrFromMsgType}
import com.evernym.verity.protocol.protocols.agentprovisioning.v_0_7.AgentProvisioningMsgFamily.{AgentCreated, CreateEdgeAgent}
import com.evernym.verity.protocol.protocols.relationship.v_1_0.Ctl.{ConnectionInvitation, Create}
import com.evernym.verity.protocol.protocols.relationship.v_1_0.RelationshipMsgFamily
import com.evernym.verity.protocol.protocols.relationship.v_1_0.Signal.{Created, Invitation}
import com.evernym.verity.protocol.protocols.updateConfigs.v_0_6.ConfigResult
import com.evernym.verity.util.Base58Util
import com.evernym.verity.vault.KeyParam
import org.json.JSONObject
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper

import java.util.UUID
import scala.concurrent.duration.{Duration, SECONDS}
import scala.reflect.ClassTag
import scala.util.Random

abstract class VeritySdkBase(param: SdkParam) extends SdkBase(param) {

  def registerWebhook(): ComMethodUpdated
  def sendCreateRelationship(connId: String): ReceivedMsgParam[Created]
  def sendCreateConnectionInvitation(connId: String, threadId: Option[ThreadId]): Invitation

  /**
   *
   * @param msg the message to be sent
   * @param threadIdOpt the thread id to be used
   * @param applyToJsonMsg function to apply to message json before gets packed,
   *                       mostly useful to test failure/negative scenarios
   *                       where we want to modify the final json string to test
   *                       how verity process/responds it.
   * @param expectedRespStatus expected http response status
   * @return
   */
  def sendMsg(msg: Any,
              threadIdOpt: Option[ThreadId] = None,
              applyToJsonMsg: String => String = { msg => msg},
              expectedRespStatus: StatusCode = OK): HttpResponse

  /**
   *
   * @param connId connection for which the message needs to be packed
   * @param msg the message to be sent
   * @param threadIdOpt the thread id to be used
   * @param applyToJsonMsg function to apply to message json before gets packed,
   *                       mostly useful to test failure/negative scenarios
   *                       where we want to modify the final json string to test
   *                       how verity process/responds it.
   * @param expectedRespStatus expected http response status
   * @return
   */
  def sendMsgForConn(connId: String,
                     msg: Any,
                     threadIdOpt: Option[ThreadId] = None,
                     applyToJsonMsg: String => String = { msg => msg},
                     expectedRespStatus: StatusCode = OK): HttpResponse

  def provisionVerityEdgeAgent(): AgentCreated = {
    provisionVerityAgentBase(CreateEdgeAgent(localAgentDidPair.verKey, None))
  }

  protected def registerWebhookBase(packaging: Option[ComMethodPackaging]): ComMethodUpdated = {
    val updateComMethod = UpdateComMethodReqMsg(
      ComMethod("1", COM_METHOD_TYPE_HTTP_ENDPOINT, msgListener.endpoint, packaging)
    )
    val typeStr = typeStrFromMsgType(EVERNYM_QUALIFIER, MSG_FAMILY_CONFIGS, MFV_0_6, MSG_TYPE_UPDATE_COM_METHOD)
    val updateComMethodJson = createJsonString(typeStr, updateComMethod)
    val routedPackedMsg = packForMyVerityAgent(updateComMethodJson)
    parseAndUnpackResponse[ComMethodUpdated](checkOKResponse(sendPOST(routedPackedMsg))).msg
  }

  def sendUpdateConfig(updateConfigReq: UpdateConfigReqMsg): ConfigResult = {
    val typeStr = typeStrFromMsgType(EVERNYM_QUALIFIER, "update-configs", MFV_0_6, "update")
    val updateConfigJson = createJsonString(typeStr, updateConfigReq)
    val routedPackedMsg = packForMyVerityAgent(updateConfigJson)
    checkOKResponse(sendPOST(routedPackedMsg))
    expectMsgOnWebhook[ConfigResult]().msg
  }

  protected def addForRel(connId: String, msg: String): String = {
    val relationship = myPairwiseRelationships(connId)
    val jsonObject = new JSONObject(msg)
    jsonObject.put("~for_relationship", relationship.verityAgentDIDPair.get.DID)
    jsonObject.toString
  }

  def msgListener: MsgListenerBase[_]
  def expectMsgOnWebhook[T: ClassTag](timeout: Duration = Duration(30, SECONDS)): ReceivedMsgParam[T]
}

/**
 * contains helper methods for issuer sdk side of the operations
 *
 * @param param sdk parameters
 */
abstract class IssuerVerifierSdk(param: SdkParam) extends VeritySdkBase(param) {

  def registerWebhook(): ComMethodUpdated = {
    val packaging = Option(ComMethodPackaging(MPF_INDY_PACK.toString, Option(Set(myLocalAgentVerKey))))
    registerWebhookBase(packaging)
  }

  def sendCreateRelationship(connId: String): ReceivedMsgParam[Created] = {
    val createKey = Create(label = Option(connId), None, None)
    val createKeyJson = createJsonString(createKey, RelationshipMsgFamily)
    val routedPackedMsg = packForMyVerityAgent(createKeyJson)
    checkOKResponse(sendPOST(routedPackedMsg))
    val receivedMsg = expectMsgOnWebhook[Created]()
    val created = receivedMsg.msg
    myPairwiseRelationships += (connId -> PairwiseRel(None, Option(DidPair(created.did, created.verKey))))
    receivedMsg
  }

  def sendCreateConnectionInvitation(connId: String, threadId: Option[ThreadId]): Invitation = {
    sendMsgForConn(connId, ConnectionInvitation(), threadId)
    val receivedMsg = expectMsgOnWebhook[Invitation]()
    receivedMsg.msg
  }

  def sendMsg(msg: Any,
              threadIdOpt: Option[ThreadId] = None,
              applyToJsonMsg: String => String = { msg => msg},
              expectedRespStatus: StatusCode = OK): HttpResponse = {
    val threadId = threadIdOpt.getOrElse(UUID.randomUUID().toString)
    val msgFamily = getMsgFamily(msg)
    val msgJson = applyToJsonMsg(withThreadIdAdded(createJsonString(msg, msgFamily), threadId))
    val routedPackedMsg = packForMyVerityAgent(msgJson)
    checkResponse(sendPOST(routedPackedMsg), expectedRespStatus)
  }

  def sendMsgForConn(connId: String,
                     msg: Any,
                     threadIdOpt: Option[ThreadId] = None,
                     applyToJsonMsg: String => String = { msg => msg},
                     expectedRespStatus: StatusCode = OK): HttpResponse = {
    val threadId = threadIdOpt.getOrElse(UUID.randomUUID().toString)
    val msgFamily = getMsgFamily(msg)
    val msgJson = applyToJsonMsg(withThreadIdAdded(createJsonString(msg, msgFamily), threadId))
    val routedPackedMsg = addForRelAndPackForConn(connId, msgJson)
    checkResponse(sendPOST(routedPackedMsg), expectedRespStatus)
  }

  private def addForRelAndPackForConn(connId: String, msg: String): Array[Byte] = {
    val forRelMsg = addForRel(connId, msg)
    val verityAgentPackedMsg = packFromLocalAgentKey(forRelMsg, Set(KeyParam.fromVerKey(verityAgentDidPair.verKey)))
    prepareFwdMsg(agencyDID, verityAgentDidPair.DID, verityAgentPackedMsg)
  }

  /**
   * this webhook expects packed messages
   * @tparam T expected message type
   * @return
   */
  def expectMsgOnWebhook[T: ClassTag](timeout: Duration = Duration(30, SECONDS)): ReceivedMsgParam[T] = {
    val msg = msgListener.expectMsg(timeout)
    unpackMsg(msg)
  }

  lazy val msgListener: PackedMsgListener = {
    val port = 8000 + Random.nextInt(1000)
    new PackedMsgListener(port)(ActorSystem(s"listener-$port"))
  }

}

case class IssuerSdk(param: SdkParam) extends IssuerVerifierSdk(param)

case class VerifierSdk(param: SdkParam) extends IssuerVerifierSdk(param)


case class IssuerRestSDK(param: SdkParam) extends VeritySdkBase(param) {
  import scala.collection.immutable

  def registerWebhook(): ComMethodUpdated = {
    val packaging = Option(ComMethodPackaging(MPF_PLAIN.toString, None))
    registerWebhookBase(packaging)
  }

  def sendCreateRelationship(connId: String): ReceivedMsgParam[Created] = {
    val msg = Create(None, None)
    val resp = sendMsg(msg)
    resp.status shouldBe Accepted
    val rmp = expectMsgOnWebhook[Created]()
    myPairwiseRelationships += (connId -> PairwiseRel(None, Option(DidPair(rmp.msg.did, rmp.msg.verKey))))
    rmp
  }

  def sendCreateConnectionInvitation(connId: String, threadId: Option[ThreadId]): Invitation = {
    sendMsgForConn(connId, ConnectionInvitation(), threadId)
    val receivedMsg = expectMsgOnWebhook[Invitation]()
    receivedMsg.msg
  }

  def sendMsg(msg: Any,
              threadIdOpt: Option[ThreadId] = None,
              applyToJsonMsg: String => String = { msg => msg},
              expectedRespStatus: StatusCode = OK): HttpResponse = {
    val threadId = threadIdOpt.getOrElse(UUID.randomUUID().toString)
    val msgFamily = getMsgFamily(msg)
    val msgJson = applyToJsonMsg(withThreadIdAdded(createJsonString(msg, msgFamily), threadId))
    checkResponse(sendPostReqBase(msgFamily, msgJson, verityAgentDidPair.DID, myDIDApiKey, threadIdOpt), Accepted)
  }

  def sendMsgForConn(connId: String,
                     msg: Any,
                     threadIdOpt: Option[ThreadId] = None,
                     applyToJsonMsg: String => String = { msg => msg},
                     expectedRespStatus: StatusCode = OK): HttpResponse = {
    val threadId = threadIdOpt.getOrElse(UUID.randomUUID().toString)
    val msgFamily = getMsgFamily(msg)
    val updatedPayload = applyToJsonMsg(withThreadIdAdded(addForRel(connId, createJsonString(msg, msgFamily)), threadId))
    checkResponse(sendPostReqBase(msgFamily, updatedPayload, verityAgentDidPair.DID, myDIDApiKey, threadIdOpt), Accepted)
  }

  private def sendPostReqBase(msgFamily: MsgFamily,
                              payload: String,
                              route: String,
                              routeApiKey: String,
                              threadIdOpt: Option[ThreadId] = None): HttpResponse = {
    val threadId = threadIdOpt.getOrElse(UUID.randomUUID.toString)
    val url = s"${param.verityRestApiUrl}/$route/${msgFamily.name}/${msgFamily.version}/$threadId"
    sendPostJsonReqToUrl(payload, url, routeApiKey)
  }

  def sendGetStatusReqForConn[T: ClassTag](connId: String,
                                           msgFamily: MsgFamily,
                                           threadIdOpt: Option[ThreadId] = None): RestGetResponse[T] = {
    val forRel = myPairwiseRelationships(connId).myVerityAgentDID
    val queryParam = Option(s"~for_relationship=$forRel")
    sendGetReqBase(msgFamily, verityAgentDidPair.DID, myDIDApiKey, threadIdOpt, queryParam)
  }

  def sendGetStatusReq[T: ClassTag](threadIdOpt: Option[ThreadId] = None): RestGetResponse[T] = {
    val msgFamily = getMsgFamily
    sendGetReqBase(msgFamily, verityAgentDidPair.DID, myDIDApiKey, threadIdOpt)
  }

  private def sendGetReqBase[T: ClassTag](msgFamily: MsgFamily,
                                          route: String,
                                          routeApiKey: String,
                                          threadIdOpt: Option[ThreadId] = None,
                                          queryParamOpt: Option[String]=None): RestGetResponse[T] = {
    val threadId = threadIdOpt.getOrElse(UUID.randomUUID.toString)
    val url = s"${param.verityRestApiUrl}/$route/${msgFamily.name}/${msgFamily.version}/$threadId" +
      queryParamOpt.map(qp => s"?$qp").getOrElse("")
    val resp = parseHttpResponse(sendGetJsonReqToUrl(url, routeApiKey))
    val jsonObject = new JSONObject(resp)
    val resultResp = DefaultMsgCodec.fromJson[T](jsonObject.getJSONObject("result").toString)
    RestGetResponse(resultResp, jsonObject.getString("status"))
  }

  private def sendPostJsonReqToUrl(payload: String, url: String, apiKey: String): HttpResponse = {
    awaitFut(
      Http().singleRequest(
        HttpRequest(
          method=HttpMethods.POST,
          uri = url,
          entity = HttpEntity(
            ContentTypes.`application/json`,
            payload
          ),
          headers = Seq(RawHeader("X-API-key", apiKey)).to[immutable.Seq]
        )
      )
    )
  }

  private def sendGetJsonReqToUrl(url: String, apiKey: String): HttpResponse = {
    awaitFut(
      Http().singleRequest(
        HttpRequest(
          method = HttpMethods.GET,
          uri = url,
          entity = HttpEntity.Empty,
          headers = Seq(RawHeader("X-API-key", apiKey)).to[immutable.Seq]
        )
      )
    )
  }

  private lazy val myDIDApiKey: String = {
    val myDIDSignature = computeSignature(myLocalAgentVerKey)
    s"$myLocalAgentVerKey:$myDIDSignature"
  }

  private def computeSignature(verKey: VerKey): String = {
    val signedMsg = testWalletAPI.executeSync[SignedMsg](
      SignMsg(KeyParam.fromVerKey(verKey), verKey.getBytes))(walletAPIParam)
    Base58Util.encode(signedMsg.msg)
  }

  /**
   * this webhook expects plain (json) messages
   * @tparam T
   * @return
   */
  def expectMsgOnWebhook[T: ClassTag](timeout: Duration = Duration(30, SECONDS)): ReceivedMsgParam[T] = {
    val msg = msgListener.expectMsg(timeout)
    ReceivedMsgParam(msg)
  }

  lazy val msgListener: PlainMsgListener = {
    val port = 7000 + Random.nextInt(1000)
    new PlainMsgListener(port)(ActorSystem(s"listener-$port"))
  }
}

case class RestGetResponse[T](result: T, status: String)