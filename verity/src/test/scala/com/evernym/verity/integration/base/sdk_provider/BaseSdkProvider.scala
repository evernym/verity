package com.evernym.verity.integration.base.sdk_provider

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.model._
import com.evernym.verity.actor.agent.MsgPackFormat.MPF_INDY_PACK
import com.evernym.verity.actor.wallet._
import com.evernym.verity.did.didcomm.v1.{Thread => MsgThread}
import com.evernym.verity.actor.AgencyPublicDid
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.agentmsg.msgcodec.jackson.JacksonMsgCodec
import com.evernym.verity.agentmsg.msgpacker.AgentMsgPackagingUtil
import com.evernym.verity.libindy.wallet.LibIndyWalletProvider
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.protocols.agentprovisioning.v_0_7.AgentProvisioningMsgFamily.AgentCreated
import com.evernym.verity.protocol.protocols.connections.v_1_0.Msg.ConnResponse
import com.evernym.verity.protocol.protocols.connections.v_1_0.Msg
import com.evernym.verity.protocol.protocols.relationship.v_1_0.Signal.Invitation
import com.evernym.verity.protocol.protocols.writeSchema.{v_0_6 => writeSchema0_6}
import com.evernym.verity.protocol.protocols.writeCredentialDefinition.{v_0_6 => writeCredDef0_6}
import com.evernym.verity.testkit.{BasicSpec, LegacyWalletAPI}
import com.evernym.verity.util.Base64Util
import com.evernym.verity.vault.{KeyParam, WalletAPIParam}
import com.evernym.verity.util2.ServiceEndpoint
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.actor.testkit.actor.ActorSystemVanilla
import com.evernym.verity.agentmsg.msgfamily.ConfigDetail
import com.evernym.verity.agentmsg.msgfamily.configs.UpdateConfigReqMsg
import com.evernym.verity.did.didcomm.v1.messages.{MsgFamily, MsgId}
import com.evernym.verity.did.{DidPair, DidStr, VerKeyStr}
import com.evernym.verity.integration.base.verity_provider.{VerityEnv, VerityEnvUrlProvider}
import com.evernym.verity.ledger.LedgerTxnExecutor
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByName
import com.evernym.verity.observability.metrics.NoOpMetricsWriter
import com.evernym.verity.protocol.engine.util.DIDDoc
import com.evernym.verity.protocol.protocols
import com.evernym.verity.protocol.protocols.issuersetup.v_0_6.{Create, PublicIdentifierCreated}
import com.typesafe.scalalogging.Logger
import org.json.JSONObject
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.util.UUID
import scala.collection.JavaConverters._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.{Duration, FiniteDuration, SECONDS}
import scala.reflect.ClassTag
import scala.util.Try


trait SdkProvider { this: BasicSpec =>

  def setupIssuerSdk(verityEnv: VerityEnv, executionContext: ExecutionContext, walletExecutionContext: ExecutionContext, oauthParam: Option[OAuthParam]=None): IssuerSdk =
    IssuerSdk(buildSdkParam(verityEnv), executionContext, walletExecutionContext, oauthParam)
  def setupIssuerRestSdk(verityEnv: VerityEnv, executionContext: ExecutionContext, walletExecutionContext: ExecutionContext, oauthParam: Option[OAuthParam]=None): IssuerRestSDK =
    IssuerRestSDK(buildSdkParam(verityEnv), executionContext, walletExecutionContext, oauthParam)
  def setupVerifierSdk(verityEnv: VerityEnv, executionContext: ExecutionContext, walletExecutionContext: ExecutionContext, oauthParam: Option[OAuthParam]=None): VerifierSdk =
    VerifierSdk(buildSdkParam(verityEnv), executionContext, walletExecutionContext, oauthParam)

  def setupHolderSdk(
                      verityEnv: VerityEnv,
                      ledgerTxnExecutor: LedgerTxnExecutor,
                      executionContext: ExecutionContext,
                      walletExecutionContext: ExecutionContext
                    ): HolderSdk =
    HolderSdk(buildSdkParam(verityEnv), Option(ledgerTxnExecutor), executionContext, walletExecutionContext, None)

