package com.evernym.integrationtests.e2e.flow

import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes.{MovedPermanently, OK}
import akka.http.scaladsl.model.{HttpEntity, HttpMethods, HttpRequest, Uri}
import com.evernym.integrationtests.e2e.TestConstants
import com.evernym.integrationtests.e2e.msg.JSONObjectUtil.threadId
import com.evernym.integrationtests.e2e.scenario.{ApplicationAdminExt, Scenario}
import com.evernym.integrationtests.e2e.sdk.vcx.{VcxBasicMessage, VcxIssueCredential, VcxPresentProof, VcxSdkProvider}
import com.evernym.integrationtests.e2e.sdk.{ListeningSdkProvider, MsgReceiver, RelData, VeritySdkProvider}
import com.evernym.integrationtests.e2e.util.ProvisionTokenUtil.genTokenOpt
import com.evernym.sdk.vcx.VcxException
import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreLog
import com.evernym.verity.did.didcomm.v1.messages.MsgFamily
import com.evernym.verity.did.didcomm.v1.messages.MsgFamily.msgTypeFromTypeStr
import com.evernym.verity.did.{DidStr, VerKeyStr}
import com.evernym.verity.fixture.TempDir
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByName
import com.evernym.verity.observability.metrics.CustomMetrics.AS_NEW_PROTOCOL_COUNT
import com.evernym.verity.protocol.engine.Constants.`@TYPE`
import com.evernym.verity.protocol.protocols.basicMessage.v_1_0.BasicMessageMsgFamily
import com.evernym.verity.protocol.protocols.committedAnswer.v_1_0.CommittedAnswerMsgFamily
import com.evernym.verity.protocol.protocols.connections.v_1_0.ConnectionsMsgFamily
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.IssueCredMsgFamily
import com.evernym.verity.protocol.protocols.outofband.v_1_0.OutOfBandMsgFamily
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.PresentProofMsgFamily
import com.evernym.verity.protocol.protocols.relationship.v_1_0.RelationshipMsgFamily
import com.evernym.verity.protocol.protocols.writeCredentialDefinition.v_0_6.CredDefMsgFamily
import com.evernym.verity.protocol.protocols.writeSchema.v_0_6.WriteSchemaMsgFamily
import com.evernym.verity.sdk.protocols.connecting.v1_0.ConnectionsV1_0
import com.evernym.verity.sdk.protocols.issuecredential.v1_0.IssueCredentialV1_0
import com.evernym.verity.sdk.protocols.presentproof.common.RestrictionBuilder
import com.evernym.verity.sdk.protocols.presentproof.v1_0.PresentProofV1_0
import com.evernym.verity.sdk.protocols.questionanswer.v1_0.CommittedAnswerV1_0
import com.evernym.verity.sdk.protocols.relationship.v1_0.{GoalCode, RelationshipV1_0}
import com.evernym.verity.sdk.protocols.writecreddef.v0_6.{RevocationRegistryConfig, WriteCredentialDefinitionV0_6}
import com.evernym.verity.sdk.protocols.writeschema.v0_6.WriteSchemaV0_6
import com.evernym.verity.sdk.utils.ContextBuilder
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.testkit.util.LedgerUtil
import com.evernym.verity.util.{Base58Util, Base64Util, OptionUtil}
import com.typesafe.scalalogging.Logger
import org.json.JSONObject
import org.scalatest.concurrent.Eventually
import org.scalatest.concurrent.PatienceConfiguration.{Interval, Timeout}
import org.scalatest.time.{Millis, Seconds, Span}

import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable
import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}
import scala.concurrent.{Await, ExecutionException}
import scala.language.postfixOps
import scala.util.Try

trait InteractiveSdkFlow extends MetricsFlow {
  this: BasicSpec with TempDir with Eventually =>

  val logger: Logger = getLoggerByName(getClass.getName)

  import InteractiveSdkFlow._

  var iterationCountMap: mutable.Map[String, Int] = mutable.Map()

  def availableSdk(app: ApplicationAdminExt)(implicit scenario: Scenario): Unit = {
    app.sdks.foreach { sdk =>
      s"check sdk [${sdk.sdkConfig.name}] for [${app.instance.name}]" - {
        s"[${app.instance.name}] should have an available sdk" in {
          sdk.available()
        }
      }
    }
  }

  def provisionAgent(aae: ApplicationAdminExt)(implicit scenario: Scenario): Unit = {
    aae.sdks.foreach { sdk =>
      provisionAgent(aae.name, sdk, aae.urlParam.url)
    }
  }

  def prepareSdkContextWithoutProvisioning(adminSdk: VeritySdkProvider, sdk: VeritySdkProvider): Unit = {
    val context = ContextBuilder.fromScratch(
      sdk.walletConfig(this.suiteTempDir.resolve("wallets").toString),
      adminSdk.context.verityUrl(),
      adminSdk.context.domainDID(),
      adminSdk.context.verityAgentVerKey()
    )
    sdk.updateContext(context)
  }

  def provisionAgent(name: String, sdk: VeritySdkProvider, verityUrl: String)(implicit scenario: Scenario): Unit = {
    s"provision agent for $name" - {
      s"[$name] provisioning agent to application for sdk [${sdk.sdkConfig.name}]"  taggedAs UNSAFE_IgnoreLog in {
        val token = genTokenOpt(
          sdk.sdkConfig.verityInstance.sponsorSeed
        )
        val walletConfig = sdk.walletConfig(this.suiteTempDir.resolve("wallets").toString)
        val config = sdk.sdkConfig
        // FIXME Move this to sdk
        val context = OptionUtil.allOrNone(config.domainDid, config.agentVerkey, config.keySeed) match {
          case None =>
            val ctx = ContextBuilder.fromScratch(
              walletConfig,
              verityUrl,
              sdk.sdkConfig.keySeed.orNull
            )
            val proto = token.map(sdk.provision_0_7).getOrElse(sdk.provision_0_7)
            proto.provision(ctx)
          case Some((domainDID, verkey, seed)) =>
            ContextBuilder.fromScratch(
              walletConfig,
              verityUrl,
              domainDID,
              verkey,
              seed
            )
        }
        sdk.updateContext(context)
        // TODO how to test that provisioning is complete and/or successful?
      }

      sdk match {
        case s: ListeningSdkProvider =>
          s"[$name] start listening web-hook endpoint for sdk [${sdk.sdkConfig.name}]" in {
            s.startListener()
          }
          s"[$name] update sdk web-hook endpoint for sdk [${sdk.sdkConfig.name}]" in {
            val newContext = s.context
              .toContextBuilder
              .endpointUrl(s.endpointUrl)
              .build()

            s.updateContext(newContext)

            s.updateEndpoint_0_6.update(s.context)
          }
        case _ => // Non-Listening Providers do nothing here
      }
    }
  }

  def cleanupSdk(app: ApplicationAdminExt)(implicit scenario: Scenario): Unit = {
    app.sdk.foreach { sdk =>
      s"[${app.name}] cleaning SDK" taggedAs UNSAFE_IgnoreLog in {
        sdk match {
          case s: ListeningSdkProvider =>
            s.stopListener
          case _ => // Non-Listening Providers do nothing here
        }
        sdk.clean()
      }
    }
  }

  def setupIssuer(issuerSdk: VeritySdkProvider,
                  msgReceiverSdkProvider: VeritySdkProvider,
                  ledgerUtil: LedgerUtil,
                  endorser: Option[String]
                 )(implicit scenario: Scenario): Unit = {
    val issuerName = issuerSdk.sdkConfig.name
    s"create issuer public identifier on $issuerName" - {

      val receiverSdk = receivingSdk(Option(msgReceiverSdkProvider))

      s"[$issuerName] use issuer-setup protocol" in {

        issuerSdk.issuerSetup_0_7.currentPublicIdentifier(issuerSdk.context)

        receiverSdk.checkMsg(){ resp =>
          if(resp.getString(`@TYPE`).contains("problem-report")) {
            issuerSdk.issuerSetup_0_7
              .create(issuerSdk.context, "did:sov", "someDID")

            receiverSdk.expectMsg("public-identifier-created") { resp =>
              resp shouldBe an[JSONObject]

              assert(resp.getJSONObject("identifier").has("verKey"))
              assert(resp.getJSONObject("identifier").has("did"))
              assert(resp.getJSONObject("status").has("needsEndorsement"))
            }
          }
          else if (resp.getString(`@TYPE`).contains("public-identifier")) {
            logger.info("Issuer is already setup")
          }
          else {
            throw new Exception("Unexpected message type")
          }
        }
      }
    }
  }

  def writeIssuerToLedger(sdk: VeritySdkProvider, ledgerUtil: LedgerUtil)(implicit scenario: Scenario): Unit = {
    writeIssuerToLedger(sdk, sdk, ledgerUtil)
  }

  def writeIssuerToLedger(issuerSdk: VeritySdkProvider,
                          msgReceiverSdkProvider: VeritySdkProvider,
                          ledgerUtil: LedgerUtil)(implicit scenario: Scenario): Unit = {
    val issuerName = issuerSdk.sdkConfig.name
    val receiverSdk = receivingSdk(Option(msgReceiverSdkProvider))

    s"[$issuerName] write issuer DID to ledger" taggedAs UNSAFE_IgnoreLog in {
      val (issuerDID, issuerVerkey): (DidStr, VerKeyStr) = currentIssuerId(issuerSdk, receiverSdk)
      issuerSdk.publicDID = Some(issuerDID)

      ledgerUtil.bootstrapNewDID(issuerDID, issuerVerkey, "ENDORSER")
      eventually(Timeout(scenario.timeout), Interval(Duration("20 seconds"))) {
        ledgerUtil.checkDidOnLedger(issuerDID, issuerVerkey)
      }
    }
  }

  def updateConfigs(sdk: VeritySdkProvider,
                    ledgerUtil: LedgerUtil,
                    name: String,
                    logoUrl: String)(implicit scenario: Scenario): Unit = {
    updateConfigs(sdk, sdk, ledgerUtil, name, logoUrl)
  }

