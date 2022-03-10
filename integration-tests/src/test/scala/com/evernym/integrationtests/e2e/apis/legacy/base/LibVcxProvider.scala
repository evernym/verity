package com.evernym.integrationtests.e2e.apis.legacy.base

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpEntity, HttpMethods, HttpRequest, HttpResponse}
import com.evernym.sdk.vcx.connection.ConnectionApi
import com.evernym.sdk.vcx.credential.CredentialApi
import com.evernym.sdk.vcx.credentialDef.CredentialDefApi
import com.evernym.sdk.vcx.issuer.IssuerApi
import com.evernym.sdk.vcx.proof.{DisclosedProofApi, ProofApi}
import com.evernym.sdk.vcx.schema.SchemaApi
import com.evernym.sdk.vcx.utils.UtilsApi
import com.evernym.sdk.vcx.vcx.VcxApi
import com.evernym.verity.actor.AgencyPublicDid
import com.evernym.verity.actor.testkit.actor.ActorSystemVanilla
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.agentmsg.msgcodec.jackson.JacksonMsgCodec
import com.evernym.verity.config.{AppConfig, ConfigUtil}
import com.evernym.verity.did.didcomm.v1.messages.MsgFamily
import com.evernym.verity.did.{DidPair, DidStr, VerKeyStr}
import com.evernym.verity.integration.base.sdk_provider.MsgFamilyHelper.buildMsgTypeStr
import com.evernym.verity.integration.base.sdk_provider.{JsonMsgUtil, MsgFamilyHelper}
import com.evernym.verity.testkit.util.LedgerUtil
import com.evernym.verity.testkit.BasicSpecWithIndyCleanup
import com.evernym.verity.testkit.util.HttpUtil.{checkOKResponse, parseHttpResponseAs}
import org.json.{JSONArray, JSONObject}
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}

import java.util.UUID
import com.evernym.verity.util2.ExecutionContextProvider

import scala.jdk.CollectionConverters._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.{Duration, SECONDS}
import scala.reflect.ClassTag
import scala.util.{Random, Try}