  def setupHolderSdk(
                      verityEnv: VerityEnv,
                      ledgerTxnExecutor: Option[LedgerTxnExecutor],
                      executionContext: ExecutionContext,
                      walletExecutionContext: ExecutionContext
                    ): HolderSdk =
    HolderSdk(buildSdkParam(verityEnv), ledgerTxnExecutor, executionContext, walletExecutionContext, None)

  def setupHolderSdk(
                      verityEnv: VerityEnv,
                      oauthParam: OAuthParam,
                      executionContext: ExecutionContext,
                      walletExecutionContext: ExecutionContext
                    ): HolderSdk =
    HolderSdk(buildSdkParam(verityEnv), None, executionContext, walletExecutionContext, Option(oauthParam))

  def setupHolderSdk(
                      verityEnv: VerityEnv,
                      ledgerTxnExecutor: Option[LedgerTxnExecutor],
                      oauthParam: Option[OAuthParam],
                      executionContext: ExecutionContext,
                      walletExecutionContext: ExecutionContext
                    ): HolderSdk =
    HolderSdk(buildSdkParam(verityEnv), ledgerTxnExecutor, executionContext, walletExecutionContext, oauthParam)

  private def buildSdkParam(verityEnv: VerityEnv): SdkParam = {
    SdkParam(VerityEnvUrlProvider(verityEnv.nodes))
  }

  def provisionEdgeAgent(sdk: VeritySdkBase): Unit = {
    sdk.fetchAgencyKey()
    sdk.provisionVerityEdgeAgent()
    sdk.registerWebhook()
    sdk.sendUpdateConfig(UpdateConfigReqMsg(Set(ConfigDetail("name", "issuer-name"),
      ConfigDetail("logoUrl", "issuer-logo-url"))))
  }

  def provisionCloudAgent(holderSDK: HolderSdk): Unit = {
    holderSDK.fetchAgencyKey()
    holderSDK.provisionVerityCloudAgent()
  }

  def establishConnection(connId: String, inviterSDK: VeritySdkBase, inviteeSDK: HolderSdk): Unit = {
    val receivedMsg = inviterSDK.sendCreateRelationship(connId)
    val lastReceivedThread = receivedMsg.threadOpt
    val invitation = inviterSDK.sendCreateConnectionInvitation(connId, lastReceivedThread)

    inviteeSDK.sendCreateNewKey(connId)
    inviteeSDK.sendConnReqForInvitation(connId, invitation)

    inviterSDK.expectConnectionComplete(connId)
  }

  def setupIssuer(issuerSDK: VeritySdkBase): Unit = {
    issuerSDK.sendMsg(Create())
    issuerSDK.expectMsgOnWebhook[PublicIdentifierCreated]()
  }

  def writeSchema(issuerSDK: VeritySdkBase, write: writeSchema0_6.Write): SchemaId = {
    issuerSDK.sendMsg(write)
    val receivedMsg = issuerSDK.expectMsgOnWebhook[writeSchema0_6.StatusReport]()
    receivedMsg.msg.schemaId
  }

  def writeCredDef(issuerSDK: VeritySdkBase, write: writeCredDef0_6.Write): CredDefId = {
    issuerSDK.sendMsg(write)
    val receivedMsg = issuerSDK.expectMsgOnWebhook[writeCredDef0_6.StatusReport]()
    receivedMsg.msg.credDefId
  }

  type SchemaId = String
  type CredDefId = String
}


/**
 * a base sdk class for issuer/holder sdk
 * @param param sdk parameters
 */
