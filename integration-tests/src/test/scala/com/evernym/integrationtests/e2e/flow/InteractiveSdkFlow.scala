package com.evernym.integrationtests.e2e.flow

import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes.MovedPermanently
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, Uri}
import com.evernym.integrationtests.e2e.msg.JSONObjectUtil.threadId
import com.evernym.integrationtests.e2e.scenario.{ApplicationAdminExt, Scenario}
import com.evernym.integrationtests.e2e.sdk.vcx.{VcxIssueCredential, VcxPresentProof, VcxBasicMessage}
import com.evernym.integrationtests.e2e.sdk.{ListeningSdkProvider, MsgReceiver, RelData, VeritySdkProvider}
import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreLog
import com.evernym.verity.fixture.TempDir
import com.evernym.verity.protocol.engine.{DID, VerKey}
import com.evernym.verity.sdk.protocols.connecting.v1_0.ConnectionsV1_0
import com.evernym.verity.sdk.protocols.presentproof.common.RestrictionBuilder
import com.evernym.verity.sdk.protocols.presentproof.v1_0.PresentProofV1_0
import com.evernym.verity.sdk.protocols.relationship.v1_0.GoalCode
import com.evernym.verity.sdk.protocols.writecreddef.v0_6.RevocationRegistryConfig
import com.evernym.verity.sdk.utils.ContextBuilder
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.testkit.util.LedgerUtil
import com.evernym.verity.util.{Base64Util, OptionUtil}
import org.json.JSONObject
import org.scalatest.concurrent.Eventually
import org.scalatest.concurrent.PatienceConfiguration.{Interval, Timeout}

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}

trait InteractiveSdkFlow {
  this: BasicSpec with TempDir with Eventually =>

  import InteractiveSdkFlow._

  val system: ActorSystem = ActorSystem.create("InteractiveSdkFlow")

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
      provisionAgent(aae.name, sdk, aae.urlDetail.url)
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
            val proto = sdk.provision_0_7
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

  def setupIssuer(sdk: VeritySdkProvider, ledgerUtil: LedgerUtil)(implicit scenario: Scenario): Unit = {
    setupIssuer(sdk, sdk, ledgerUtil)
  }

