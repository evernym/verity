package com.evernym.verity.integration.base.sdk_provider

import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest, HttpResponse, StatusCode}
import akka.http.scaladsl.model.StatusCodes.{Accepted, OK}
import akka.http.scaladsl.model.headers.RawHeader
import com.evernym.verity.actor.ComMethodUpdated
import com.evernym.verity.did.didcomm.v1.{Thread => MsgThread}
import com.evernym.verity.actor.agent.MsgPackFormat.{MPF_INDY_PACK, MPF_PLAIN}
import com.evernym.verity.actor.wallet.{SignMsg, SignedMsg}
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil.{MSG_FAMILY_CONFIGS, MSG_TYPE_UPDATE_COM_METHOD}
import com.evernym.verity.agentmsg.msgfamily.configs.{ComMethod, ComMethodAuthentication, ComMethodPackaging, UpdateComMethodReqMsg, UpdateConfigReqMsg}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.constants.Constants.COM_METHOD_TYPE_HTTP_ENDPOINT
import com.evernym.verity.did.didcomm.v1.messages.MsgFamily
import com.evernym.verity.did.didcomm.v1.messages.MsgFamily.{EVERNYM_QUALIFIER, MsgFamilyName, MsgFamilyVersion, typeStrFromMsgType}
import com.evernym.verity.did.{DidPair, VerKeyStr}
import com.evernym.verity.integration.base.PortProvider
import com.evernym.verity.integration.base.sdk_provider.msg_listener.{JsonMsgListener, MsgListenerBase, PackedMsgListener, ReceivedMsgCounter}
import com.evernym.verity.protocol.engine.Constants.MFV_0_6
import com.evernym.verity.protocol.engine.ThreadId
import com.evernym.verity.protocol.protocols.agentprovisioning.v_0_7.AgentProvisioningMsgFamily.{AgentCreated, CreateEdgeAgent}
import com.evernym.verity.protocol.protocols.connections.v_1_0.Signal.{Complete, ConnRequestReceived, ConnResponseSent}
import com.evernym.verity.protocol.protocols.relationship.v_1_0.Ctl.{ConnectionInvitation, Create, OutOfBandInvitation}
import com.evernym.verity.protocol.protocols.relationship.v_1_0.Signal.{Created, Invitation}
import com.evernym.verity.protocol.protocols.updateConfigs.v_0_6.Sig.ConfigResult
import com.evernym.verity.testkit.util.HttpUtil
import com.evernym.verity.util.Base58Util
import com.evernym.verity.vault.KeyParam
import org.json.JSONObject

import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{Duration, SECONDS}
import scala.reflect.ClassTag

