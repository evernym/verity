package com.evernym.verity.integration.base.sdk_provider

import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import com.evernym.verity.actor.agent.MsgPackFormat.{MPF_INDY_PACK, MPF_MSG_PACK}
import com.evernym.verity.actor.wallet._
import com.evernym.verity.did.didcomm.v1.{Thread => MsgThread}
import com.evernym.verity.actor.AgencyPublicDid
import com.evernym.verity.actor.agent.MsgPackFormat
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.agentmsg.msgpacker.{AgentMsgPackagingUtil, AgentMsgParseUtil}
import com.evernym.verity.vdrtools.wallet.LibIndyWalletProvider
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.protocols.agentprovisioning.v_0_7.AgentProvisioningMsgFamily.AgentCreated
import com.evernym.verity.protocol.protocols.connections.v_1_0.Msg.ConnResponse
import com.evernym.verity.protocol.protocols.connections.v_1_0.Msg
import com.evernym.verity.protocol.protocols.relationship.v_1_0.Signal.Invitation
import com.evernym.verity.protocol.protocols.writeSchema.{v_0_6 => writeSchema0_6}
import com.evernym.verity.protocol.protocols.writeCredentialDefinition.{v_0_6 => writeCredDef0_6}
import com.evernym.verity.testkit.{BasicSpec, LegacyWalletAPI}
import com.evernym.verity.util.{Base64Util, MessagePackUtil, Util}
import com.evernym.verity.vault.{KeyParam, WalletAPIParam}
import com.evernym.verity.util2.ServiceEndpoint
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.actor.testkit.actor.ActorSystemVanilla
import com.evernym.verity.agentmsg.msgfamily.{BundledMsg_MFV_0_5, ConfigDetail, TypeDetail}
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil.{CREATE_MSG_TYPE_CONN_REQ, CREATE_MSG_TYPE_CONN_REQ_ANSWER, CREATE_MSG_TYPE_GENERAL, MSG_TYPE_CREATE_KEY, MSG_TYPE_CREATE_MSG, MSG_TYPE_DETAIL_ACCEPT_CONN_REQ, MSG_TYPE_DETAIL_CONN_REQ, MSG_TYPE_DETAIL_CREATE_AGENT, MSG_TYPE_DETAIL_CREATE_KEY, MSG_TYPE_MSG_DETAIL, MSG_TYPE_UPDATE_COM_METHOD, MSG_TYPE_UPDATE_CONFIGS}
import com.evernym.verity.agentmsg.msgfamily.configs.{ConfigsUpdatedRespMsg_MFV_0_5, UpdateConfigReqMsg}
import com.evernym.verity.agentmsg.msgfamily.pairwise.{AnswerInviteMsgDetail_MFV_0_5, ConnReqRespMsg_MFV_0_6, GeneralCreateMsgDetail_MFV_0_5, InviteCreateMsgDetail_MFV_0_5, KeyCreatedRespMsg_MFV_0_5, KeyCreatedRespMsg_MFV_0_6}
import com.evernym.verity.constants.Constants.COM_METHOD_TYPE_HTTP_ENDPOINT
import com.evernym.verity.did.didcomm.v1.messages.{MsgFamily, MsgId}
import com.evernym.verity.did.{DidPair, DidStr, VerKeyStr}
import com.evernym.verity.integration.base.sdk_provider.JsonMsgUtil.createJsonString
import com.evernym.verity.integration.base.verity_provider.{VerityEnv, VerityEnvUrlProvider}
import com.evernym.verity.ledger.LedgerTxnExecutor
import com.evernym.verity.observability.logs.LoggingUtil.{getLoggerByClass, getLoggerByName}
import com.evernym.verity.observability.metrics.NoOpMetricsWriter
import com.evernym.verity.protocol.engine.Constants.{MSG_TYPE_CONNECT, MSG_TYPE_CREATE_AGENT, MSG_TYPE_SIGN_UP, MTV_1_0}
import com.evernym.verity.protocol.engine.util.DIDDoc
import com.evernym.verity.protocol.protocols
import com.evernym.verity.protocol.protocols.connecting.common.InviteDetail
import com.evernym.verity.protocol.protocols.issuersetup.v_0_6.{Create, PublicIdentifierCreated}
import com.evernym.verity.testkit.agentmsg.{CreateInviteResp_MFV_0_5, InviteAcceptedResp_MFV_0_5}
import com.evernym.verity.testkit.util.{AcceptConnReq_MFV_0_6, AgentCreated_MFV_0_5, AgentCreated_MFV_0_6, ComMethodUpdated_MFV_0_5, ConnReqAccepted_MFV_0_6, ConnReq_MFV_0_6, Connect_MFV_0_5, Connected_MFV_0_5, CreateAgent_MFV_0_5, CreateAgent_MFV_0_6, CreateKey_MFV_0_5, CreateKey_MFV_0_6, CreateMsg_MFV_0_5, InviteMsgDetail_MFV_0_5, MsgCreated_MFV_0_5, MsgsSent_MFV_0_5, SignUp_MFV_0_5, SignedUp_MFV_0_5, TestComMethod, TestConfigDetail, UpdateComMethod_MFV_0_5, UpdateConfigs_MFV_0_5}
import com.evernym.verity.util.MsgIdProvider.getNewMsgId
import com.evernym.verity.util2.Status.MSG_STATUS_ACCEPTED
import com.evernym.verity.testkit.util.HttpUtil._
import com.typesafe.scalalogging.Logger
import org.json.JSONObject
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.util.UUID
import scala.jdk.CollectionConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}