  def setupIssuer(issuerSdk: VeritySdkProvider,
                  msgReceiverSdkProvider: VeritySdkProvider,
                  ledgerUtil: LedgerUtil)(implicit scenario: Scenario): Unit = {
    val issuerName = issuerSdk.sdkConfig.name
    s"create issuer public identifier on $issuerName" - {

      val receiverSdk = receivingSdk(Option(msgReceiverSdkProvider))

      s"[$issuerName] use issuer-setup protocol" in {
        issuerSdk.issuerSetup_0_6
          .create(issuerSdk.context)

        receiverSdk.expectMsg("public-identifier-created") { resp =>
          resp shouldBe an[JSONObject]

          assert(resp.getJSONObject("identifier").has("verKey"))
          assert(resp.getJSONObject("identifier").has("did"))
        }
      }

      s"[$issuerName] write issuer DID to ledger" taggedAs UNSAFE_IgnoreLog in {
        val (issuerDID, issuerVerkey): (DID, VerKey) = currentIssuerId(issuerSdk, receiverSdk)
        issuerSdk.publicDID = Some(issuerDID)

        ledgerUtil.bootstrapNewDID(issuerDID, issuerVerkey, "ENDORSER")
        eventually(Timeout(scenario.timeout), Interval(Duration("20 seconds"))) {
          ledgerUtil.checkDidOnLedger(issuerDID, issuerVerkey, "ENDORSER")
        }

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

  def resolveShortInviteUrl(shortInviteUrl: String): String = {
    val fut = Http()(system).singleRequest(
      HttpRequest(
        method = HttpMethods.GET,
        uri = shortInviteUrl,
      ))
    val response = Await.result(fut, 10.seconds)
    response.status shouldBe MovedPermanently
    response.getHeader("Location").get.value
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
    s"write schema on $issuerName" - {

      val msgReceiverSdk = receivingSdk(Option(msgReceiverSdkProvider))

      //FIXME: RTM -> Write test which uses an issuer did that doesn't have write privileges on Ledger
      s"[$issuerName] use write-schema protocol" in {
        val schema = issuerSdk.writeSchema_0_6(schemaName, schemaVersion, schemaAttrs.toArray: _*)
        schema.write(issuerSdk.context)

        var schemaId = ""
        msgReceiverSdk.expectMsg("status-report") {resp =>
          resp shouldBe an[JSONObject]
          schemaId = resp.getString("schemaId")
        }

        issuerSdk.updateData(s"$schemaName-$schemaVersion-id",schemaId)
      }
      s"[$issuerName] check schema is on ledger" in {
        val (issuerDID, _): (DID, VerKey) = currentIssuerId(issuerSdk, msgReceiverSdk)
        ledgerUtil.checkSchemaOnLedger(issuerDID, schemaName, schemaVersion)
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

    s"write credential def ($credDefName) on $issuerName" - {
      s"[$issuerName] use write-cred-def protocol" taggedAs UNSAFE_IgnoreLog in {
        val schemaId = issuerSdk.data_!(s"$schemaName-$schemaVersion-id")
        issuerSdk.writeCredDef_0_6(credDefName, schemaId, Some(credTag), Some(revocation))
          .write(issuerSdk.context)

        var credDefId = ""
        msgReceiverSdk.expectMsg("status-report", Some(credDefTimeout.max(scenario.timeout))) { resp =>
          credDefId = resp.getString("credDefId")
        }
        issuerSdk.updateData(credDefIdKey(credDefName, credTag), credDefId)
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
        //        println(s"response error message: $errorMessage")
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
    var threadId: String = null
    var connReq: JSONObject = null
    var inviteeConnection: ConnectionsV1_0 = null

    val inviterMsgReceiverSdk = receivingSdk(Option(inviterMsgReceiverSdkProvider))
    val inviteeMsgReceiverSdk = receivingSdk(Option(inviteeSdk))

    s"connect (1.0) $inviterName and $inviteeName (connectionId: $connectionId)" - {
      s"[$inviterName] start relationship protocol" - {
        "send create" taggedAs UNSAFE_IgnoreLog in {
          val relProvisioning = inviterSdk.relationship_1_0("inviter")
          relProvisioning.create(inviterSdk.context)
          inviterMsgReceiverSdk.expectMsg("created") { msg =>
            createdRespMsg = msg
            println("created response: " + msg)
            msg shouldBe an[JSONObject]
            msg.getString("@type") shouldBe "did:sov:123456789abcdefghi1234;spec/relationship/1.0/created"
            threadId = msg.getJSONObject("~thread").getString("thid")
          }

        }

        s"prepares invitation" in {
          val forRel = createdRespMsg.getString("did")
          val relationship = inviterSdk.relationship_1_0(forRel, threadId)
          relationship.connectionInvitation(inviterSdk.context, true)
          inviterMsgReceiverSdk.expectMsg("invitation") { msg =>
            invite = msg
            println("prepare invite response: " + msg)
            inviteUrl = msg.getString("inviteURL")

            // check shortInviteURL.
            val shortInviteUrl = msg.getString("shortInviteURL")
            resolveShortInviteUrl(shortInviteUrl) shouldBe inviteUrl

            msg.getJSONObject("~thread").getString("thid") shouldBe threadId
          }
        }
      }

      s"[$inviteeName] accept invite" taggedAs UNSAFE_IgnoreLog in {
        inviteeConnection = inviteeSdk.connecting_1_0(connectionId, label, inviteUrl)
        inviteeConnection.accept(inviteeSdk.context)
      }

      s"[$inviterName] receive a signal about 'request-received' msg" in {
        inviterMsgReceiverSdk.expectMsg("request-received") { msg =>
          connReq = msg
          println("connReq: " + connReq)
        }

      }

      s"[$inviterName] receive a signal about 'response-sent' msg" in {
        inviterMsgReceiverSdk.expectMsg("response-sent") { connResp =>
          println("connResp: " + connResp)
          val sigData = connResp.getJSONObject("resp").getJSONObject("connection~sig").getString("sig_data")
          val connJson = new JSONObject(new String(Base64Util.getBase64UrlDecoded(sigData).drop(8), StandardCharsets.UTF_8))
          val theirDID = connReq.getJSONObject("conn").getString("DID")
          val relData = RelData(connectionId, connJson.getString("DID"), Option(theirDID))
          inviterSdk.updateRelationship(connectionId, relData)
        }
      }

      s"[$inviteeName] receives 'response' message " taggedAs UNSAFE_IgnoreLog in {
        inviteeMsgReceiverSdk.expectMsg("response") {response =>
          val sigData = response.getJSONObject("connection~sig").getString("sig_data")
          val connJson = new JSONObject(new String(Base64Util.getBase64UrlDecoded(sigData).drop(8), StandardCharsets.UTF_8))
          val myDID = connReq.getJSONObject("conn").getString("DID")
          val relData = RelData(connectionId, myDID, Option(connJson.getString("DID")))
          inviteeConnection.status(inviteeSdk.context)
          inviteeSdk.updateRelationship(connectionId, relData)
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
    var threadId: String = null
    var connReq: JSONObject = null
    var inviteeConnection: ConnectionsV1_0 = null

    val inviterMsgReceiverSdk = receivingSdk(Option(inviterMsgReceiverSdkProvider))
    val inviteeMsgReceiverSdk = receivingSdk(Option(inviteeSdk))

    s"connect with OutOfBand invitation (1.0) $inviterName and $inviteeName (connectionId: $connectionId)" - {
      s"[$inviterName] start relationship protocol for OutOfBand" - {
        "send create" in {
          val relProvisioning = inviterSdk.relationship_1_0("inviter")
          relProvisioning.create(inviterSdk.context)
          inviterMsgReceiverSdk.expectMsg("created") { msg =>
            createdRespMsg = msg
            println("created response: " + msg)
            msg shouldBe an[JSONObject]
            msg.getString("@type") shouldBe "did:sov:123456789abcdefghi1234;spec/relationship/1.0/created"
            threadId = msg.getJSONObject("~thread").getString("thid")
          }

        }

        s"prepares invitation" in {
          val forRel = createdRespMsg.getString("did")
          val relationship = inviterSdk.relationship_1_0(forRel, threadId)
          relationship.outOfBandInvitation(inviterSdk.context, true, goal)
          inviterMsgReceiverSdk.expectMsg("invitation") { msg =>
            invite = msg
            println("prepare invite response: " + msg)
            inviteUrl = msg.getString("inviteURL")

            // check shortInviteURL.
            val shortInviteUrl = msg.getString("shortInviteURL")
            resolveShortInviteUrl(shortInviteUrl) shouldBe inviteUrl

            msg.getJSONObject("~thread").getString("thid") shouldBe threadId

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
        inviterMsgReceiverSdk.expectMsg("request-received") { msg =>
          connReq = msg
          println("connReq: " + connReq)
        }

      }

      s"[$inviterName] receive a signal about 'response-sent' msg" in {
        inviterMsgReceiverSdk.expectMsg("response-sent") { connResp =>
          println("connResp: " + connResp)
          val sigData = connResp.getJSONObject("resp").getJSONObject("connection~sig").getString("sig_data")
          val connJson = new JSONObject(new String(Base64Util.getBase64UrlDecoded(sigData).drop(8), StandardCharsets.UTF_8))
          val theirDID = connReq.getJSONObject("conn").getString("DID")
          val relData = RelData(connectionId, connJson.getString("DID"), Option(theirDID))
          inviterSdk.updateRelationship(connectionId, relData)
        }
      }

      s"[$inviteeName] receives 'response' message" taggedAs UNSAFE_IgnoreLog in {
        inviteeMsgReceiverSdk.expectMsg("response") { response =>
          val sigData = response.getJSONObject("connection~sig").getString("sig_data")
          val connJson = new JSONObject(new String(Base64Util.getBase64UrlDecoded(sigData).drop(8), StandardCharsets.UTF_8))
          val myDID = connReq.getJSONObject("conn").getString("DID")
          val relData = RelData(connectionId, myDID, Option(connJson.getString("DID")))
          inviteeConnection.status(inviteeSdk.context)
          inviteeSdk.updateRelationship(connectionId, relData)
        }
      }


      s"[$inviteeName] send handshake-reuse message" in {
        inviteeSdk.outOfBand_1_0(threadId, inviteUrl).handshakeReuse(inviteeSdk.context)
        inviterMsgReceiverSdk.expectMsg("relationship-reused") { connectionReusedMsg =>
          assert(connectionReusedMsg.getJSONObject("~thread").getString("pthid") != null)
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
    s"issue credential (1.0) to $holderName from $issuerName on relationship ($relationshipId)" - {
      val issuerMsgReceiver = receivingSdk(Option(issuerMsgReceiverSdkProvider))
      val holderMsgReceiver = receivingSdk(Option(holderMsgReceiverSdkProvider))
      var tid = ""

      s"[$issuerName] send a credential offer" in {
        val forRel = issuerSdk.relationship_!(relationshipId).owningDID
        val credDefId = issuerSdk.data_!(credDefIdKey(credDefName, credTag))

        val issueCred = issuerSdk.issueCredential_1_0(forRel, credDefId, credValues, "comment-123", "0")
        issueCred.offerCredential(issuerSdk.context)
        checkSignal(issuerMsgReceiver, "sent")
      }

      s"[$holderName] send a credential request" taggedAs UNSAFE_IgnoreLog in {
        val forRel = holderSdk.relationship_!(relationshipId).owningDID
        holderMsgReceiver.expectMsg("ask-accept") {askAccept =>
          println("askAccept: " + askAccept)
          tid = threadId(askAccept)
        }
        holderSdk.issueCredential_1_0(forRel, tid).requestCredential(holderSdk.context)
      }

      s"[$issuerName] issue credential" in {
        issuerMsgReceiver.expectMsg("accept-request") { askAccept =>
          tid = threadId(askAccept)
        }
        val forRel = issuerSdk.relationship_!(relationshipId).owningDID

        issuerSdk.issueCredential_1_0(forRel, tid).status(issuerSdk.context)
        checkStatus(issuerMsgReceiver, "RequestReceived")

        issuerSdk.issueCredential_1_0(forRel, tid).issueCredential(issuerSdk.context)
        checkSignal(issuerMsgReceiver, "sent")

        issuerSdk.issueCredential_1_0(forRel, tid).status(issuerSdk.context)
        checkStatus(issuerMsgReceiver, "CredSent")
      }

      s"[$holderName] receive credential" taggedAs UNSAFE_IgnoreLog in {
        holderMsgReceiver.expectMsgOnly("cred-received")
        holderSdk.issueCredentialComplete_1_0()
      }

      def checkStatus(fromReceiver: VeritySdkProvider with MsgReceiver, expectedStatus: String): Unit = {
        fromReceiver.expectMsg("status-report") {resp =>
          resp shouldBe an[JSONObject]
          resp.getString("status") shouldBe expectedStatus
        }
      }

      def checkSignal(fromReceiver: VeritySdkProvider with MsgReceiver, expectedSignalMsgName: String): Unit = {
        fromReceiver.expectMsg(expectedSignalMsgName) { resp =>
          resp shouldBe an[JSONObject]
          resp.getString("@type") shouldBe s"did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/issue-credential/1.0/$expectedSignalMsgName"
        }

      }
    }
  }

  def issueCredentialViaOob_1_0(issuer: ApplicationAdminExt,
                                holder: ApplicationAdminExt,
                                relationshipId: String,
                                credValues: Map[String, String],
                                credDefName: String,
                                credTag: String)
                               (implicit scenario: Scenario): Unit = {
    val issuerSdk = issuer.sdks.head
    val holderSdk = holder.sdks.head
    issueCredentialViaOob_1_0(issuerSdk, issuerSdk, holderSdk, holderSdk, relationshipId, credValues, credDefName, credTag)
  }

  def issueCredentialViaOob_1_0(issuerSdk: VeritySdkProvider,
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

    var relDid = ""
    var inviteUrl = ""
    var connReq: JSONObject = null
    var inviteeConnection: ConnectionsV1_0 = null

    s"issue credential (1.0) to $holderName from $issuerName via Out-of-band invite" - {
      val issuerMsgReceiver = receivingSdk(Option(issuerMsgReceiverSdkProvider))
      val holderMsgReceiver = receivingSdk(Option(holderMsgReceiverSdkProvider))

      s"[$issuerName] start relationship protocol to issue to" in {
        val relProvisioning = issuerSdk.relationship_1_0("inviter")
        relProvisioning.create(issuerSdk.context)
        issuerMsgReceiver.expectMsg("created") { msg =>
          msg shouldBe an[JSONObject]
          relDid = msg.getString("did")
        }
      }

      s"[$issuerName] start issue-credential using byInvitation" in {
        val credDefId = issuerSdk.data_!(credDefIdKey(credDefName, credTag))
        val issueCred = issuerSdk.issueCredential_1_0(relDid, credDefId, credValues, "comment-123", byInvitation = true)
        issueCred.offerCredential(issuerSdk.context)
        issuerMsgReceiver.expectMsg("protocol-invitation") { msg =>
          inviteUrl = msg.getString("inviteURL")
        }
      }

      s"[$holderName] accept connection from invite" in {
        inviteeConnection = holderSdk.connectingWithOutOfBand_1_0(relationshipId, "", inviteUrl)
        inviteeConnection.accept(holderSdk.context)
      }

      s"[$issuerName] receive a signal about 'request-received' msg" in {
        issuerMsgReceiver.expectMsg("request-received") { msg =>
          connReq = msg
        }
      }

      s"[$issuerName] receive a signal about 'response-sent' msg" in {
        issuerMsgReceiver.expectMsg("response-sent") { connResp =>
          println("connResp: " + connResp)
          val sigData = connResp.getJSONObject("resp").getJSONObject("connection~sig").getString("sig_data")
          val connJson = new JSONObject(new String(Base64Util.getBase64UrlDecoded(sigData).drop(8), StandardCharsets.UTF_8))
          val theirDID = connReq.getJSONObject("conn").getString("DID")
          val relData = RelData(relationshipId, connJson.getString("DID"), Option(theirDID))
          issuerSdk.updateRelationship(relationshipId, relData)
        }
      }

      s"[$holderName] receives 'response' message" taggedAs UNSAFE_IgnoreLog in {
        holderMsgReceiver.expectMsg("response") { response =>
          val sigData = response.getJSONObject("connection~sig").getString("sig_data")
          val connJson = new JSONObject(new String(Base64Util.getBase64UrlDecoded(sigData).drop(8), StandardCharsets.UTF_8))
          val myDID = connReq.getJSONObject("conn").getString("DID")
          val relData = RelData(relationshipId, myDID, Option(connJson.getString("DID")))
          inviteeConnection.status(holderSdk.context)
          holderSdk.updateRelationship(relationshipId, relData)
        }
      }

      s"[$holderName] request credential from attached offer" in {
        val offerMsg = {
          val inviteStr = Base64Util.urlDecodeToStr(
            Uri(inviteUrl)
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
        issuerMsgReceiver.expectMsg("accept-request") { askAccept =>
          val tid = threadId(askAccept)
          val forRel = issuerSdk.relationship_!(relationshipId).owningDID
          issuerSdk.issueCredential_1_0(forRel, tid).issueCredential(issuerSdk.context)
        }

        issuerMsgReceiver.expectMsg("sent") { msg =>

        }
      }

      s"[$holderName] receive credential" taggedAs UNSAFE_IgnoreLog in {
        holderMsgReceiver.expectMsgOnly("cred-received")
        holderSdk.issueCredentialComplete_1_0()
      }
    }

  }

  def presentProofViaOob_1_0(verifier: ApplicationAdminExt,
                             prover: ApplicationAdminExt,
                             relationshipId: String,
                             proofName: String,
                             attributes: Seq[String])
                             (implicit scenario: Scenario): Unit = {
    val verifierSdk = verifier.sdks.head
    val proverSdk = prover.sdks.head
    presentProofViaOob_1_0(verifierSdk, verifierSdk, proverSdk, proverSdk, relationshipId, proofName, attributes)
  }

  def presentProofViaOob_1_0(verifierSdk: VeritySdkProvider,
                             verifierMsgReceiverSdkProvider: VeritySdkProvider,
                             proverSdk: VeritySdkProvider,
                             proverMsgReceiverSdkProvider: VeritySdkProvider,
                             relationshipId: String,
                             proofName: String,
                             attributes: Seq[String])
                             (implicit scenario: Scenario): Unit = {
    val verifierName = verifierSdk.sdkConfig.name
    val proverName = proverSdk.sdkConfig.name

    var relDid = ""
    var inviteUrl = ""
    var connReq: JSONObject = null
    var inviteeConnection: ConnectionsV1_0 = null

    s"present proof (1.0) to $proverName from $verifierName via Out-of-band invite" - {
      val verifierMsgReceiver = receivingSdk(Option(verifierMsgReceiverSdkProvider))
      val holderMsgReceiver = receivingSdk(Option(proverMsgReceiverSdkProvider))

      s"[$verifierName] start relationship protocol to issue to" in {
        val relProvisioning = verifierSdk.relationship_1_0("inviter")
        relProvisioning.create(verifierSdk.context)
        verifierMsgReceiver.expectMsg("created") { msg =>
          msg shouldBe an[JSONObject]
          relDid = msg.getString("did")
        }
      }

      s"[$verifierName] start present-proof using byInvitation" in {
        val (issuerDID, _): (DID, VerKey) = currentIssuerId(verifierSdk, verifierMsgReceiver)

        val restriction = RestrictionBuilder
          .blank()
          .issuerDid(issuerDID)
          .build()

        val nameAttr = PresentProofV1_0.attribute(Array("first_name", "last_name"), restriction)
        val numAttr = PresentProofV1_0.attribute("license_num", restriction)

        val t = Array(nameAttr, numAttr)

        verifierSdk
          .presentProof_1_0(relDid, proofName, Array(nameAttr, numAttr), Array.empty, true)
          .request(verifierSdk.context)

          verifierMsgReceiver.expectMsg("protocol-invitation") { msg =>
          inviteUrl = msg.getString("inviteURL")
        }
      }

      s"[$proverName] accept connection from invite" in {
        inviteeConnection = proverSdk.connectingWithOutOfBand_1_0(relationshipId, "", inviteUrl)
        inviteeConnection.accept(proverSdk.context)
      }

      s"[$verifierName] receive a signal about 'request-received' msg" in {
        verifierMsgReceiver.expectMsg("request-received") { msg =>
          connReq = msg
        }
      }

      s"[$verifierName] receive a signal about 'response-sent' msg" in {
        verifierMsgReceiver.expectMsg("response-sent") { connResp =>
          println("connResp: " + connResp)
          val sigData = connResp.getJSONObject("resp").getJSONObject("connection~sig").getString("sig_data")
          val connJson = new JSONObject(new String(Base64Util.getBase64UrlDecoded(sigData).drop(8), StandardCharsets.UTF_8))
          val theirDID = connReq.getJSONObject("conn").getString("DID")
          val relData = RelData(relationshipId, connJson.getString("DID"), Option(theirDID))
          verifierSdk.updateRelationship(relationshipId, relData)
        }
      }

      s"[$proverName] receives 'response' message" taggedAs UNSAFE_IgnoreLog in {
        holderMsgReceiver.expectMsg("response") { response =>
          val sigData = response.getJSONObject("connection~sig").getString("sig_data")
          val connJson = new JSONObject(new String(Base64Util.getBase64UrlDecoded(sigData).drop(8), StandardCharsets.UTF_8))
          val myDID = connReq.getJSONObject("conn").getString("DID")
          val relData = RelData(relationshipId, myDID, Option(connJson.getString("DID")))
          inviteeConnection.status(proverSdk.context)
          proverSdk.updateRelationship(relationshipId, relData)
        }
      }

      s"[$proverName] present proof from attached offer" in {
        val requestMsg = {
          val inviteStr = Base64Util.urlDecodeToStr(
            Uri(inviteUrl)
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
<<<<<<< HEAD
//        proof.accept(proverSdk.context)
=======
        proof.acceptRequest(proverSdk.context)
>>>>>>> origin/master
      }

      s"[$verifierName] receive presentation" in {
        val forRel = verifierSdk.relationship_!(relationshipId).owningDID
        var tid = ""

        var presentation = new JSONObject()

        verifierMsgReceiver.expectMsg("presentation-result") { result =>
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

        verifierSdk.presentProof_1_0(forRel, tid).status(verifierSdk.context)
        verifierMsgReceiver.expectMsg("status-report") { status =>
          status.getString("status") shouldBe "Complete"
          val presentationAgain = status
            .getJSONObject("results")
            .getJSONObject("requested_presentation")
          presentationAgain.toString shouldBe presentation.toString
        }
      }
    }

  }

  case class CredAttribute(name: String, `mime-type`: Option[String], value: String)

  def presentProof_1_0(verifier: ApplicationAdminExt,
                   holder: ApplicationAdminExt,
                   relationshipId: String,
                   proofName: String,
                   attributes: Seq[String])
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
                   attributes: Seq[String])
                  (implicit scenario: Scenario): Unit = {
    val holderName = holderSdk.sdkConfig.name
    val verifierName = verifierSdk.sdkConfig.name
    s"present proof from $holderName verifier $verifierName on relationship ($relationshipId)" - {
      val verifierMsgReceiver = receivingSdk(Option(verifierMsgReceiverSdk))
      val holderMsgReceiver = receivingSdk(Option(holderMsgReceiverSdk))

      s"[$verifierName] request a proof presentation" in {
        val forRel = verifierSdk.relationship_!(relationshipId).owningDID

        val (issuerDID, _): (DID, VerKey) = currentIssuerId(verifierSdk, verifierMsgReceiver)

        val restriction = RestrictionBuilder
          .blank()
          .issuerDid(issuerDID)
          .build()

        val nameAttr = PresentProofV1_0.attribute(Array("first_name", "last_name"), restriction)
        val numAttr = PresentProofV1_0.attribute("license_num", restriction)

        verifierSdk
          .presentProof_1_0(forRel, proofName, Array(nameAttr, numAttr), Array.empty)
          .request(verifierSdk.context)
      }


      s"[$holderName] send a proof presentation" taggedAs UNSAFE_IgnoreLog in {
        var tid = ""
        holderMsgReceiver.expectMsg("ask-accept") { askAccept =>
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

        verifierMsgReceiver.expectMsg("presentation-result") { result =>
          println(s"Presentation result: ${result.toString(2)}")
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

        verifierSdk.presentProof_1_0(forRel, tid).status(verifierSdk.context)
        verifierMsgReceiver.expectMsg("status-report") { status =>
          status.getString("status") shouldBe "Complete"
          val presentationAgain = status
            .getJSONObject("results")
            .getJSONObject("requested_presentation")
          presentationAgain.toString shouldBe presentation.toString
        }
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
        verifierMsgReceiver.expectMsg("review-proposal") { proposal =>
          println(s"Proposal received: ${proposal.toString(2)}")
          tid = threadId(proposal)
        }

        verifierSdk
          .presentProof_1_0(forRel, tid)
          .acceptProposal(verifierSdk.context)
      }

      s"[$holderName] send a proof presentation" taggedAs UNSAFE_IgnoreLog in {
        var tid = ""
        holderMsgReceiver.expectMsg("ask-accept") { askAccept =>
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

        verifierMsgReceiver.expectMsg("presentation-result") { result =>
          println(s"Presentation result: ${result.toString(2)}")
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

        //        verifierSdk.presentProof_1_0(forRel, tid).status(verifierSdk.context)
        //        verifierMsgReceiver.expectMsg("status-report") { status =>
        //          status.getString("status") shouldBe "Complete"
        //          val presentationAgain = status
        //            .getJSONObject("results")
        //            .getJSONObject("requested_presentation")
        //          presentationAgain.toString shouldBe presentation.toString
        //        }
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
    s"ask committed answer to ${responder.name} from ${questioner.name}" - {
      val questionerSdk = receivingSdk(questioner)
      val responderSdk = receivingSdk(responder)

      s"[${questioner.name}] ask committed question" in {
        val forRel = questionerSdk.relationship_!(relationshipId).owningDID
        questionerSdk.committedAnswer_1_0(forRel, question, description, answers, requireSig)
          .ask(questionerSdk.context)
      }

      s"[${responder.name}] answer committed question" taggedAs UNSAFE_IgnoreLog in {
        val forRel = responderSdk.relationship_!(relationshipId).owningDID
        var tid = ""
        responderSdk.expectMsg("answer-needed") { question =>
          tid =  threadId(question)
        }
        responderSdk.committedAnswer_1_0(forRel, tid, answer)
          .answer(responderSdk.context)
      }

      s"[${questioner.name}] check answer" in {
        var tid = ""
        var forRel = ""

        questionerSdk.expectMsg("answer-given") { receivedAnswer =>
          receivedAnswer.getBoolean("valid_answer") shouldBe true
          receivedAnswer.getBoolean("valid_signature") shouldBe true
          receivedAnswer.getBoolean("not_expired") shouldBe true
          receivedAnswer.getString("answer") shouldBe answer

          tid = threadId(receivedAnswer)
          forRel = questionerSdk.relationship_!(relationshipId).owningDID
        }

        questionerSdk.committedAnswer_1_0(forRel, tid).status(questionerSdk.context)

        questionerSdk.expectMsg("status-report") { status =>
          status.getString("status") shouldBe "AnswerReceived"

          val statusAnswer = status.getJSONObject("answer")
          statusAnswer.getBoolean("valid_answer") shouldBe true
          statusAnswer.getBoolean("valid_signature") shouldBe true
          statusAnswer.getBoolean("not_expired") shouldBe true
          statusAnswer.getString("answer") shouldBe answer
        }

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

      s"[${sender.name}] send message" in {
        val forRel = senderSdk.relationship_!(relationshipId).owningDID
        senderSdk.basicMessage_1_0(forRel, content, sentTime, localization)
          .message(senderSdk.context)
      }
      s"[${receiver.name}] check message" in {
        var tid = ""
        var forRel = ""

        receiverSdk.expectMsg("received-message") { receivedAnswer =>
          receivedAnswer.getString("content") shouldBe "Hello, World!"
          receivedAnswer.getString("sent_time") shouldBe "2018-1-19T01:24:00-000"
          receivedAnswer.getJSONObject("~l10n").getString("locale") shouldBe "en"

          tid = threadId(receivedAnswer)
          forRel = senderSdk.relationship_!(relationshipId).owningDID
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
        var tid = ""
        var forRel = ""

        receiverSdk.expectMsg("received-message") { receivedAnswer =>
          receivedAnswer.getString("content") shouldBe "Hello, World!"
          receivedAnswer.getString("sent_time") shouldBe "2018-1-19T01:24:00-000"
          receivedAnswer.getJSONObject("~l10n").getString("locale") shouldBe "en"

          tid = threadId(receivedAnswer)
          forRel = senderSdk.relationship_!(relationshipId).owningDID
        }
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
                      msgReceiverSdk: VeritySdkProvider with MsgReceiver)(implicit scenario: Scenario): (DID, VerKey) = {
    var did = ""
    var verkey = ""
    issuerSdk.issuerSetup_0_6.currentPublicIdentifier(issuerSdk.context)

    msgReceiverSdk.expectMsg("public-identifier") { resp =>
      did = resp.getString("did")
      verkey = resp.getString("verKey")
    }
    (did, verkey)
  }

  def credDefIdKey(name: String, tag: String): String = s"$name-$tag-id"
}