  def updateConfigs(issuerSdk: VeritySdkProvider,
                    msgReceiverSdkProvider: VeritySdkProvider,
                    ledgerUtil: LedgerUtil,
                    name: String,
                    logoUrl: String)(implicit scenario: Scenario): Unit = {
    val issuerName = issuerSdk.sdkConfig.name
    s"update config on $issuerName" - {

      val msgReceiverSdk = receivingSdk(Option(msgReceiverSdkProvider))

      s"[$issuerName] use update-config protocol" taggedAs UNSAFE_IgnoreLog in {
        val config = issuerSdk.updateConfigs_0_6(name, logoUrl)
        config.update(issuerSdk.context)
        msgReceiverSdk.expectMsg("status-report") {resp =>
          checkUpdateConfigsResponse(resp, name, logoUrl)
        }

      }

      s"[$issuerName] get-status of configs" in {
        val config = issuerSdk.updateConfigs_0_6()
        config.status(issuerSdk.context)
        msgReceiverSdk.expectMsg("status-report") {resp =>
          checkUpdateConfigsResponse(resp, name, logoUrl)
        }

      }
    }
  }

  def checkUpdateConfigsResponse(resp: JSONObject, name: String, logoUrl: String): Unit = {
    resp shouldBe an [JSONObject]
    val result = Set(0, 1).map( i =>
      resp.getJSONArray("configs").getJSONObject(i).get("value"))
    assert(result.contains(name))
    assert(result.contains(logoUrl))
  }

  def checkShortInviteUrl(shortInviteUrl: String, inviteUrl: String)(implicit scenario: Scenario): Unit = {
    val fut = Http()(scenario.actorSystem).singleRequest(
      HttpRequest(
        method = HttpMethods.GET,
        uri = shortInviteUrl,
      ))
    val response = Await.result(fut, 10.seconds)
    response.status match {
      case MovedPermanently =>
        response.getHeader("Location").get.value shouldBe inviteUrl
      case OK =>
        val respString = response.entity.asInstanceOf[HttpEntity.Strict].getData().utf8String
        new JSONObject(respString) // checking that we go valid JSON
      case s => fail(s"Un-expected response status: $s")
    }
  }

  def expectSignal(fromReceiver: VeritySdkProvider with MsgReceiver,
                   msgFamily: MsgFamily,
                   expectedSignalMsgName: String,
                   maxDuration: Option[Duration] = None)
                  (check: JSONObject => Unit = {_ => })
                  (implicit scenario: Scenario): Unit = {
    logger.info(s"Expect message: $msgFamily $expectedSignalMsgName")
    fromReceiver.expectMsg(expectedSignalMsgName, maxDuration) { resp =>
      resp shouldBe an[JSONObject]

      // check msg type
      val msgType = msgTypeFromTypeStr(resp.getString("@type"))
      msgType.familyQualifier shouldBe msgFamily.qualifier
      msgType.familyName shouldBe msgFamily.name
      msgType.familyVersion shouldBe msgFamily.version
      msgType.msgName shouldBe expectedSignalMsgName

      check(resp)
    }
  }

  def writeSchema(sdk: VeritySdkProvider,
                  ledgerUtil: LedgerUtil,
                  schemaName: String,
                  schemaVersion: String,
                  schemaAttrs: String*)
                 (implicit scenario: Scenario): Unit = {
    writeSchema(sdk, sdk, ledgerUtil, schemaName, schemaVersion, schemaAttrs: _*)
  }

  def writeSchema(issuerSdk: VeritySdkProvider,
                  msgReceiverSdkProvider: VeritySdkProvider,
                  ledgerUtil: LedgerUtil,
                  schemaName: String,
                  schemaVersion: String,
                  schemaAttrs: String*)
                 (implicit scenario: Scenario): Unit = {
    val issuerName = issuerSdk.sdkConfig.name
    s"write schema $schemaName for $issuerName" - {

      val msgReceiverSdk = receivingSdk(Option(msgReceiverSdkProvider))
      var writeSchema: WriteSchemaV0_6 = null

      s"[$issuerName] use write-schema protocol" in {
        writeSchema = issuerSdk.writeSchema_0_6(schemaName, schemaVersion, schemaAttrs.toIndexedSeq: _*)
        writeSchema.write(issuerSdk.context)

        var schemaId = ""
        expectSignal(msgReceiverSdk, WriteSchemaMsgFamily, "status-report") { resp =>
          threadId(resp) shouldBe writeSchema.getThreadId
          schemaId = resp.getString("schemaId")
        }

        issuerSdk.updateData(s"$schemaName-$schemaVersion-id", schemaId)
      }

      s"[$issuerName] reuse thread should fail" in {
        // if tried to reuse this thread a problem-report is received.
        writeSchema.write(issuerSdk.context)

        expectSignal(msgReceiverSdk, WriteSchemaMsgFamily, "problem-report") { resp =>
          threadId(resp) shouldBe writeSchema.getThreadId
          resp.getString("message") shouldBe "Unexpected message in current state"
        }
      }

      s"[$issuerName] check schema is on ledger" in {
        val (issuerDID, _): (DidStr, VerKeyStr) = currentIssuerId(issuerSdk, msgReceiverSdk)
        eventually(timeout(Span(5, Seconds)), interval(Span(100, Millis))) {
          ledgerUtil.checkSchemaOnLedger(issuerDID, schemaName, schemaVersion)
        }
      }
    }
  }

  def writeFailingSchema(issuerSdk: VeritySdkProvider,
                  msgReceiverSdkProvider: VeritySdkProvider,
                  ledgerUtil: LedgerUtil,
                  schemaName: String,
                  schemaVersion: String,
                  expectedErrorMessage: String,
                  schemaAttrs: String*)
                 (implicit scenario: Scenario): Unit = {
    val issuerName = issuerSdk.sdkConfig.name
    s"write schema $schemaName on $issuerName" - {

      val msgReceiverSdk = receivingSdk(Option(msgReceiverSdkProvider))

      s"[$issuerName] use write-schema protocol" in {
        val writeSchema = issuerSdk.writeSchema_0_6(schemaName, schemaVersion, schemaAttrs.toIndexedSeq: _*)
        writeSchema.write(issuerSdk.context)

        expectSignal(msgReceiverSdk, WriteSchemaMsgFamily, "problem-report") { resp =>
          threadId(resp) shouldBe writeSchema.getThreadId
          resp.getString("message") should include (expectedErrorMessage)
        }
      }
    }
  }

  def writeSchemaNeedsEndorsement(sdk: VeritySdkProvider,
                                  ledgerUtil: LedgerUtil,
                                  schemaName: String,
                                  schemaVersion: String,
                                  schemaAttrs: String*)
                                 (implicit scenario: Scenario): Unit = {
    writeSchemaNeedsEndorsement(sdk, sdk, ledgerUtil, schemaName, schemaVersion, schemaAttrs: _*)
  }

  def writeSchemaNeedsEndorsement(issuerSdk: VeritySdkProvider,
                                  msgReceiverSdkProvider: VeritySdkProvider,
                                  ledgerUtil: LedgerUtil,
                                  schemaName: String,
                                  schemaVersion: String,
                                  schemaAttrs: String*)
                                 (implicit scenario: Scenario): Unit = {
    val issuerName = issuerSdk.sdkConfig.name
    "write schema using unknown endorser DID" - {

      val msgReceiverSdk = receivingSdk(Option(msgReceiverSdkProvider))

      s"[$issuerName] use write-schema with unknown endoerser DID to recieve needs-endorsement" in {
        val receiverSdk = receivingSdk(Option(msgReceiverSdkProvider))
        val (issuerDID, issuerVerkey): (DidStr, VerKeyStr) = currentIssuerId(issuerSdk, receiverSdk)


        val endorserDID = "8hx9VZn2oCTHbGX7UYMYBy"   //Unknown DID
        val writeSchema = issuerSdk.writeSchema_0_6(schemaName, schemaVersion, schemaAttrs.toIndexedSeq: _*)
        writeSchema.write(issuerSdk.context, endorserDID)

        expectSignal(msgReceiverSdk, WriteSchemaMsgFamily, "needs-endorsement") { resp =>
          threadId(resp) shouldBe writeSchema.getThreadId
          resp.getString("schemaJson").contains(endorserDID) shouldBe true
        }
      }
    }
  }

  def writeCredDefNeedsEndorsement(sdk: VeritySdkProvider,
                                   schemaName: String,
                                   schemaVersion: String,
                                   credDefName: String,
                                   credTag: String,
                                   revocation: RevocationRegistryConfig,
                                   ledgerUtil: LedgerUtil
                                  )
                                  (implicit scenario: Scenario): Unit = {
    writeCredDefNeedsEndorsement(sdk, sdk, schemaName, schemaVersion, credDefName, credTag, revocation, ledgerUtil)
  }

  def writeCredDefNeedsEndorsement(issuerSdk: VeritySdkProvider,
                                   msgReceiverSdkProvider: VeritySdkProvider,
                                   schemaName: String,
                                   schemaVersion: String,
                                   credDefName: String,
                                   credTag: String,
                                   revocation: RevocationRegistryConfig,
                                   ledgerUtil: LedgerUtil)
                                  (implicit scenario: Scenario): Unit = {
    val issuerName = issuerSdk.sdkConfig.name

    "write cred-def using unknown endorser DID" - {

      val msgReceiverSdk = receivingSdk(Option(msgReceiverSdkProvider))

      s"[$issuerName] use write-cred-def protocol before issuer DID is on ledger" in {
        val receiverSdk = receivingSdk(Option(msgReceiverSdkProvider))
        val (issuerDID, issuerVerkey): (DidStr, VerKeyStr) = currentIssuerId(issuerSdk, receiverSdk)


        val endorserDID = "8hx9VZn2oCTHbGX7UYMYBy"    //Unknown DID
        val schemaId = issuerSdk.data_!(s"$schemaName-$schemaVersion-id")
        val writeCredDef = issuerSdk.writeCredDef_0_6(credDefName, schemaId, Some(credTag), Some(revocation))
        writeCredDef.write(issuerSdk.context, endorserDID)

        expectSignal(msgReceiverSdk, CredDefMsgFamily, "needs-endorsement") { resp =>
          threadId(resp) shouldBe writeCredDef.getThreadId
          resp.getString("credDefJson").contains(endorserDID) shouldBe true
        }
      }
    }
  }


