//package com.evernym.integrationtests.e2e.apis.legacy.v1tov2migration
//
//import akka.http.scaladsl.model.Uri
//import com.evernym.integrationtests.e2e.apis.legacy.base.{CreateCredDefParam, CreateSchemaParam, IssuerSetup, LibVcxProvider}
//import com.evernym.verity.actor.testkit.TestAppConfig
//import com.evernym.verity.agentmsg.DefaultMsgCodec
//import com.evernym.verity.config.AppConfig
//import com.evernym.verity.did.DidPair
//import com.evernym.verity.integration.base.{CAS, EAS, VAS, VerityProviderBaseSpec}
//import com.evernym.verity.integration.base.sdk_provider.{IssuerRestSDK, SdkProvider}
//import com.evernym.verity.integration.base.verity_provider.VerityEnv
//import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
//import com.evernym.verity.protocol.protocols.committedAnswer.v_1_0.Ctl.AskQuestion
//import com.evernym.verity.protocol.protocols.committedAnswer.v_1_0.Msg.{Answer, Question}
//import com.evernym.verity.protocol.protocols.committedAnswer.v_1_0.Signal.AnswerGiven
//import com.evernym.verity.protocol.protocols.committedAnswer.v_1_0.{QuestionResponse, Sig}
//import com.evernym.verity.protocol.protocols.connecting.common.InviteDetailAbbreviated
//import com.evernym.verity.protocol.protocols.connections.v_1_0.Signal.{ConnRequestReceived, ConnResponseSent}
//import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Ctl.Offer
//import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Sig.Sent
//import com.evernym.verity.protocol.protocols.presentproof.v_1_0.Ctl.Request
//import com.evernym.verity.protocol.protocols.presentproof.v_1_0.ProofAttribute
//import com.evernym.verity.protocol.protocols.presentproof.v_1_0.Sig.PresentationResult
//import com.evernym.verity.testkit.util.HttpUtil
//import com.evernym.verity.util.{Base64Util, MsgUtil, TestExecutionContextProvider}
//import com.evernym.verity.util2.ExecutionContextProvider
//import com.typesafe.config.ConfigFactory
//import com.typesafe.scalalogging.Logger
//import org.json.JSONObject
//
//import java.nio.file.{Files, Paths}
//import scala.concurrent.ExecutionContext
//
////NOTE: This test is not yet fully automatic,
//// it depends on verity-migration to be done externally.
//// Keeping it to be used locally to verify the migration.
//// (Somehow this gets included and run as part of the integration test,
//// hence commenting this for now, until that gets fixed)
//
//class MigrateVerity1ToVerity2Spec
//  extends VerityProviderBaseSpec
//    with SdkProvider
//    with LibVcxProvider {
//
//  //override lazy val isTaaEnabled: Boolean = false
//
//  val setupData: SetupData = SetupData(
//    totalEntAgents = 2,
//    preMigrationConnsPerEntAgent = 2,
//    postMigrationConnsPerEntAgent = 2
//  )
//
//  lazy val issuerVAS: VerityEnv =
//    VerityEnvBuilder
//      .default()
//      .withConfig(COMMON_VERITY_CONFIG)
//      .build(VAS)
//
//  lazy val holderCAS: VerityEnv =
//    VerityEnvBuilder
//      .default()
//      .withConfig(COMMON_VERITY_CONFIG)
//      .build(CAS)
//
//  lazy val issuerEAS: VerityEnv =
//    VerityEnvBuilder
//      .default()
//      .withConfig(COMMON_VERITY_CONFIG)
//      .build(EAS)
//
//  var easAgencyDidPair: Option[DidPair] = None
//  var vasAgencyDidPair: Option[DidPair] = None
//  var casAgencyDidPair: Option[DidPair] = None
//
//  override def beforeAll(): Unit = {
//    super.beforeAll()
//
//    easAgencyDidPair = Option(fetchAgencyKey(issuerEAS.endpointProvider.availableNodeUrls.head).didPair)
//    vasAgencyDidPair = Option(fetchAgencyKey(issuerVAS.endpointProvider.availableNodeUrls.head).didPair)
//    casAgencyDidPair = Option(fetchAgencyKey(holderCAS.endpointProvider.availableNodeUrls.head).didPair)
//
//    setupEntV1Agents()
//    setupPreMigrationState()
//    setupEntV2Agents()
//  }
//
//  "VerityAdmin" - {
//    "when preparing the migration configuration" - {
//      "should be successful" in {
//        val candidates = setupData.entAgents.map { case (entName, entAgentState) =>
//          val easOwner = identityOwner(entName)
//          Candidate(
//            Verity1Params(
//              easOwner.sdkToRemoteDID,
//              SqliteWallet(easOwner.walletName, easOwner.walletKey)
//            ),
//            Verity2Params(
//              entAgentState.issuerRestSDK.verityAgentDidPairOpt.get.did,
//              entAgentState.issuerRestSDK.verityAgentDidPairOpt.get.verKey
//            )
//          )
//        }
//        val migrationParam = MigrationParam(
//          validate = "True",
//          mode = "start",
//          casEndpoint = holderCAS.endpointProvider.availableNodeUrls.head,
//          easEndpoint = issuerEAS.endpointProvider.availableNodeUrls.head,
//          vasEndpoint = issuerVAS.endpointProvider.availableNodeUrls.head,
//          candidates.toList
//        )
//        val migrationParamJson = DefaultMsgCodec.toJson(migrationParam)
//        println("migrationParamJson: " + migrationParamJson)
//      }
//    }
//
//    "when started migration" - {
//      "should be successful" in {
//        do {
//          Thread.sleep(5000)    //wait for 5 seconds
//        } while (! isMigrationFinished)    //check for migration completion
//        issuerVAS.restartAllNodes()
//      }
//    }
//  }
//
//  "EntAdaption" - {
//    "when started" - {
//      "should be successful" in {
//        issuerEAS.stopAllNodes()
//        setupData.entAgents.foreach { case (entName, entAgentState) =>
//          entAgentState.preMigrationConns.foreach{ case (userName, userAgentState) =>
//            val easPairwiseDID = userAgentState.abbreviatedInvitation.s.d
//            val easPairwiseVerKey = userAgentState.abbreviatedInvitation.s.v
//            entAgentState.issuerRestSDK.updateMyPairwiseRelationships(userAgentState.connId, easPairwiseDID, easPairwiseVerKey)
//          }
//        }
//      }
//    }
//  }
//
//  "PostMigration" - {
//    "when messages exchanged" - {
//      "should be successful" in {
//        testMsgsOverPreMigrationConns()
//        testMsgsOverNewConnPostMigration()
//      }
//    }
//  }
//
//  private def isMigrationFinished: Boolean = {
//    Files.exists(Paths.get("/tmp/migrated"))
//  }
//
//  private def setupEntV1Agents(): Unit = {
//    (1 to setupData.totalEntAgents).foreach { i =>
//      logger.warn(s"setting up v1 ent agent #$i ")
//      val entName = s"ent-$i"
//      provisionIssuer(
//        entName,
//        issuerEAS.endpointProvider.availableNodeUrls.head,
//        easAgencyDidPair.get,
//        "1.0" //will/should result into using 0.5 protocols on verity side
//      )
//      val issuerSetup = setupIssuer(
//        entName,
//        s"sourceId-$i",
//        CreateSchemaParam(
//          s"degree-schema-v1.0",
//          getRandomSchemaVersion,
//          """["first-name","last-name","age"]"""
//        ),
//        CreateCredDefParam(s"degree-v1.0")
//      )
//
//      setupData.addEntAgentState(entName, issuerSetup)
//    }
//  }
//
//  private def setupEntV2Agents(): Unit = {
//    setupData.entAgents.foreach { case (entName, entAgentState) =>
//      logger.warn(s"setting up v2 ent agent: $entName")
//      val sdk = setupIssuerRestSdk(issuerVAS, executionContext)
//      sdk.fetchAgencyKey()
//      sdk.provisionVerityEdgeAgent()
//      sdk.registerWebhook()
//      entAgentState.setRestSdk(sdk)
//    }
//  }
//
//  private def setupPreMigrationState(): Unit = {
//    setupData.entAgents.foreach { case (entName, entAgentState) =>
//      (1 to setupData.preMigrationConnsPerEntAgent).foreach { i =>
//        logger.warn(s"setting up pre migration state: $entName:$i")
//        val userId = s"$entName-user-$i"
//        val connId = s"$entName-conn-$i"
//        val invitation = createConnection(entName, connId)
//
//        provisionHolder(
//          userId,
//          holderCAS.endpointProvider.availableNodeUrls.head,
//          casAgencyDidPair.get,
//          "3.0" //member pass uses 3.0
//        )
//        entAgentState.addPreMigrationConnState(userId, connId, invitation)
//
//        acceptInvitationLegacy(userId, connId, invitation)
//        checkConnectionAccepted(entName, connId)
//      }
//    }
//    //exchange some messages (question-answer)
//    exchangeCommittedAnswerBeforeMigration("How are you?", "fine")
//  }
//
//  private def exchangeCommittedAnswerBeforeMigration(question: String,
//                                                     answer: String): Unit = {
//    setupData.entAgents.foreach { case (entName, entAgentState) =>
//      entAgentState.preMigrationConns.foreach { case (userName, userAgentState) =>
//        logger.warn(s"testing committed-answer pre migration: $userName")
//        sendMessage(entName, userAgentState.connId,
//          Question(
//            question_text = question,
//            question_detail = None,
//            valid_responses = Vector(QuestionResponse(answer, "nonce")),
//            `@timing` = None
//          )
//        )
//        val expectedQuestionMsg = expectMsg[Question](userName, userAgentState.connId)
//        expectedQuestionMsg.msg.question_text shouldBe question
//
//        sendMessage(
//          userName,
//          userAgentState.connId,
//          Answer(
//            `response.@sig` = Sig(
//              signature = "signature",
//              sig_data = Base64Util.getBase64Encoded(answer.getBytes),
//              timestamp = "2021-12-06T17:29:34+0000"
//            )
//          ),
//          expectedQuestionMsg.thread.flatMap(_.thid)
//        )
//        val expectedAnswerMsg = expectMsg[Answer](entName, userAgentState.connId)
//        expectedAnswerMsg.msg.`response.@sig`.signature shouldBe "signature"
//      }
//    }
//  }
//
//  private def testMsgsOverPreMigrationConns(): Unit = {
//    testCommittedAnswerPostMigration(forPreMigrationConn = true)
//    //NOTE (for existing connection only):
//    // 'issue-cred', 'present-proof' (and may be other) protocols
//    // doesn't work post verity side of the migration and user will have to
//    // upgrade the app (with libvcx connection migration changes)
//    // which will migrate the connection in libvcx to be able to use/respond to
//    // 'issue-cred', 'present-proof' protocols.
//  }
//
//  private def testMsgsOverNewConnPostMigration(): Unit = {
//    testEstablishNewConnsPostMigration()
//    testCommittedAnswerPostMigration(forPreMigrationConn = false)
//    testIssueCredPostMigration(forPreMigrationConn = false)
//    testPresentProofPostMigration(forPreMigrationConn = false)
//  }
//
//  private def testEstablishNewConnsPostMigration(): Unit = {
//    setupData.entAgents.foreach { case (entName, entAgentState) =>
//      (1 to setupData.postMigrationConnsPerEntAgent).foreach { i =>
//        val userId = s"$entName-user-$i"
//        val connId = s"$entName-conn-$i"
//
//        val issuerRestSDK = entAgentState.issuerRestSDK
//        val receivedMsg = issuerRestSDK.sendCreateRelationship(connId)
//        val lastReceivedThread = receivedMsg.threadOpt
//        val invitation = issuerRestSDK.sendCreateConnectionInvitation(connId, lastReceivedThread)
//        val decoded = Base64Util.urlDecodeToStr(
//          Uri(invitation.inviteURL)
//            .query()
//            .getOrElse(
//              "c_i",
//              throw new Exception("Invalid invite URL")
//            )
//        )
//        val invite = new JSONObject(decoded)
//        val updatedInvite = invite.put("@id", MsgUtil.newMsgId)
//
//        entAgentState.addPostMigrationConnState(userId, connId, updatedInvite.toString)
//
//        acceptInvitation(userId, connId, updatedInvite.toString)
//        issuerRestSDK.expectMsgOnWebhook[ConnRequestReceived]()
//        issuerRestSDK.expectMsgOnWebhook[ConnResponseSent]()
//      }
//    }
//  }
//
//  private def testCommittedAnswerPostMigration(forPreMigrationConn: Boolean): Unit = {
//    setupData.entAgents.foreach { case (entName, entAgentState) =>
//      entAgentState.getTargetConns(forPreMigrationConn).foreach { case (userName, userAgentState) =>
//        val issuerRestSDK = entAgentState.issuerRestSDK
//        val connId = userAgentState.connId
//
//        val question = s"PostMigration-$connId: How are you?"
//        val answer = s"PostMigration-$connId: I am fine"
//
//        val msg =
//          AskQuestion(
//            text = question,
//            detail = Option("question-detail"),
//            valid_responses = Vector(answer),
//            expiration = None
//          )
//        issuerRestSDK.sendMsgForConn(connId, msg)
//
//        val expectedQuestionMsg = expectMsg[Question](userName, connId)
//        expectedQuestionMsg.msg.question_text shouldBe question
//
//        answerMsg(
//          userName,
//          connId,
//          expectedQuestionMsg.msgStr,
//          DefaultMsgCodec.toJson(expectedQuestionMsg.msg.valid_responses.head)
//        )
//
//        val answerGivenMsg = issuerRestSDK.expectMsgOnWebhook[AnswerGiven]()
//        answerGivenMsg.msg.answer shouldBe answer
//      }
//    }
//  }
//
//  private def testIssueCredPostMigration(forPreMigrationConn: Boolean): Unit = {
//    setupData.entAgents.foreach { case (entName, entAgentState) =>
//      entAgentState.getTargetConns(forPreMigrationConn).foreach { case (userName, userAgentState) =>
//        val issuerRestSDK = entAgentState.issuerRestSDK
//        val issuerSetup = entAgentState.issuerSetup
//        val connId = userAgentState.connId
//
//        val msg = Offer(
//          cred_def_id = issuerSetup.credDefId,
//          credential_values = Map("first-name" -> "Hi", "last-name" -> "there", "age" -> "30"),
//          auto_issue = Option(true)
//        )
//        issuerRestSDK.sendMsgForConn(connId, msg)
//        issuerRestSDK.expectMsgOnWebhook[Sent]()
//
//        val expectedOfferMsg = expectMsg[Any](userName, connId, Option("credential-offer"))
//        sendCredReq(userName, connId, issuerSetup.sourceId, expectedOfferMsg.msgStr)
//        checkReceivedCred(userName, connId, issuerSetup.sourceId)
//      }
//    }
//  }
//
//  private def testPresentProofPostMigration(forPreMigrationConn: Boolean): Unit = {
//    setupData.entAgents.foreach { case (entName, entAgentState) =>
//      entAgentState.getTargetConns(forPreMigrationConn).foreach { case (userName, userAgentState) =>
//        val issuerRestSDK = entAgentState.issuerRestSDK
//        val issuerSetup = entAgentState.issuerSetup
//        val connId = userAgentState.connId
//
//        val msg = Request(
//          name = "test",
//          Option(List(
//            ProofAttribute(
//              None,
//              Option(List("first-name", "last-name", "age")),
//              None,
//              None,
//              self_attest_allowed = false)
//          )),
//          None,
//          None
//        )
//        issuerRestSDK.sendMsgForConn(connId, msg)
//
//        val expectedProofReq = expectMsg[Any](userName, connId, Option("presentation-request"))
//        sendProof(userName, connId, issuerSetup.sourceId, expectedProofReq.msgStr)
//        checkProofAccepted(userName, connId, issuerSetup.sourceId)
//
//        issuerRestSDK.expectMsgOnWebhook[PresentationResult]()
//      }
//    }
//  }
//
//  lazy val CONN_ID_PREFIX = "oldConn"
//
//  lazy val ecp: ExecutionContextProvider = TestExecutionContextProvider.ecp
//  lazy val executionContext: ExecutionContext = ecp.futureExecutionContext
//  override def futureExecutionContext: ExecutionContext = executionContext
//  override def executionContextProvider: ExecutionContextProvider = ecp
//
//  override def appConfig: AppConfig = {
//    val conf = ConfigFactory.load()
//    new TestAppConfig(Option(conf))
//  }
//
//  val COMMON_VERITY_CONFIG = ConfigFactory.parseString(
//    """
//      |verity.http.host = 0.0.0.0
//      |verity.rest-api.enabled = true
//      |verity.metrics.enabled = N
//      |cinnamon.chmetrics.reporters = "none"
//      |
//      |verity.lib-vdrtools.wallet.type = "mysql"
//      |verity {
//      |
//      |  wallet-storage {
//      |
//      |    read-host-ip = "localhost"
//      |    read-host-ip = ${?MYSQL_HOST}
//      |
//      |    write-host-ip = "localhost"
//      |    write-host-ip = ${?MYSQL_HOST}
//      |
//      |    host-port = 3306
//      |    host-port = ${?WALLET_STORAGE_HOST_PORT}
//      |
//      |    credentials-username = "msuser"
//      |    credentials-username = ${?WALLET_STORAGE_CREDENTIAL_USERNAME}
//      |
//      |    credentials-password = "mspassword"
//      |    credentials-password = ${?WALLET_STORAGE_CREDENTIAL_PASSWORD}
//      |
//      |    db-name = "wallet"
//      |    db-name = ${?WALLET_STORAGE_DB_NAME}
//      |  }
//      |}
//      |
//      |""".stripMargin
//  ).resolve()
//
//  override val genesisTxnFilePath: String = {
//    val resp = HttpUtil.sendGET("http://localhost:5679/genesis.txt")(futureExecutionContext)
//    val genesisContent = HttpUtil.parseHttpResponseAsString(resp)(futureExecutionContext)
//    val path = Files.createTempDirectory("").resolve("genesis.txn")
//    Files.write(path, genesisContent.getBytes)
//    path.toString
//  }
//
//  val logger: Logger = getLoggerByClass(getClass)
//}
//
//case class SetupData(totalEntAgents: Int,
//                     preMigrationConnsPerEntAgent: Int,
//                     postMigrationConnsPerEntAgent: Int) {
//
//  type EntName = String
//  var entAgents = Map.empty[EntName, EntAgentState]
//
//  def addEntAgentState(entName: EntName, issuerSetup: IssuerSetup): Unit = {
//    entAgents = entAgents ++ Map(entName -> EntAgentState(issuerSetup))
//  }
//}
//
//case class EntAgentState(issuerSetup: IssuerSetup) {
//  type UserName = String
//  var preMigrationConns: Map[UserName, ConnState] = Map.empty
//  var postMigrationConns: Map[UserName, ConnState] = Map.empty
//
//  var issuerRestSDK: IssuerRestSDK = null
//
//  def addPreMigrationConnState(userName: UserName, connId: String, invitation: String): Unit = {
//    preMigrationConns = preMigrationConns ++ Map(userName-> ConnState(connId, invitation))
//  }
//
//  def addPostMigrationConnState(userName: UserName, connId: String, invitation: String): Unit = {
//    preMigrationConns = preMigrationConns ++ Map(userName-> ConnState(connId, invitation))
//  }
//
//  def setRestSdk(sdk: IssuerRestSDK): Unit = {
//    issuerRestSDK = sdk
//  }
//
//  def getTargetConns(forPreMigrationConn: Boolean): Map[UserName, ConnState] = {
//    if (forPreMigrationConn) preMigrationConns
//    else postMigrationConns
//  }
//}
//
//case class ConnState(connId: String, invitation: String = "") {
//  def abbreviatedInvitation: InviteDetailAbbreviated = DefaultMsgCodec.fromJson[InviteDetailAbbreviated](invitation)
//}
//
//case class MigrationParam(validate: String,
//                          mode: String,
//                          casEndpoint: String,
//                          easEndpoint: String,
//                          vasEndpoint: String,
//                          candidates: List[Candidate])
//case class Candidate(verity1Params: Verity1Params, verity2Params: Verity2Params)
//case class Verity1Params(easAgentDID: String, sqliteWallet: SqliteWallet)
//case class SqliteWallet(name: String, key: String)
//case class Verity2Params(vasDomainDID: String, vasDomainDIDVerKey: String)