trait SdkProvider { this: BasicSpec =>

  def setupIssuerSdk(verityEnv: VerityEnv,
                     executionContext: ExecutionContext,
                     oauthParam: Option[OAuthParam]=None): IssuerSdk =
    IssuerSdk(buildSdkParam(verityEnv), executionContext, oauthParam)

  def setupIssuerRestSdk(verityEnv: VerityEnv,
                         executionContext: ExecutionContext,
                         oauthParam: Option[OAuthParam]=None): IssuerRestSDK =
    IssuerRestSDK(buildSdkParam(verityEnv), executionContext, oauthParam)

  def setupVerifierSdk(verityEnv: VerityEnv,
                       executionContext: ExecutionContext,
                       oauthParam: Option[OAuthParam]=None): VerifierSdk =
    VerifierSdk(buildSdkParam(verityEnv), executionContext, oauthParam)

  def setupHolderSdk(verityEnv: VerityEnv,
                     ledgerTxnExecutor: LedgerTxnExecutor,
                     executionContext: ExecutionContext): HolderSdk =
    HolderSdk(buildSdkParam(verityEnv), Option(ledgerTxnExecutor), executionContext, None)

  def setupHolderSdk(verityEnv: VerityEnv,
                     executionContext: ExecutionContext,
                     ledgerTxnExecutor: Option[LedgerTxnExecutor]): HolderSdk =
    HolderSdk(buildSdkParam(verityEnv), ledgerTxnExecutor, executionContext, None)

  def setupHolderSdk(verityEnv: VerityEnv,
                     oauthParam: OAuthParam,
                     executionContext: ExecutionContext): HolderSdk =
    HolderSdk(buildSdkParam(verityEnv), None, executionContext, Option(oauthParam))

  def setupHolderSdk(verityEnv: VerityEnv,
                     executionContext: ExecutionContext,
                     ledgerTxnExecutor: Option[LedgerTxnExecutor] = None,
                     oauthParam: Option[OAuthParam] = None): HolderSdk =
    HolderSdk(buildSdkParam(verityEnv), ledgerTxnExecutor, executionContext, oauthParam)

  def setupIssuerSdkAsync(verityEnv: Future[VerityEnv],
                          executionContext: ExecutionContext,
                          oauthParam: Option[OAuthParam] = None): Future[IssuerSdk] =
    verityEnv.map(env => {
      setupIssuerSdk(env, executionContext, oauthParam)
    })(executionContext)

  def setupIssuerRestSdkAsync(verityEnv: Future[VerityEnv],
                              executionContext: ExecutionContext,
                              oauthParam: Option[OAuthParam] = None): Future[IssuerRestSDK] =
    verityEnv.map(env => {
      setupIssuerRestSdk(env, executionContext, oauthParam)
    })(executionContext)

  def setupVerifierSdkAsync(verityEnv: Future[VerityEnv],
                            executionContext: ExecutionContext,
                            oauthParam: Option[OAuthParam] = None): Future[VerifierSdk] =
    verityEnv.map(env => {
      setupVerifierSdk(env, executionContext, oauthParam)
    })(executionContext)


  def setupHolderSdkAsync(verityEnv: Future[VerityEnv],
                          ledgerTxnExecutor: LedgerTxnExecutor,
                          executionContext: ExecutionContext): Future[HolderSdk] = {
    verityEnv.map {
      env => setupHolderSdk(env, ledgerTxnExecutor, executionContext)
    }(executionContext)
  }


  def setupHolderSdkAsync(verityEnv: Future[VerityEnv],
                          ledgerTxnExecutor: Option[LedgerTxnExecutor],
                          executionContext: ExecutionContext): Future[HolderSdk] =
    verityEnv.map(env => {
      setupHolderSdk(env, executionContext, ledgerTxnExecutor)
    })(executionContext)


