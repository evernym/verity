package com.evernym.integrationtests.e2e.apis.legacy.v1tov2migration

import akka.http.scaladsl.model.Uri
import com.evernym.integrationtests.e2e.apis.legacy.base.{CreateCredDefParam, CreateSchemaParam, IssuerSetup, LibVcxProvider}
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.config.AppConfig
import com.evernym.verity.did.DidPair
import com.evernym.verity.integration.base.{CAS, EAS, VAS, VerityProviderBaseSpec}
import com.evernym.verity.integration.base.sdk_provider.SdkProvider
import com.evernym.verity.integration.base.verity_provider.VerityEnv
import com.evernym.verity.protocol.protocols.committedAnswer.v_1_0.Ctl.AskQuestion
import com.evernym.verity.protocol.protocols.committedAnswer.v_1_0.Msg.{Answer, Question}
import com.evernym.verity.protocol.protocols.committedAnswer.v_1_0.Signal.AnswerGiven
import com.evernym.verity.protocol.protocols.committedAnswer.v_1_0.{QuestionResponse, Sig}
import com.evernym.verity.protocol.protocols.connecting.common.InviteDetailAbbreviated
import com.evernym.verity.protocol.protocols.connections.v_1_0.Signal.{ConnRequestReceived, ConnResponseSent}
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Ctl.Offer
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Sig.Sent
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.Ctl.Request
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.ProofAttribute
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.Sig.PresentationResult
import com.evernym.verity.util.{Base64Util, MsgUtil, TestExecutionContextProvider}
import com.evernym.verity.util2.ExecutionContextProvider
import com.typesafe.config.ConfigFactory
import org.json.JSONObject

import java.nio.file.{Files, Paths}
import scala.concurrent.ExecutionContext

//NOTE: This test is not yet fully automatic, it depends on verity-migration to be done externally.
// Keeping it to be used locally to verity the migration.