abstract class SdkBase(param: SdkParam,
                       executionContext: ExecutionContext,
                       walletExecutionContext: ExecutionContext) extends Matchers {

  implicit val ec: ExecutionContext = executionContext
  type ConnId = String

  def fetchAgencyKey(): AgencyPublicDid = {
    val resp = checkOKResponse(sendGET("agency"))
    val apd = parseHttpResponseAs[AgencyPublicDid](resp)
    require(apd.DID.nonEmpty, "agency DID should not be empty")
    require(apd.verKey.nonEmpty, "agency verKey should not be empty")
    storeTheirKey(DidPair(apd.didPair.did, apd.didPair.verKey))
    agencyPublicDidOpt = Option(apd)
    apd
  }

  protected def provisionVerityAgentBase(createAgentMsg: Any): AgentCreated = {
    val jsonMsgBuilder = JsonMsgBuilder(createAgentMsg)
    val packedMsg = packFromLocalAgentKey(jsonMsgBuilder.jsonMsg, Set(KeyParam.fromVerKey(agencyVerKey)))
    val routedPackedMsg = prepareFwdMsg(agencyDID, agencyDID, packedMsg)
    val receivedMsgParam = parseAndUnpackResponse[AgentCreated](sendPOST(routedPackedMsg))
    val agentCreated = receivedMsgParam.msg
    require(agentCreated.selfDID.trim.nonEmpty, "agent provisioning selfDID can't be empty")
    require(agentCreated.agentVerKey.trim.nonEmpty, "agent provisioning verKey can't be empty")
    verityAgentDidPairOpt = Option(DidPair(agentCreated.selfDID, agentCreated.agentVerKey))
    storeTheirKey(DidPair(agentCreated.selfDID, agentCreated.agentVerKey))
    agentCreated
  }

  def sendToRoute[T: ClassTag](msg: Any, fwdToDID: DidStr): ReceivedMsgParam[T] = {
    val jsonMsgBuilder = JsonMsgBuilder(msg)
    val packedMsg = packFromLocalAgentKey(jsonMsgBuilder.jsonMsg, Set(KeyParam.fromVerKey(agencyVerKey)))
    val routedPackedMsg = prepareFwdMsg(agencyDID, fwdToDID, packedMsg)
    parseAndUnpackResponse[T](sendPOST(routedPackedMsg))
  }

  protected def packForMyVerityAgent(msg: String): Array[Byte] = {
    val packedMsgForVerityAgent = packFromLocalAgentKey(msg, Set(KeyParam.fromVerKey(verityAgentDidPair.verKey)))
    prepareFwdMsg(agencyDID, verityAgentDidPair.did, packedMsgForVerityAgent)
  }

  protected def packFromLocalAgentKey(msg: String, recipVerKeyParams: Set[KeyParam]): Array[Byte] = {
    packMsg(msg, recipVerKeyParams, Option(KeyParam.fromVerKey(myLocalAgentVerKey)))
  }

  /**
   *
   * @param recipDID recipient of fwd msg
   * @param fwdToDID destination of the given 'msg'
   * @param msg the message to be sent
   * @return
   */
  protected def prepareFwdMsg(recipDID: DidStr, fwdToDID: DidStr, msg: Array[Byte]): Array[Byte] = {
    val fwdJson = AgentMsgPackagingUtil.buildFwdJsonMsg(MPF_INDY_PACK, fwdToDID, msg)
    val senderKey = if (recipDID == agencyDID) None else Option(KeyParam.fromVerKey(myLocalAgentVerKey))
    packMsg(fwdJson, Set(KeyParam.fromDID(recipDID)), senderKey)
  }

  protected def packMsg(msg: String,
                      recipVerKeyParams: Set[KeyParam],
                      senderKeyParam: Option[KeyParam]): Array[Byte] = {
    val msgBytes = msg.getBytes()
    val pm = testWalletAPI.executeSync[PackedMsg](
      PackMsg(msgBytes, recipVerKeyParams, senderKeyParam))
    pm.msg
  }

  def unpackMsg[T: ClassTag](msg: Array[Byte]): ReceivedMsgParam[T] = {
    val json = testWalletAPI.executeSync[UnpackedMsg](UnpackMsg(msg)).msgString
    val jsonObject = new JSONObject(json)
    ReceivedMsgParam(jsonObject.getString("message"))
  }

  protected def checkOKResponse(resp: HttpResponse): HttpResponse = {
    checkResponse(resp, OK)
  }

  protected def checkResponse(resp: HttpResponse, expected: StatusCode): HttpResponse = {
    val json = parseHttpResponseAsString(resp)
    require(resp.status.intValue() == expected.intValue,
      s"http response '${resp.status}' was not equal to expected '${expected.value}': $json")
    resp
  }

  protected def sendPOST(payload: Array[Byte]): HttpResponse =
    sendBinaryReqToUrl(payload, param.verityPackedMsgUrl)

  protected def sendBinaryReqToUrl(payload: Array[Byte], url: String): HttpResponse = {
    awaitFut(
      Http().singleRequest(
        HttpRequest(
          method=HttpMethods.POST,
          uri = url,
          entity = HttpEntity(
            ContentTypes.`application/octet-stream`,
            payload
          )
        )
      )
    )
  }

  protected def sendGET(pathSuffix: String): HttpResponse = {
    val actualPath = param.verityBaseUrl + s"/$pathSuffix"
    awaitFut(
      Http().singleRequest(
        HttpRequest(
          method=HttpMethods.GET,
          uri = actualPath,
          entity = HttpEntity.Empty
        )
      )
    )
  }

  protected def parseAndUnpackResponse[T: ClassTag](resp: HttpResponse): ReceivedMsgParam[T] = {
    checkOKResponse(resp)
    val packedMsg = parseHttpResponseAsString(resp)
    unpackMsg[T](packedMsg.getBytes)
  }

  def parseHttpResponseAs[T: ClassTag](resp: HttpResponse): T = {
    val respString = parseHttpResponseAsString(resp)
    JacksonMsgCodec.fromJson[T](respString)
  }

  def parseHttpResponseAsString(resp: HttpResponse): String = {
    awaitFut(resp.entity.dataBytes.runReduce(_ ++ _).map(_.utf8String))
  }

  protected def awaitFut[T](fut: Future[T]): T = {
    Await.result(fut, Duration(25, SECONDS))
  }

  def randomUUID(): String = UUID.randomUUID().toString
  def randomSeed(): String = randomUUID().replace("-", "")

  implicit val walletAPIParam: WalletAPIParam = WalletAPIParam(UUID.randomUUID().toString)
  implicit val system: ActorSystem = ActorSystemVanilla(randomUUID())

  var agencyPublicDidOpt: Option[AgencyPublicDid] = None

  //local agent (on sdk side)
  val localAgentDidPair: DidPair = createNewKey()

  //verity agent (edge/cloud)
  var verityAgentDidPairOpt: Option[DidPair] = None

  var myPairwiseRelationships: Map[ConnId, PairwiseRel] = Map.empty

  def createNewKey(seed: Option[String] = Option(randomSeed())): DidPair = {
    testWalletAPI.executeSync[NewKeyCreated](CreateNewKey(seed = seed)).didPair
  }

  def storeTheirKey(didPair: DidPair): Unit = {
    testWalletAPI.executeSync[TheirKeyStored](StoreTheirKey(didPair.did, didPair.verKey))
  }

  def agencyPublicDid: AgencyPublicDid = agencyPublicDidOpt.getOrElse(
    throw new RuntimeException("agency key is not yet fetched")
  )
  def verityAgentDidPair: DidPair = verityAgentDidPairOpt.getOrElse(
    throw new RuntimeException("verity agent not yet created")
  )
  def agencyDID: DidStr = agencyPublicDid.DID
  def agencyVerKey: VerKeyStr = agencyPublicDid.verKey
  def myLocalAgentVerKey: VerKeyStr = localAgentDidPair.verKey

  protected lazy val testAppConfig = new TestAppConfig()

  protected lazy val testWalletAPI: LegacyWalletAPI = {
    val walletProvider = LibIndyWalletProvider
    val walletAPI = new LegacyWalletAPI(testAppConfig, walletProvider, None, NoOpMetricsWriter(), walletExecutionContext)
    walletAPI.executeSync[WalletCreated.type](CreateWallet())
    walletAPI
  }

  /**
   * checks message orders (sender and received)
   * @param threadOpt
   * @param expectedSenderOrder
   * @param expectedReceivedOrder assuming two participants as of now
   */
  def checkMsgOrders(threadOpt: Option[MsgThread],
                     expectedSenderOrder: Int,
                     expectedReceivedOrder: Map[ConnId, Int]): Unit = {
    val receivedOrderByDid = expectedReceivedOrder.map { case (connId, count) =>
      val theirDID = myPairwiseRelationships(connId).theirDIDDoc.get.getDID
      theirDID -> count
    }
      threadOpt.flatMap(_.sender_order) shouldBe Some(expectedSenderOrder)
    threadOpt.map(_.received_orders) shouldBe Some(receivedOrderByDid)
  }
}