  // credDef protocols need a long timeout because creating the issuer keys can take a long time to
  // generate because it has to find two safe prime numbers randomly.
  val credDefTimeout: FiniteDuration = FiniteDuration(120, TimeUnit.SECONDS)

  def writeCredDef(sdk: VeritySdkProvider,
                   credDefName: String,
                   credTag: String,
                   revocation: RevocationRegistryConfig,
                   schemaName: String,
                   schemaVersion: String,
                   ledgerUtil: LedgerUtil
                  )
                  (implicit scenario: Scenario): Unit = {
    writeCredDef(sdk, sdk, credDefName, credTag, revocation, schemaName, schemaVersion, ledgerUtil)
  }

  def writeCredDef(issuerSdk: VeritySdkProvider,
                   msgReceiverSdkProvider: VeritySdkProvider,
                   credDefName: String,
                   credTag: String,
                   revocation: RevocationRegistryConfig,
                   schemaName: String,
                   schemaVersion: String,
                   ledgerUtil: LedgerUtil
                  )
                  (implicit scenario: Scenario): Unit = {

    val issuerName = issuerSdk.sdkConfig.name

    val msgReceiverSdk = receivingSdk(Option(msgReceiverSdkProvider))
    var writeCredDef: WriteCredentialDefinitionV0_6 = null

      s"write credential def ($credDefName) for $issuerName" - {
      s"[$issuerName] use write-cred-def protocol" taggedAs UNSAFE_IgnoreLog in {
        val schemaId = issuerSdk.data_!(s"$schemaName-$schemaVersion-id")
        writeCredDef = issuerSdk.writeCredDef_0_6(credDefName, schemaId, Some(credTag), Some(revocation))
        writeCredDef.write(issuerSdk.context)

        var credDefId = ""
        expectSignal(msgReceiverSdk, CredDefMsgFamily, "status-report", Some(credDefTimeout.max(scenario.timeout))) { resp =>
          threadId(resp) shouldBe writeCredDef.getThreadId
          credDefId = resp.getString("credDefId")
        }

        issuerSdk.updateData(credDefIdKey(credDefName, credTag), credDefId)
      }

      s"[$issuerName] reuse thread should fail" in {
        // if tried to reuse this thread a problem-report is received.
        writeCredDef.write(issuerSdk.context)

        expectSignal(msgReceiverSdk, CredDefMsgFamily, "problem-report") { resp =>
          threadId(resp) shouldBe writeCredDef.getThreadId
          resp.getString("message") shouldBe "Unexpected message in current state"
        }
      }

      s"[$issuerName] check cred def is on ledger" in {
        val credDefId = issuerSdk.data_!(credDefIdKey(credDefName, credTag))
        ledgerUtil.checkCredDefOnLedger(credDefId)
      }

      s"[$issuerName] try to create duplicate entry" ignore {
        fail()
        //        val revocationDetails = buildRevocationDetails(supportRevocation = false)
        //        val credDef = new WriteCredentialDefinition("cred_name1", schemaId, "tag", revocationDetails)
        //        credDef.write(veritySdkContext)
        //        val resp:JSONObject = expectMsg(credDefTimeout.max(scenario.timeout))
        //        resp shouldBe an [JSONObject]
        //        val errorMessage = resp.get("message").asInstanceOf[String]
        //        logger.error(s"response error message: $errorMessage")
      }
    }
  }

  def connect_1_0(inviter: ApplicationAdminExt,
                  invitee: ApplicationAdminExt,
                  connectionId: String,
                  label: String)
                 (implicit scenario: Scenario): Unit = {
    connect_1_0(inviter.name, receivingSdk(inviter), receivingSdk(inviter),
      invitee.name, receivingSdk(invitee), connectionId, label)
  }

  def connect_1_0(inviterName: String,
                  inviterSdk: VeritySdkProvider,
                  inviterMsgReceiverSdkProvider: VeritySdkProvider,
                  inviteeName: String,
                  inviteeSdk: VeritySdkProvider,
                  connectionId: String,
                  label: String)
                 (implicit scenario: Scenario): Unit = {
    var invite: JSONObject = null
    var createdRespMsg: JSONObject = null
    var inviteUrl: String = null
    var inviterTId: String = null
    var connReq: JSONObject = null
    var inviteeConnection: ConnectionsV1_0 = null
    var relProvisioning: RelationshipV1_0 = null

    val inviterMsgReceiverSdk = receivingSdk(Option(inviterMsgReceiverSdkProvider))
    val inviteeMsgReceiverSdk = receivingSdk(Option(inviteeSdk))

    s"connect (1.0) $inviterName and $inviteeName (connectionId: $connectionId)" - {
      s"[$inviterName] start relationship protocol" - {
        "send create" taggedAs UNSAFE_IgnoreLog in {
          relProvisioning = inviterSdk.relationship_1_0("inviter")
          relProvisioning.create(inviterSdk.context)
          expectSignal(inviterMsgReceiverSdk, RelationshipMsgFamily, "created") { msg =>
            createdRespMsg = msg
            logger.info("created response: " + msg)
            inviterTId = threadId(msg)
            inviterTId shouldBe relProvisioning.getThreadId
          }
        }

        s"[$inviterName] prepares invitation" in {
          val forRel = createdRespMsg.getString("did")
          val relationship = inviterSdk.relationship_1_0(forRel, inviterTId)
          relationship.connectionInvitation(inviterSdk.context, true)
          expectSignal(inviterMsgReceiverSdk, RelationshipMsgFamily, "invitation") { msg =>
            invite = msg
            logger.info("prepare invite response: " + msg)
            inviteUrl = msg.getString("inviteURL")

            // check shortInviteURL.
            val shortInviteUrl = msg.getString("shortInviteURL")
            checkShortInviteUrl(shortInviteUrl, inviteUrl)

            threadId(msg) shouldBe inviterTId
          }
        }
      }

      s"[$inviteeName] accept invite" taggedAs UNSAFE_IgnoreLog in {
        inviteeConnection = inviteeSdk.connecting_1_0(connectionId, label, inviteUrl)
        inviteeConnection.accept(inviteeSdk.context)
      }

      s"[$inviterName] receive a signal about 'request-received' msg" in {
        expectSignal(inviterMsgReceiverSdk, ConnectionsMsgFamily, "request-received") { msg =>
          connReq = msg
          logger.info("connReq: " + connReq)
        }
      }

      s"[$inviterName] receive a signal about 'response-sent' msg" in {
        expectSignal(inviterMsgReceiverSdk, ConnectionsMsgFamily, "response-sent") { connResp =>
          logger.info("connResp: " + connResp)
          val sigData = connResp.getJSONObject("resp").getJSONObject("connection~sig").getString("sig_data")
          val connJson = new JSONObject(new String(Base64Util.getBase64UrlDecoded(sigData).drop(8), StandardCharsets.UTF_8))
          val theirDID = connReq.getJSONObject("conn").getString("DID")
          val relData = RelData(connectionId, connJson.getString("DID"), Option(theirDID))
          inviterSdk.updateRelationship(connectionId, relData)
        }
      }

      s"[$inviteeName] receives 'response' message " taggedAs UNSAFE_IgnoreLog in {
        expectSignal(inviteeMsgReceiverSdk, ConnectionsMsgFamily, "response") { response =>
          val sigData = response.getJSONObject("connection~sig").getString("sig_data")
          val connJson = new JSONObject(new String(Base64Util.getBase64UrlDecoded(sigData).drop(8), StandardCharsets.UTF_8))
          val myDID = connReq.getJSONObject("conn").getString("DID")
          val relData = RelData(connectionId, myDID, Option(connJson.getString("DID")))
          inviteeConnection.status(inviteeSdk.context)
          inviteeSdk.updateRelationship(connectionId, relData)
        }
      }

      s"[$inviterName] reuse thread should fail" in {
        // if tried to reuse this thread a problem-report is received.
        relProvisioning.create(inviterSdk.context)

        expectSignal(inviterMsgReceiverSdk, RelationshipMsgFamily, "problem-report") { resp =>
          threadId(resp) shouldBe relProvisioning.getThreadId
          resp.getJSONObject("description").getString("code") shouldBe "unexpected-message"
        }
      }

    }
  }

  def out_of_band_with_connect_1_0(inviter: ApplicationAdminExt,
                                   invitee: ApplicationAdminExt,
                                   connectionId: String,
                                   label: String,
                                   goal: GoalCode)
                                  (implicit scenario: Scenario): Unit = {
    out_of_band_with_connect_1_0(inviter.name, receivingSdk(inviter), receivingSdk(inviter),
      invitee.name, receivingSdk(invitee), connectionId, label, goal)
  }