abstract class VeritySdkBase(param: SdkParam,
                             ec: ExecutionContext,
                             oauthParam: Option[OAuthParam]=None)
  extends SdkBase(param, ec) {

  def registerWebhook(id: Option[String] = None, authentication: Option[ComMethodAuthentication]=None): ComMethodUpdated
  def sendCreateRelationship(connId: String): ReceivedMsgParam[Created]
  def sendCreateConnectionInvitation(connId: String, thread: Option[MsgThread]): Invitation

  def expectConnectionComplete(connId: ConnId): Complete = {
    val msgReceived = expectMsgOnWebhook[ConnRequestReceived]()
    val conn = msgReceived.msg.conn
    val theirDIDDoc = conn.DIDDoc.toDIDDoc
    val updatedPairwiseRel = myPairwiseRelationships(connId).copy(theirDIDDoc = Option(theirDIDDoc))
    myPairwiseRelationships += (connId -> updatedPairwiseRel)
    expectMsgOnWebhook[ConnResponseSent]()
    expectMsgOnWebhook[Complete]().msg
  }
  /**
   *
   * @param msg the message to be sent
   * @param threadOpt the msg thread
   * @param applyToJsonMsg function to apply to message json before gets packed,
   *                       mostly useful to test failure/negative scenarios
   *                       where we want to modify the final json string to test
   *                       how verity process/responds it.
   * @param expectedRespStatus expected http response status
   * @return
   */
  def sendMsg(msg: Any,
              threadOpt: Option[MsgThread] = None,
              applyToJsonMsg: String => String = { msg => msg},
              expectedRespStatus: StatusCode = OK): HttpResponse

  /**
   *
   * @param connId connection for which the message needs to be packed
   * @param msg the message to be sent
   * @param threadOpt the msg thread
   * @param applyToJsonMsg function to apply to message json before gets packed,
   *                       mostly useful to test failure/negative scenarios
   *                       where we want to modify the final json string to test
   *                       how verity process/responds it.
   * @param expectedRespStatus expected http response status
   * @return
   */
  def sendMsgForConn(connId: String,
                     msg: Any,
                     threadOpt: Option[MsgThread] = None,
                     applyToJsonMsg: String => String = { msg => msg},
                     expectedRespStatus: StatusCode = OK): HttpResponse

  def provisionVerityEdgeAgent(): AgentCreated = {
    provisionVerityAgentBase(CreateEdgeAgent(localAgentDidPair.verKey, None))
  }

  //used to update enterprise name and logo url
  def sendUpdateConfig(updateConfigReq: UpdateConfigReqMsg): ConfigResult = {
    val typeStr = typeStrFromMsgType(EVERNYM_QUALIFIER, "update-configs", MFV_0_6, "update")
    val updateConfigJson = JsonMsgUtil.createJsonString(typeStr, updateConfigReq)
    val routedPackedMsg = packForMyVerityAgent(updateConfigJson)
    checkOKResponse(sendPOST(routedPackedMsg))
    expectMsgOnWebhook[ConfigResult]().msg
  }

  protected def addForRel(connId: String, jsonMsgParam: JsonMsgBuilder): JsonMsgBuilder = {
    val relationship = myPairwiseRelationships(connId)
    jsonMsgParam.forRelDID(relationship.verityAgentDIDPair.get.did)
  }

  def defaultAuthentication: Option[ComMethodAuthentication] = oauthParam.map { _ =>
    ComMethodAuthentication(
      "OAuth2",
      "v1",
      Map(
        "url"           -> msgListener.oAuthAccessTokenEndpoint,
        "grant_type"    -> "client_credentials",
        "client_id"     -> "client_id",           //dummy data
        "client_secret" -> "client_secret"        //dummy data
      )
    )
  }

  def msgListener: MsgListenerBase[_]
  def expectMsgOnWebhook[T: ClassTag](timeout: Duration = Duration(60, SECONDS)): ReceivedMsgParam[T]
  def resetPlainMsgsCounter: ReceivedMsgCounter = msgListener.resetPlainMsgsCounter
  def resetAuthedMsgsCounter: ReceivedMsgCounter = msgListener.resetAuthedMsgsCounter
  def resetFailedAuthedMsgsCounter: ReceivedMsgCounter = msgListener.resetFailedAuthedMsgsCounter
}

/**
 * contains helper methods for issuer sdk side of the operations
 *
 * @param param sdk parameters
 */