trait LibVcxProvider
  extends BasicSpecWithIndyCleanup
    with Eventually {

  def appConfig: AppConfig
  def genesisTxnFilePath: String
  implicit val executionContext: ExecutionContext

  lazy val isTaaEnabled: Boolean = true

  lazy val ledgerUtil = new LedgerUtil(
    appConfig,
    None,
    executionContextProvider.futureExecutionContext,
    taa = ConfigUtil.findTAAConfig(appConfig, "1.0.0"),
    genesisTxnPath = Option(genesisTxnFilePath)
  )

  def identityOwner(ownerName: IdentityOwnerName): IdentityOwner = {
    vcxConfigMapping(ownerName)
  }

  def provisionIssuer(identityOwnerName: IdentityOwnerName,
                      verityEndpoint: String,
                      agencyDidPair: DidPair,
                      protocolVersion: String): Unit = {
    val idOwner = provisionIssuerBase(identityOwnerName, verityEndpoint, agencyDidPair, protocolVersion)
    ledgerUtil.bootstrapNewDID(idOwner.sdkToRemoteDID, idOwner.sdkToRemoteVerKey)
  }

  def provisionHolder(identityOwnerName: IdentityOwnerName,
                      verityEndpoint: String,
                      agencyDidPair: DidPair,
                      protocolVersion: String): IdentityOwner = {
    val jsonObject: JSONObject = new JSONObject()
    provisionIdentityOwner(identityOwnerName, verityEndpoint, agencyDidPair, protocolVersion, jsonObject)
  }

  def setupIssuer(identityOwnerName: IdentityOwnerName,
                  sourceId: String,
                  createSchemaParam: CreateSchemaParam,
                  credDefParam: CreateCredDefParam): IssuerSetup = {
    val schemaId: String = withVcxIdentityOwner(identityOwnerName) { _ =>
      val schemaHandle =
        SchemaApi.schemaCreate(
          sourceId,
          createSchemaParam.name,
          createSchemaParam.version,
          createSchemaParam.attribute,
          0
        ).get()

      SchemaApi.schemaGetSchemaId(schemaHandle).get()
    }

    val credDefId = withVcxIdentityOwner(identityOwnerName) { idOwner =>
      val credDefHandle =
        CredentialDefApi.credentialDefCreate(
          sourceId,
          credDefParam.name,
          schemaId,
          null,
          "tag",
          "{}",
          0
        ).get()

      val credDefId = CredentialDefApi.credentialDefGetCredentialDefId(credDefHandle).get()
      idOwner.updateCredDefHandle(credDefId, credDefHandle)
      credDefId
    }
    IssuerSetup(sourceId, schemaId, credDefId)
  }

  def createConnection(identityOwnerName: IdentityOwnerName,
                       connId: String): String = {
    withVcxIdentityOwner(identityOwnerName) { idOwner =>
      val connectionHandle = ConnectionApi.vcxConnectionCreate(connId).get()
      val jsonInviteDetail = ConnectionApi.vcxConnectionConnect(connectionHandle, "{}").get
      ConnectionApi.vcxConnectionUpdateState(connectionHandle).get()
      idOwner.updateConnectionHandle(connId, connectionHandle)
      jsonInviteDetail
    }
  }

  def acceptInvitationLegacy(identityOwnerName: IdentityOwnerName,
                             connId: String,
                             invite: String): Unit = {
    withVcxIdentityOwner(identityOwnerName) { idOwner =>
      val inviteJsonObject = new JSONObject(invite)
      val inviteId = inviteJsonObject.getString("id")
      val acceptResult = ConnectionApi.vcxConnectionAcceptConnectionInvite(inviteId, invite, null).get()
      idOwner.updateConnectionHandle(connId, acceptResult.getConnectionHandle)
    }
  }

  def acceptInvitation(identityOwnerName: IdentityOwnerName,
                       connId: String,
                       invite: String): Unit = {
    withVcxIdentityOwner(identityOwnerName) { idOwner =>
      val inviteJsonObject = new JSONObject(invite)
      val inviteId = inviteJsonObject.getString("@id")
      val connHandle = ConnectionApi.vcxCreateConnectionWithInvite(inviteId, invite).get()
      ConnectionApi.vcxConnectionConnect(connHandle, "{}").get()
      eventually(timeout(Span(5, Seconds)), interval(Span(100, Millis))) {
        ConnectionApi.vcxConnectionUpdateState(connHandle).get()
        val curState = ConnectionApi.connectionGetState(connHandle).get()
        curState shouldBe 4
      }
      idOwner.updateConnectionHandle(connId, connHandle)
    }
  }

  def checkConnectionAccepted(idOwnerName: IdentityOwnerName,
                              connId: String): Unit = {
    withConnection(idOwnerName, connId) { (_, connHandle) =>
      eventually(timeout(Span(5, Seconds)), interval(Span(100, Millis))) {
        ConnectionApi.vcxConnectionUpdateState(connHandle).get()
        val curState = ConnectionApi.connectionGetState(connHandle).get()
        curState shouldBe 4
      }
    }
  }

  def getRandomSchemaVersion: String = {
    val random = new Random(UUID.randomUUID().toString.hashCode)
    val major = random.nextInt(100)
    val minor = 100 + random.nextInt(100)
    val path  = 200 + random.nextInt(100)
    s"$major.$minor.$path"
  }

  def sendMessage[T: ClassTag](fromOwnerName: IdentityOwnerName,
                               toConn: String,
                               msg: T,
                               threadId: Option[String]=None): Unit = {
    withConnection(fromOwnerName, toConn) { (_, connHandle) =>
      val msgFamily = MsgFamilyHelper.getMsgFamilyOpt(msg.getClass).getOrElse(
        throw new RuntimeException("message family not found for given message: " + msg.getClass.getSimpleName)
      )
      val msgType = msgFamily.msgType(msg.getClass)
      val typeStr = MsgFamily.typeStrFromMsgType(msgType)
      val jsonMsg = {
        val jsonObject = new JSONObject(JsonMsgUtil.createJsonString(typeStr, msg))
        jsonObject.put("@id", UUID.randomUUID().toString)
        threadId.map { thId =>
          val threadJsonObject = new JSONObject()
          threadJsonObject.put("thid", thId)
          jsonObject.put("~thread", threadJsonObject)
        }
        jsonObject.toString
      }
      ConnectionApi.connectionSendMessage(
        connHandle,
        jsonMsg,
        """{"msg_type":"type", "msg_title": "title"}""").get()
    }
  }

  def answerMsg(fromOwnerName: IdentityOwnerName,
                toConn: String,
                question: String,
                answer: String): Unit = {
    withConnection(fromOwnerName, toConn) { (_, connHandle) =>
      ConnectionApi.connectionSendAnswer(connHandle, question, answer)
    }
  }

  def sendCredOffer(fromOwnerName: IdentityOwnerName,
                    connId: String,
                    sourceId: String,
                    credDefId: String,
                    credData: String,
                    credName: String,
                    price: String="0"): Unit = {
    withConnection(fromOwnerName, connId) { (idOwner, connHandle) =>
      idOwner.getCredDefHandler(credDefId).foreach { credDefHandle =>
        val credHandle = IssuerApi.issuerCreateCredential(sourceId, credDefHandle, idOwner.institutionDID,
          credData, credName, price).get()
        IssuerApi.issuerSendCredentialOffer(credHandle, connHandle).get()
        idOwner.updateIssuerCredHandle(connId, sourceId, credHandle)
      }
    }
  }

  def sendCredReq(fromOwnerName: IdentityOwnerName,
                  connId: String,
                  sourceId: String,
                  offer: String): Unit = {
    withConnection(fromOwnerName, connId) { (idOwner, connHandle) =>
      val credHandle = CredentialApi.credentialCreateWithOffer(sourceId, offer).get()
      CredentialApi.credentialSendRequest(credHandle, connHandle, 0).get()
      idOwner.updateHolderCredHandle(connId, sourceId, credHandle)
    }
  }

  def checkReceivedCredReq(fromOwnerName: IdentityOwnerName,
                           connId: String,
                           sourceId: String,
                           reqMsg: String): Unit = {
    withConnection(fromOwnerName, connId) { (idOwner, connHandle) =>
      idOwner.getIssuerCredHandler(connId, sourceId).foreach { credHandle =>
        IssuerApi.issuerCredentialUpdateStateWithMessage(credHandle, reqMsg).get()
        IssuerApi.issuerSendCredential(credHandle, connHandle).get()
        val credState = IssuerApi.issuerCredentialGetState(credHandle).get()
        credState shouldBe 4
      }
    }
  }

  def checkReceivedCred(fromOwnerName: IdentityOwnerName,
                        connId: String,
                        sourceId: String,
                        cred: String): Unit = {
    withVcxIdentityOwner(fromOwnerName) { idOwner =>
      idOwner.getHolderCredHandler(connId, sourceId).foreach { credHandle =>
        CredentialApi.credentialUpdateStateWithMessage(credHandle, cred).get()
        val credState = CredentialApi.credentialGetState(credHandle).get()
        credState shouldBe 4
        CredentialApi.getCredential(credHandle).get()
      }
    }
  }

  def sendProofReq(fromOwnerName: IdentityOwnerName,
                   connId: String,
                   sourceId: String,
                   requestedAttrs: String,
                   reqPredicates: String): Unit = {
    withConnection(fromOwnerName, connId) { (idOwner, connHandle) =>
      val proofHandle = ProofApi.proofCreate(sourceId, requestedAttrs,
        reqPredicates, """{}""", "name").get()
      ProofApi.proofSendRequest(proofHandle, connHandle).get()
      idOwner.updateVerifierProofHandle(connId, sourceId, proofHandle)
    }
  }

  def sendProof(fromOwnerName: IdentityOwnerName,
                connId: String,
                sourceId: String,
                request: String): Unit = {
    withConnection(fromOwnerName, connId) { (idOwner, connHandle) =>
      val proofHandle = DisclosedProofApi.proofCreateWithRequest(sourceId, request).get()
      val credentials = new JSONObject(DisclosedProofApi.proofRetrieveCredentials(proofHandle).get())

      // Use the first available credentials to satisfy the proof request
      val attrsObject = credentials.getJSONObject("attrs")
      attrsObject.keySet().asScala.foreach { key =>
        val credAttrValue = new JSONObject()
        credAttrValue.put("credential", attrsObject.getJSONArray(key).getJSONObject(0))
        attrsObject.put(key, credAttrValue)
      }
      credentials.put("attrs", attrsObject)
      DisclosedProofApi.proofGenerate(proofHandle, credentials.toString, "{}").get()
      DisclosedProofApi.proofSend(proofHandle, connHandle).get()
      idOwner.updateHolderProofHandle(connId, sourceId, proofHandle)
    }
  }

  def checkProofValid(fromOwnerName: IdentityOwnerName,
                      connId: String,
                      sourceId: String,
                      proof: String): Unit = {
    withVcxIdentityOwner(fromOwnerName) { idOwner =>
      idOwner.getVerifierProofHandler(connId, sourceId).foreach { proofHandle =>
      ProofApi.proofUpdateStateWithMessage(proofHandle, proof).get()
      val credState = ProofApi.proofGetState(proofHandle).get()
      credState shouldBe 4
      }
    }
  }

  def checkProofAccepted(fromOwnerName: IdentityOwnerName,
                         connId: String,
                         sourceId: String): Unit = {
    withVcxIdentityOwner(fromOwnerName) { idOwner =>
      idOwner.getHolderProofHandler(connId, sourceId).foreach { proofHandle =>
        eventually(timeout(Span(5, Seconds)), interval(Span(100, Millis))) {
          DisclosedProofApi.proofUpdateState(proofHandle).get()
          val credState = DisclosedProofApi.proofGetState(proofHandle).get()
          credState shouldBe 4
        }
      }
    }
  }

  def upgradeConnection(fromOwnerName: IdentityOwnerName,
                        connId: String,
                        data: String): Unit = {
    withConnection(fromOwnerName, connId) { (_, connHandle) =>
      ConnectionApi.connectionUpgrade(connHandle, data).get()
    }
  }

  def getPairwiseDID(fromOwnerName: IdentityOwnerName,
                     connId: String): String = {
    withConnection(fromOwnerName, connId) { (_, connHandle) =>
      ConnectionApi.connectionGetPwDid(connHandle).get()
    }
  }

  def expectMsg[T: ClassTag](idOwnerName: IdentityOwnerName,
                             fromConn: String,
                             legacyMsgTypeName: Option[String] = None): ExpectedMsg[T] = {
    withConnection(idOwnerName, fromConn) { (_, connHandle) =>
      val expectedPairwiseDID = ConnectionApi.connectionGetPwDid(connHandle).get()
      val expectedMsgTypeStr = Try(buildMsgTypeStr)
      eventually(timeout(Span(15, Seconds)), interval(Span(1000, Millis))) {
        val getMsgResult = UtilsApi.vcxGetMessages("MS-103", null, s"$expectedPairwiseDID").get()
        val jsonArray = new JSONArray(getMsgResult).asScala
        val pairwiseMsgsOpt = jsonArray.find { obj =>
          val jsonObject = new JSONObject(obj.toString)
          val receivedPairwiseDID = jsonObject.getString("pairwiseDID")
          receivedPairwiseDID  == expectedPairwiseDID
        }
        pairwiseMsgsOpt.isDefined shouldBe true
        val pairwiseMsgs = pairwiseMsgsOpt.get
        val jsonObject = new JSONObject(pairwiseMsgs.toString)
        val receivedMsgs = jsonObject.getJSONArray("msgs").asScala.map { obj =>
          val jsonMsg = new JSONObject(obj.toString)
          val msgId = jsonMsg.getString("uid")
          val decryptedPayload =  new JSONObject(jsonMsg.getString("decryptedPayload"))
          val payload = decryptedPayload.getString("@msg")
          val msgTypeName = decryptedPayload.getJSONObject("@type").getString("name")
          ReceivedMsg(msgId, msgTypeName, payload)
        }
        val expectedMsg = receivedMsgs.find { rm =>
          legacyMsgTypeName.contains(rm.msgTypeName) ||
            Try {
              val receivedMsg = new JSONObject(rm.msg)
              val receivedMsgType = receivedMsg.getString("@type")
              legacyMsgTypeName.contains(receivedMsgType) ||
                expectedMsgTypeStr.map(_ == receivedMsgType)
                  .getOrElse(false)
            }.getOrElse(false)
        }
        expectedMsg.isDefined shouldBe true
        val receivedMsg = expectedMsg.get
        val msgStr = receivedMsg.msg
        val typedMsg = JacksonMsgCodec.fromJson[T](msgStr)
        val thread = Try {
          val msgJsonObject = new JSONObject(msgStr)
          val threadJsonObject = msgJsonObject.getJSONObject("~thread")
          Option(DefaultMsgCodec.fromJson[com.evernym.verity.actor.agent.Thread](threadJsonObject.toString))
        }.getOrElse {
          Try {
            //for first message where `~thread` is not available
            val msgJsonObject = new JSONObject(msgStr)
            Option(com.evernym.verity.actor.agent.Thread(thid = Option(msgJsonObject.getString("@id"))))
          }.getOrElse(None)
        }
        updateMessageStatus(expectedPairwiseDID, receivedMsg.uid)
        ExpectedMsg[T](receivedMsg.uid, typedMsg, msgStr, thread)
      }
    }
  }

  def updateMessageStatus(pairwiseDID: String, msgId: String) : Unit = {
    val data = prepareUpdateMessageRequest(pairwiseDID, msgId)
    UtilsApi.vcxUpdateMessages("MS-106", data).get()
  }

  private def prepareUpdateMessageRequest(pwDid: String, messageUid: String): String = {
    val jsonArray = new JSONArray()
    val request = new JSONObject()
    val uids = new JSONArray()
    uids.put(messageUid)
    request.put("pairwiseDID", pwDid)
    request.put("uids", uids)
    jsonArray.put(request)
    jsonArray.toString
  }

  def withConnection[R](fromOwnerName: IdentityOwnerName, connId: String)(f: (IdentityOwner, Integer)=> R): R = {
    withVcxIdentityOwner(fromOwnerName) { idOwner =>
      val handle = idOwner.getConnectionHandler(connId).get
      val result = f(idOwner, handle)
      idOwner.updateConnectionHandle(connId, handle)
      result
    }
  }

  //should use this whenever we want to do any libvcx operation
  def withVcxIdentityOwner[R](ownerName: IdentityOwnerName)(f: IdentityOwner => R): R = {
    val idOwner = vcxConfigMapping(ownerName)
    VcxApi.vcxInitWithConfig(idOwner.config.toString()).get()
    val r = f(idOwner)
    VcxApi.vcxShutdown(false)
    r
  }

  def withIdentityOwner[R](ownerName: IdentityOwnerName)(f: IdentityOwner => R): R = {
    val owner = vcxConfigMapping(ownerName)
    f(owner)
  }

  def fetchAgencyKey(endpoint: String): AgencyPublicDid = {
    val resp = checkOKResponse(sendGET(endpoint + "/agency"))
    val apd = parseHttpResponseAs[AgencyPublicDid](resp)
    require(apd.DID.nonEmpty, "agency DID should not be empty")
    require(apd.verKey.nonEmpty, "agency verKey should not be empty")
    apd
  }

  protected def sendGET(url: String): HttpResponse = {
    awaitFut(
      Http().singleRequest(
        HttpRequest(
          method=HttpMethods.GET,
          uri = url,
          entity = HttpEntity.Empty
        )
      )
    )
  }

  //----------------
  private def provisionIssuerBase(identityOwnerName: IdentityOwnerName,
                                  verityEndpoint: String,
                                  agencyDidPair: DidPair,
                                  protocolVersion: String): IdentityOwner = {
    val jsonObject: JSONObject = new JSONObject()
    jsonObject.put("enterprise_seed", "000000000000000000000000Steward1")
    provisionIdentityOwner(identityOwnerName, verityEndpoint, agencyDidPair, protocolVersion, jsonObject)
  }

  private def provisionIdentityOwner(identityOwnerName: IdentityOwnerName,
                                     verityEndpoint: String,
                                     agencyDidPair: DidPair,
                                     protocolVersion: String,
                                     specificConfig: JSONObject): IdentityOwner = {
    val walletName = UUID.randomUUID().toString
    val walletKey = "test-password"

    val indyPoolNetwork = new JSONObject()
    val namespaceList = new JSONArray()
    namespaceList.put("sov")
    indyPoolNetwork.put("genesis_path", genesisTxnFilePath)
    indyPoolNetwork.put("namespace_list", namespaceList)

    if (isTaaEnabled) {
      val indyTAAConfigJson = new JSONObject()
      indyTAAConfigJson.put("text", "TAA for sandbox ledger")
      indyTAAConfigJson.put("version", "1.0.0")
      indyTAAConfigJson.put("acc_mech_type", "at_submission")
      indyTAAConfigJson.put("time", java.time.Instant.now().getEpochSecond)

      indyPoolNetwork.put("taa_config", indyTAAConfigJson)
    }

    val poolNetworks = new JSONArray()
    poolNetworks.put(indyPoolNetwork)
    val provisionConfig: JSONObject = new JSONObject(specificConfig.toString)
      .put("agency_url", verityEndpoint)
      .put("agency_did", agencyDidPair.did)
      .put("agency_verkey", agencyDidPair.verKey)
      .put("wallet_name", walletName)
      .put("wallet_key", walletKey)
      .put("pool_name", UUID.randomUUID().toString)
      .put("name", s"verity-integration-test")
      .put("logo", s"https://robohash.org/${UUID.randomUUID()}.png")
      .put("protocol_type", protocolVersion)
      .put("pool_networks", poolNetworks)

    val config = new JSONObject(UtilsApi.vcxAgentProvisionAsync(provisionConfig.toString()).get())
    val idOwner = IdentityOwner(config)
    vcxConfigMapping += identityOwnerName -> IdentityOwner(config)
    idOwner
  }

  protected def awaitFut[T](fut: Future[T]): T = {
    Await.result(fut, Duration(25, SECONDS))
  }

  def randomUUID(): String = UUID.randomUUID().toString

  implicit val system: ActorSystem = ActorSystemVanilla(randomUUID())

  type IdentityOwnerName = String
  private var vcxConfigMapping: Map[IdentityOwnerName, IdentityOwner] = Map.empty
  def executionContextProvider: ExecutionContextProvider

  case class ReceivedMsg(uid: String, msgTypeName: String, msg: String)
}