  def out_of_band_with_connect_1_0(inviterName: String,
                                   inviterSdk: VeritySdkProvider,
                                   inviterMsgReceiverSdkProvider: VeritySdkProvider,
                                   inviteeName: String,
                                   inviteeSdk: VeritySdkProvider,
                                   connectionId: String,
                                   label: String,
                                   goal: GoalCode)
                                  (implicit scenario: Scenario): Unit = {
    var invite: JSONObject = null
    var createdRespMsg: JSONObject = null
    var inviteUrl: String = null
    var inviterTId: String = null
    var connReq: JSONObject = null
    var inviteeConnection: ConnectionsV1_0 = null
    var relProvisioning: RelationshipV1_0 = null

    val inviterMsgReceiverSdk = receivingSdk(Option(inviterMsgReceiverSdkProvider))
    val inviteeMsgReceiverSdk = receivingSdk(Option(inviteeSdk))

    s"connect with OutOfBand invitation (1.0) $inviterName and $inviteeName (connectionId: $connectionId)" - {
      s"[$inviterName] start relationship protocol for OutOfBand" - {
        "send create" in {
          relProvisioning = inviterSdk.relationship_1_0("inviter")
          relProvisioning.create(inviterSdk.context)
          expectSignal(inviterMsgReceiverSdk, RelationshipMsgFamily, "created") { msg =>
            createdRespMsg = msg
            logger.info("created response: " + msg)
            inviterTId = threadId(msg)
            inviterTId shouldBe relProvisioning.getThreadId
          }
        }

        s"prepares invitation" in {
          val forRel = createdRespMsg.getString("did")
          val relationship = inviterSdk.relationship_1_0(forRel, inviterTId)
          relationship.outOfBandInvitation(inviterSdk.context, true, goal)
          expectSignal(inviterMsgReceiverSdk, RelationshipMsgFamily, "invitation") { msg =>
            invite = msg
            logger.info("prepare invite response: " + msg)
            inviteUrl = msg.getString("inviteURL")

            // check shortInviteURL.
            val shortInviteUrl = msg.getString("shortInviteURL")

            checkShortInviteUrl(shortInviteUrl, inviteUrl)
            threadId(msg) shouldBe inviterTId

            val inviteMsg = new JSONObject(
              Base64Util.urlDecodeToStr(
                Uri(inviteUrl)
                  .query()
                  .getOrElse(
                    "oob",
                    fail("must have oob query parameter")
                  )
              )
            )

            inviteMsg.getString("goal_code") shouldBe goal.code()
            inviteMsg.getString("goal") shouldBe goal.goalName()
          }
        }
      }

      s"[$inviteeName] accept invite" taggedAs UNSAFE_IgnoreLog in {
        inviteeConnection = inviteeSdk.connectingWithOutOfBand_1_0(connectionId, label, inviteUrl)
        inviteeConnection.accept(inviteeSdk.context)
      }

      s"[$inviterName] receive a signal about 'request-received' msg" in {
        expectSignal(inviterMsgReceiverSdk, ConnectionsMsgFamily, "request-received") { msg =>
          connReq = msg
          logger.info("connReq: " + connReq)
        }
      }

      s"[$inviterName] receive a signal about 'response-sent' msg" in {
        expectSignal(inviterMsgReceiverSdk, ConnectionsMsgFamily, "response-sent") { connResp =>
          logger.info("connResp: " + connResp)
          val sigData = connResp.getJSONObject("resp").getJSONObject("connection~sig").getString("sig_data")
          val connJson = new JSONObject(new String(Base64Util.getBase64UrlDecoded(sigData).drop(8), StandardCharsets.UTF_8))
          val theirDID = connReq.getJSONObject("conn").getString("DID")
          val relData = RelData(connectionId, connJson.getString("DID"), Option(theirDID))
          inviterSdk.updateRelationship(connectionId, relData)
        }
      }

      s"[$inviteeName] receives 'response' message" taggedAs UNSAFE_IgnoreLog in {
        expectSignal(inviteeMsgReceiverSdk, ConnectionsMsgFamily, "response") { response =>
          val sigData = response.getJSONObject("connection~sig").getString("sig_data")
          val connJson = new JSONObject(new String(Base64Util.getBase64UrlDecoded(sigData).drop(8), StandardCharsets.UTF_8))
          val myDID = connReq.getJSONObject("conn").getString("DID")
          val relData = RelData(connectionId, myDID, Option(connJson.getString("DID")))
          inviteeConnection.status(inviteeSdk.context)
          inviteeSdk.updateRelationship(connectionId, relData)
        }
      }

      s"[$inviteeName] send handshake-reuse message" in {
        inviteeSdk.outOfBand_1_0(inviterTId, inviteUrl).handshakeReuse(inviteeSdk.context)
        expectSignal(inviterMsgReceiverSdk, OutOfBandMsgFamily, "relationship-reused") { connectionReusedMsg =>
          assert(connectionReusedMsg.getJSONObject("~thread").getString("pthid") != null)
        }
      }

      s"[$inviterName] reuse thread should fail" in {
        // if tried to reuse this thread a problem-report is received.
        relProvisioning.create(inviterSdk.context)

        expectSignal(inviterMsgReceiverSdk, RelationshipMsgFamily, "problem-report") { resp =>
          threadId(resp) shouldBe relProvisioning.getThreadId
          resp.getJSONObject("description").getString("code") shouldBe "unexpected-message"
        }
      }
    }
  }

  def issueCredential_1_0(issuer: ApplicationAdminExt,
                      holder: ApplicationAdminExt,
                      relationshipId: String,
                      credValues: Map[String, String],
                      credDefName: String,
                      credTag: String)
                     (implicit scenario: Scenario): Unit = {
    val issuerSdk = issuer.sdks.head
    val holderSdk = holder.sdks.head
    issueCredential_1_0(issuerSdk, issuerSdk, holderSdk, holderSdk, relationshipId, credValues, credDefName, credTag)
  }

  def issueCredential_1_0(issuerSdk: VeritySdkProvider,
                      issuerMsgReceiverSdkProvider: VeritySdkProvider,
                      holderSdk: VeritySdkProvider,
                      holderMsgReceiverSdkProvider: VeritySdkProvider,
                      relationshipId: String,
                      credValues: Map[String, String],
                      credDefName: String,
                      credTag: String)
                     (implicit scenario: Scenario): Unit = {
    val issuerName = issuerSdk.sdkConfig.name
    val holderName = holderSdk.sdkConfig.name
    var issueCred: IssueCredentialV1_0 = null
    var issuerTId: String = ""

    s"issue credential (1.0) '$credDefName' to $holderName from $issuerName on relationship ($relationshipId)" - {
      val issuerMsgReceiver = receivingSdk(Option(issuerMsgReceiverSdkProvider))
      val holderMsgReceiver = receivingSdk(Option(holderMsgReceiverSdkProvider))

      s"[$issuerName] send a credential offer" in {
        val forRel = issuerSdk.relationship_!(relationshipId).owningDID
        val credDefId = issuerSdk.data_!(credDefIdKey(credDefName, credTag))

        issueCred = issuerSdk.issueCredential_1_0(forRel, credDefId, credValues, "comment-123", "0")
        issuerTId = issueCred.getThreadId
        issueCred.offerCredential(issuerSdk.context)
        expectSignal(issuerMsgReceiver, IssueCredMsgFamily, "sent")()
      }

      s"[$holderName] send a credential request" taggedAs UNSAFE_IgnoreLog in {
        var tid = ""
        val forRel = holderSdk.relationship_!(relationshipId).owningDID
        expectSignal(holderMsgReceiver, IssueCredMsgFamily, "ask-accept") { askAccept =>
          logger.info("askAccept: " + askAccept)
          tid = threadId(askAccept)
        }
        holderSdk.issueCredential_1_0(forRel, tid).requestCredential(holderSdk.context)
      }

      s"[$issuerName] issue credential" in {
        issuerMsgReceiver.expectMsg("accept-request") { askAccept =>
          threadId(askAccept) shouldBe issuerTId
        }
        val forRel = issuerSdk.relationship_!(relationshipId).owningDID

        issuerSdk.issueCredential_1_0(forRel, issuerTId).status(issuerSdk.context)
        checkStatus(issuerMsgReceiver, "RequestReceived")

        issuerSdk.issueCredential_1_0(forRel, issuerTId).issueCredential(issuerSdk.context)
        expectSignal(issuerMsgReceiver, IssueCredMsgFamily, "sent")()

        issuerSdk.issueCredential_1_0(forRel, issuerTId).status(issuerSdk.context)
        checkStatus(issuerMsgReceiver, "CredSent")
      }

      s"[$holderName] receive credential" taggedAs UNSAFE_IgnoreLog in {
        expectSignal(holderMsgReceiver, IssueCredMsgFamily, "cred-received")()
        holderSdk.issueCredentialComplete_1_0()
      }

      s"[$issuerName] reuse thread should fail" in {
        // if tried to reuse this thread a problem-report is received.
        issueCred.offerCredential(issuerSdk.context)

        expectSignal(issuerMsgReceiver, IssueCredMsgFamily, "problem-report") { resp =>
          threadId(resp) shouldBe issueCred.getThreadId
          resp.getJSONObject("description").getString("code") shouldBe "unexpected-message"
        }
      }

      def checkStatus(fromReceiver: VeritySdkProvider with MsgReceiver, expectedStatus: String): Unit = {
        expectSignal(fromReceiver, IssueCredMsgFamily, "status-report") { resp =>
          resp.getString("status") shouldBe expectedStatus
        }
      }
    }
  }

  def issueCredential_1_0_expectingError(issuerSdk: VeritySdkProvider, // todo rename
                                         holderSdk: VeritySdkProvider,
                                         relationshipId: String,
                                         credValues: Map[String, String],
                                         credDefName: String,
                                         credTag: String,
                                         errorMessage: String)
                                        (implicit scenario: Scenario): Unit = {
    val issuerName = issuerSdk.sdkConfig.name
    val holderName = holderSdk.sdkConfig.name
    s"issue credential (1.0) to $holderName from $issuerName on relationship ($relationshipId) expecting error" - {

      s"[$issuerName] send a credential offer expecting error" in {
        val forRel = issuerSdk.relationship_!(relationshipId).owningDID
        val credDefId = issuerSdk.data_!(credDefIdKey(credDefName, credTag))

        val issueCred = issuerSdk.issueCredential_1_0(forRel, credDefId, credValues, "comment-123", "0")
        val ex = intercept[Exception] {
          issueCred.offerCredential(issuerSdk.context)
        }
        ex.getMessage should include(errorMessage)
      }
    }
  }