case class PairwiseRel(myLocalAgentDIDPair: Option[DidPair] = None,
                       verityAgentDIDPair: Option[DidPair] = None,
                       theirDIDDoc: Option[DIDDoc] = None) {

  def myLocalAgentDIDPairReq: DidPair = myLocalAgentDIDPair.getOrElse(throw new RuntimeException("my pairwise key not exists"))
  def myPairwiseDID: DidStr = myLocalAgentDIDPairReq.did
  def myPairwiseVerKey: VerKeyStr = myLocalAgentDIDPairReq.verKey

  def myVerityAgentDIDPairReq: DidPair = verityAgentDIDPair.getOrElse(throw new RuntimeException("verity agent key not exists"))
  def myVerityAgentDID: DidStr = myVerityAgentDIDPairReq.did
  def myVerityAgentVerKey: VerKeyStr = myVerityAgentDIDPairReq.verKey

  def theirDIDDocReq: DIDDoc = theirDIDDoc.getOrElse(throw new RuntimeException("their DIDDoc not exists"))
  def theirAgentVerKey: VerKeyStr = theirDIDDocReq.verkey
  def theirRoutingKeys: Vector[VerKeyStr] = theirDIDDocReq.routingKeys
  def theirServiceEndpoint: ServiceEndpoint = theirDIDDocReq.endpoint

  def withProvisionalTheirDidDoc(invitation: Invitation): PairwiseRel = {
    val theirServiceDetail = extractTheirServiceDetail(invitation.inviteURL).getOrElse(
      throw new RuntimeException("invalid url: " + invitation.inviteURL)
    )
    val didDoc = DIDDoc(
      theirServiceDetail.verKey,    //TODO: come back to this if assigning ver key as id starts causing issues
      theirServiceDetail.verKey,
      theirServiceDetail.serviceEndpoint,
      theirServiceDetail.routingKeys
    )
    copy(theirDIDDoc = Option(didDoc))
  }

  def extractTheirServiceDetail(inviteURL: String): Option[TheirServiceDetail] = {
    if (inviteURL.contains("c_i=")) {
      inviteURL.split("c_i=").lastOption.map { ciVal =>
        val ciValue = Base64Util.urlDecodeToStr(ciVal)
        val ciJson = new JSONObject(ciValue)
        val theirVerKey = ciJson.getJSONArray("recipientKeys").toList.asScala.head.toString
        val theirRoutingKeys = ciJson.getJSONArray("routingKeys").toList.asScala.map(_.toString).toVector
        val theirServiceEndpoint = ciJson.getString("serviceEndpoint")
        TheirServiceDetail(theirVerKey, theirRoutingKeys, theirServiceEndpoint)
      }
    } else if (inviteURL.contains("oob=")) {
      inviteURL.split("\\?oob=").lastOption.map { oobVal =>
        val oobValue = new String(Base64Util.getBase64UrlDecoded(oobVal))
        val oobJson = new JSONObject(oobValue)
        val service = new JSONObject(oobJson.getJSONArray("service").asScala.head.toString)
        val theirVerKey = service.getJSONArray("recipientKeys").toList.asScala.head.toString
        val theirRoutingKeys = service.getJSONArray("routingKeys").toList.asScala.map(_.toString).toVector
        val theirServiceEndpoint = service.getString("serviceEndpoint")
        TheirServiceDetail(theirVerKey, theirRoutingKeys, theirServiceEndpoint)
      }
    } else None
  }

  def withFinalTheirDidDoc(connResp: ConnResponse): PairwiseRel = {
    val conn_bytes = Base64Util.getBase64UrlDecoded(connResp.`connection~sig`.sig_data)
    val connJson = new String(conn_bytes.drop(8), StandardCharsets.UTF_8)
    val conn = DefaultMsgCodec.fromJson[Msg.Connection](connJson)
    val toDidDoc = conn.did_doc.toDIDDoc
    copy(theirDIDDoc = Option(toDidDoc))
  }
}