abstract class IssuerVerifierSdk(param: SdkParam, executionContext: ExecutionContext, oauthParam: Option[OAuthParam]=None)
  extends VeritySdkBase(param, executionContext, oauthParam) {

  def appConfig: AppConfig = testAppConfig

  def registerWebhookWithoutOAuth(id: Option[String] = None): ComMethodUpdated = {
    registerWebhookBase(id, None)
  }

  def registerWebhook(id: Option[String] = None,
                      authentication: Option[ComMethodAuthentication]=None): ComMethodUpdated = {
    registerWebhookBase(id, authentication orElse defaultAuthentication)
  }

  private def registerWebhookBase(id: Option[String],
                                  authentication: Option[ComMethodAuthentication]=None): ComMethodUpdated = {
    msgListener.setCheckAuth(authentication.isDefined)
    val packaging = Option(ComMethodPackaging(MPF_INDY_PACK.toString, Option(Set(myLocalAgentVerKey))))
    val updateComMethod = UpdateComMethodReqMsg(
      ComMethod(
        id.getOrElse("webhook"),
        COM_METHOD_TYPE_HTTP_ENDPOINT,
        msgListener.webhookEndpoint,
        packaging,
        authentication)
    )
    val typeStr = typeStrFromMsgType(EVERNYM_QUALIFIER, MSG_FAMILY_CONFIGS, MFV_0_6, MSG_TYPE_UPDATE_COM_METHOD)
    val updateComMethodJson = JsonMsgUtil.createJsonString(typeStr, updateComMethod)
    val routedPackedMsg = packForMyVerityAgent(updateComMethodJson)
    val cmu = parseAndUnpackResponse[ComMethodUpdated](checkOKResponse(sendPOST(routedPackedMsg))).msg
    cmu.id.nonEmpty shouldBe true
    cmu
  }

  def sendCreateRelationship(connId: String): ReceivedMsgParam[Created] = {
    val jsonMsgBuilder = JsonMsgBuilder(Create(label = Option(connId), None))
    val routedPackedMsg = packForMyVerityAgent(jsonMsgBuilder.jsonMsg)
    checkOKResponse(sendPOST(routedPackedMsg))
    val receivedMsg = expectMsgOnWebhook[Created]()
    val created = receivedMsg.msg
    myPairwiseRelationships += (connId -> PairwiseRel(None, Option(DidPair(created.did, created.verKey))))
    receivedMsg
  }

  def sendCreateConnectionInvitation(connId: String, thread: Option[MsgThread]): Invitation = {
    sendMsgForConn(connId, ConnectionInvitation(), thread)
    val receivedMsg = expectMsgOnWebhook[Invitation]()
    receivedMsg.msg
  }

  def sendCreateOOBInvitation(connId: String, thread: Option[MsgThread]): Invitation = {
    sendMsgForConn(connId, OutOfBandInvitation(), thread)
    val receivedMsg = expectMsgOnWebhook[Invitation]()
    receivedMsg.msg
  }

  def sendMsg(msg: Any,
              threadOpt: Option[MsgThread] = None,
              applyToJsonMsg: String => String = { msg => msg},
              expectedRespStatus: StatusCode = OK): HttpResponse = {
    val jsgMsgBuilder = JsonMsgBuilder(msg, threadOpt, applyToJsonMsg)
    val routedPackedMsg = packForMyVerityAgent(jsgMsgBuilder.jsonMsg)
    checkResponse(sendPOST(routedPackedMsg), expectedRespStatus)
  }

  def sendMsgForConn(connId: String,
                     msg: Any,
                     threadOpt: Option[MsgThread] = None,
                     applyToJsonMsg: String => String = { msg => msg},
                     expectedRespStatus: StatusCode = OK): HttpResponse = {
    val routedPackedMsg = addForRelAndPackForConn(connId, JsonMsgBuilder(msg, threadOpt, applyToJsonMsg))
    checkResponse(sendPOST(routedPackedMsg), expectedRespStatus)
  }

  private def addForRelAndPackForConn(connId: String, jsonMsgBuilder: JsonMsgBuilder): Array[Byte] = {
    val forRelMsg = addForRel(connId, jsonMsgBuilder).jsonMsg
    val verityAgentPackedMsg = packFromLocalAgentKey(forRelMsg, Set(KeyParam.fromVerKey(verityAgentDidPair.verKey)))
    prepareFwdMsg(agencyDID, verityAgentDidPair.did, verityAgentPackedMsg)
  }

  /**
   * this webhook expects packed messages
   * @tparam T expected message type
   * @return
   */
  def expectMsgOnWebhook[T: ClassTag](timeout: Duration = Duration(60, SECONDS)): ReceivedMsgParam[T] = {
    val msg = msgListener.expectMsg(timeout)
    try {
      unpackMsg(msg)
    } catch {
      case _: UnexpectedMsgException =>
        //TODO: This is temporary workaround to fix the intermittent failure around message ordering
        // should analyze it and see if there is any better way to fix it
        msgListener.addToQueue(msg)
        expectMsgOnWebhook(timeout)
    }
  }

  val msgListener: MsgListenerBase[Array[Byte]] = {
    val port = PortProvider.getFreePort
    val ml = new PackedMsgListener(port, oauthParam)(system)
    ml.setCheckAuth(oauthParam.isDefined)
    ml
  }

}