  def acceptOobInvite(inviterSdk: VeritySdkProvider,
                      inviterMsgReceiverSdkProvider: VeritySdkProvider,
                      inviteeSdk: VeritySdkProvider,
                      inviteeMsgReceiverSdkProvider: VeritySdkProvider,
                      relationshipId: String,
                      inviteUrl: AtomicReference[String])
                     (implicit scenario: Scenario): Unit = {
    val inviterName = inviterSdk.sdkConfig.name
    val inviteeName = inviteeSdk.sdkConfig.name

    val inviterMsgReceiver = receivingSdk(Option(inviterMsgReceiverSdkProvider))
    val inviteeMsgReceiver = receivingSdk(Option(inviteeMsgReceiverSdkProvider))

    var inviteeConnection: ConnectionsV1_0 = null

    var connReq: JSONObject = null

    s"[$inviteeName] accept connection from invite" in {
      inviteeConnection = inviteeSdk.connectingWithOutOfBand_1_0(relationshipId, "", inviteUrl.get())
      inviteeConnection.accept(inviteeSdk.context)
    }

    s"[$inviterName] receive a signal about 'request-received' msg" in {
      expectSignal(inviterMsgReceiver, ConnectionsMsgFamily, "request-received") { msg =>
        connReq = msg
      }
    }

    s"[$inviterName] receive a signal about 'response-sent' msg" in {
      expectSignal(inviterMsgReceiver, ConnectionsMsgFamily, "response-sent") { connResp =>
        logger.info("connResp: " + connResp)
        val sigData = connResp.getJSONObject("resp").getJSONObject("connection~sig").getString("sig_data")
        val connJson = new JSONObject(new String(Base64Util.getBase64UrlDecoded(sigData).drop(8), StandardCharsets.UTF_8))
        val theirDID = connReq.getJSONObject("conn").getString("DID")
        val relData = RelData(relationshipId, connJson.getString("DID"), Option(theirDID))
        inviterSdk.updateRelationship(relationshipId, relData)
      }
    }

    s"[$inviteeName] receives 'response' message" in {
      expectSignal(inviteeMsgReceiver, ConnectionsMsgFamily, "response") { response =>
        val sigData = response.getJSONObject("connection~sig").getString("sig_data")
        val connJson = new JSONObject(new String(Base64Util.getBase64UrlDecoded(sigData).drop(8), StandardCharsets.UTF_8))
        val myDID = connReq.getJSONObject("conn").getString("DID")
        val relData = RelData(relationshipId, myDID, Option(connJson.getString("DID")))
        inviteeConnection.status(inviteeSdk.context)
        inviteeSdk.updateRelationship(relationshipId, relData)
      }
    }
  }

  def reuseRelOob(inviterSdk: VeritySdkProvider,
                  inviterMsgReceiverSdkProvider: VeritySdkProvider,
                  inviteeSdk: VeritySdkProvider,
                  inviteeMsgReceiverSdkProvider: VeritySdkProvider,
                  relationshipId: String,
                  inviteUrl: AtomicReference[String])
                 (implicit scenario: Scenario): Unit = {
    val inviterName = inviteeSdk.sdkConfig.name
    val inviteeName = inviteeSdk.sdkConfig.name

    val inviterMsgReceiver = receivingSdk(Option(inviterMsgReceiverSdkProvider))
    val inviteeMsgReceiver = receivingSdk(Option(inviteeMsgReceiverSdkProvider))

    s"[$inviteeName] handshake reuse" in {
      val reuseDID = inviterSdk.relationship_!(relationshipId).owningDID
      inviteeSdk
        .outOfBand_1_0(reuseDID, inviteUrl.get())
        .handshakeReuse(inviteeSdk.context)
    }

    s"[$inviterName] receive 'relationship-reused' signal msg" in {
      expectSignal(inviterMsgReceiver, OutOfBandMsgFamily, "relationship-reused") { msg =>
        assert(msg.getJSONObject("~thread").getString("pthid") != null)
      }
    }
  }

  def issueCredentialViaOob_1_0(issuer: ApplicationAdminExt,
                                holder: ApplicationAdminExt,
                                relationshipId: String,
                                credValues: Map[String, String],
                                credDefName: String,
                                credTag: String,
                                reuseRel: Boolean = false)
                               (implicit scenario: Scenario): Unit = {
    val issuerSdk = issuer.sdks.head
    val holderSdk = holder.sdks.head
    issueCredentialViaOob_1_0(issuerSdk, issuerSdk, holderSdk, holderSdk, relationshipId, credValues, credDefName, credTag, reuseRel)
  }

  def issueCredentialViaOob_1_0(issuerSdk: VeritySdkProvider,
                                issuerMsgReceiverSdkProvider: VeritySdkProvider,
                                holderSdk: VeritySdkProvider,
                                holderMsgReceiverSdkProvider: VeritySdkProvider,
                                relationshipId: String,
                                credValues: Map[String, String],
                                credDefName: String,
                                credTag: String,
                                reuseRel: Boolean)
                         (implicit scenario: Scenario): Unit = {
    val issuerName = issuerSdk.sdkConfig.name
    val holderName = holderSdk.sdkConfig.name

    var relDid = ""
    val inviteUrl: AtomicReference[String] = new AtomicReference[String]("")

    val count = iterCount("presentProofViaOob_1_0")

    s"issue credential (1.0) to $holderName from $issuerName via Out-of-band invite$count" - {
      val issuerMsgReceiver = receivingSdk(Option(issuerMsgReceiverSdkProvider))
      val holderMsgReceiver = receivingSdk(Option(holderMsgReceiverSdkProvider))
      var issueCred: IssueCredentialV1_0 = null

      s"[$issuerName] start relationship protocol to issue to" in {
        val relProvisioning = issuerSdk.relationship_1_0("inviter")
        relProvisioning.create(issuerSdk.context)
        expectSignal(issuerMsgReceiver, RelationshipMsgFamily, "created") { msg =>
          relDid = msg.getString("did")
        }
      }

      s"[$issuerName] start issue-credential using byInvitation" in {
        val credDefId = issuerSdk.data_!(credDefIdKey(credDefName, credTag))
        issueCred = issuerSdk.issueCredential_1_0(relDid, credDefId, credValues, "comment-123", byInvitation = true)
        issueCred.offerCredential(issuerSdk.context)
        expectSignal(issuerMsgReceiver, IssueCredMsgFamily, "protocol-invitation") { msg =>
          threadId(msg) shouldBe issueCred.getThreadId
          inviteUrl.getAndSet(msg.getString("inviteURL"))
        }
      }

      if (reuseRel) {
        reuseRelOob(
          issuerSdk,
          issuerMsgReceiverSdkProvider,
          holderSdk,
          holderMsgReceiverSdkProvider,
          relationshipId,
          inviteUrl
        )
      }
      else {
        acceptOobInvite(
          issuerSdk,
          issuerMsgReceiverSdkProvider,
          holderSdk,
          holderMsgReceiverSdkProvider,
          relationshipId,
          inviteUrl
        )
      }

      s"[$holderName] request credential from attached offer" in {
        val offerMsg = {
          val inviteStr = Base64Util.urlDecodeToStr(
            Uri(inviteUrl.get)
              .query()
              .getOrElse(
                "oob",
                fail("must have oob query parameter")
              )
          )
          Base64Util.decodeToStr(
            new JSONObject(inviteStr)
              .getJSONArray("request~attach")
              .getJSONObject(0)
              .getJSONObject("data")
              .getString("base64")
          )
        }
        val tid = threadId(new JSONObject(offerMsg))

        val forRel = holderSdk.relationship_!(relationshipId).owningDID
        val issue = holderSdk.asInstanceOf[VcxIssueCredential].issueCredential_1_0(forRel, tid, offerMsg)
        issue.requestCredential(holderSdk.context)
      }

      s"[$issuerName] issue credential" in {
        expectSignal(issuerMsgReceiver, IssueCredMsgFamily, "accept-request") { askAccept =>
          val tid = threadId(askAccept)
          tid shouldBe issueCred.getThreadId
          val forRel = issuerSdk.relationship_!(relationshipId).owningDID
          issuerSdk.issueCredential_1_0(forRel, tid).issueCredential(issuerSdk.context)
        }

        expectSignal(issuerMsgReceiver, IssueCredMsgFamily, "sent") { msg =>

        }
      }

      s"[$holderName] receive credential" taggedAs UNSAFE_IgnoreLog in {
        expectSignal(holderMsgReceiver, IssueCredMsgFamily, "cred-received")()
        holderSdk.issueCredentialComplete_1_0()
      }
    }

  }

  def issueCredentialViaOob_1_0_expectingError(issuerSdk: VeritySdkProvider,
                                issuerMsgReceiverSdkProvider: VeritySdkProvider,
                                holderSdk: VeritySdkProvider,
                                holderMsgReceiverSdkProvider: VeritySdkProvider,
                                relationshipId: String,
                                credValues: Map[String, String],
                                credDefName: String,
                                credTag: String,
                                errorMessage: String)
                               (implicit scenario: Scenario): Unit = {
    val issuerName = issuerSdk.sdkConfig.name
    val holderName = holderSdk.sdkConfig.name

    var relDid = ""
    val inviteUrl: AtomicReference[String] = new AtomicReference[String]("")

    val count = iterCount("presentProofViaOob_1_0")

    s"issue credential (1.0) to $holderName from $issuerName via Out-of-band invite$count expecting error" - {
      val issuerMsgReceiver = receivingSdk(Option(issuerMsgReceiverSdkProvider))
      val holderMsgReceiver = receivingSdk(Option(holderMsgReceiverSdkProvider))

      s"[$issuerName] start relationship protocol to issue to" in {
        val relProvisioning = issuerSdk.relationship_1_0("inviter")
        relProvisioning.create(issuerSdk.context)
        expectSignal(issuerMsgReceiver, RelationshipMsgFamily, "created") { msg =>
          relDid = msg.getString("did")
        }
      }

      s"[$issuerName] start issue-credential using byInvitation" in {
        val credDefId = issuerSdk.data_!(credDefIdKey(credDefName, credTag))
        val issueCred = issuerSdk.issueCredential_1_0(relDid, credDefId, credValues, "comment-123", byInvitation = true)
        val ex = intercept[Exception] {
          issueCred.offerCredential(issuerSdk.context)
        }
        ex.getMessage should include(errorMessage)
      }
    }
  }