case class IdentityOwner(config: JSONObject) {

  type ConnId = String
  type SerializedConnHandle = String
  type SerializedCredDefHandle = String
  type SerializedCredHandle = String
  type SerializedProofHandle = String

  private var serializedConnHandles: Map[ConnId, SerializedConnHandle] = Map.empty
  private var serializedCredDefHandles: Map[ConnId, SerializedCredDefHandle] = Map.empty

  private var serializedHolderCredHandles: Map[ConnId, SerializedCredHandle] = Map.empty
  private var serializedIssuerCredHandles: Map[ConnId, SerializedCredHandle] = Map.empty

  private var serializedVerifierProofHandles: Map[ConnId, SerializedProofHandle] = Map.empty
  private var serializedHolderProofHandles: Map[ConnId, SerializedProofHandle] = Map.empty

  def updateConnectionHandle(connId1: String, handle: Integer): Unit = {
    val ser = ConnectionApi.connectionSerialize(handle).get
    serializedConnHandles += connId1 -> ser
  }

  def updateCredDefHandle(credDefId: String, handle: Integer): Unit = {
    val ser = CredentialDefApi.credentialDefSerialize(handle).get
    serializedCredDefHandles += credDefId -> ser
  }

  def updateHolderCredHandle(connId1: String, sourceId: String, handle: Integer): Unit = {
    val ser = CredentialApi.credentialSerialize(handle).get
    val key = connId1 + sourceId
    serializedHolderCredHandles += key -> ser
  }

  def updateIssuerCredHandle(connId1: String, sourceId: String, handle: Integer): Unit = {
    val ser = IssuerApi.issuerCredentialSerialize(handle).get
    val key = connId1 + sourceId
    serializedIssuerCredHandles += key -> ser
  }

  def updateVerifierProofHandle(connId1: String, sourceId: String, handle: Integer): Unit = {
    val ser = ProofApi.proofSerialize(handle).get
    val key = connId1 + sourceId
    serializedVerifierProofHandles += key -> ser
  }

  def updateHolderProofHandle(connId1: String, sourceId: String, handle: Integer): Unit = {
    val ser = DisclosedProofApi.proofSerialize(handle).get
    val key = connId1 + sourceId
    serializedHolderProofHandles += key -> ser
  }

  def getConnectionHandler(connId1: String): Option[Integer] = {
    serializedConnHandles.get(connId1).map(ConnectionApi.connectionDeserialize(_).get)
  }

  def getCredDefHandler(credDefId: String): Option[Integer] = {
    serializedCredDefHandles.get(credDefId).map(CredentialDefApi.credentialDefDeserialize(_).get)
  }

  def getHolderCredHandler(connId1: String, sourceId: String): Option[Integer] = {
    val key = connId1 + sourceId
    serializedHolderCredHandles.get(key).map(CredentialApi.credentialDeserialize(_).get)
  }

  def getIssuerCredHandler(connId1: String, sourceId: String): Option[Integer] = {
    val key = connId1 + sourceId
    serializedIssuerCredHandles.get(key).map(IssuerApi.issuerCredentialDeserialize(_).get)
  }

  def getVerifierProofHandler(connId1: String, sourceId: String): Option[Integer] = {
    val key = connId1 + sourceId
    serializedVerifierProofHandles.get(key).map(ProofApi.proofDeserialize(_).get)
  }

  def getHolderProofHandler(connId1: String, sourceId: String): Option[Integer] = {
    val key = connId1 + sourceId
    serializedHolderProofHandles.get(key).map(DisclosedProofApi.proofDeserialize(_).get)
  }

  val walletKey: String = config.getString("wallet_key")
  val walletName: String = config.getString("wallet_name")
  val sdkToRemoteDID: DidStr = config.getString("sdk_to_remote_did")
  val sdkToRemoteVerKey: VerKeyStr = config.getString("sdk_to_remote_verkey")
  val institutionDID: DidStr = config.getString("institution_did")
}

case class CreateSchemaParam(name: String, version: String, attribute: String)
case class CreateCredDefParam(name: String)

case class ExpectedMsg[T](uid: String, msg: T, msgStr: String, thread: Option[com.evernym.verity.actor.agent.Thread])

case class IssuerSetup(sourceId: String, schemaId: String, credDefId: String)
