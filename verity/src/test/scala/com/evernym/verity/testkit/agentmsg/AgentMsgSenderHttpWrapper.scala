package com.evernym.verity.testkit.agentmsg

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.`X-Real-Ip`
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.scaladsl.{Flow, Sink, Source}
import com.evernym.verity.util2.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.util2.Status._
import com.evernym.verity.actor._
import com.evernym.verity.actor.agent.MsgPackFormat.MPF_MSG_PACK
import com.evernym.verity.actor.agent.user.ComMethodDetail
import com.evernym.verity.actor.testkit.{AkkaTestBasic, CommonSpecUtil, TestAppConfig}
import com.evernym.verity.actor.wallet.{CreateNewKey, NewKeyCreated, PackedMsg, SignMsg, SignedMsg}
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.agentmsg.msgfamily.pairwise.PairwiseMsgUids
import com.evernym.verity.agentmsg.tokenizer.SendToken
import com.evernym.verity.config.AppConfig
import com.evernym.verity.constants.Constants._
import com.evernym.verity.http.common.StatusDetailResp
import com.evernym.verity.logging.LoggingUtil.getLoggerByName
import com.evernym.verity.metrics.{MetricDetail, PrometheusMetricsParser}
import com.evernym.verity.protocol.engine.Constants._
import com.evernym.verity.protocol.engine.{DID, MsgId}
import com.evernym.verity.protocol.protocols.agentprovisioning.v_0_7.AgentProvisioningMsgFamily
import com.evernym.verity.protocol.protocols.agentprovisioning.v_0_7.AgentProvisioningMsgFamily.RequesterKeys
import com.evernym.verity.protocol.protocols.connecting.common.InviteDetail
import com.evernym.verity.protocol.protocols.walletBackup.BackupInitParams
import com.evernym.verity.testkit.util._
import com.evernym.verity.testkit.{AgentWithMsgHelper, LedgerClient, agentmsg}
import com.evernym.verity.util._
import com.evernym.verity.util2.UrlParam
import com.evernym.verity.vault._
import com.typesafe.scalalogging.Logger
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Seconds, Span}

import java.net.InetAddress
import java.util.UUID
import scala.concurrent.duration.{Duration, _}
import scala.concurrent.{Await, Future}
import scala.util.Left

/**
 * prepares agent message and sends it via http and handles response message
 */