  def presentProofViaOob_1_0(verifier: ApplicationAdminExt,
                             prover: ApplicationAdminExt,
                             relationshipId: String,
                             proofName: String,
                             attributes: Seq[String],
                             reuseRel: Boolean = false)
                             (implicit scenario: Scenario): Unit = {
    val verifierSdk = verifier.sdks.head
    val proverSdk = prover.sdks.head
    presentProofViaOob_1_0(verifierSdk, verifierSdk, proverSdk, proverSdk, relationshipId, proofName, attributes, reuseRel)
  }

  def presentProofViaOob_1_0(verifierSdk: VeritySdkProvider,
                             verifierMsgReceiverSdkProvider: VeritySdkProvider,
                             proverSdk: VeritySdkProvider,
                             proverMsgReceiverSdkProvider: VeritySdkProvider,
                             relationshipId: String,
                             proofName: String,
                             attributes: Seq[String],
                             reuseRel: Boolean)
                             (implicit scenario: Scenario): Unit = {
    val verifierName = verifierSdk.sdkConfig.name
    val proverName = proverSdk.sdkConfig.name

    var relDid = ""
    var verifierTId = ""
    var presentation = new JSONObject()
    val inviteUrl: AtomicReference[String] = new AtomicReference[String]("")
    val count = iterCount("presentProofViaOob_1_0")

    s"present proof (1.0) to $proverName from $verifierName via Out-of-band invite$count" - {
      val verifierMsgReceiver = receivingSdk(Option(verifierMsgReceiverSdkProvider))
      val holderMsgReceiver = receivingSdk(Option(proverMsgReceiverSdkProvider))

      s"[$verifierName] start relationship protocol to issue to" in {
        val relProvisioning = verifierSdk.relationship_1_0("inviter")
        relProvisioning.create(verifierSdk.context)
        expectSignal(verifierMsgReceiver, RelationshipMsgFamily, "created") { msg =>
          relDid = msg.getString("did")
        }
      }

      s"[$verifierName] start present-proof using byInvitation" in {
        val (issuerDID, _): (DidStr, VerKeyStr) = currentIssuerId(verifierSdk, verifierMsgReceiver)

        val restriction = RestrictionBuilder
          .blank()
          .issuerDid(issuerDID)
          .build()

        val nameAttr = PresentProofV1_0.attribute(Array("first_name", "last_name"), restriction)
        val numAttr = PresentProofV1_0.attribute("license_num", restriction)

        val t = Array(nameAttr, numAttr)

        val presentProof: PresentProofV1_0 = verifierSdk
          .presentProof_1_0(relDid, proofName, Array(nameAttr, numAttr), Array.empty, byInvitation = true)

        verifierTId = presentProof.getThreadId
        presentProof.request(verifierSdk.context)

        expectSignal(verifierMsgReceiver, PresentProofMsgFamily, "protocol-invitation") { msg =>
          inviteUrl.getAndSet(msg.getString("inviteURL"))
        }
      }

      if (reuseRel) {
        reuseRelOob(
          verifierSdk,
          verifierMsgReceiverSdkProvider,
          proverSdk,
          proverMsgReceiverSdkProvider,
          relationshipId,
          inviteUrl
        )
      }
      else {
        acceptOobInvite(
          verifierSdk,
          verifierMsgReceiverSdkProvider,
          proverSdk,
          proverMsgReceiverSdkProvider,
          relationshipId,
          inviteUrl
        )
      }


      s"[$proverName] present proof from attached offer" in {
        val requestMsg = {
          val inviteStr = Base64Util.urlDecodeToStr(
            Uri(inviteUrl.get)
              .query()
              .getOrElse(
                "oob",
                fail("must have oob query parameter")
              )
          )
          Base64Util.decodeToStr(
            new JSONObject(inviteStr)
              .getJSONArray("request~attach")
              .getJSONObject(0)
              .getJSONObject("data")
              .getString("base64")
          )
        }
        val tid = threadId(new JSONObject(requestMsg))

        val forRel = proverSdk.relationship_!(relationshipId).owningDID
        val proof = proverSdk.asInstanceOf[VcxPresentProof].presentProof_1_0(forRel, tid, requestMsg)

        proof.acceptRequest(proverSdk.context)
      }

      s"[$verifierName] receive presentation" in {
        val forRel = verifierSdk.relationship_!(relationshipId).owningDID

        expectSignal(verifierMsgReceiver, PresentProofMsgFamily, "presentation-result") { result =>
          threadId(result) shouldBe verifierTId
          result.getString("verification_result") shouldBe "ProofValidated"

          presentation = result.getJSONObject("requested_presentation")

          presentation
            .getJSONObject("revealed_attrs")
            .getJSONObject("license_num")
            .getString("value") shouldBe "123"
          presentation
            .getJSONObject("revealed_attrs")
            .getJSONObject("first_name")
            .getString("value") shouldBe "Bob"
          presentation
            .getJSONObject("revealed_attrs")
            .getJSONObject("last_name")
            .getString("value") shouldBe "Marley"
        }
      }

      s"[$verifierName] check presentation status" in {
        val forRel = verifierSdk.relationship_!(relationshipId).owningDID

        verifierSdk.presentProof_1_0(forRel, verifierTId).status(verifierSdk.context)
        expectSignal(verifierMsgReceiver, PresentProofMsgFamily, "status-report") { status =>
          status.getString("status") shouldBe "Complete"
          // Data may not be available since it may have been redacted but its there it should match
          Try{
            status
              .getJSONObject("results")
              .getJSONObject("requested_presentation")
          }.foreach { r =>
            r.toString shouldBe presentation.toString
          }
        }
      }

      s"[$verifierName] invalid control msg in state should be reported correctly" in {
        val forRel = verifierSdk.relationship_!(relationshipId).owningDID

        verifierSdk.presentProof_1_0(forRel, verifierTId).acceptProposal(verifierSdk.context)
        expectSignal(verifierMsgReceiver, PresentProofMsgFamily, "problem-report") { problemReport =>
          problemReport.getJSONObject("description").getString("code") shouldBe "unexpected-message"
        }
      }
    }

  }

  case class CredAttribute(name: String, `mime-type`: Option[String], value: String)

  def presentProof_1_0(verifier: ApplicationAdminExt,
                   holder: ApplicationAdminExt,
                   relationshipId: String,
                   proofName: String,
                   attributes: List[(String, String, String)])
                  (implicit scenario: Scenario): Unit = {
    val verifierSdk = verifier.sdks.head
    val holderSdk = holder.sdks.head

    presentProof_1_0(verifierSdk, verifierSdk, holderSdk, holderSdk, relationshipId, proofName, attributes)
  }

  def presentProof_1_0(verifierSdk: VeritySdkProvider,
                   verifierMsgReceiverSdk: VeritySdkProvider,
                   holderSdk: VeritySdkProvider,
                   holderMsgReceiverSdk: VeritySdkProvider,
                   relationshipId: String,
                   proofName: String,
                   attributes: List[(String, String, String)])
                  (implicit scenario: Scenario): Unit = {
    val holderName = holderSdk.sdkConfig.name
    val verifierName = verifierSdk.sdkConfig.name
    var presentProof: PresentProofV1_0 = null

    s"present proof from $holderName verifier $verifierName on relationship ($relationshipId) length ${attributes.size}" - {
      val verifierMsgReceiver = receivingSdk(Option(verifierMsgReceiverSdk))
      val holderMsgReceiver = receivingSdk(Option(holderMsgReceiverSdk))
      var verifierTId = ""
      var presentation = new JSONObject()

      s"[$verifierName] request a proof presentation" in {
        val forRel = verifierSdk.relationship_!(relationshipId).owningDID

        val (issuerDID, _): (DidStr, VerKeyStr) = currentIssuerId(verifierSdk, verifierMsgReceiver)

        val restriction = RestrictionBuilder
          .blank()
          .issuerDid(issuerDID)
          .build()

        val claims = attributes.map(pair => PresentProofV1_0.attribute(pair._1, restriction)).toArray

        presentProof = verifierSdk.presentProof_1_0(forRel, proofName, claims, Array.empty)
        verifierTId = presentProof.getThreadId
        presentProof.request(verifierSdk.context)
      }


      s"[$holderName] send a proof presentation" taggedAs UNSAFE_IgnoreLog in {
        var tid = ""
        expectSignal(holderMsgReceiver, PresentProofMsgFamily, "ask-accept") { askAccept =>
          tid = threadId(askAccept)
        }

        val forRel = holderSdk.relationship_!(relationshipId).owningDID

        holderSdk.presentProof_1_0(forRel, tid)
          .acceptRequest(holderSdk.context)
      }
      s"[$verifierName] receive presentation" in {
        expectSignal(verifierMsgReceiver, PresentProofMsgFamily, "presentation-result") { result =>
          logger.info(s"Presentation result: ${result.toString(2)}")
          threadId(result) shouldBe verifierTId
          result.getString("verification_result") shouldBe "ProofValidated"

          presentation = result.getJSONObject("requested_presentation")

          for (pair <- attributes) {
            presentation
              .getJSONObject("revealed_attrs")
              .getJSONObject(pair._2)
              .getString("value") shouldBe pair._3
          }
        }
      }

      s"[$verifierName] check presentation status" in {
        val forRel = verifierSdk.relationship_!(relationshipId).owningDID

        verifierSdk.presentProof_1_0(forRel, verifierTId).status(verifierSdk.context)
        expectSignal(verifierMsgReceiver, PresentProofMsgFamily, "status-report") { status =>
          status.getString("status") shouldBe "Complete"
          // Data may not be available since it may have been redacted but its there it should match
          Try{
            status
              .getJSONObject("results")
              .getJSONObject("requested_presentation")
          }.foreach { r =>
            r.toString shouldBe presentation.toString
          }
        }
      }

      s"[$verifierName] reuse thread should fail" in {
        // if tried to reuse this thread a problem-report is received.
        presentProof.request(verifierSdk.context)

        expectSignal(verifierMsgReceiver, PresentProofMsgFamily, "problem-report") { resp =>
          threadId(resp) shouldBe presentProof.getThreadId
          resp.getJSONObject("description").getString("code") shouldBe "unexpected-message"
        }
      }
    }
  }