object ReceivedMsgParam {

  def apply[T: ClassTag](msg: String): ReceivedMsgParam[T] = {
    val message = new JSONObject(msg)
    val threadOpt = Try {
      Option(DefaultMsgCodec.fromJson[MsgThread](message.getJSONObject("~thread").toString))
    }.getOrElse(None)
    val expMsg = DefaultMsgCodec.fromJson[T](message.toString)
    checkInvalidFieldValues(msg, expMsg)
    ReceivedMsgParam(expMsg, msg, None, threadOpt)
  }

  private def checkInvalidFieldValues(msgString: String, msg: Any): Unit = {
    //this condition would be true if the received message is different than expected message type
    // in which case the deserialized message fields will have null values
    if (msg.asInstanceOf[Product].productIterator.contains(null)) {
      throw new UnexpectedMsgException(s"expected message '${msg.getClass.getSimpleName}', but found: " + msgString)
    }
  }

  val logger: Logger = getLoggerByName(getClass.getName)
}

/**
 *
 * @param msg the received message
 * @param msgIdOpt message id used by verity agent to uniquely identify a message
 *                 (this will be only available for messages retrieved from CAS/EAS)
 * @param threadOpt received message's thread
 * @tparam T
 */
case class ReceivedMsgParam[T: ClassTag](msg: T,
                                         jsonMsgStr: String,
                                         msgIdOpt: Option[MsgId] = None,
                                         threadOpt: Option[MsgThread]=None) {
  def msgId: MsgId = msgIdOpt.getOrElse(throw new RuntimeException("msgId not available in received message"))
  def threadIdOpt: Option[ThreadId] = threadOpt.flatMap(_.thid)
}


