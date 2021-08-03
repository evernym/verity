package com.evernym.integrationtests.e2e.apis.legacy.vcx

import com.evernym.integrationtests.e2e.apis.legacy.AgencyAdminEnvironment
import com.evernym.integrationtests.e2e.env.AppInstance.AppInstance
import com.evernym.integrationtests.e2e.env.EnvUtils.IntegrationEnv
import com.evernym.integrationtests.e2e.env.VerityInstance
import com.evernym.integrationtests.e2e.flow.SetupFlow
import com.evernym.integrationtests.e2e.scenario.{ApplicationAdminExt, Scenario}
import com.evernym.sdk.vcx.connection.ConnectionApi
import com.evernym.sdk.vcx.credentialDef.CredentialDefApi
import com.evernym.sdk.vcx.schema.SchemaApi
import com.evernym.sdk.vcx.utils.UtilsApi
import com.evernym.sdk.vcx.vcx.VcxApi
import com.evernym.verity.agentmsg.msgcodec.jackson.JacksonMsgCodec
import com.evernym.verity.config.ConfigUtil
import com.evernym.verity.did.{DID, VerKey}
import com.evernym.verity.fixture.TempDir
import com.evernym.verity.integration.base.sdk_provider.MsgFamilyHelper.buildMsgTypeStr
import com.evernym.verity.integration.base.sdk_provider.{JsonMsgUtil, MsgFamilyHelper}
import com.evernym.verity.logging.LoggingUtil.getLoggerByName
import com.evernym.verity.protocol.engine.MsgFamily
import com.evernym.verity.testkit.util.LedgerUtil
import com.evernym.verity.testkit.{BasicSpecWithIndyCleanup, CancelGloballyAfterFailure}
import com.typesafe.scalalogging.Logger
import org.json.{JSONArray, JSONObject}
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}

import java.time.Instant
import java.util.UUID
import scala.collection.JavaConverters._
import scala.reflect.ClassTag
import scala.util.Random