  def presentProof_1_0ExpectingErrorOnRequest(verifier: ApplicationAdminExt,
                                              holder: ApplicationAdminExt,
                                              relationshipId: String,
                                              proofName: String,
                                              attributes: List[(String, String, String)],
                                              errorMessage: String)
                      (implicit scenario: Scenario): Unit = {
    val verifierSdk = verifier.sdks.head
    val holderSdk = holder.sdks.head

    presentProof_1_0ExpectingErrorOnRequest(verifierSdk, verifierSdk, holderSdk, holderSdk, relationshipId, proofName, attributes, errorMessage)
  }

  def presentProof_1_0ExpectingErrorOnRequest(verifierSdk: VeritySdkProvider,
                                              verifierMsgReceiverSdk: VeritySdkProvider,
                                              holderSdk: VeritySdkProvider,
                                              holderMsgReceiverSdk: VeritySdkProvider,
                                              relationshipId: String,
                                              proofName: String,
                                              attributes: List[(String, String, String)],
                                              errorMessage: String)
                      (implicit scenario: Scenario): Unit = {
    val holderName = holderSdk.sdkConfig.name
    val verifierName = verifierSdk.sdkConfig.name
    s"present proof from $holderName verifier $verifierName on relationship ($relationshipId) length ${attributes.size}" - {
      val verifierMsgReceiver = receivingSdk(Option(verifierMsgReceiverSdk))
      val holderMsgReceiver = receivingSdk(Option(holderMsgReceiverSdk))

      s"[$verifierName] fails to request a proof presentation" in {
        val forRel = verifierSdk.relationship_!(relationshipId).owningDID

        val (issuerDID, _): (DidStr, VerKeyStr) = currentIssuerId(verifierSdk, verifierMsgReceiver)

        val restriction = RestrictionBuilder
          .blank()
          .issuerDid(issuerDID)
          .build()

        val claims = attributes.map(pair => PresentProofV1_0.attribute(pair._1, restriction)).toArray

        var presentProof = verifierSdk
          .presentProof_1_0(forRel, proofName, claims, Array.empty)

        val ex = intercept[Exception] {
          presentProof.request(verifierSdk.context)
        }
        ex.getMessage should include(errorMessage)
      }
    }
  }

  def presentProof_1_0ExpectingErrorOnResponse(verifier: ApplicationAdminExt,
                                               holder: ApplicationAdminExt,
                                               relationshipId: String,
                                               proofName: String,
                                               attributes: List[(String, String, String)],
                                               errorMessage: String)
                                             (implicit scenario: Scenario): Unit = {
    val verifierSdk = verifier.sdks.head
    val holderSdk = holder.sdks.head

    presentProof_1_0ExpectingErrorOnResponse(verifierSdk, verifierSdk, holderSdk, holderSdk, relationshipId, proofName, attributes, errorMessage)
  }

  def presentProof_1_0ExpectingErrorOnResponse(verifierSdk: VeritySdkProvider,
                                              verifierMsgReceiverSdk: VeritySdkProvider,
                                              holderSdk: VeritySdkProvider,
                                              holderMsgReceiverSdk: VeritySdkProvider,
                                              relationshipId: String,
                                              proofName: String,
                                              attributes: List[(String, String, String)],
                                              errorMessage: String)
                                             (implicit scenario: Scenario): Unit = {
    val holderName = holderSdk.sdkConfig.name
    val verifierName = verifierSdk.sdkConfig.name
    s"present proof from $holderName verifier $verifierName on relationship ($relationshipId) length ${attributes.size}" - {
      val verifierMsgReceiver = receivingSdk(Option(verifierMsgReceiverSdk))
      val holderMsgReceiver = receivingSdk(Option(holderMsgReceiverSdk))

      s"[$verifierName] request a proof presentation" in {
        val forRel = verifierSdk.relationship_!(relationshipId).owningDID

        val (issuerDID, _): (DidStr, VerKeyStr) = currentIssuerId(verifierSdk, verifierMsgReceiver)

        val restriction = RestrictionBuilder
          .blank()
          .issuerDid(issuerDID)
          .build()

        val claims = attributes.map(pair => PresentProofV1_0.attribute(pair._1, restriction)).toArray

        verifierSdk
          .presentProof_1_0(forRel, proofName, claims, Array.empty)
          .request(verifierSdk.context)
      }


      s"[$holderName] send a proof presentation" taggedAs UNSAFE_IgnoreLog in {
        var tid = ""
        expectSignal(holderMsgReceiver, PresentProofMsgFamily, "ask-accept") { askAccept =>
          tid = threadId(askAccept)
        }

        val forRel = holderSdk.relationship_!(relationshipId).owningDID

        val presentProof= holderSdk.presentProof_1_0(forRel, tid)
        val ex = intercept[ExecutionException] {
          presentProof.acceptRequest(holderSdk.context)
        }
        val cause = ex.getCause
        cause.asInstanceOf[VcxException].getSdkFullMessage should include(errorMessage)

      }
    }
  }

  def presentProof_1_0_with_proposal(verifier: ApplicationAdminExt,
                                     holder: ApplicationAdminExt,
                                     relationshipId: String,
                                     proofName: String,
                                     attributes: Seq[String])
                                    (implicit scenario: Scenario): Unit = {
    val verifierSdk = verifier.sdks.head
    val holderSdk = holder.sdks.head

    presentProof_1_0_with_proposal(verifierSdk, verifierSdk, holderSdk, holderSdk, relationshipId, proofName, attributes)
  }

  def iterCount(methodName: String): String = {
    val curCount = iterationCountMap.getOrElse(methodName, 0)
    iterationCountMap.put(methodName, curCount + 1)
    if (curCount == 0) "" else s" [$curCount]"
  }

  def presentProof_1_0_with_proposal(verifierSdk: VeritySdkProvider,
                                     verifierMsgReceiverSdk: VeritySdkProvider,
                                     holderSdk: VeritySdkProvider,
                                     holderMsgReceiverSdk: VeritySdkProvider,
                                     relationshipId: String,
                                     proofName: String,
                                     attributes: Seq[String])
                                    (implicit scenario: Scenario): Unit = {
    val holderName = holderSdk.sdkConfig.name
    val verifierName = verifierSdk.sdkConfig.name



    s"present proof with proposal from $holderName verifier $verifierName on relationship ($relationshipId)" - {
      val verifierMsgReceiver = receivingSdk(Option(verifierMsgReceiverSdk))
      val holderMsgReceiver = receivingSdk(Option(holderMsgReceiverSdk))

      s"[$holderName] propose a proof presentation" in {
        val forRel = holderSdk.relationship_!(relationshipId).owningDID
        val credDefId = verifierSdk.data_!(credDefIdKey("cred_name1", "tag"))

        val firstNameAttr = PresentProofV1_0.proposedAttribute("first_name", credDefId, "Bob")
        val lastNameAttr = PresentProofV1_0.proposedAttribute("last_name", credDefId, "Marley")
        val numAttr = PresentProofV1_0.proposedAttribute("license_num", credDefId, "123")

        holderSdk
          .presentProof_1_0(forRel, Array(firstNameAttr, lastNameAttr, numAttr), Array.empty)
          .propose(verifierSdk.context)
      }

      s"[$verifierName] accept the proof proposal" in {
        val forRel = verifierSdk.relationship_!(relationshipId).owningDID

        var tid = ""
        expectSignal(verifierMsgReceiver, PresentProofMsgFamily, "review-proposal") { proposal =>
          logger.info(s"Proposal received: ${proposal.toString(2)}")
          tid = threadId(proposal)
        }

        verifierSdk
          .presentProof_1_0(forRel, tid)
          .acceptProposal(verifierSdk.context)
      }

      s"[$holderName] send a proof presentation" taggedAs UNSAFE_IgnoreLog in {
        var tid = ""
        expectSignal(holderMsgReceiver, PresentProofMsgFamily, "ask-accept") { askAccept =>
          tid = threadId(askAccept)
        }

        val forRel = holderSdk.relationship_!(relationshipId).owningDID

        holderSdk.presentProof_1_0(forRel, tid)
          .acceptRequest(holderSdk.context)
      }
      s"[$verifierName] receive presentation" in {
        val forRel = verifierSdk.relationship_!(relationshipId).owningDID
        var tid = ""

        var presentation = new JSONObject()

        expectSignal(verifierMsgReceiver, PresentProofMsgFamily, "presentation-result") { result =>
          logger.info(s"Presentation result: ${result.toString(2)}")
          tid = threadId(result)
          result.getString("verification_result") shouldBe "ProofValidated"

          presentation = result.getJSONObject("requested_presentation")

          presentation
            .getJSONObject("revealed_attrs")
            .getJSONObject("license_num")
            .getString("value") shouldBe "123"
          presentation
            .getJSONObject("revealed_attrs")
            .getJSONObject("first_name")
            .getString("value") shouldBe "Bob"
          presentation
            .getJSONObject("revealed_attrs")
            .getJSONObject("last_name")
            .getString("value") shouldBe "Marley"
        }
      }
    }
  }