case class IssuerSdk(param: SdkParam,
                     executionContext: ExecutionContext,
                     oauthParam: Option[OAuthParam]=None)
  extends IssuerVerifierSdk(param, executionContext, oauthParam)

case class VerifierSdk(param: SdkParam,
                       executionContext: ExecutionContext,
                       oauthParam: Option[OAuthParam]=None)
  extends IssuerVerifierSdk(param, executionContext, oauthParam)

case class IssuerRestSDK(param: SdkParam,
                         executionContext: ExecutionContext,
                         oauthParam: Option[OAuthParam]=None)
  extends VeritySdkBase(param, executionContext, oauthParam) {

  def appConfig: AppConfig = testAppConfig
  import scala.collection.immutable

  def registerWebhookWithoutOAuth(): ComMethodUpdated = {
    registerWebhookBase(None, None)
  }

  def registerWebhook(id: Option[String],
                      authentication: Option[ComMethodAuthentication]=None): ComMethodUpdated = {
    registerWebhookBase(id, authentication orElse defaultAuthentication)
  }

  private def registerWebhookBase(id: Option[String],
                                  authentication: Option[ComMethodAuthentication]=None): ComMethodUpdated = {
    msgListener.setCheckAuth(authentication.isDefined)
    val packaging = Option(ComMethodPackaging(MPF_PLAIN.toString, None))
    val updateComMethodJson = {
      val updateComMethod = UpdateComMethodReqMsg(
        ComMethod(
          id.getOrElse("webhook"),
          COM_METHOD_TYPE_HTTP_ENDPOINT,
          msgListener.webhookEndpoint,
          packaging,
          authentication
        )
      )
      val typeStr = typeStrFromMsgType(EVERNYM_QUALIFIER, MSG_FAMILY_CONFIGS, MFV_0_6, MSG_TYPE_UPDATE_COM_METHOD)
      JsonMsgUtil.createJsonString(typeStr, updateComMethod)
    }
    sendPostReqBase(
      MSG_FAMILY_CONFIGS,
      MFV_0_6,
      updateComMethodJson,
      UUID.randomUUID().toString,
      verityAgentDidPair.did, myDIDApiKey
    )
    val cmu = expectMsgOnWebhook[ComMethodUpdated]().msg
    cmu.id.nonEmpty shouldBe true
    cmu
  }

  def sendCreateRelationship(connId: String): ReceivedMsgParam[Created] = {
    val resp = sendMsg(Create(None, None))
    resp.status shouldBe Accepted
    val rmp = expectMsgOnWebhook[Created]()
    myPairwiseRelationships += (connId -> PairwiseRel(None, Option(DidPair(rmp.msg.did, rmp.msg.verKey))))
    rmp
  }

  def sendCreateConnectionInvitation(connId: String, thread: Option[MsgThread]): Invitation = {
    sendMsgForConn(connId, ConnectionInvitation(), thread)
    val receivedMsg = expectMsgOnWebhook[Invitation]()
    receivedMsg.msg
  }

  def sendMsg(msg: Any,
              threadOpt: Option[MsgThread] = None,
              applyToJsonMsg: String => String = { msg => msg },
              expectedRespStatus: StatusCode = Accepted): HttpResponse = {
    val jsonMsgBuilder = JsonMsgBuilder(msg, threadOpt, applyToJsonMsg)
    checkResponse(sendPostReqBase(jsonMsgBuilder, verityAgentDidPair.did, myDIDApiKey), Accepted)
  }

  def sendMsgForConn(connId: String,
                     msg: Any,
                     threadOpt: Option[MsgThread] = None,
                     applyToJsonMsg: String => String = { msg => msg},
                     expectedRespStatus: StatusCode = Accepted): HttpResponse = {
    val jsonMsgBuilder = addForRel(connId, JsonMsgBuilder(msg, threadOpt, applyToJsonMsg))
    checkResponse(sendPostReqBase(jsonMsgBuilder, verityAgentDidPair.did, myDIDApiKey), expectedRespStatus)
  }

  private def sendPostReqBase(jsonMsgBuilder: JsonMsgBuilder,
                              route: String,
                              routeApiKey: String): HttpResponse = {
    sendPostReqBase(
      jsonMsgBuilder.msgFamily.name,
      jsonMsgBuilder.msgFamily.version,
      jsonMsgBuilder.jsonMsg,
      jsonMsgBuilder.threadId,
      route,
      routeApiKey
    )
  }

  private def sendPostReqBase(msgFamilyName: MsgFamilyName,
                              msgFamilyVersion: MsgFamilyVersion,
                              jsonMsg: String,
                              threadId: ThreadId,
                              route: String,
                              routeApiKey: String): HttpResponse = {
    val url = s"${param.verityRestApiUrl}/$route/$msgFamilyName/$msgFamilyVersion/$threadId"
    sendPostJsonReqToUrl(jsonMsg, url, routeApiKey)
  }

  def sendGetStatusReqForConn[T: ClassTag](connId: String,
                                           msgFamily: MsgFamily,
                                           threadOpt: Option[MsgThread] = None): RestGetResponse[T] = {
    val forRel = myPairwiseRelationships(connId).myVerityAgentDID
    val queryParam = Option(s"~for_relationship=$forRel")
    sendGetReqBase(msgFamily, verityAgentDidPair.did, myDIDApiKey, threadOpt, queryParam)
  }

  def sendGetStatusReq[T: ClassTag](threadOpt: Option[MsgThread] = None): RestGetResponse[T] = {
    val msgFamily = MsgFamilyHelper.getMsgFamily
    sendGetReqBase(msgFamily, verityAgentDidPair.did, myDIDApiKey, threadOpt)
  }

  private def sendGetReqBase[T: ClassTag](msgFamily: MsgFamily,
                                          route: String,
                                          routeApiKey: String,
                                          threadOpt: Option[MsgThread] = None,
                                          queryParamOpt: Option[String]=None): RestGetResponse[T] = {
    val threadId = threadOpt.flatMap(_.thid).getOrElse(UUID.randomUUID.toString)
    val url = s"${param.verityRestApiUrl}/$route/${msgFamily.name}/${msgFamily.version}/$threadId" +
      queryParamOpt.map(qp => s"?$qp").getOrElse("")
    val resp = HttpUtil.parseHttpResponseAsString(sendGetJsonReqToUrl(url, routeApiKey))
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

  private def computeSignature(verKey: VerKeyStr): String = {
    val signedMsg = testWalletAPI.executeSync[SignedMsg](
      SignMsg(KeyParam.fromVerKey(verKey), verKey.getBytes))(walletAPIParam)
    Base58Util.encode(signedMsg.msg)
  }

  /**
   * this webhook expects json messages
   * @tparam T
   * @return
   */
  def expectMsgOnWebhook[T: ClassTag](timeout: Duration = Duration(60, SECONDS)): ReceivedMsgParam[T] = {
    val msg = msgListener.expectMsg(timeout)
    try {
      ReceivedMsgParam(msg)
    } catch {
      case _: UnexpectedMsgException =>
        //TODO: This is temporary workaround to fix the intermittent failure around message ordering
        // should analyze it and see if there is any better way to fix it
        msgListener.addToQueue(msg)
        expectMsgOnWebhook(timeout)
    }
  }

  val msgListener: MsgListenerBase[String] = {
    val port = PortProvider.getFreePort
    val ml = new JsonMsgListener(port, oauthParam)(system)
    ml.setCheckAuth(oauthParam.isDefined)
    ml
  }
}

case class RestGetResponse[T](result: T, status: String)