trait BaseVcxFlowSpec
  extends BasicSpecWithIndyCleanup
    with Eventually
    with TempDir
    with IntegrationEnv
    with SetupFlow
    with CancelGloballyAfterFailure {

  override val logger: Logger = getLoggerByName("VcxFlowSpec")

  def appNameCAS: String = APP_NAME_CAS_1
  def appNameEAS: String = APP_NAME_EAS_1

  val cas = testEnv.instance_!(appNameCAS)
  val eas = testEnv.instance_!(appNameEAS)

  val requiredAppInstances: List[AppInstance] = List(cas.appInstance, eas.appInstance)
  val agencyScenario = Scenario("Agency setup scenario", requiredAppInstances, suiteTempDir, projectDir)

  val agencyAdminEnv: AgencyAdminEnvironment = AgencyAdminEnvironment(
    agencyScenario,
    casVerityInstance = testEnv.instance_!(appNameCAS),
    easVerityInstance = testEnv.instance_!(appNameEAS))

  setupAgency(agencyAdminEnv)

  //agency environment detail
  def setupAgency(ae: AgencyAdminEnvironment): Unit = {
    implicit def sc: Scenario = ae.scenario
    s"${ae.scenario.name}" - {
      "Consumer Agency Admin" - {
        setupApplication(ae.consumerAgencyAdmin, ledgerUtil)
      }

      "Enterprise Agency Admin" - {
        setupApplication(ae.enterpriseAgencyAdmin, ledgerUtil)
      }
    }
  }

  lazy val ledgerUtil = new LedgerUtil(
    appConfig,
    None,
    taa = ConfigUtil.findTAAConfig(appConfig, "1.0.0"),
    genesisTxnPath = Some(testEnv.ledgerConfig.genesisFilePath)
  )

  def provisionIssuer(identityOwnerName: IdentityOwnerName,
                      verityInstance: VerityInstance,
                      agencyAdmin: ApplicationAdminExt,
                      protocolVersion: String
                     ): Unit = {
    val idOwner = provisionIssuerBase(identityOwnerName, verityInstance, agencyAdmin, protocolVersion)
    withTAAAccepted(identityOwnerName) { _ =>
      ledgerUtil.bootstrapNewDID(idOwner.sdkToRemoteDID, idOwner.sdkToRemoteVerKey)
    }
  }

  def provisionHolder(identityOwnerName: IdentityOwnerName,
                      verityInstance: VerityInstance,
                      agencyAdmin: ApplicationAdminExt,
                      protocolVersion: String): IdentityOwner = {
    val jsonObject: JSONObject = new JSONObject()
    provisionIdentityOwner(identityOwnerName, verityInstance, agencyAdmin, protocolVersion, jsonObject)
  }

  def setupIssuer(identityOwnerName: IdentityOwnerName,
                  sourceId: String,
                  createSchemaParam: CreateSchemaParam,
                  credDefParam: CreateCredDefParam): Unit = {

    val schemaId: String = withTAAAccepted(identityOwnerName) { _ =>
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

    withTAAAccepted(identityOwnerName) { _ =>
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

      CredentialDefApi.credentialDefGetCredentialDefId(credDefHandle).get()
    }
  }

  def createConnection(identityOwnerName: IdentityOwnerName, connId: String): String = {
    withVcxIdentityOwner(identityOwnerName) { idOwner =>
      val connectionHandle = ConnectionApi.vcxConnectionCreate(connId).get()
      val jsonInviteDetail = ConnectionApi.vcxConnectionConnect(connectionHandle, "{}").get
      ConnectionApi.vcxConnectionUpdateState(connectionHandle).get()
      idOwner.updateConnectionHandle(connId, connectionHandle)
      jsonInviteDetail
    }
  }

  def acceptInvitation(identityOwnerName: IdentityOwnerName,
                       connId: String,
                       invite: String): Unit = {
    withVcxIdentityOwner(identityOwnerName) { idOwner =>
      val inviteJsonObject = new JSONObject(invite)
      val inviteId = inviteJsonObject.getString("id")
      val acceptResult = ConnectionApi.vcxConnectionAcceptConnectionInvite(inviteId, invite, null).get()
      idOwner.updateConnectionHandle(connId, acceptResult.getConnectionHandle)

      eventually(timeout(Span(5, Seconds)), interval(Span(100, Millis))) {
        ConnectionApi.vcxConnectionUpdateState(acceptResult.getConnectionHandle).get()
        val curState = ConnectionApi.connectionGetState(acceptResult.getConnectionHandle).get()
        curState shouldBe 4
      }
    }
  }

  def checkConnectionAccepted(idOwner: IdentityOwnerName, connId: String): Unit = {
    withConnection(idOwner, connId) { handle =>
      eventually(timeout(Span(5, Seconds)), interval(Span(100, Millis))) {
        ConnectionApi.vcxConnectionUpdateState(handle).get()
        val curState = ConnectionApi.connectionGetState(handle).get()
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

  def withTAAAccepted[R](forOwnerName: IdentityOwnerName)(f: IdentityOwner => R): R = {
    withVcxIdentityOwner(forOwnerName) { owner =>
      val agreement = new JSONObject(UtilsApi.getLedgerAuthorAgreement.get())
      val acceptMechanism = "at_submission"
      UtilsApi.setActiveTxnAuthorAgreementMeta(
        agreement.getString("text"),
        agreement.getString("version"),
        null,
        acceptMechanism,
        Instant.now.getEpochSecond
      )
      f(owner)
    }
  }

  //----------------
  private def provisionIssuerBase(identityOwnerName: IdentityOwnerName,
                                  verityInstance: VerityInstance,
                                  agencyAdmin: ApplicationAdminExt,
                                  protocolVersion: String): IdentityOwner = {
    val jsonObject: JSONObject = new JSONObject()
    jsonObject.put("enterprise_seed", "000000000000000000000000Steward1")
    provisionIdentityOwner(identityOwnerName, verityInstance, agencyAdmin, protocolVersion, jsonObject)
  }

  private def provisionIdentityOwner(identityOwnerName: IdentityOwnerName,
                                     verityInstance: VerityInstance,
                                     agencyAdmin: ApplicationAdminExt,
                                     protocolVersion: String,
                                     specificConfig: JSONObject): IdentityOwner = {
    val walletName = UUID.randomUUID().toString
    val walletKey = "test-password"

    val provisionConfig: JSONObject = new JSONObject(specificConfig.toString)
      .put("agency_url", verityInstance.endpoint.toString)
      .put("agency_did", agencyAdmin.agencyIdentity.DID)
      .put("agency_verkey", agencyAdmin.agencyIdentity.verKey)
      .put("wallet_name", walletName)
      .put("wallet_key", walletKey)
      .put("pool_name", UUID.randomUUID().toString)
      .put("name", s"verity-integration-test")
      .put("logo", s"https://robohash.org/${UUID.randomUUID()}.png")
      .put("path", testEnv.ledgerConfig.genesisFilePath)
      .put("protocol_type", protocolVersion)

    val config = new JSONObject(UtilsApi.vcxAgentProvisionAsync(provisionConfig.toString()).get())
    val idOwner = IdentityOwner(config)
    vcxConfigMapping += identityOwnerName -> IdentityOwner(config)
    idOwner
  }

  def sendMessage[T: ClassTag](fromOwnerName: IdentityOwnerName, toConn: String, msg: T): Unit = {
    withConnection(fromOwnerName, toConn) { handle =>
      val msgFamily = MsgFamilyHelper.getMsgFamilyOpt(msg.getClass).getOrElse(
        throw new RuntimeException("message family not found for given message: " + msg.getClass.getSimpleName)
      )
      val msgType = msgFamily.msgType(msg.getClass)
      val typeStr = MsgFamily.typeStrFromMsgType(msgType)
      val jsonMsg = JsonMsgUtil.createJsonString(typeStr, msg)
      ConnectionApi.connectionSendMessage(handle, jsonMsg,
      """{"msg_type":"type", "msg_title": "title"}""").get()
    }
  }

  def expectMsg[T: ClassTag](idOwnerName: IdentityOwnerName,
                             fromConn: String): T = {
    withConnection(idOwnerName, fromConn) { handle =>
      val expectedMsgType = buildMsgTypeStr
      val expectedPairwiseDID = ConnectionApi.connectionGetPwDid(handle).get()

      eventually(timeout(Span(5, Seconds)), interval(Span(100, Millis))) {
        val getMsgResult = UtilsApi.vcxGetMessages("MS-103", null, null).get()
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
          val decryptedPayload = new JSONObject(obj.toString).getString("decryptedPayload")
          new JSONObject(decryptedPayload).getString("@msg")
        }
        val expectedMsg = receivedMsgs.find { msg =>
          val receivedMsg = new JSONObject(msg)
          receivedMsg.getString("@type") == expectedMsgType
        }
        expectedMsg.isDefined shouldBe true
        JacksonMsgCodec.fromJson[T](expectedMsg.get)
      }
    }
  }

  def withConnection[R](fromOwnerName: IdentityOwnerName, connId: String)(f: Integer=> R): R = {
    withVcxIdentityOwner(fromOwnerName) { idOwner =>
      val handle = idOwner.getConnectionHandler(connId).get
      val result = f(handle)
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

  type IdentityOwnerName = String
  private var vcxConfigMapping: Map[IdentityOwnerName, IdentityOwner] = Map.empty

}

case class IdentityOwner(config: JSONObject) {

  type ConnId = String
  type SerializedConnHandle = String

  private var serializedConnection: Map[ConnId, SerializedConnHandle] = Map.empty

  def updateConnectionHandle(connId1: String, handle: Integer): Unit = {
    serializedConnection += connId1 -> ConnectionApi.connectionSerialize(handle).get
  }

  def getConnectionHandler(connId1: String): Option[Integer] = {
    serializedConnection.get(connId1).map(ConnectionApi.connectionDeserialize(_).get)
  }

  def sdkToRemoteDID: DID = config.getString("sdk_to_remote_did")
  def sdkToRemoteVerKey: VerKey = config.getString("sdk_to_remote_verkey")
}

case class CreateSchemaParam(name: String, version: String, attribute: String)
case class CreateCredDefParam(name: String)