  def committedAnswer(questioner: ApplicationAdminExt,
                      responder: ApplicationAdminExt,
                      relationshipId: String,
                      question: String,
                      description: String,
                      answers: Seq[String],
                      answer: String,
                      requireSig: Boolean)
                      (implicit scenario: Scenario): Unit = {
    s"ask committed answer to ${responder.name} from ${questioner.name} for question '$question'" - {
      val questionerSdk = receivingSdk(questioner)
      val responderSdk = receivingSdk(responder)
      var committedAnswer: CommittedAnswerV1_0 = null
      var questionerTId: String = ""

      s"[${questioner.name}] ask committed question" in {
        val forRel = questionerSdk.relationship_!(relationshipId).owningDID
        committedAnswer = questionerSdk.committedAnswer_1_0(forRel, question, description, answers, requireSig)
        questionerTId = committedAnswer.getThreadId
        committedAnswer.ask(questionerSdk.context)
      }

      s"[${responder.name}] answer committed question" taggedAs UNSAFE_IgnoreLog in {
        val forRel = responderSdk.relationship_!(relationshipId).owningDID
        var tid = ""
        expectSignal(responderSdk, CommittedAnswerMsgFamily, "answer-needed") { question =>
          tid =  threadId(question)
        }
        responderSdk.committedAnswer_1_0(forRel, tid, answer)
          .answer(responderSdk.context)
      }

      s"[${questioner.name}] check answer" in {
          expectSignal(questionerSdk, CommittedAnswerMsgFamily, "answer-given") { receivedAnswer =>
          receivedAnswer.getBoolean("valid_answer") shouldBe true
          receivedAnswer.getBoolean("valid_signature") shouldBe true
          receivedAnswer.getBoolean("not_expired") shouldBe true
          receivedAnswer.getString("answer") shouldBe answer

          threadId(receivedAnswer) shouldBe questionerTId
        }
      }

      s"[${questioner.name}] check status" in {
        val forRel = questionerSdk.relationship_!(relationshipId).owningDID

        questionerSdk.committedAnswer_1_0(forRel, questionerTId).status(questionerSdk.context)

        expectSignal(questionerSdk, CommittedAnswerMsgFamily, "status-report") { status =>
          status.getString("status") shouldBe "AnswerReceived"

          val statusAnswer = status.getJSONObject("answer")
          statusAnswer.getBoolean("valid_answer") shouldBe true
          statusAnswer.getBoolean("valid_signature") shouldBe true
          statusAnswer.getBoolean("not_expired") shouldBe true
          statusAnswer.getString("answer") shouldBe answer
        }
      }

      s"[$questioner.name] reuse thread should fail" in {
        // if tried to reuse this thread a problem-report is received.
        committedAnswer.ask(questionerSdk.context)

        expectSignal(questionerSdk, CommittedAnswerMsgFamily, "problem-report") { resp =>
          threadId(resp) shouldBe committedAnswer.getThreadId
          resp.getJSONObject("description").getString("code") shouldBe "unexpected-message"
        }
      }
    }
  }

  def committedAnswerWithError(questioner: ApplicationAdminExt,
                      responder: ApplicationAdminExt,
                      relationshipId: String,
                      question: String,
                      description: String,
                      answers: Seq[String],
                      requireSig: Boolean,
                      expectedError: String)
                     (implicit scenario: Scenario): Unit = {
    s"ask committed answer to ${responder.name} from ${questioner.name} awaiting error for question '$question'" - {
      val questionerSdk = receivingSdk(questioner)
      val responderSdk = receivingSdk(responder)

      s"[${questioner.name}] ask committed question" in {
        val forRel = questionerSdk.relationship_!(relationshipId).owningDID
        val error = intercept[Exception]{
          questionerSdk.committedAnswer_1_0(forRel, question, description, answers, requireSig)
            .ask(questionerSdk.context)
        }
        error.getMessage should include(expectedError)
      }
    }
  }

  def basicMessage(sender: ApplicationAdminExt,
                   receiver: ApplicationAdminExt,
                   relationshipId: String,
                   content: String,
                   sentTime: String,
                   localization: String)
                     (implicit scenario: Scenario): Unit = {
    s"send message to ${receiver.name} from ${sender.name}" - {
      val senderSdk = receivingSdk(sender)
      val receiverSdk = receivingSdk(receiver)

      s"[${sender.name}] send message with huge forRelationship" in {
        val str = Base58Util.encode(UUID.randomUUID().toString.getBytes())
        val forRel =  (1 to 1000).foldLeft("")((prev, _) => prev + str)
        val caught = intercept[Exception] {
          senderSdk.basicMessage_1_0(forRel, content, sentTime, localization)
            .message(senderSdk.context)
        }
        caught.getMessage should include ("Route value is too long")
      }

      s"[${sender.name}] send message" in {
        val forRel = senderSdk.relationship_!(relationshipId).owningDID
        senderSdk.basicMessage_1_0(forRel, content, sentTime, localization)
          .message(senderSdk.context)
      }

      s"[${receiver.name}] check message" in {
        expectSignal(receiverSdk, BasicMessageMsgFamily, "received-message") { receivedMessage =>

          receivedMessage.getString("content") shouldBe "Hello, World!"
          receivedMessage.getString("sent_time") shouldBe "2018-1-19T01:24:00-000"
          receivedMessage.getJSONObject("~l10n").getString("locale") shouldBe "en"

          val forRel = receiverSdk.relationship_!(relationshipId).owningDID
          receivedMessage.getString("relationship") shouldBe forRel
        }
      }
    }

    s"send message to ${receiver.name} from ${sender.name}" - {
      val receiverSdk = receivingSdk(sender)
      val senderSdk = receivingSdk(receiver)

      s"[${receiver.name}] send message" in {
        val forRel = senderSdk.relationship_!(relationshipId).owningDID

        val tid = "12345"

        senderSdk.asInstanceOf[VcxBasicMessage].basicMessage_1_0(forRel, tid, content, sentTime, localization)
          .message(senderSdk.context)
      }

      s"[${sender.name}] check message" in {
        var forRel = ""

        expectSignal(receiverSdk, BasicMessageMsgFamily, "received-message") { receivedMessage =>

          receivedMessage.getString("content") shouldBe "Hello, World!"
          receivedMessage.getString("sent_time") shouldBe "2018-1-19T01:24:00-000"
          receivedMessage.getJSONObject("~l10n").getString("locale") shouldBe "en"

          val forRel = receiverSdk.relationship_!(relationshipId).owningDID
          receivedMessage.getString("relationship") shouldBe forRel
        }
      }
    }
  }


  // The 'expectedMetricCount' will change depending how many times the app scenario ran a specific protocol
  def validateProtocolMetrics(app: ApplicationAdminExt,
                              protoRef: String,
                              expectedMetricCount: Double,
                              dumpToFile: Boolean=false): Unit = {
    s"${app.name} validating protocol metrics" - {
      s"[$protoRef] validation" in {
        eventually(timeout(30 seconds), Interval(2 seconds)) {
          //Get metrics for specific app
          val currentNodeMetrics = app.getAllNodeMetrics(app.metricsHost)
          if (dumpToFile) dumpMetrics(currentNodeMetrics, app)
          val expectedTags = Map("proto_ref" -> protoRef)
          val protoMetric =
            currentNodeMetrics
              .filter(_.isName(AS_NEW_PROTOCOL_COUNT))
              .find (m => expectedTags.toSet subsetOf m.tags.getOrElse(Map.empty).toSet)

          protoMetric.value should not be None

          //TODO: When integration tests start provisioning using a sponsor (0.7), the sponsor tag may change the count
          if (protoMetric.get.value != expectedMetricCount)
            fail(s"$protoRef did not have the expected number of metrics - found: ${protoMetric.value}, expected: $expectedMetricCount")
        }
      }
    }
  }

  def overflowAndRead(sender: ApplicationAdminExt, receiver: ApplicationAdminExt, limitMsg: Int, numberMsg: Int, expectedNumber: Int, connectionId: String)(implicit scenario: Scenario): Unit = {
    val msg = "Hello, World!"*limitMsg
    s"Overflow inbox of VCX client with ${numberMsg} commited questions from Verity SDK" - {

      val senderSdk = receivingSdk(sender)
      val receiverSdk = receiver.sdk match {
        case Some(s: VcxSdkProvider) => s
        case Some(x) => throw new Exception(s"InboxOverflow receiver works with VcxSdkProvider only -- Not ${x.getClass.getSimpleName}")
        case _ => throw new Exception(s"InboxOverflow receiver works with VcxSdkProvider only")
      }

      s"Send ${numberMsg} basic messages length ${msg.length}" in {
        val relDID = senderSdk.relationship_!(connectionId).owningDID
        for (a <- 1 to numberMsg) {
          senderSdk.basicMessage_1_0(relDID, msg, "2018-1-19T01:24:00-000", "en")
            .message(senderSdk.context)
          Thread.sleep(2000)
        }
      }

      s"Receive ${expectedNumber} messages" in {
        val result = receiverSdk.getAllMsgsFromConnection(TestConstants.defaultTimeout, connectionId)
        result.length shouldBe expectedNumber
      }
    }
  }

}

object InteractiveSdkFlow {
  def msgReceiverRequired: Nothing = throw new Exception("A Listing SDK is required for this interaction")
  def sdkRequired: Nothing = throw new Exception("An SDK is required for this interaction")

  def receivingSdk(app: ApplicationAdminExt): VeritySdkProvider with MsgReceiver = receivingSdk(app.sdk)

  def receivingSdk(sdk:  Option[VeritySdkProvider]): VeritySdkProvider with MsgReceiver = {
    sdk
      .orElse(sdkRequired)
      .map {
        case s: MsgReceiver => s
        case _ => msgReceiverRequired
      }
      .get
  }

  def currentIssuerId(issuerSdk: VeritySdkProvider,
                      msgReceiverSdk: VeritySdkProvider with MsgReceiver)(implicit scenario: Scenario): (DidStr, VerKeyStr) = {
    var did = ""
    var verkey = ""
    issuerSdk.issuerSetup_0_7.currentPublicIdentifier(issuerSdk.context)

    msgReceiverSdk.expectMsg("public-identifier") { resp =>
      did = resp.getString("did")
      verkey = resp.getString("verKey")
    }
    (did, verkey)
  }

  def credDefIdKey(name: String, tag: String): String = s"$name-$tag-id"
}