case class SdkParam(verityEnvUrlProvider: VerityEnvUrlProvider) {

  /**
   * will provide verity url of one of the available (started) nodes
   * in round robin fashion
   * @return
   */
  private def verityUrl: String = {
    val verityUrls = verityEnvUrlProvider.availableNodeUrls
    if (verityUrls.isEmpty) throw new RuntimeException("no verity node available")
    lastVerityUrlUsedIndex = {
      if (lastVerityUrlUsedIndex >= verityUrls.size - 1) 0
      else lastVerityUrlUsedIndex + 1
    }
    verityUrls(lastVerityUrlUsedIndex)
  }
  var lastVerityUrlUsedIndex: Int = -1

  def verityBaseUrl: String = s"$verityUrl"
  def verityPackedMsgUrl: String = s"$verityUrl/agency/msg"
  def verityRestApiUrl: String = s"$verityUrl/api"
}

object JsonMsgBuilder {

  private val defaultJsonApply: String => String = { msg => msg }

  def apply(givenMsg: Any): JsonMsgBuilder =
    JsonMsgBuilder(givenMsg, None, None, defaultJsonApply)

  def apply(givenMsg: Any, threadOpt: Option[MsgThread]): JsonMsgBuilder =
    JsonMsgBuilder(givenMsg, threadOpt, None, defaultJsonApply)

  def apply(givenMsg: Any,
            threadOpt: Option[MsgThread],
            applyToJsonMsg: String => String): JsonMsgBuilder =
    JsonMsgBuilder(givenMsg, threadOpt, None, applyToJsonMsg)
}

