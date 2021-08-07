package com.evernym.verity.http.rest.base

import java.time.LocalDateTime
import java.util.UUID
import akka.http.scaladsl.model.ContentTypes._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.headers.{RawHeader, `Content-Type`}
import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
import akka.util.ByteString
import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.actor.testkit.actor.ProvidesMockPlatform
import com.evernym.verity.actor.wallet.{PackedMsg, SignMsg, SignedMsg}
import com.evernym.verity.http.base.open.{AgentProvisioningSpec, AriesInvitationDecodingSpec, ProvisionRelationshipSpec, UpdateComMethodSpec}
import com.evernym.verity.http.base.restricted.{AgencySetupSpec, AgentConfigsSpec, RestrictedRestApiSpec}
import com.evernym.verity.http.base.EdgeEndpointBaseSpec
import com.evernym.verity.http.route_handlers.open.RestAcceptedResponse
import com.evernym.verity.did.{DidStr, DidPair, VerKeyStr}
import com.evernym.verity.testkit.BasicSpecWithIndyCleanup
import com.evernym.verity.testkit.mock.agent.MockEdgeAgent._
import com.evernym.verity.testkit.mock.agent.MockEnv
import com.evernym.verity.util.Base58Util
import com.evernym.verity.vault.KeyParam
import org.json.JSONObject

import scala.reflect.ClassTag