class MigrateVerity1ToVerity2Spec
  extends VerityProviderBaseSpec
    with SdkProvider
    with LibVcxProvider {

  var agentState: AgentState = AgentState(
    "LegacyFaber",  //which uses libvcx
    "Alice",        //which uses libvcx
    "Faber",        //which will use verity-sdk ('issuerRestSDK')
    Map.empty
  )

  val totalOldConnections = 10

  lazy val issuerVAS: VerityEnv =
    VerityEnvBuilder
      .default()
      .withConfig(COMMON_VERITY_CONFIG)
      .build(VAS)

  lazy val holderCAS: VerityEnv =
    VerityEnvBuilder
      .default()
      .withConfig(COMMON_VERITY_CONFIG)
      .build(CAS)

  lazy val issuerEAS: VerityEnv =
    VerityEnvBuilder
      .default()
      .withConfig(COMMON_VERITY_CONFIG)
      .build(EAS)

  lazy val issuerRestSDK = setupIssuerRestSdk(issuerVAS, executionContext)

  var easAgencyDidPair: Option[DidPair] = None
  var vasAgencyDidPair: Option[DidPair] = None
  var casAgencyDidPair: Option[DidPair] = None
  var issuerSetup = IssuerSetup("", "")

  override def beforeAll(): Unit = {
    super.beforeAll()

    easAgencyDidPair = Option(fetchAgencyKey(issuerEAS.endpointProvider.availableNodeUrls.head).didPair)
    vasAgencyDidPair = Option(fetchAgencyKey(issuerVAS.endpointProvider.availableNodeUrls.head).didPair)
    casAgencyDidPair = Option(fetchAgencyKey(holderCAS.endpointProvider.availableNodeUrls.head).didPair)

    setupLibVcxEntAgent()
    setupLibVcxUserAgent()
    setupPreMigrationState(totalOldConnections)
    setupVASEntAgent()
  }

  "AgentDataMigration" - {
    "when started" - {
      "should be successful" in {
        val easOwner = identityOwner(agentState.entAgentName)
        val migrationParam = MigrationParam(
          validate = "True",
          mode = "start",
          casEndpoint = holderCAS.endpointProvider.availableNodeUrls.head,
          easEndpoint = issuerEAS.endpointProvider.availableNodeUrls.head,
          vasEndpoint = issuerVAS.endpointProvider.availableNodeUrls.head,
          getPairwiseDidsBatchSize = 3,
          List(
            Candidate(
              Verity1Params(easOwner.sdkToRemoteDID, SqliteWallet(easOwner.walletName, easOwner.walletKey)),
              Verity2Params(issuerRestSDK.verityAgentDidPairOpt.get.did, issuerRestSDK.verityAgentDidPairOpt.get.verKey)
            )
          )
        )
        val migrationParamJson = DefaultMsgCodec.toJson(migrationParam)
        println("migrationParamJson: " + migrationParamJson)
      }
    }

    "when waiting for completion" - {
      "should be successful" in {
        do {
          Thread.sleep(5000)    //wait for 5 seconds
        } while (! isMigrationFinished)    //check for migration completion
        issuerVAS.restartAllNodes()
      }
    }
  }

  "EntAdaption" - {
    "when started" - {
      "should be successful" in {
        issuerEAS.stopAllNodes()
        (1 to totalOldConnections).foreach { index =>
          val connId = s"$CONN_ID_PREFIX-$index"
          val easPairwiseDID = agentState.connections(connId).abbreviatedInvitation.s.d
          val easPairwiseVerKey = agentState.connections(connId).abbreviatedInvitation.s.v
          issuerRestSDK.updateMyPairwiseRelationships(connId, easPairwiseDID, easPairwiseVerKey)
        }
      }
    }
  }

  "PostMigration" - {
    "when messages exchanged" - {
      "should be successful" in {
        (1 to totalOldConnections).foreach { index =>
          val connId = s"$CONN_ID_PREFIX-$index"
          testMsgsOverExistingConnPostMigration(connId)
        }
        testMsgsOverNewConnPostMigration()
      }
    }
  }

  private def isMigrationFinished: Boolean = {
    Files.exists(Paths.get("/tmp/migrated"))
  }

  private def setupLibVcxEntAgent(): Unit = {
    provisionIssuer(
      agentState.entAgentName,
      issuerEAS.endpointProvider.availableNodeUrls.head,
      easAgencyDidPair.get,
      "1.0"     //will/should result into using 0.5 protocols on verity side
    )
    issuerSetup = setupIssuer(
      agentState.entAgentName,
      "sourceId1",
      CreateSchemaParam(
        s"degree-schema-v1.0",
        getRandomSchemaVersion,
        """["first-name","last-name","age"]"""
      ),
      CreateCredDefParam(s"degree-v1.0")
    )
  }

  private def setupLibVcxUserAgent(): Unit = {
    provisionHolder(
      agentState.userAgentName,
      holderCAS.endpointProvider.availableNodeUrls.head,
      casAgencyDidPair.get,
      "3.0"     //member pass uses 3.0
    )
  }

  private def setupPreMigrationState(totalConn: Int = 10): Unit = {
    (1 to totalConn).foreach { index =>
      val connId = s"$CONN_ID_PREFIX-$index"
      val invitation = createConnection(agentState.entAgentName, connId)
      agentState = agentState.updateConnState(connId, invitation)
      acceptInvitationLegacy(agentState.userAgentName, connId, invitation)
      checkConnectionAccepted(agentState.entAgentName, connId)
      //exchange some messages (question-answer)
      exchangeCommittedAnswerBeforeMigration(connId, "How are you?", "fine")
    }
  }


  private def exchangeCommittedAnswerBeforeMigration(connId: String, question: String, answer: String): Unit = {
    sendMessage(agentState.entAgentName, connId,
      Question(
        question_text = question,
        question_detail = None,
        valid_responses = Vector(QuestionResponse(answer, "nonce")),
        `@timing` = None
      )
    )
    val expectedQuestionMsg = expectMsg[Question](agentState.userAgentName, connId)
    expectedQuestionMsg.msg.question_text shouldBe question

    sendMessage(
      agentState.userAgentName,
      connId,
      Answer(
        `response.@sig` = Sig(
          signature = "signature",
          sig_data = Base64Util.getBase64Encoded(answer.getBytes),
          timestamp = "2021-12-06T17:29:34+0000"
        )
      ),
      expectedQuestionMsg.thread.flatMap(_.thid)
    )
    val expectedAnswerMsg = expectMsg[Answer](agentState.entAgentName, connId)
    expectedAnswerMsg.msg.`response.@sig`.signature shouldBe "signature"
  }

  private def testMsgsOverExistingConnPostMigration(connId: String): Unit = {
    testCommittedAnswerPostMigration(connId)
    //NOTE (for existing connection only):
    // 'issue-cred', 'present-proof' (and may be other) protocols
    // doesn't work post verity side of the migration and user will have to
    // upgrade the app (with libvcx connection migration changes)
    // which will migrate the connection in libvcx to be able to use/respond to
    // 'issue-cred', 'present-proof' protocols.
  }

  private def testMsgsOverNewConnPostMigration(): Unit = {
    val newConnId = "newConn"
    testEstablishNewConnPostMigration(newConnId)
    testCommittedAnswerPostMigration(newConnId)
    testIssueCredPostMigration(newConnId, s"$newConnId:sourceId1")
    testPresentProofPostMigration(newConnId, s"$newConnId:sourceId1")
  }

  private def testEstablishNewConnPostMigration(connId: String): Unit = {
    val receivedMsg = issuerRestSDK.sendCreateRelationship(connId)
    val lastReceivedThread = receivedMsg.threadOpt
    val invitation = issuerRestSDK.sendCreateConnectionInvitation(connId, lastReceivedThread)
    val decoded = Base64Util.urlDecodeToStr(
      Uri(invitation.inviteURL)
        .query()
        .getOrElse(
          "c_i",
          throw new Exception("Invalid invite URL")
        )
    )
    val invite = new JSONObject(decoded)
    val updatedInvite = invite.put("@id", MsgUtil.newMsgId)
    acceptInvitation(agentState.userAgentName, connId, updatedInvite.toString)
    issuerRestSDK.expectMsgOnWebhook[ConnRequestReceived]()
    issuerRestSDK.expectMsgOnWebhook[ConnResponseSent]()
  }

  private def testIssueCredPostMigration(connId: String, sourceId: String): Unit = {
    val msg = Offer(
      cred_def_id = issuerSetup.credDefId,
      credential_values = Map("first-name" -> "Hi", "last-name" -> "there", "age" -> "30"),
      auto_issue = Option(true)
    )
    issuerRestSDK.sendMsgForConn(connId, msg)
    issuerRestSDK.expectMsgOnWebhook[Sent]()

    val expectedOfferMsg = expectMsg[Any](agentState.userAgentName, connId, Option("credential-offer"))
    sendCredReq(agentState.userAgentName, connId, sourceId, expectedOfferMsg.msgStr)
    checkReceivedCred(agentState.userAgentName, connId, sourceId)
  }

  private def testPresentProofPostMigration(connId: String, sourceId: String): Unit = {
    val msg = Request(
      name = "test",
      Option(List(
        ProofAttribute(
          None,
          Option(List("first-name",  "last-name", "age")),
          None,
          None,
          self_attest_allowed = false)
      )),
      None,
      None
    )
    issuerRestSDK.sendMsgForConn(connId, msg)

    val expectedProofReq = expectMsg[Any](agentState.userAgentName, connId, Option("presentation-request"))
    sendProof(agentState.userAgentName, connId, sourceId, expectedProofReq.msgStr)
    checkProofAccepted(agentState.userAgentName, connId, sourceId)

    issuerRestSDK.expectMsgOnWebhook[PresentationResult]()
  }

  private def testCommittedAnswerPostMigration(connId: String): Unit = {
    val question = s"PostMigration-$connId: How are you?"
    val answer = s"PostMigration-$connId: I am fine"

    val msg =
      AskQuestion(
        text = question,
        detail = Option("question-detail"),
        valid_responses = Vector(answer),
        expiration = None
      )
    issuerRestSDK.sendMsgForConn(connId, msg)

    val expectedQuestionMsg = expectMsg[Question](agentState.userAgentName, connId)
    expectedQuestionMsg.msg.question_text shouldBe question

    answerMsg(
      agentState.userAgentName,
      connId,
      expectedQuestionMsg.msgStr,
      DefaultMsgCodec.toJson(expectedQuestionMsg.msg.valid_responses.head)
    )

    val answerGivenMsg = issuerRestSDK.expectMsgOnWebhook[AnswerGiven]()
    answerGivenMsg.msg.answer shouldBe answer
  }

  def setupVASEntAgent(): Unit = {
    issuerRestSDK.fetchAgencyKey()
    println("============")
    issuerRestSDK.provisionVerityEdgeAgent()    //this sends a packed message (not REST api call)
    println("============")
    issuerRestSDK.registerWebhook()
  }

  lazy val CONN_ID_PREFIX = "oldConn"

  lazy val ecp: ExecutionContextProvider = TestExecutionContextProvider.ecp
  lazy val executionContext: ExecutionContext = ecp.futureExecutionContext
  override def futureExecutionContext: ExecutionContext = executionContext
  override def executionContextProvider: ExecutionContextProvider = ecp

  override def appConfig: AppConfig = {
    val conf = ConfigFactory.load()
    new TestAppConfig(Option(conf))
  }

  val COMMON_VERITY_CONFIG = ConfigFactory.parseString(
    """
      |verity.http.host = 0.0.0.0
      |verity.rest-api.enabled = true
      |verity.metrics.enabled = N
      |cinnamon.chmetrics.reporters = "none"
      |
      |verity.lib-vdrtools.wallet.type = "mysql"
      |verity {
      |
      |  wallet-storage {
      |
      |    read-host-ip = "localhost"
      |    read-host-ip = ${?MYSQL_HOST}
      |
      |    write-host-ip = "localhost"
      |    write-host-ip = ${?MYSQL_HOST}
      |
      |    host-port = 3306
      |    host-port = ${?WALLET_STORAGE_HOST_PORT}
      |
      |    credentials-username = "msuser"
      |    credentials-username = ${?WALLET_STORAGE_CREDENTIAL_USERNAME}
      |
      |    credentials-password = "mspassword"
      |    credentials-password = ${?WALLET_STORAGE_CREDENTIAL_PASSWORD}
      |
      |    db-name = "wallet"
      |    db-name = ${?WALLET_STORAGE_DB_NAME}
      |  }
      |}
      |
      |""".stripMargin
  ).resolve()
}

case class AgentState(entAgentName: String,
                      userAgentName: String,
                      verityAgentName: String,
                      connections: Map[String, ConnectionState]) {

  def updateConnState(connId: String, invitation: String): AgentState = {
    val newConnectionState = connections + (connId ->  ConnectionState(invitation))
    copy(connections = newConnectionState)
  }
}

case class UserAgentState(name: String)
case class EntAgentState(name: String)
case class VerityAgentState(name: String)
case class ConnectionState(invitation: String = "") {
  def abbreviatedInvitation: InviteDetailAbbreviated = DefaultMsgCodec.fromJson[InviteDetailAbbreviated](invitation)
}

case class MigrationParam(validate: String,
                          mode: String,
                          casEndpoint: String,
                          easEndpoint: String,
                          vasEndpoint: String,
                          getPairwiseDidsBatchSize: Int,
                          candidates: List[Candidate])
case class Candidate(verity1Params: Verity1Params, verity2Params: Verity2Params)
case class Verity1Params(easAgentDID: String, sqliteWallet: SqliteWallet)
case class SqliteWallet(name: String, key: String)
case class Verity2Params(vasDomainDID: String, vasDomainDIDVerKey: String)