case class JsonMsgBuilder(private val givenMsg: Any,
                          private val threadOpt: Option[MsgThread],
                          private val forRelId: Option[DidStr],
                          private val applyToJsonMsg: String => String = { msg => msg}) {

  lazy val thread = threadOpt.getOrElse(MsgThread(Option(UUID.randomUUID().toString)))
  lazy val threadId = thread.thid.getOrElse(throw new RuntimeException("thread id not available"))
  lazy val msgFamily = getMsgFamily(givenMsg)
  lazy val jsonMsg = {
    val basicMsg = createJsonString(givenMsg, msgFamily)
    val threadedMsg = withThreadIdAdded(basicMsg, thread)
    val relationshipMsg = forRelId match {
      case Some(did)  => addForRel(did, threadedMsg)
      case None       => threadedMsg
    }
    applyToJsonMsg(relationshipMsg)
  }

  def forRelDID(did: DidStr): JsonMsgBuilder = copy(forRelId = Option(did))

  private def createJsonString(msg: Any, msgFamily: MsgFamily): String = {
    val msgType = msgFamily.msgType(msg.getClass)
    val typeStr = MsgFamily.typeStrFromMsgType(msgType)
    JsonMsgUtil.createJsonString(typeStr, msg)
  }

  private def withThreadIdAdded(msg: String, thread: MsgThread): String = {
    val coreJson = new JSONObject(msg)
    val threadJSON = new JSONObject()
    thread.thid.foreach(threadJSON.put("thid", _))
    thread.pthid.foreach(threadJSON.put("pthid", _))
    //TODO: not adding 'sender_order' and 'received_orders' for now
    // can do it when need arises
    coreJson.put("~thread", threadJSON).toString
  }

  private def addForRel(did: DidStr, jsonMsg: String): String = {
    val jsonObject = new JSONObject(jsonMsg)
    jsonObject.put("~for_relationship", did).toString()
  }

  protected def getMsgFamily(msg: Any): MsgFamily = {
    MsgFamilyHelper.getMsgFamilyOpt(msg.getClass).getOrElse(
      throw new RuntimeException("message family not found for given message: " + msg.getClass.getSimpleName)
    )
  }

  //  protected def legacyType(name: String, ver: String): JSONObject = {
  //    val jsonObj = new JSONObject()
  //    jsonObj.put("name", name)
  //    jsonObj.put("ver", ver)
  //    jsonObj
  //  }
}

object JsonMsgUtil {
  def createJsonString(typeStr: String, msg: Any): String = {
    val coreJson = createJSONObject(msg)
    coreJson.put("@type", typeStr).toString
  }

  def createJSONObject(msg: Any): JSONObject = {
    val coreJson = DefaultMsgCodec.toJson(msg)
    new JSONObject(coreJson)
  }

}

object MsgFamilyHelper {

  val protoDefs: Seq[ProtoDef] = protocols.protocolRegistry.entries.map(_.protoDef)

  def getMsgFamilyOpt[T: ClassTag]: Option[MsgFamily] = {
    val clazz = implicitly[ClassTag[T]].runtimeClass
    getMsgFamilyOpt(clazz)
  }

  def getMsgFamily[T: ClassTag]: MsgFamily = {
    val clazz = implicitly[ClassTag[T]].runtimeClass
    getMsgFamilyOpt.getOrElse(
      throw new RuntimeException("message family not found for given message: " + clazz.getSimpleName)
    )
  }

  def getMsgFamilyOpt(clazz: Class[_]): Option[MsgFamily] = {
    val protoDefOpt =
      protoDefs
        .find { pd =>
          Try (pd.msgFamily.lookupAllMsgName(clazz).nonEmpty).getOrElse(false)
        }
    protoDefOpt.map(_.msgFamily)
  }

  def buildMsgTypeStr[T: ClassTag]: String = {
    val clazz = implicitly[ClassTag[T]].runtimeClass
    val msgType = MsgFamilyHelper.getMsgFamilyOpt.map(_.msgType(clazz))
    msgType.map(MsgFamily.typeStrFromMsgType)
      .getOrElse(throw new RuntimeException("message type not found in any registered protocol: " + clazz.getClass.getSimpleName))
  }
}

case class TheirServiceDetail(verKey: VerKeyStr, routingKeys: Vector[VerKeyStr], serviceEndpoint: ServiceEndpoint)

case class OAuthParam(tokenExpiresDuration: FiniteDuration)

class UnexpectedMsgException(msg: String) extends RuntimeException(msg)