trait RestApiBaseSpec
  extends BasicSpecWithIndyCleanup
    with ProvidesMockPlatform
    with EdgeEndpointBaseSpec
    with AgencySetupSpec
    with RestrictedRestApiSpec
    with AgentProvisioningSpec
    with AgentConfigsSpec
    with UpdateComMethodSpec
    with ProvisionRelationshipSpec
    with AriesInvitationDecodingSpec {

  def withRestApiDisabled(testCode: => Unit): Unit = {
    overrideRestEnable = false
    testCode
    overrideRestEnable = true
  }

  // Disable rest api by default, we will override it in the test, when needed,
  // but this way we may check if it responds correctly when disabled.
  lazy override val restApiEnabled: Boolean = false
  var overrideRestEnable: Boolean = true

  override def checkIfRestApiEnabled(): Unit = {
    if (!overrideRestEnable)
      super.checkIfRestApiEnabled()
  }

  override lazy val agentActorContext: AgentActorContext = platform.agentActorContext

  lazy val mockEntRestEnv: MockRestEnv = MockRestEnv(mockEntEdgeEnv)
  lazy val mockUserRestEnv: MockRestEnv = MockRestEnv(mockUserEdgeEnv)

  def performIssuerSetup(mockRestEnv: MockRestEnv): Unit = {
    val (_, lastPayload) = withExpectNewRestMsgAtRegisteredEndpoint({
      val createIssuerPayload = ByteString(s"""{"@type":"did:sov:123456789abcdefghi1234;spec/issuer-setup/0.6/create","@id":"${UUID.randomUUID.toString}"}""")
      buildPostReq(s"/api/${mockRestEnv.myDID}/issuer-setup/0.6/${UUID.randomUUID.toString}",
        HttpEntity.Strict(ContentTypes.`application/json`, createIssuerPayload),
        Seq(RawHeader("X-API-key", s"${mockRestEnv.myDIDApiKey}"))
      ) ~> epRoutes ~> check {
        status shouldBe Accepted
        header[`Content-Type`] shouldEqual Some(`Content-Type`(`application/json`))
        responseTo[RestAcceptedResponse] shouldBe RestAcceptedResponse()
      }
    })
    lastPayload.isDefined shouldBe true
    lastPayload.get.contains("did:sov:123456789abcdefghi1234;spec/issuer-setup/0.6/public-identifier-created") shouldBe true
  }

  def performWriteSchema(mockRestEnv: MockRestEnv, payload: ByteString): Unit = {
    buildPostReq(s"/api/${mockRestEnv.myDID}/write-schema/0.6/${UUID.randomUUID.toString}",
      HttpEntity.Strict(ContentTypes.`application/json`, payload),
      Seq(RawHeader("X-API-key", s"${mockRestEnv.myDIDApiKey}"))
    ) ~> epRoutes ~> check {
      status shouldBe Accepted
      header[`Content-Type`] shouldEqual Some(`Content-Type`(`application/json`))
      responseTo[RestAcceptedResponse] shouldBe RestAcceptedResponse()
    }
  }

  def createConnectionRequest(mockRestEnv: MockRestEnv, connId: String, payload: ByteString): Unit = {
    val (_, lastPayload) = withExpectNewRestMsgAtRegisteredEndpoint({
      buildPostReq(s"/api/${mockRestEnv.myDID}/connecting/0.6/1?1%20and%203614%3d3623=1",
        HttpEntity.Strict(ContentTypes.`application/json`, payload),
        Seq(RawHeader("X-API-key", s"${mockRestEnv.myDIDApiKey}"))
      ) ~> epRoutes ~> check {
        status shouldBe Accepted
        header[`Content-Type`] shouldEqual Some(`Content-Type`(`application/json`))
        responseTo[RestAcceptedResponse] shouldBe RestAcceptedResponse()
      }
    })
    lastPayload.isDefined shouldBe true
    val jsonMsgString = lastPayload.get

    val jsonMsg = new JSONObject(jsonMsgString)
    val regInvite = jsonMsg.getJSONObject("inviteDetail")
    val abrInvite = jsonMsg.getJSONObject("truncatedInviteDetail")

    regInvite.getString("connReqId") shouldBe abrInvite.getString("id")
    regInvite.getString("targetName") shouldBe abrInvite.getString("t")
    regInvite.getString("statusCode") shouldBe abrInvite.getString("sc")
    regInvite.getString("statusMsg") shouldBe abrInvite.getString("sm")
    regInvite.getString("version") shouldBe abrInvite.getString("version")

    // senderAgencyDetail
    val regSAD = regInvite.getJSONObject("senderAgencyDetail")
    val abrSAD = abrInvite.getJSONObject("sa")

    regSAD.getString("DID") shouldBe abrSAD.getString("d")
    regSAD.getString("verKey") shouldBe abrSAD.getString("v")
    regSAD.getString("endpoint") shouldBe abrSAD.getString("e")

    // senderDetail
    val regSD = regInvite.getJSONObject("senderDetail")
    val abrSD = abrInvite.getJSONObject("s")

    val senderDID = regSD.getString("DID")
    senderDID shouldBe abrSD.getString("d")
    val senderDIDVerKey = regSD.getString("verKey")
    senderDIDVerKey shouldBe abrSD.getString("v")
    regSD.getString("name") shouldBe abrSD.getString("n")
    regSD.getString("logoUrl") shouldBe abrSD.getString("l")

    // senderDetail.agentKeyDlgProof
    val regDP = regSD.getJSONObject("agentKeyDlgProof")
    val abrDP = abrSD.getJSONObject("dp")

    regDP.getString("agentDID") shouldBe abrDP.getString("d")
    regDP.getString("agentDelegatedKey") shouldBe abrDP.getString("k")
    regDP.getString("signature") shouldBe abrDP.getString("s")

    mockRestEnv.mockEnv.edgeAgent.add(INVITE_JSON_OBJECT, regInvite)
    mockRestEnv.mockEnv.edgeAgent.addNewLocalPairwiseKey(connId, DidPair(senderDID, senderDIDVerKey), storeKey = true)
  }

  def createKeyRequest(mockRestEnv: MockRestEnv, connId: String): Unit = {
    val le = mockRestEnv.mockEnv.edgeAgent.addNewLocalPairwiseKey(connId)

    val jsonObject = new JSONObject()
    jsonObject.put("@type", "did:sov:123456789abcdefghi1234;spec/connecting/0.6/CREATE_KEY")
    jsonObject.put("forDID", le.myPairwiseDidPair.did)
    jsonObject.put("forDIDVerKey", le.myPairwiseDidPair.verKey)
    val payload = ByteString(jsonObject.toString)

    val (_, lastPayload) = withExpectNewRestMsgAtRegisteredEndpoint({
      buildPostReq(s"/api/${mockRestEnv.myDID}/connecting/0.6/",
        HttpEntity.Strict(ContentTypes.`application/json`, payload),
        Seq(RawHeader("X-API-key", s"${mockRestEnv.myDIDApiKey}"))
      ) ~> epRoutes ~> check {
        status shouldBe Accepted
        header[`Content-Type`] shouldEqual Some(`Content-Type`(`application/json`))
        responseTo[RestAcceptedResponse] shouldBe RestAcceptedResponse()
      }
    })
    lastPayload.isDefined shouldBe true
    val respJsonObject = new JSONObject(lastPayload.get)
    val withPairwiseDID = respJsonObject.getString("withPairwiseDID")
    val withPairwiseDIDVerKey = respJsonObject.getString("withPairwiseDIDVerKey")

    le.setMyCloudAgentPairwiseDidPair(withPairwiseDID, withPairwiseDIDVerKey)
  }

  def acceptConnectionRequest(mockRestEnv: MockRestEnv, connId: String, invite: JSONObject): Unit = {

    val connReqId = invite.getString("connReqId")
    val senderDetail = invite.getJSONObject("senderDetail")
    val senderAgencyDetail = invite.getJSONObject("senderAgencyDetail")

    val keyDlgProof = mockRestEnv.mockEnv.edgeAgent.buildAgentKeyDlgProofForConn(connId)
    val keyDlgJsonObject = new JSONObject()
    keyDlgJsonObject.put("agentDID", keyDlgProof.agentDID)
    keyDlgJsonObject.put("agentDelegatedKey", keyDlgProof.agentDelegatedKey)
    keyDlgJsonObject.put("signature", keyDlgProof.signature)

    val jsonObject = new JSONObject()
    jsonObject.put("@type", "did:sov:123456789abcdefghi1234;spec/connecting/0.6/ACCEPT_CONN_REQ")
    jsonObject.put("@id", s"${UUID.randomUUID.toString}")
    jsonObject.put("sendMsg", true)
    jsonObject.put("senderDetail", senderDetail)
    jsonObject.put("senderAgencyDetail", senderAgencyDetail)
    jsonObject.put("replyToMsgId", connReqId)
    jsonObject.put("keyDlgProof", keyDlgJsonObject)
    jsonObject.put("~for_relationship", s"${mockRestEnv.connRelRoutingDID(connId)}")

    val payload = ByteString(jsonObject.toString)

    val (_, lastPayload) = withExpectNewMsgAtRegisteredEndpoint({
      buildPostReq(s"/api/${mockRestEnv.myDID}/connecting/0.6/",
        HttpEntity.Strict(ContentTypes.`application/json`, payload),
        Seq(RawHeader("X-API-key", s"${mockRestEnv.myDIDApiKey}"))
      ) ~> epRoutes ~> check {
        status shouldBe Accepted
        header[`Content-Type`] shouldEqual Some(`Content-Type`(`application/json`))
        responseTo[RestAcceptedResponse] shouldBe RestAcceptedResponse()
      }
    })
    processReceivedPackedMsg(lastPayload.get)
  }

  def sendMsgWithOthersMsgForRel(mockRestEnv: MockRestEnv,
                                 othersForRelDID: DidStr): Unit = {
    val jsonObject = new JSONObject()
    jsonObject.put("@type", "did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/basicmessage/1.0/send-message")
    jsonObject.put("content", s"basic message")
    jsonObject.put("sent_time", LocalDateTime.now().toString)
    jsonObject.put("~for_relationship", othersForRelDID)

    val payload = ByteString(jsonObject.toString())
    buildPostReq(s"/api/${mockRestEnv.myDID}/basicmessage/1.0/${UUID.randomUUID().toString}",
      HttpEntity.Strict(ContentTypes.`application/json`, payload),
      Seq(RawHeader("X-API-key", s"${mockRestEnv.myDIDApiKey}"))
    ) ~> epRoutes ~> check {
      val resp = responseAs[String]
      status shouldBe Unauthorized
      resp shouldBe "{\"errorCode\":\"GNR-108\",\"errorDetails\":\"provided relationship doesn't belong to requested domain\",\"status\":\"Error\"}"
    }
  }

  def sendMsgWithLargeMsgForRel(mockRestEnv: MockRestEnv): Unit = {
    val str = Base58Util.encode(UUID.randomUUID().toString.getBytes())
    val largeInvalidForRelString =  (1 to 1000).foldLeft("")((prev, _) => prev + str)
    val jsonObject = new JSONObject()
    jsonObject.put("@type", "did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/committedanswer/1.0/ask-question")
    jsonObject.put("@id", s"${UUID.randomUUID.toString}")
    jsonObject.put("~for_relationship", s"$largeInvalidForRelString")

    val payload = ByteString(jsonObject.toString())
    buildPostReq(s"/api/${mockRestEnv.myDID}/committedanswer/1.0/${UUID.randomUUID().toString}",
      HttpEntity.Strict(ContentTypes.`application/json`, payload),
      Seq(RawHeader("X-API-key", s"${mockRestEnv.myDIDApiKey}"))
    ) ~> epRoutes ~> check {
      status shouldBe BadRequest
    }
  }

  def sendMaliciousCredOffer(mockRestEnv: MockRestEnv): Unit = {
    val largeForRelString = (1 to 100).foldLeft("")((prev, _) => prev + UUID.randomUUID().toString)
    val jsonObject = new JSONObject()
    jsonObject.put("@type", "did:sov:CYp5MAwt9X3Jq7MZ9Dx2MP;spec/issue-credential/1.0/offer")
    jsonObject.put("@id", s"${UUID.randomUUID.toString}")
    jsonObject.put("cred_def_id", 1)
    jsonObject.put("credential_values", "<object>")
    jsonObject.put("price", 0)
    jsonObject.put("comment", "<img src=a onerror=prompt(document.domain)>")
    jsonObject.put("auto_issue", false)
    jsonObject.put("by_invitation", false)
    jsonObject.put("~for_relationship", s"$largeForRelString")

    val payload = ByteString(jsonObject.toString())
    buildPostReq(s"/api/${mockRestEnv.myDID}/issue-credential/1.0/${UUID.randomUUID().toString}",
      HttpEntity.Strict(ContentTypes.`application/json`, payload),
      Seq(RawHeader("X-API-key", s"${mockRestEnv.myDIDApiKey}"))
    ) ~> epRoutes ~> check {
      status shouldBe BadRequest
    }
  }

  def sendBasicMessage(mockRestEnv: MockRestEnv, connId: String, threadId: String): Unit = {
    val jsonObject = new JSONObject()
    jsonObject.put("@type", "did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/basicmessage/1.0/send-message")
    jsonObject.put("content", s"basic message")
    jsonObject.put("sent_time", LocalDateTime.now().toString)
    jsonObject.put("~for_relationship", s"${mockRestEnv.connRelRoutingDID(connId)}")

    val payload = ByteString(jsonObject.toString())
    buildPostReq(s"/api/${mockRestEnv.myDID}/basicmessage/1.0/$threadId",
      HttpEntity.Strict(ContentTypes.`application/json`, payload),
      Seq(RawHeader("X-API-key", s"${mockRestEnv.myDIDApiKey}"))
    ) ~> epRoutes ~> check {
      status shouldBe Accepted
    }
  }

  //this is mostly use where one agency sent a packed message to another agency
  def processReceivedPackedMsg(payload: PackedMsg): Unit = {
    buildAgentPostReq(payload.msg) ~> epRoutes ~> check {
      status shouldBe OK
    }
  }

  //this is mostly use where one agency sent a packed message to another agency
  def processLastReceivedPackedMsg[T: ClassTag](mockRestEnv: MockRestEnv, connId: String): T = {
    val (_, lastPayload) = withExpectNewMsgAtRegisteredEndpoint({
      testMsgSendingSvc.lastBinaryMsgSent.foreach { pm =>
        buildAgentPostReq(pm) ~> epRoutes ~> check {
          status shouldBe OK
        }
      }
    })
    lastPayload.isDefined shouldBe true
    mockRestEnv.mockEnv.edgeAgent.unpackAgentMsgFromConn[T](lastPayload.get.msg, connId)
  }
}


case class MockRestEnv(mockEnv: MockEnv) {
  lazy val myDID: DidStr = mockEnv.edgeAgent.myDIDDetail.did
  lazy val myDIDVerKey: VerKeyStr = mockEnv.edgeAgent.myDIDDetail.verKey
  lazy val myDIDSignature: String = computeSignature(myDIDVerKey)
  lazy val myDIDApiKey = s"$myDIDVerKey:$myDIDSignature"

  def connRelRoutingDID(connId: String): DidStr =
    mockEnv.edgeAgent.pairwiseConnDetail(connId).myPairwiseDidPair.did

  def connRelDIDApiKey(connId: String): String = {
    val pcd = mockEnv.edgeAgent.pairwiseConnDetail(connId).myPairwiseDidPair
    val signature = computeSignature(pcd.verKey)
    s"${pcd.verKey}:$signature"
  }

  def computeSignature(verKey: VerKeyStr): String = {
    val signedMsg = mockEnv.edgeAgent.testWalletAPI.executeSync[SignedMsg](
      SignMsg(KeyParam.fromVerKey(verKey), verKey.getBytes))(mockEnv.edgeAgent.wap)
    Base58Util.encode(signedMsg.msg)
  }
}