  def setupHolderSdkAsync(verityEnv: Future[VerityEnv],
                          oauthParam: OAuthParam,
                          executionContext: ExecutionContext): Future[HolderSdk] =
    verityEnv.map(env => {
      setupHolderSdk(env, oauthParam, executionContext)
    })(executionContext)

  def setupHolderSdkAsync(verityEnv: Future[VerityEnv],
                          ledgerTxnExecutor: Option[LedgerTxnExecutor],
                          oauthParam: Option[OAuthParam],
                          executionContext: ExecutionContext): Future[HolderSdk] =
    verityEnv.map(env => {
      setupHolderSdk(env, executionContext, ledgerTxnExecutor, oauthParam)
    })(executionContext)

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

  def establishConnection(connId: String,
                          label: String,
                          inviterSDK: VeritySdkBase,
                          inviteeSDK: HolderSdk): Unit = {
    establishConnection(connId, inviterSDK, inviteeSDK, Option(label))
  }

  def establishConnection(connId: String,
                          inviterSDK: VeritySdkBase,
                          inviteeSDK: HolderSdk,
                          label: Option[String]=None): Unit = {
    val receivedMsg = inviterSDK.sendCreateRelationship(connId, label)
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
                       executionContext: ExecutionContext)
  extends LegacySdkBase_0_5
    with LegacySdkBase_0_6
    with Matchers {

  implicit val ec: ExecutionContext = executionContext
  type ConnId = String

  def fetchAgencyKey(): AgencyPublicDid = {
    val resp = checkOKResponse(sendGET(buildFullUrl("agency")))
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
    val receivedMsgParam = parseAndUnpackResponse[AgentCreated](checkOKResponse(sendPOST(routedPackedMsg)))
    val agentCreated = receivedMsgParam.msg
    require(agentCreated.selfDID.trim.nonEmpty, "agent provisioning selfDID can't be empty")
    require(agentCreated.agentVerKey.trim.nonEmpty, "agent provisioning verKey can't be empty")
    updateVerityAgentDidPair(DidPair(agentCreated.selfDID, agentCreated.agentVerKey))
    agentCreated
  }

  def updateVerityAgentDidPair(dp: DidPair): Unit = {
    verityAgentDidPairOpt = Option(dp)
    storeTheirKey(DidPair(dp.did, dp.verKey))
  }

  def updatePairwiseAgentDidPair(connId: String, myPairwiseKey: DidPair, agentDidPair: DidPair): PairwiseRel = {
    storeTheirKey(agentDidPair)
    val pairwiseRel = PairwiseRel(Option(myPairwiseKey), Option(agentDidPair))
    myPairwiseRelationships += (connId -> pairwiseRel)
    pairwiseRel
  }

  def sendToRoute[T: ClassTag](msg: Any, fwdToDID: DidStr): ReceivedMsgParam[T] = {
    val jsonMsgBuilder = JsonMsgBuilder(msg)
    val packedMsg = packFromLocalAgentKey(jsonMsgBuilder.jsonMsg, Set(KeyParam.fromVerKey(agencyVerKey)))
    val routedPackedMsg = prepareFwdMsg(agencyDID, fwdToDID, packedMsg)
    parseAndUnpackResponse[T](checkOKResponse(sendPOST(routedPackedMsg)))
  }

  protected def packForMyVerityAgent(msg: String)
                                    (implicit mpf: MsgPackFormat = MPF_INDY_PACK): Array[Byte] = {
    val packedMsgForVerityAgent = packFromLocalAgentKey(msg, Set(KeyParam.fromVerKey(verityAgentDidPair.verKey)))
    prepareFwdMsg(agencyDID, verityAgentDidPair.did, packedMsgForVerityAgent)
  }

  protected def packFromLocalAgentKey(msg: String, recipVerKeyParams: Set[KeyParam])
                                     (implicit mpf: MsgPackFormat = MPF_INDY_PACK): Array[Byte] = {
    packMsg(msg, recipVerKeyParams, Option(KeyParam.fromVerKey(myLocalAgentVerKey)))
  }

  def packForMyPairwiseRel(connId: String, msg: String)
                          (implicit mpf: MsgPackFormat = MPF_INDY_PACK): Array[Byte] = {
    val pairwiseRel = myPairwiseRelationships(connId)
    val senderVerKeyParam = Option(KeyParam.fromVerKey(pairwiseRel.myPairwiseVerKey))
    val recipVerKeyParams = Set(KeyParam.fromVerKey(pairwiseRel.myVerityAgentVerKey))
    val verityAgentPackedMsg = packMsg(msg, recipVerKeyParams, senderVerKeyParam)
    prepareFwdMsg(agencyDID, pairwiseRel.myPairwiseDID, verityAgentPackedMsg)
  }

  protected def packForAgencyAgent(msg: String)
                                  (implicit mpf: MsgPackFormat = MPF_INDY_PACK): Array[Byte] = {
    val recipVerKeyParams = Set(KeyParam.fromVerKey(agencyVerKey))
    val packedMsgForAgencyAgent = packMsg(msg, recipVerKeyParams, Option(KeyParam.fromVerKey(myLocalAgentVerKey)))
    prepareFwdMsg(agencyDID, agencyDID, packedMsgForAgencyAgent)
  }

  /**
   *
   * @param recipDID recipient of fwd msg
   * @param fwdToDID destination of the given 'msg'
   * @param msg the message to be sent
   * @return
   */
  protected def prepareFwdMsg(recipDID: DidStr, fwdToDID: DidStr, msg: Array[Byte])
                             (implicit mpf: MsgPackFormat = MPF_INDY_PACK): Array[Byte] = {
    val fwdJson = AgentMsgPackagingUtil.buildFwdJsonMsg(mpf, fwdToDID, msg)
    val senderKey = if (recipDID == agencyDID) None else Option(KeyParam.fromVerKey(myLocalAgentVerKey))
    packMsg(fwdJson, Set(KeyParam.fromDID(recipDID)), senderKey)
  }

  protected def packMsg(msg: String,
                        recipVerKeyParams: Set[KeyParam],
                        senderKeyParam: Option[KeyParam])
                       (implicit mpf: MsgPackFormat = MPF_INDY_PACK): Array[Byte] = {
    val walletMsg = mpf match {
      case MPF_MSG_PACK => LegacyPackMsg(MessagePackUtil.convertJsonStringToPackedMsg(msg), recipVerKeyParams, senderKeyParam)
      case _            => PackMsg(msg.getBytes(), recipVerKeyParams, senderKeyParam)
    }
    val pm = testWalletAPI.executeSync[PackedMsg](walletMsg)
    pm.msg
  }

  def unpackMsg[T: ClassTag](msg: Array[Byte])
                            (implicit mpf: MsgPackFormat = MPF_INDY_PACK): ReceivedMsgParam[T] = {
    val walletMsg = mpf match {
      case MPF_MSG_PACK => LegacyUnpackMsg(msg, Option(KeyParam.fromVerKey(myLocalAgentVerKey)), isAnonCryptedMsg = false)
      case _            => UnpackMsg(msg)
    }
    val upm = testWalletAPI.executeSync[UnpackedMsg](walletMsg)
    val jsonMsg = mpf match {
      case MPF_MSG_PACK => MessagePackUtil.convertPackedMsgToJsonString(upm.msg)
      case _            => new JSONObject(new String(upm.msg)).getString("message")
    }
    ReceivedMsgParam(jsonMsg)
  }

  protected def sendPOST(payload: Array[Byte])(implicit ec: ExecutionContext): HttpResponse =
    sendBinaryReqToUrl(payload, param.verityPackedMsgUrl)

  def buildFullUrl(suffix: String): String = param.verityBaseUrl + s"/$suffix"

  protected def parseAndUnpackResponse[T: ClassTag](resp: HttpResponse)
                                                   (implicit mpf: MsgPackFormat = MPF_INDY_PACK): ReceivedMsgParam[T] = {
    val packedMsg = awaitFut(Unmarshal(resp.entity).to[Array[Byte]])
    unpackMsg[T](packedMsg)
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

  val logger: Logger = getLoggerByClass(getClass)

  protected lazy val testWalletAPI: LegacyWalletAPI = {
    val walletProvider = LibIndyWalletProvider
    val walletAPI = new LegacyWalletAPI(testAppConfig, walletProvider, None, NoOpMetricsWriter(), executionContext)
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

trait LegacySdkBase { this: SdkBase =>

  def updateTheirDidDoc(connId: String, theirDidDoc: DIDDoc): Unit = {
    val myPairwiseRel = myPairwiseRelationships(connId)
    myPairwiseRelationships += (connId ->
      myPairwiseRel.copy(theirDIDDoc = Option(theirDidDoc)))
  }

  def processConnectionCompleted(connId: String, inviteeDidPair: DidPair): Unit = {
    updateTheirDidDoc(
      connId,
      DIDDoc(
        inviteeDidPair.did,
        inviteeDidPair.verKey,
        "",
        Vector.empty
      )
    )
  }

  def packForTheirPairwiseRel(connId: String,
                              msg: String)
                             (implicit mpf: MsgPackFormat = MPF_INDY_PACK): Array[Byte] = {
    val pairwiseRel = myPairwiseRelationships(connId)
    packMsg(
      msg,
      Set(KeyParam.fromVerKey(pairwiseRel.theirAgentVerKey)),
      Option(KeyParam.fromVerKey(pairwiseRel.myPairwiseVerKey))
    )
  }
}

trait LegacySdkBase_0_5
  extends LegacySdkBase { this: SdkBase =>

  def provisionAgent_0_5()(implicit mpf: MsgPackFormat = MPF_MSG_PACK): AgentCreated_MFV_0_5 = {
    val connected = sendConnect_0_5()
    sendSignup_0_5(connected)
    val ac = sendCreateAgent_0_5(connected)
    updateVerityAgentDidPair(DidPair(ac.withPairwiseDID, ac.withPairwiseDIDVerKey))
    ac
  }

  def updateComMethod_0_5(endpoint: String)
                         (implicit mpf: MsgPackFormat = MPF_MSG_PACK): ComMethodUpdated_MFV_0_5 = {
    val agentMsg = UpdateComMethod_MFV_0_5(
      TypeDetail(MSG_TYPE_UPDATE_COM_METHOD, MTV_1_0),
      TestComMethod(
        "webhook",
        COM_METHOD_TYPE_HTTP_ENDPOINT,
        Option(endpoint),
        None)
    )
    val agentJsonMsg = AgentMsgPackagingUtil.buildAgentMsgJson(List(agentMsg), wrapInBundledMsgs = true)
    val routedPackedMsg = packForMyVerityAgent(agentJsonMsg)
    parseAndUnpackResponse[ComMethodUpdated_MFV_0_5](checkOKResponse(sendPOST(routedPackedMsg))).msg
  }

  def updateConfigs(configs: Set[TestConfigDetail],
                    connId: Option[String]=None)
                   (implicit mpf: MsgPackFormat = MPF_MSG_PACK): ConfigsUpdatedRespMsg_MFV_0_5 = {
    val agentMsg = UpdateConfigs_MFV_0_5(TypeDetail(MSG_TYPE_UPDATE_CONFIGS, MTV_1_0), configs)
    val agentJsonMsg = AgentMsgPackagingUtil.buildAgentMsgJson(List(agentMsg), wrapInBundledMsgs = true)
    val routedPackedMsg = connId match {
      case Some(cId) => packForMyPairwiseRel(cId, agentJsonMsg)
      case None      => packForMyVerityAgent(agentJsonMsg)
    }
    parseAndUnpackResponse[ConfigsUpdatedRespMsg_MFV_0_5](checkOKResponse(sendPOST(routedPackedMsg))).msg
  }

  private def sendConnect_0_5()(implicit mpf: MsgPackFormat = MPF_MSG_PACK): Connected_MFV_0_5 = {
    val agentMsg = Connect_MFV_0_5(TypeDetail(MSG_TYPE_CONNECT, MTV_1_0), localAgentDidPair.did, localAgentDidPair.verKey)
    val agentJsonMsg = AgentMsgPackagingUtil.buildAgentMsgJson(List(agentMsg), wrapInBundledMsgs = true)
    val routedPackedMsg = packForAgencyAgent(agentJsonMsg)
    parseAndUnpackResponse[Connected_MFV_0_5](checkOKResponse(sendPOST(routedPackedMsg))).msg
  }

  private def sendSignup_0_5(connected: Connected_MFV_0_5)(implicit mpf: MsgPackFormat = MPF_MSG_PACK): SignedUp_MFV_0_5 = {
    val agentMsg = SignUp_MFV_0_5(TypeDetail(MSG_TYPE_SIGN_UP, MTV_1_0))
    val agentJsonMsg = AgentMsgPackagingUtil.buildAgentMsgJson(List(agentMsg), wrapInBundledMsgs = true)
    val recipVerKeyParams = Set(KeyParam.fromVerKey(connected.withPairwiseDIDVerKey))
    val packedMsgForAgencyPairwiseAgent = packMsg(agentJsonMsg, recipVerKeyParams, Option(KeyParam.fromVerKey(myLocalAgentVerKey)))
    val routedPackedMsg = prepareFwdMsg(agencyDID, connected.withPairwiseDIDVerKey, packedMsgForAgencyPairwiseAgent)
    parseAndUnpackResponse[SignedUp_MFV_0_5](checkOKResponse(sendPOST(routedPackedMsg))).msg
  }

  private def sendCreateAgent_0_5(connected: Connected_MFV_0_5)(implicit mpf: MsgPackFormat = MPF_MSG_PACK): AgentCreated_MFV_0_5 = {
    val agentMsg = CreateAgent_MFV_0_5(TypeDetail(MSG_TYPE_CREATE_AGENT, MTV_1_0))
    val agentJsonMsg = AgentMsgPackagingUtil.buildAgentMsgJson(List(agentMsg), wrapInBundledMsgs = true)
    val recipVerKeyParams = Set(KeyParam.fromVerKey(connected.withPairwiseDIDVerKey))
    val packedMsgForAgencyPairwiseAgent = packMsg(agentJsonMsg, recipVerKeyParams, Option(KeyParam.fromVerKey(myLocalAgentVerKey)))
    val routedPackedMsg = prepareFwdMsg(agencyDID, connected.withPairwiseDIDVerKey, packedMsgForAgencyPairwiseAgent)
    parseAndUnpackResponse[AgentCreated_MFV_0_5](checkOKResponse(sendPOST(routedPackedMsg))).msg
  }

  def createKey_0_5(connId: String)(implicit mpf: MsgPackFormat = MPF_MSG_PACK): KeyCreatedRespMsg_MFV_0_5 = {
    val newPairwiseKey = createNewKey()
    val createKey = CreateKey_MFV_0_5(TypeDetail(MSG_TYPE_CREATE_KEY, MTV_1_0), newPairwiseKey.did, newPairwiseKey.verKey)
    val agentJsonMsg = AgentMsgPackagingUtil.buildAgentMsgJson(List(createKey), wrapInBundledMsgs = true)
    val routedPackedMsg = packForMyVerityAgent(agentJsonMsg)
    val kcr = parseAndUnpackResponse[KeyCreatedRespMsg_MFV_0_5](checkOKResponse(sendPOST(routedPackedMsg))).msg
    myPairwiseRelationships += (connId -> PairwiseRel(Option(newPairwiseKey), Option(DidPair(kcr.withPairwiseDID, kcr.withPairwiseDIDVerKey))))
    kcr
  }

  def sendConnReq_0_5(connId: String)(implicit mpf: MsgPackFormat = MPF_MSG_PACK): CreateInviteResp_MFV_0_5 = {
    val myPairwiseRel = myPairwiseRelationships(connId)
    val keyDlgProof = Util
      .getAgentKeyDlgProof(
        myPairwiseRel.myPairwiseVerKey,
        myPairwiseRel.myVerityAgentDID,
        myPairwiseRel.myVerityAgentVerKey
      )(testWalletAPI, walletAPIParam)

    val agentMsgs = List(
      CreateMsg_MFV_0_5(TypeDetail(MSG_TYPE_CREATE_MSG, MTV_1_0), CREATE_MSG_TYPE_CONN_REQ, None),
      InviteCreateMsgDetail_MFV_0_5(TypeDetail(MSG_TYPE_MSG_DETAIL, MTV_1_0), keyDlgProof, phoneNo = None,
        includePublicDID = Option(false))
    )
    val agentJsonMsg = AgentMsgPackagingUtil.buildAgentMsgJson(agentMsgs, wrapInBundledMsgs = true)
    val routedPackedMsg = packForMyPairwiseRel(connId, agentJsonMsg)
    parseAndUnpackResponse[CreateInviteResp_MFV_0_5](checkOKResponse(sendPOST(routedPackedMsg))).msg
  }

  def sendConnReqAnswer_0_5(connId: String,
                            inviteDetail: InviteDetail)
                           (implicit mpf: MsgPackFormat = MPF_MSG_PACK): DidPair = {
    val myPairwiseRel = myPairwiseRelationships(connId)

    updateTheirDidDoc(
      connId,
      DIDDoc(
        inviteDetail.senderDetail.DID,
        inviteDetail.senderDetail.verKey,
        inviteDetail.senderAgencyDetail.endpoint,
        Vector(inviteDetail.senderDetail.agentKeyDlgProof.get.agentDelegatedKey,
          inviteDetail.senderAgencyDetail.verKey)
      )
    )
    val keyDlgProof = Util
      .getAgentKeyDlgProof(
        myPairwiseRel.myPairwiseVerKey,
        myPairwiseRel.myVerityAgentDID,
        myPairwiseRel.myVerityAgentVerKey
      )(testWalletAPI, walletAPIParam)

    val agentMsgs = List(
      CreateMsg_MFV_0_5(TypeDetail(MSG_TYPE_CREATE_MSG, MTV_1_0),
        CREATE_MSG_TYPE_CONN_REQ_ANSWER, None, replyToMsgId = Option(inviteDetail.connReqId), sendMsg = true),
      AnswerInviteMsgDetail_MFV_0_5(TypeDetail(MSG_TYPE_MSG_DETAIL, MTV_1_0),
        inviteDetail.senderDetail, inviteDetail.senderAgencyDetail, MSG_STATUS_ACCEPTED.statusCode, Option(keyDlgProof))
    )
    val agentJsonMsg = AgentMsgPackagingUtil.buildAgentMsgJson(agentMsgs, wrapInBundledMsgs = true)
    val routedPackedMsg = packForMyPairwiseRel(connId, agentJsonMsg)
    parseAndUnpackResponse[InviteAcceptedResp_MFV_0_5](checkOKResponse(sendPOST(routedPackedMsg))).msg

    DidPair(myPairwiseRel.myPairwiseDID, myPairwiseRel.myPairwiseVerKey)
  }

  def sendCreateMsgReq_0_5(connId: String,
                           msg: Array[Byte],
                           senderName: Option[String]=None)
                          (implicit mpf: MsgPackFormat = MPF_MSG_PACK): BundledMsg_MFV_0_5 = {
    val agentMsgs = List(
      CreateMsg_MFV_0_5(TypeDetail(MSG_TYPE_CREATE_MSG, MTV_1_0), CREATE_MSG_TYPE_GENERAL, None, sendMsg=true),
      GeneralCreateMsgDetail_MFV_0_5(
        TypeDetail(MSG_TYPE_MSG_DETAIL, MTV_1_0),
        msg,
        senderName = senderName
      )
    )
    val agentJsonMsg = AgentMsgPackagingUtil.buildAgentMsgJson(agentMsgs, wrapInBundledMsgs = true)
    val routedPackedMsg = packForMyPairwiseRel(connId, agentJsonMsg)
    parseAndUnpackResponse[BundledMsg_MFV_0_5](checkOKResponse(sendPOST(routedPackedMsg))).msg
  }
}

trait LegacySdkBase_0_6
  extends LegacySdkBase { this: SdkBase =>

  def provisionAgent_0_6(): AgentCreated_MFV_0_6 = {
    val ac = sendCreateAgent_0_6()
    updateVerityAgentDidPair(DidPair(ac.withPairwiseDID, ac.withPairwiseDIDVerKey))
    ac
  }

  private def sendCreateAgent_0_6(): AgentCreated_MFV_0_6 = {
    val jsonMsg = createJsonString(CreateAgent_MFV_0_6(MSG_TYPE_DETAIL_CREATE_AGENT, localAgentDidPair.did, localAgentDidPair.verKey))
    val packedMsgForAgencyAgent = packFromLocalAgentKey(jsonMsg, Set(KeyParam.fromVerKey(agencyVerKey)))
    val routedPackedMsg = prepareFwdMsg(agencyDID, agencyDID, packedMsgForAgencyAgent)
    parseAndUnpackResponse[AgentCreated_MFV_0_6](checkOKResponse(sendPOST(routedPackedMsg))).msg
  }

  def createKey_0_6(connId: String): KeyCreatedRespMsg_MFV_0_6 = {
    val newPairwiseKey = createNewKey()
    val jsonMsg = createJsonString(CreateKey_MFV_0_6(MSG_TYPE_DETAIL_CREATE_KEY, newPairwiseKey.did, newPairwiseKey.verKey))
    val routedPackedMsg = packForMyVerityAgent(jsonMsg)
    val kcr = parseAndUnpackResponse[KeyCreatedRespMsg_MFV_0_6](checkOKResponse(sendPOST(routedPackedMsg))).msg
    myPairwiseRelationships += (connId -> PairwiseRel(Option(newPairwiseKey), Option(DidPair(kcr.withPairwiseDID, kcr.withPairwiseDIDVerKey))))
    kcr
  }

  def sendConnReq_0_6(connId: String): ConnReqRespMsg_MFV_0_6 = {
    val myPairwiseRel = myPairwiseRelationships(connId)

    val keyDlgProof = Util
      .getAgentKeyDlgProof(
        myPairwiseRel.myPairwiseVerKey,
        myPairwiseRel.myVerityAgentDID,
        myPairwiseRel.myVerityAgentVerKey
      )(testWalletAPI, walletAPIParam)

    val jsonMsg = createJsonString(
      ConnReq_MFV_0_6(
        MSG_TYPE_DETAIL_CONN_REQ,
        getNewMsgId,
        `~thread` = MsgThread(thid=Option("1")),
        keyDlgProof = Option(keyDlgProof)
      )
    )
    val routedPackedMsg = packForMyPairwiseRel(connId, jsonMsg)
    parseAndUnpackResponse[ConnReqRespMsg_MFV_0_6](checkOKResponse(sendPOST(routedPackedMsg))).msg
  }

  def sendConnReqAnswer_0_6(connId: String, inviteDetail: InviteDetail): ConnReqAccepted_MFV_0_6 = {
    val myPairwiseRel = myPairwiseRelationships(connId)

    updateTheirDidDoc(
      connId,
      DIDDoc(
        inviteDetail.senderDetail.DID,
        inviteDetail.senderDetail.verKey,
        inviteDetail.senderAgencyDetail.endpoint,
        Vector(inviteDetail.senderDetail.agentKeyDlgProof.get.agentDelegatedKey,
          inviteDetail.senderAgencyDetail.verKey)
      )
    )

    val keyDlgProof = Util
      .getAgentKeyDlgProof(
        myPairwiseRel.myPairwiseVerKey,
        myPairwiseRel.myVerityAgentDID,
        myPairwiseRel.myVerityAgentVerKey
      )(testWalletAPI, walletAPIParam)

    val jsonMsg = createJsonString(
      AcceptConnReq_MFV_0_6(MSG_TYPE_DETAIL_ACCEPT_CONN_REQ,
        getNewMsgId, sendMsg = true,
        keyDlgProof,
        inviteDetail.senderDetail,
        inviteDetail.senderAgencyDetail,
        inviteDetail.connReqId, MsgThread())
    )
    val routedPackedMsg = packForMyPairwiseRel(connId, jsonMsg)
    parseAndUnpackResponse[ConnReqAccepted_MFV_0_6](checkOKResponse(sendPOST(routedPackedMsg))).msg
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
    Try {
      val message = new JSONObject(msg)
      val threadOpt = Try {
        Option(DefaultMsgCodec.fromJson[MsgThread](message.getJSONObject("~thread").toString))
      }.getOrElse(None)
      val expMsg = DefaultMsgCodec.fromJson[T](message.toString)
      checkInvalidFieldValues(msg, expMsg)
      ReceivedMsgParam(expMsg, msg, None, threadOpt)
    } match {
      case Success(resp) => resp
      case Failure(ex)   => fromLegacy(msg).getOrElse(throw ex)
    }
  }

  private def checkInvalidFieldValues(msgString: String, msg: Any): Unit = {
    //this condition would be true if the received message is different than expected message type
    // in which case the deserialized message fields will have null values
    if (msg.asInstanceOf[Product].productIterator.contains(null)) {
      throw new UnexpectedMsgException(s"expected message '${msg.getClass.getSimpleName}', but found: " + msgString)
    }
  }

  private def fromLegacy[T: ClassTag](msg: String): Option[ReceivedMsgParam[T]] = {
    Try {
      val bm = AgentMsgParseUtil.convertTo[BundledMsg_MFV_0_5](msg)
      if (bm.bundled.size == 1) {
        Option(ReceivedMsgParam(MessagePackUtil.convertPackedMsgToJsonString(bm.bundled.head)))
      } else {
        val clazz = implicitly[ClassTag[T]].runtimeClass
        if (classOf[CreateInviteResp_MFV_0_5].isAssignableFrom(clazz)) {
          val expMsg = {
            val mcJson = MessagePackUtil.convertPackedMsgToJsonString(bm.bundled.head)
            val mdJson = MessagePackUtil.convertPackedMsgToJsonString(bm.bundled(1))
            val msJson = if (bm.bundled.size == 3) Option(MessagePackUtil.convertPackedMsgToJsonString(bm.bundled(2))) else None

            val mc = DefaultMsgCodec.fromJson[MsgCreated_MFV_0_5](mcJson)
            val md = DefaultMsgCodec.fromJson[InviteMsgDetail_MFV_0_5](mdJson)
            val ms = msJson.map(DefaultMsgCodec.fromJson[MsgsSent_MFV_0_5])
            CreateInviteResp_MFV_0_5(mc, md, ms)
          }
          Option(ReceivedMsgParam[T](expMsg.asInstanceOf[T], msg, None, None))
        } else if (classOf[InviteAcceptedResp_MFV_0_5].isAssignableFrom(clazz)) {
          val expMsg = {
            val mcJson = MessagePackUtil.convertPackedMsgToJsonString(bm.bundled.head)
            val msJson = if (bm.bundled.size == 2) Option(MessagePackUtil.convertPackedMsgToJsonString(bm.bundled(1))) else None
            val mc = DefaultMsgCodec.fromJson[MsgCreated_MFV_0_5](mcJson)
            val ms = msJson.map(DefaultMsgCodec.fromJson[MsgsSent_MFV_0_5])
            InviteAcceptedResp_MFV_0_5(mc, ms)
          }
          Option(ReceivedMsgParam[T](expMsg.asInstanceOf[T], msg, None, None))
        } else {
          throw new RuntimeException("can't deserialize response into given class: " + clazz)
        }
      }
    }.getOrElse(None)
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

  def createJsonString(msg: Any): String = {
    createJSONObject(msg).toString
  }

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

trait OAuthParam
case class V1OAuthParam(tokenExpiresDuration: FiniteDuration) extends OAuthParam
case class V2OAuthParam(fixedToken: String) extends OAuthParam

class UnexpectedMsgException(msg: String) extends RuntimeException(msg)