trait AgentMsgSenderHttpWrapper
  extends CommonSpecUtil
    with LedgerClient
    with Eventually {

  val logger: Logger = getLoggerByName(getClass.getName)

  def urlParam: UrlParam

  val respWaitTime: Duration = 15.seconds

  def mockClientAgent: AgentWithMsgHelper
  implicit def walletAPIParam: WalletAPIParam = mockClientAgent.wap

  implicit lazy val system: ActorSystem = AkkaTestBasic.system()
  def appConfig: AppConfig = new TestAppConfig()
  implicit val url: UrlParam = urlParam

  private def getConnection(ep: UrlParam): Flow[HttpRequest, HttpResponse, Any] = {
    Http().outgoingConnection(ep.host, ep.port)
  }

  def buildReq(hm: HttpMethod, path: String, he: RequestEntity = HttpEntity.Empty): HttpRequest =  {
    val req = HttpRequest(
      method = hm,
      uri = path,
      entity = he
    )
    req.addHeader(`X-Real-Ip`(RemoteAddress(InetAddress.getLocalHost)))
  }

  def buildPostReq(path: String, he: RequestEntity = HttpEntity.Empty): HttpRequest =
    buildReq(HttpMethods.POST, path, he)

  def buildPutReq(path: String, he: RequestEntity = HttpEntity.Empty): HttpRequest =
    buildReq(HttpMethods.PUT, path, he)

  def buildGetReq(path: String): HttpRequest =  {
    buildReq(HttpMethods.GET, path)
  }

  def logApiCallProgressMsg(msg: String): Unit = {
    Logger("TestAPIExecutor").info("    " + msg)
  }

  private def apiRequest(request: HttpRequest)(implicit url: UrlParam): Future[HttpResponse] =
    Source.single(request).via(getConnection(url)).runWith(Sink.head).recover {
      case _ =>
        val errMsg = s"connection not established with remote server ${url.toString}"
        logApiCallProgressMsg("ERROR MSG: " + errMsg)
        HttpResponse(StatusCodes.custom(GatewayTimeout.intValue, errMsg, errMsg))
    }

  private def sendPostMsgToEndpoint(payload: String)(implicit url: UrlParam, urlPath: String):
  Either[StatusDetailResp, String] = {
    sendMsgToEndpoint(buildPostReq(s"$urlPath", HttpEntity(MediaTypes.`application/json`, payload)))
  }

  private def sendPutMsgToEndpoint(payload: String)(implicit url: UrlParam, urlPath: String):
  Either[StatusDetailResp, String] = {
    sendMsgToEndpoint(buildPutReq(s"$urlPath", HttpEntity(MediaTypes.`application/json`, payload)))
  }

  private def sendPostMsgWrapperToEndpoint(payload: Array[Byte])(implicit url: UrlParam, urlPath: String):
  Either[StatusDetailResp, Array[Byte]] = {
    val req = buildPostReq(urlPath, HttpEntity(MediaTypes.`application/octet-stream`, payload))
    sendMsgWrapperToEndpoint(req)
  }

  private def sendGetMsgToEndpoint(implicit url: UrlParam, urlPath: String): Either[StatusDetailResp, String] = {
    sendMsgToEndpoint(buildGetReq(s"$urlPath"))
  }

  private def sendMsgToEndpoint(hr: HttpRequest)(implicit url: UrlParam): Either[StatusDetailResp, String] = {
    hr.addHeader(`X-Real-Ip`(RemoteAddress(InetAddress.getLocalHost)))
    val respFut = apiRequest(hr).flatMap { response =>
      response.status match {
        case OK =>
          logApiCallProgressMsg(s"remote communication (OK)")
          Unmarshal(response.entity).to[String].map(Right(_))
        case BadRequest =>
          Unmarshal(response.entity).to[String].map { sdr =>
            val nativeSdr = DefaultMsgCodec.fromJson[StatusDetailResp](sdr)
            logApiCallProgressMsg("remote communication (BadRequest): " + sdr)
            Left(nativeSdr)
          }
        case Forbidden =>
          Future(Left(StatusDetailResp(FORBIDDEN.statusCode, "forbidden", None)))
        case _ => Unmarshal(response.entity).to[String].flatMap { _ =>
          Unmarshal(response.entity).to[String].map { rs =>
            val error = s"remote communication (ERROR) => sending given msg to '${url.toString}' FAILED " +
              s"with status code ${response.status} and response '$rs'"
            logApiCallProgressMsg("ERROR MSG: " + error)
            Left(StatusDetailResp(UNHANDLED.statusCode, error, None))
          }
        }
      }
    }
    resolveFutResponse(respFut)
  }

  private def sendMsgWrapperToEndpoint(hr: HttpRequest)(implicit url: UrlParam):
  Either[StatusDetailResp, Array[Byte]] = {
    val respFut = apiRequest(hr).flatMap { response =>
      response.status match {
        case OK =>
          logApiCallProgressMsg(s"remote communication (OK)")
          import akka.http.scaladsl.unmarshalling.PredefinedFromEntityUnmarshallers.byteArrayUnmarshaller
          Unmarshal(response.entity).to[Array[Byte]].map(Right(_))
        case BadRequest =>
          Unmarshal(response.entity).to[String].map { sdr =>
            val nativeSdr = DefaultMsgCodec.fromJson[StatusDetailResp](sdr)
            logApiCallProgressMsg("remote communication (BadRequest): " + sdr)
            Left(nativeSdr)
          }
        case _ => Unmarshal(response.entity).to[String].flatMap { _ =>
          Unmarshal(response.entity).to[String].map { rs =>
            val error = s"remote communication (ERROR) => sending given msg to '${url.toString}' FAILED " +
              s"with status code ${response.status} and response '$rs'"
            logApiCallProgressMsg("ERROR MSG: " + error)
            Left(StatusDetailResp(UNHANDLED.statusCode, error, None))
          }
        }
      }
    }
    resolveMsgWrapperFutResponse(respFut)
  }

  private def resolveMsgWrapperFutResponse(futResp: Future[Either[StatusDetailResp, Array[Byte]]]):
  Either[StatusDetailResp, Array[Byte]] = {
    val result = Await.result(futResp, respWaitTime)
    result match {
      case Left(statusDetailResp) =>
        logApiCallProgressMsg(s"Completed future exceptionally within $respWaitTime seconds. StatusDetailResp: $statusDetailResp")
      case Right(bytes) =>
        logApiCallProgressMsg(s"Completed future normally within $respWaitTime seconds. bytes: $bytes")

    }
    result
  }

  private def resolveFutResponse(futResp: Future[Either[StatusDetailResp, String]]):
  Either[StatusDetailResp, String] = {
    Await.result(futResp, respWaitTime)
  }

  def sendPostRequest(payload: String, respHandlerFuncOpt: Option[String => Any] = None)
                     (implicit url: UrlParam, urlPath: String): Any = {
    val resp = sendPostMsgToEndpoint(payload)(url, urlPath)
    handleResponse(resp, respHandlerFuncOpt)
  }

  def sendPutRequest(payload: String, respHandlerFuncOpt: Option[String => Any] = None)
                     (implicit url: UrlParam, urlPath: String): Any = {
    val resp = sendPutMsgToEndpoint(payload)(url, urlPath)
    handleResponse(resp, respHandlerFuncOpt)
  }

  def handleResponse(resp: Either[StatusDetailResp, String],
                     respHandlerFuncOpt: Option[String => Any] = None): Any = {
    resp match {
      case Right(rsp: String) =>
        respHandlerFuncOpt.map { f =>
          logApiCallProgressMsg("resp handler invoked for response: " + rsp)
          f(rsp)
        }.getOrElse(None)
      case Left(sdr: StatusDetailResp) => logApiCallProgressMsg("ERROR MSG: " + sdr)
      case e => logApiCallProgressMsg("ERROR MSG: " + e)
    }
  }

  def sendPostRequestWithPackedMsg(packedMsg: PackedMsg,
                                   respHandlerFuncOpt: Option[(PackedMsg, Map[String, Any]) => Any],
                                   otherData: Map[String, Any] = Map.empty)
                                  (implicit url: UrlParam, urlPath: String="/agency/msg"): Any = {
    val resp = sendPostMsgWrapperToEndpoint(packedMsg.msg)(url, urlPath)
    resp match {
      case Right(rsp: Array[Byte]) =>
        respHandlerFuncOpt.map  { f =>
          logApiCallProgressMsg("resp handler invoked for response: " + rsp)
          f(PackedMsg(rsp), otherData)
        }.getOrElse(None)
      case Left(sdr: StatusDetailResp) => logApiCallProgressMsg("ERROR MSG: " + sdr); sdr
      case e => logApiCallProgressMsg("ERROR MSG: " + e); e
    }
  }

  def sendGetRequest(respHandlerFuncOpt: Option[String => Unit] = None)
                               (implicit url: UrlParam, urlPath: String): Any = {
    val resp = sendGetMsgToEndpoint(url, urlPath)
    resp match {
      case Right(rsp: String) => respHandlerFuncOpt.foreach (f => f(rsp)); rsp
      case Left(sdr: StatusDetailResp) => throw new RuntimeException(sdr.toString)
      case e => throw new RuntimeException(e.toString)
    }
  }

  def sendFetchAgencyKeyRequest(respHandlerFuncOpt: Option[AgencyPublicDid => Unit] = None)
                               (implicit url: UrlParam, urlPath: String): Any = {
    val resp = sendGetMsgToEndpoint(url, urlPath)
    resp match {
      case Right(rsp: String) =>
        val akDetail = mockClientAgent.convertTo[AgencyPublicDid ](rsp)
        respHandlerFuncOpt.foreach (f => f(akDetail))
        akDetail
      case Left(sdr: StatusDetailResp) => logApiCallProgressMsg("ERROR MSG: " + sdr)
      case e => logApiCallProgressMsg("ERROR MSG: " + e)
    }
  }

  def bootstrapAgency(ad: AgencyPublicDid,
                      fromDID: Option[DID]=None,
                      withSeed: Option[String]=None,
                      config: Option[AppConfig]=None,
                      withRole: Option[String]=None): Unit = {
    createLedgerUtil(config, fromDID, withSeed).bootstrapNewDID(ad.DID, ad.verKey, withRole.orNull)
  }

  def updateAgencyEndpointInLedger(did: DID, withSeed: String, endpoint: String, config: Option[AppConfig]=None): Unit = {
    createLedgerUtil(config, Option(did), Option(withSeed)).setEndpointUrl(did, endpoint)
  }

  def getAttribFromLedger(did: DID, withSeed: String, attribName: String, config: Option[AppConfig]=None): Unit = {
    createLedgerUtil(config, Option(did), Option(withSeed)).sendGetAttrib(did, attribName)
  }

  def buildClientNamePrependedMsg(msg: String): String = {
    s"[${mockClientAgent.name}] $msg"
  }

  def logApiStart(msg: String): Unit = {
    val finalMsg = buildClientNamePrependedMsg(msg)
    logger.info("[START] " + finalMsg)

  }

  def logApiFinish(msg: String): Unit = {
    val finalMsg = buildClientNamePrependedMsg(msg)
    logger.info("[FINISH] " + finalMsg)
  }

  def setupAgencyKey(seedOpt: Option[String]=None): Any = {
    val seed = seedOpt.getOrElse(UUID.randomUUID().toString.replace("-", ""))
    logApiStart("setup agency key started...")
    val r = sendPostRequest(s"""{"seed":"$seed"}""", Option(mockClientAgent.handleInitResp))(url, s"$agencySetupUrlPathPrefix/key")
    logApiFinish(s"setup agency key finished: " + r)
    r
  }

  def setupAgencyKeyRepeated(): Unit = {
    logApiStart(s"setup agency key started...")
    sendPostRequest("", Option(mockClientAgent.handleForbiddenResp))(url,s"$agencySetupUrlPathPrefix/key")
    logApiFinish(s"setup agency key finished")
  }

  def setupAgencyEndpoint(): Unit = {
    logApiStart(s"setup endpoint started...")
    sendPostRequest("", Option(mockClientAgent.handleEndpointSet))(url,s"$agencySetupUrlPathPrefix/endpoint")
    logApiFinish(s"setup endpoint finished")
  }

  def setupStartMessageTracking(trackingId: String): Unit = {
    logApiStart(s"setup message tracking started...")
    sendPostRequest("", Option(mockClientAgent.handleMessageTrackingStarted))(url,s"$agencyInternalPathPrefix/msg-progress-tracker/$trackingId")
    logApiFinish(s"setup message tracking finished")
  }

  def reloadConfig(): Unit = {
    logApiStart(s"reload config started...")
    sendPutRequest("", None)(url,s"$agencyInternalPathPrefix/maintenance/config/reload")
    logApiFinish(s"reload config finished")
  }

  def updateAgencyEndpoint(): Unit = {
    logApiStart(s"update endpoint started...")
    sendPutRequest("", None)(url,s"$agencySetupUrlPathPrefix/endpoint")
    logApiFinish(s"update endpoint finished")
  }

  def setupAgencyEndpointRepeated(): Unit = {
    logApiStart(s"setup endpoint again started...")
    sendPostRequest("", Option(mockClientAgent.handleForbiddenResp))(url,s"$agencySetupUrlPathPrefix/endpoint")
    logApiFinish(s"setup endpoint again finished")
  }

  def checkAppStatus(): Any = {
    try {
      logApiStart(s"checking if app is started and responding at $url...")
      val r = sendGetRequest(None)(url,"/agency/heartbeat")
      logApiFinish(s"app is started and responding: " + r)

      val validResponses = List(
        s"""{"statusCode":"GNR-129","statusMsg":"Listening"}"""
      )
      if (! validResponses.contains(r.toString)) {
        throw new RuntimeException("application not accepting traffic")
      }
    } catch {
      case e: Exception =>
        logApiFinish(s"app is in unusable state: " + e.getMessage)
        throw e
    }
  }

  def fetchAgencyKey(): Any = {
    logApiStart(s"fetch agency key started...")
    val r = sendFetchAgencyKeyRequest(Option(mockClientAgent.handleFetchAgencyKey))(url,"/agency")
    logApiFinish(s"fetch agency key finished: " + r)
    r
  }

  def getConfigAtPath(path: String): String = {
    logApiStart(s"get config started...")
    val r = sendGetRequest(None)(url,s"$agencyInternalPathPrefix/health-check/config?path=$path")
    logApiFinish(s"get config finished: " + r)
    r.toString
  }

  def fetchWrongAgencyKey(): Any = {
    logApiStart(s"fetch agency key started...")
    val r = generateNewAgentDIDDetail()
    mockClientAgent.handleFetchAgencyKey(AgencyPublicDid(r.did, r.verKey))
    logApiFinish(s"fetch agency key finished: " + r)
    r
  }

  def sendConnectWithAgency(): Any = {
    logApiStart(s"connect started...")
    val r = sendPostRequestWithPackedMsg(
      mockClientAgent.v_0_5_req.prepareConnectMsgForAgency,
      Option(mockClientAgent.v_0_5_resp.handleConnectedResp))
    logApiFinish(s"connect finished: " + r)
    r
  }

  def sendConnectCreateKey_MFV_0_6(): Any = {
    logApiStart(s"create key (MFV 0.6) started...")
    val fromDID = mockClientAgent.myDIDDetail.did
    val fromDIDVerKey = mockClientAgent.getVerKeyFromWallet(fromDID)
    logApiStart(s"Agency did: ${mockClientAgent.agencyAgentDetailReq.DID}")
    val r = sendPostRequestWithPackedMsg(
      mockClientAgent.v_0_6_req.prepareConnectCreateKeyForAgency(fromDID, fromDIDVerKey, mockClientAgent.agencyAgentDetailReq.DID),
      Option(mockClientAgent.v_0_6_resp.handleConnectKeyCreatedResp))
    logApiFinish(s"create key (MFV 0.6) finished: " + r)
    r
  }

  def sendBackupInit_0_6(): Any = {
    logApiStart(s"wallet backup init (MFV 0.6) started...")
    val fromDID = mockClientAgent.myDIDDetail.did
    val fromDIDVerKey = mockClientAgent.getVerKeyFromWallet(fromDID)
    val r = sendPostRequestWithPackedMsg(
      mockClientAgent.v_0_6_req.prepareWalletBackupInitMsgForAgency(BackupInitParams(fromDIDVerKey, "address", Array(0, 1, 2))),
      None)
    logApiFinish(s"wallet backup init (MFV 0.6) finished: " + r)
    r
  }

  def sendBackup_0_6(wallet: Any)(implicit msgPackagingContext: AgentMsgPackagingContext): Any = {
    logApiStart(s"backup wallet (MFV 0.6) started...")
    val r = sendPostRequestWithPackedMsg(
      mockClientAgent.prepareWalletBackupMsg(wallet),
      None)
    logApiFinish(s"backup wallet (MFV 0.6) finished: " + r)
    r
  }

  def createPublicIdentifier(): Any = {
    logApiStart(s"setup issuer method started...")
    val r = sendPostRequestWithPackedMsg(
      mockClientAgent.v_0_6_req.prepareSetupIssuerCreateMethodMsgForAgency(),
      None)
    logApiFinish(s"setup issuer method finished: " + r)
    r
  }

  def storePublicIdentifier(bytes: Array[Byte]): Any = {
    val c: PublicIdentifierCreated_MFV_0_6 = mockClientAgent.v_0_6_resp.handlePublicIdentifierCreated(PackedMsg(bytes))
    mockClientAgent.publicIdentifier = Some(c.identifier)
    c.identifier
  }

  def getMsgsFromAgent_MPV_0_6(implicit msgPackagingContext: AgentMsgPackagingContext): Any = {
    logApiStart(s"send get msgs started...")
    val r = sendPostRequestWithPackedMsg(
      mockClientAgent.prepareGetMsgsForAgent(MTV_1_0),
      Option(mockClientAgent.v_0_5_resp.handleGetMsgsResp))
    logApiFinish(s"send get msgs finished: " + r)
    r
  }

  def sendConnReq_MFV_0_6(): Any = {
    logApiStart(s"connection request (MFV 0.6) started...")
    val r = sendPostRequestWithPackedMsg(
      mockClientAgent.v_0_6_req.prepareCreateInviteForAgency(
        mockClientAgent.agencyPairwiseAgentDetailReq.DID, None), None)
    logApiFinish(s"connection request (MFV 0.6) finished: " + r)
    r
  }

  // TODO: can be removed with Provisioning 0.5 and 0.6 are removed.
  def sendCreateAgentDeprecated_MFV_0_6(): Any = {
    logApiStart(s"create agent started...")
    val fromDID = mockClientAgent.myDIDDetail.did
    logApiStart(s"get verkey from wallet started...")
    val fromDIDVerKey = mockClientAgent.getVerKeyFromWallet(fromDID)
    logApiStart(s"get verkey from wallet finished...")
    logApiStart(s"send post request with packed message started...")
    val r = sendPostRequestWithPackedMsg(
      mockClientAgent.v_0_6_req.prepareCreateAgentMsgForAgency(
        mockClientAgent.agencyPairwiseAgentDetailReq.DID, fromDID, fromDIDVerKey),
      Option(mockClientAgent.v_0_6_resp.handleAgentCreatedResp))
    logApiStart(s"send post request with packed message finished...")
    logApiFinish(s"agent creation finished: " + r)
    r
  }

  def sendCreateAgent_MFV_0_6(): AgentCreated_MFV_0_6 = {
    logApiStart(s"create agent started...")
    val fromDID = mockClientAgent.myDIDDetail.did
    logApiStart(s"get verkey from wallet started...")
    val fromDIDVerKey = mockClientAgent.getVerKeyFromWallet(fromDID)
    logApiStart(s"get verkey from wallet finished...")
    logApiStart(s"send post request with packed message started...")
    val r = sendPostRequestWithPackedMsg(
      mockClientAgent.v_0_6_req.prepareCreateAgentMsgForAgency(
        mockClientAgent.agencyPairwiseAgentDetailReq.DID, fromDID, fromDIDVerKey),
      Option(mockClientAgent.v_0_6_resp.handleAgentCreatedResp))
    logApiStart(s"send post request with packed message finished...")
    logApiFinish(s"agent creation finished: " + r)
    r.asInstanceOf[AgentCreated_MFV_0_6]
  }

  def sendCreateAgentFailures_MFV_0_7(): CreateAgentProblemReport_MFV_0_7 = {
    logApiStart(s"create agent started 0.7...")
    val fromDID = mockClientAgent.myDIDDetail.did
    logApiStart(s"get verkey from wallet started...")
    val fromDIDVerKey = mockClientAgent.getVerKeyFromWallet(fromDID)
    logApiStart(s"get verkey from wallet finished...")
    logApiStart(s"create sponsor keys...")


    logApiStart(s"fromDid: $fromDID")
    logApiStart(s"send post request with packed message started...")
    val r = sendPostRequestWithPackedMsg(
      mockClientAgent.v_0_7_req.prepareCreateAgentMsgForAgency(
        mockClientAgent.agencyPairwiseAgentDetailReq.DID, RequesterKeys(fromDID, fromDIDVerKey), None),
      Option(mockClientAgent.v_0_7_resp.handleCreateAgentProblemReport))
    logApiStart(s"send post request with packed message finished...")
    logApiFinish(s"agent creation failed: " + r)
    r.asInstanceOf[CreateAgentProblemReport_MFV_0_7]
  }

  def sendCreateAgent_MFV_0_7(): AgentCreated_MFV_0_7 = {
    logApiStart(s"create agent started 0.7...")
    val fromDID = mockClientAgent.myDIDDetail.did
    logApiStart(s"get verkey from wallet started...")
    val fromDIDVerKey = mockClientAgent.getVerKeyFromWallet(fromDID)
    logApiStart(s"get verkey from wallet finished...")
    logApiStart(s"create sponsor keys...")
    lazy val sponsorKeys: NewKeyCreated = mockClientAgent.testWalletAPI.executeSync[NewKeyCreated](CreateNewKey(seed=Some("000000000000000000000000Trustee1")))

    val id = "my-id"
    val sponsorId = "evernym-test-sponsorabc123"
    val nonce = "12345678"
    val timestamp = TimeUtil.nowDateString

    val encrypted = mockClientAgent.testWalletAPI.executeSync[SignedMsg](
      SignMsg(KeyParam.fromVerKey(sponsorKeys.verKey),
        (nonce + timestamp + id + sponsorId).getBytes()
      )
    ).msg

    val token = Some(AgentProvisioningMsgFamily.ProvisionToken(id, sponsorId, nonce, timestamp, Base64Util.getBase64Encoded(encrypted), sponsorKeys.verKey))
    logApiStart(s"send post request with packed message started...")
    val r = sendPostRequestWithPackedMsg(
      mockClientAgent.v_0_7_req.prepareCreateAgentMsgForAgency(
        mockClientAgent.agencyPairwiseAgentDetailReq.DID, RequesterKeys(fromDID, fromDIDVerKey), token),
      Option(mockClientAgent.v_0_7_resp.handleAgentCreatedResp))
    logApiStart(s"send post request with packed message finished...")
    logApiFinish(s"agent creation finished: " + r)
    r.asInstanceOf[AgentCreated_MFV_0_7]
  }

  def sendGetToken(id: String, sponsorId: String, comMethod: String): SendToken = {
    logApiStart(s"get token started 0.1...")
    logApiStart(s"agency did: ${mockClientAgent.agencyAgentDetailReq.DID}")
    val r = sendPostRequestWithPackedMsg(
      mockClientAgent.v_0_1_req.prepareGetTokenRoute(
        id,
        sponsorId,
        ComMethodDetail(COM_METHOD_TYPE_PUSH, comMethod)),
      Option(mockClientAgent.v_0_1_resp.handleSendTokenResp))

    logApiFinish(s"get token finished: " + r)
    r.asInstanceOf[SendToken]
  }

  // TODO: can be removed with Provisioning 0.5 and 0.6 are removed.
  def registerWithAgencyDeprecatedProvisioning(): Any = {
    logApiStart(s"register started...")
    val r = sendPostRequestWithPackedMsg(
      mockClientAgent.v_0_5_req.prepareSignUpMsgForAgency,
      Option(mockClientAgent.v_0_5_resp.handleSignedUpResp))
    logApiFinish(s"register finished: " + r)
    r
  }

  def registerWithAgency(): Any = {
    logApiStart(s"register started...")
    val r = sendPostRequestWithPackedMsg(
      mockClientAgent.v_0_5_req.prepareSignUpMsgForAgency,
      Option(mockClientAgent.v_0_5_resp.handleSignedUpResp))
    logApiFinish(s"register finished: " + r)
    r
  }

  def sendCreateAgent(): Any = {
    logApiStart(s"create agent started...")
    val r = sendPostRequestWithPackedMsg(
      mockClientAgent.v_0_5_req.prepareCreateAgentMsgForAgency,
      Option(mockClientAgent.v_0_5_resp.handleAgentCreatedResp))
    logApiFinish(s"agent creation finished: " + r)
    r
  }

  def sendUpdateAgentConfig(configs: Set[TestConfigDetail]): Any = {
    logApiStart(s"update agent config started...")
    val r = sendPostRequestWithPackedMsg(
      mockClientAgent.v_0_5_req.prepareUpdateConfigsForAgentMsgForAgency(configs),
      Option(mockClientAgent.v_0_5_resp.handleConfigsUpdatedResp))
    logApiFinish(s"update agent config finished: " + r)
    r
  }

  def sendUpdateAgentComMethod(cm: TestComMethod): Any = {
    logApiStart(s"update agent com method started...")
    val r = sendPostRequestWithPackedMsg(
      mockClientAgent.v_0_5_req.prepareUpdateComMethodMsgForAgency(cm),
      Option(mockClientAgent.v_0_5_resp.handleComMethodUpdatedResp))
    logApiFinish(s"update agent com method finished: " + r)
    r
  }

  def sendUpdateConnStatus_MFV_0_5(connId: String, statusCode: String)(implicit msgPackagingContext: AgentMsgPackagingContext): Any = {
    logApiStart(s"update connection status started...")
    val r = sendPostRequestWithPackedMsg(
      mockClientAgent.v_0_5_req.prepareUpdateConnStatusMsg(connId, statusCode),
      Option(mockClientAgent.v_0_5_resp.handleConnStatusUpdatedResp))
    logApiFinish(s"update connection status finished: " + r)
    r
  }

  def createPairwiseKey_MFV_0_5(connId: String): Any = {
    logApiStart(s"create pairwise key started...")
    val r = sendPostRequestWithPackedMsg(
      mockClientAgent.v_0_5_req.prepareCreateKeyMsgForAgency(connId),
      Option(mockClientAgent.v_0_5_resp.handleKeyCreatedResp), buildConnIdMap(connId))
    logApiFinish(s"create pairwise key finished: " + r)
    r
  }

  def createPairwiseKey_MFV_0_6(connId: String): Any = {
    logApiStart(s"create key (MFV 0.6) started...")
    val r = sendPostRequestWithPackedMsg(
      mockClientAgent.v_0_6_req.preparePairwiseCreateKeyForAgency(mockClientAgent.cloudAgentDetailReq.DID, connId),
      Option(mockClientAgent.v_0_6_resp.handlePairwiseKeyCreatedResp), buildConnIdMap(connId))
    logApiFinish(s"create key (MFV 0.6) finished: " + r)
    r
  }

  def sendInviteForConn(connId: String, ph: Option[String]=None, includePublicDID: Boolean = false): Any = {
    logApiStart(s"create & send invite started...")
    val r = sendPostRequestWithPackedMsg(
      mockClientAgent.v_0_5_req.prepareCreateInviteMsgForAgency(connId, ph, includePublicDID = includePublicDID),
      Option(mockClientAgent.v_0_5_resp.handleInviteCreatedResp),  buildConnIdMap(connId))
    logApiFinish(s"create & send invite finished: " + r)
    r
  }

  def sendInviteForConn_MFV_0_6(connId: String, ph: Option[String]=None, includePublicDID: Boolean = true): Any = {
    logApiStart(s"connection request (MFV 0.6) started...")
    val pairwiseKeyDetail = mockClientAgent.pairwiseConnDetail(connId)
    val r = sendPostRequestWithPackedMsg(
      mockClientAgent.v_0_6_req.prepareCreateInviteForAgency(
        pairwiseKeyDetail.myCloudAgentPairwiseDidPair.DID, Some(connId), ph = ph,
        includeKeyDlgProof = true, includeSendMsg=true, includePublicDID=includePublicDID),
      Option(mockClientAgent.v_0_6_resp.handleInviteCreatedResp), buildConnIdMap(connId))
    logApiFinish(s"connection request (MFV 0.6) finished: " + r)
    r
  }

  def sendMsgsToConn(connId: String, uids: List[String]): Any = {
    logApiStart(s"send msg started...")
    val r = sendPostRequestWithPackedMsg(
      mockClientAgent.v_0_5_req.prepareSendMsgsForAgency(connId, uids),
      Option(mockClientAgent.v_0_5_resp.handleMsgSentResp), buildConnIdMap(connId))
    logApiFinish(s"send msg finished: " + r)
    r
  }

  def answerInviteForConn(connId: String, inviteDetail: InviteDetail): Any = {
    logApiStart(s"send invite answer started...")
    val r = sendPostRequestWithPackedMsg(
      mockClientAgent.v_0_5_req.prepareCreateAnswerInviteMsgForAgency(connId, includeSendMsg = true, inviteDetail),
      Option(mockClientAgent.v_0_5_resp.handleInviteAnswerCreatedResp), buildConnIdMap(connId))
    logApiFinish(s"send invite answer finished: " + r)
    r
  }

  def acceptInviteForConn_MFV_0_6(connId: String, inviteDetail: InviteDetail, alreadyAccepted: Boolean): Any = {
    logApiStart(s"send invite answer started...")
    val r = sendPostRequestWithPackedMsg(
      mockClientAgent.v_0_6_req.prepareAcceptConnReqMsgForAgency(
        connId, includeSendMsg = true, inviteDetail, alreadyAccepted),
      Option(mockClientAgent.v_0_6_resp.handleConnReqAcceptedResp), buildConnIdMap(connId))
    logApiFinish(s"send invite answer finished: " + r)
    r
  }

  def redirectConnReq_MFV_0_5(oldConnId: String, connId: String, inviteDetail: InviteDetail): Any = {
    logApiStart(s"send redirect conn req started...")
    val r = sendPostRequestWithPackedMsg(
      mockClientAgent.v_0_5_req.prepareRedirectConnReqMsgForAgency(oldConnId, connId, inviteDetail),
      Option(mockClientAgent.v_0_5_resp.handleConnReqRedirectedResp), buildConnIdMap(connId))
    logApiFinish(s"send redirect conn req finished: " + r)
    r
  }

  def redirectConnReq_MFV_0_6(oldConnId: String, connId: String, inviteDetail: InviteDetail): Any = {
    logApiStart(s"send redirect conn request...")
    val r = sendPostRequestWithPackedMsg(
      mockClientAgent.v_0_6_req.prepareRedirectConnReqMsgForAgency(oldConnId, connId, inviteDetail),
      Option(mockClientAgent.v_0_6_resp.handleConnReqRedirectedResp), buildConnIdMap(connId))
    logApiFinish(s"send invite answer finished: " + r)
    r
  }

  def sendPackedMsgToConn(connId: String, msgType: String, pm: PackedMsg, replyToMsgId: Option[String]=None,
                           title: Option[String] = None, detail: Option[String] = None): Any = {
    logApiStart(s"send general msg ($msgType) started...")
    val r = sendPostRequestWithPackedMsg(
      mockClientAgent.v_0_5_req.prepareCreateGeneralMsgForConnForAgency(connId,
        includeSendMsg = true, msgType, pm, replyToMsgId, title, detail),
      Option(mockClientAgent.v_0_5_resp.handleGeneralMsgCreatedResp), buildConnIdMap(connId))
    logApiFinish(s"send general msg ($msgType) finished: " + r)
    r
  }

  def sendGeneralMsgToConn(connId: String, msgType: String, msg: String, replyToMsgId: Option[String]=None,
                           title: Option[String] = None, detail: Option[String] = None): Any = {
    sendPackedMsgToConn(connId, msgType, PackedMsg(msg.getBytes), replyToMsgId, title, detail)
  }

  def sendGeneralMsgToConn_MFV_0_6(connId: String, msgType: String, msg: String, replyToMsgId: Option[String]=None,
                                   title: Option[String] = None, detail: Option[String] = None): Any = {
    logApiStart(s"send general msg 0.6 ($msgType) started...")
    val r = sendPostRequestWithPackedMsg(
      mockClientAgent.v_0_6_req.prepareSendRemoteMsgForConnForAgencyWithVersion(
        MFV_1_0, connId, MsgUtil.newMsgId,
        sendMsg=true, msgType, PackedMsg(msg.getBytes),
      replyToMsgId, title, detail),
      Option(mockClientAgent.v_0_6_resp.handleSendRemoteMsgResp), buildConnIdMap(connId))
    logApiFinish(s"send general msg 0.6 ($msgType) finished: " + r)
    r
  }

  def checkServiceMetric(metrics: String): Unit = {
    checkMetricExists(metrics,"as_endpoint_http_agent_msg_count")
    checkMetricExists(metrics,"as_endpoint_http_agent_msg_succeed_count")
    checkMetricExists(metrics,"as_service_dynamodb_persist_succeed_count")
    checkMetricExists(metrics,"as_service_dynamodb_persist_duration")
    checkMetricExists(metrics,"as_service_dynamodb_snapshot_succeed_count")
  }

  private def checkMetricExists(metrics: String, expectedMetricName: String): Unit = {
    require(metrics.contains(expectedMetricName), "expected metrics not found: " + expectedMetricName)
  }

  def getAllNodeMetrics(metricsHost: String): List[MetricDetail] = {
    val url = UrlParam(metricsHost)
    val data = sendGetRequest(None)(url, "/metrics")
    PrometheusMetricsParser.parseString(data.toString)
  }

  def getMetrics(fetchFromAllNodes: Boolean): String = {
    val allNodes = if (fetchFromAllNodes) "Y" else "N"
    logApiStart(s"query metrics started...")
    eventually (timeout(Span(10, Seconds)), interval(Span(3, Seconds))) {
      val r = sendGetRequest(Some(checkServiceMetric))(url, s"$agencyInternalPathPrefix/metrics?allNodes=$allNodes&includeTags=Y&filtered=N")
      r.asInstanceOf[String]
    }
  }

  def checkMessageTrackingData(messageTrackingData: String): Unit = {
    //require(messageTrackingData.contains("as_endpoint_http_agent_msg_count"))
  }

  def getMessageTrackingData(id: String="global", withEvents: String = "Y", inHtml: String = "N"): String = {
//    printApiCallStartedMsg(s"query message tracking data started ($id) ...")
    val r = sendGetRequest(Some(checkMessageTrackingData))(url,
      s"$agencyInternalPathPrefix/msg-progress-tracker/$id?withEvents=$withEvents&inHtml=$inHtml")
//    printApiCallStartedMsg(s"query message tracking data finished ($id)")
    r.asInstanceOf[String]
  }

  def getMsgsFromConn_MPV_0_5(connId: String, excludePayload: Option[String]=None,
                              uids: Option[List[String]] = None,
                              statusCodes: Option[List[String]] = None): Any = {
    implicit val msgPackagingContext: AgentMsgPackagingContext =
      agentmsg.AgentMsgPackagingContext(MPF_MSG_PACK, MFV_1_0, packForAgencyRoute = true)
    logApiStart(s"send get msgs started...")
    val r = sendPostRequestWithPackedMsg(
      mockClientAgent.v_0_5_req.prepareGetMsgsFromConn(connId, excludePayload, uids, statusCodes),
      Option(mockClientAgent.v_0_5_resp.handleGetMsgsRespFromConn), buildConnIdMap(connId))
    logApiFinish(s"send get msgs finished: " + r)
    r
  }

  def getMsgsFromConns_MPV_0_5(pairwiseDIDs: Option[List[DID]] = None,
                               excludePayload: Option[String] = None,
                               uids: Option[List[String]] = None,
                               statusCodes: Option[List[String]] = None)
                              (implicit msgPackagingContext: AgentMsgPackagingContext): Any = {
    logApiStart(s"send get msgs by conns started...")
    val r = sendPostRequestWithPackedMsg(
      mockClientAgent.v_0_5_req.prepareGetMsgsFromConns(
          pairwiseDIDs,
          excludePayload,
          uids,
          statusCodes
      ),
      Option(mockClientAgent.v_0_5_resp.handleGetMsgsFromConnsResp)
    )
    logApiFinish(s"send get msgs by conns finished: " + r)
    r
  }

  def getMsgsFromConns_MPV_0_6(pairwiseDIDs: Option[List[DID]] = None,
                               excludePayload: Option[String] = None,
                               uids: Option[List[String]] = None,
                               statusCodes: Option[List[String]] = None)
                              (implicit msgPackagingContext: AgentMsgPackagingContext): Any = {
    logApiStart(s"send get msgs by conns started...")
    val r = sendPostRequestWithPackedMsg(
      mockClientAgent.v_0_6_req.prepareGetMsgsFromConns(pairwiseDIDs,
        excludePayload, uids, statusCodes),
      Option(mockClientAgent.v_0_6_resp.handleGetMsgsByConnsResp))
    logApiFinish(s"send get msgs by conns finished: " + r)
    r
  }

  def updateMsgStatusForConn_MFV_0_5(connId: String, uids: List[String], statusCode: String)
                                    (implicit msgPackagingContext: AgentMsgPackagingContext): Any = {
    logApiStart(s"send update msg started...")
    val r = sendPostRequestWithPackedMsg(
      mockClientAgent.v_0_5_req.prepareUpdateMsgStatusForConn(connId, uids, statusCode),
      Option(mockClientAgent.v_0_5_resp.handleMsgStatusUpdatedRespFromConn), buildConnIdMap(connId))
    logApiFinish(s"send update msg finished: " + r)
    r
  }

  def updateMsgStatusByConns(statusCode: String, uidsByConns: List[PairwiseMsgUids])
                            (implicit msgPackagingContext: AgentMsgPackagingContext): Any = {
    logApiStart(s"send update msg by conns started...")
    val r = sendPostRequestWithPackedMsg(
      mockClientAgent.v_0_5_req.prepareUpdateMsgStatusByConns(uidsByConns, statusCode),
      Option(mockClientAgent.v_0_5_resp.handleMsgStatusUpdatedByConnsResp))
    logApiFinish(s"send update msg by conns finished: " + r)
    r
  }

  def setupAgencyAgentKey(seedOpt: Option[String]=None): AgencyPublicDid = {
    setupAgencyKey(seedOpt).asInstanceOf[AgencyPublicDid]
  }

  def setupAgency(seedOpt: Option[String]=None): Unit = {
    setupAgencyAgentKey(seedOpt)
    bootstrapAgencyAgentToLedger()
  }

  def bootstrapAgencyAgentToLedger(): Unit = {
    fetchAgencyKey()
    bootstrapAgency(mockClientAgent.agencyPublicDid.get)
    setupAgencyEndpoint()
  }

  def createAgentExt(executeInitAgency: Boolean = false, getWrongAgencyKey: Boolean = false): Unit = {
    if (executeInitAgency) {
      setupAgency()
    }
    if (getWrongAgencyKey) {
      fetchWrongAgencyKey()
    } else {
      fetchAgencyKey()
    }
    sendConnectWithAgency()
    registerWithAgency()
    sendCreateAgent()
  }

  def setupEAgent(executeInitAgency: Boolean = false,
                                        getWrongAgencyKey: Boolean = false): Unit = {
    createAgentExt(executeInitAgency, getWrongAgencyKey)
    sendUpdateAgentConfig(Set(TestConfigDetail(NAME_KEY, Option("ent-name")),
      TestConfigDetail(LOGO_URL_KEY, Option("ent-logo-url"))))
  }

  def sendInviteForConnExt(connId: String): Unit = {
    createPairwiseKey_MFV_0_5(connId)
    sendInviteForConn(connId, Option(phoneNo))
  }

  def setupCAgent(executeInitAgency: Boolean = false,
                                        getWrongAgencyKey: Boolean = false): Unit = {
    createAgentExt(executeInitAgency, getWrongAgencyKey)
    sendUpdateAgentComMethod(TestComMethod("1", COM_METHOD_TYPE_PUSH, Option("FCM:12345")))
  }

  def answerInviteForConnExt(connId: String, entSdk: AgentWithMsgHelper): Unit = {
    createPairwiseKey_MFV_0_5(connId)
    val entPCD = entSdk.pairwiseConnDetail(connId)
    answerInviteForConn(connId, entPCD.lastSentInvite)
  }

}

case class MsgBasicDetail(uid: MsgId, typ: String, replyToMsgId: Option[String])
