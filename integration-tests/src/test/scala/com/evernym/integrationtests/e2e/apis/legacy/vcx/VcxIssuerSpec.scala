package com.evernym.integrationtests.e2e.apis.legacy.vcx

import com.evernym.integrationtests.e2e.apis.legacy.LegacyApiFlowBaseSpec
import com.evernym.integrationtests.e2e.env.VerityInstance
import com.evernym.integrationtests.e2e.scenario.Scenario
import com.evernym.sdk.vcx.connection.ConnectionApi
import com.evernym.sdk.vcx.utils.UtilsApi
import com.evernym.sdk.vcx.vcx.VcxApi
import com.evernym.verity.agentmsg.msgcodec.jackson.JacksonMsgCodec
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil.CREATE_MSG_TYPE_CONN_REQ_ANSWER
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.protocol.protocols.connecting.common.{AgentKeyDlgProof, InviteDetail, InviteDetailAbbreviated, SenderAgencyDetail, SenderDetail}
import com.evernym.verity.testkit.agentmsg.{InviteAcceptedResp_MFV_0_5, MsgBasicDetail}
import com.evernym.verity.testkit.util.AssertionUtil.expectMsgType
import com.typesafe.scalalogging.Logger
import org.json.JSONObject
import org.scalatest.time.{Millis, Seconds, Span}

import java.util.UUID


class VcxIssuerSpec
  extends LegacyApiFlowBaseSpec {

  override val logger: Logger = getLoggerByClass(getClass)

  def appNameCAS: String = APP_NAME_CAS_1
  def appNameEAS: String = APP_NAME_EAS_1

  val connId = "connId1"

  implicit val scenario: Scenario = Scenario(
    "Legacy api testing",
    requiredAppInstances,
    suiteTempDir,
    projectDir,
    connIds = Set(connId)
  )

  val enterprise = new EntAgentOwner(scenario, enterpriseAgencyEndpoint)
  val user = new UserAgentOwner(scenario, consumerAgencyEndpoint)

  var connectionHandle: Integer = -1
  var invitation: InviteDetail = _

  "Issuer" - {

    "when tried to initialize vcx" - {
      "should be successful" in {
        initIssuerVcx(eas)
      }
    }

    "when tried to create invitation" - {
      "should be successful" in {
        connectionHandle = ConnectionApi.vcxConnectionCreate("source-id-1").get()
        val jsonInviteDetail = ConnectionApi.vcxConnectionConnect(connectionHandle, "{}").get
        ConnectionApi.connectionGetState(connectionHandle).get
        val abbreviatedInvite = JacksonMsgCodec.fromJson[InviteDetailAbbreviated](jsonInviteDetail)
        invitation = InviteDetailHelper.fromAbbreviated(abbreviatedInvite)
      }
    }
  }

  "A holder" - {

    "when tried to provision an agent" - {
      "should be successful" - {
        user.setupTillAgentCreation(scenario, agencyAdminEnv)
      }
    }

    "when tried to accept invitation" - {
      "should be successful" in {
        answerInvite(connId)
      }
    }

    "Issuer" - {
      "when checking for invite answer message" - {
        "should be successful" in {
          eventually(timeout(Span(120, Seconds)), interval(Span(100, Millis))) {
            ConnectionApi.vcxConnectionUpdateState(connectionHandle).get
            val curState = ConnectionApi.connectionGetState(connectionHandle).get
            curState shouldBe 4
          }
        }
      }
    }
  }

  private def answerInvite(connId: String): Unit = {
    user.createPairwiseKey_MFV_0_5(connId)
    val pcd = user.getPairwiseConnDetail(connId)
    pcd.setTheirPairwiseDidPair(invitation.senderDetail.DID, invitation.senderDetail.verKey)
    val iar = expectMsgType[InviteAcceptedResp_MFV_0_5](user.answerInviteForConn(connId, invitation))
    user.addToMsgs(connId, CLIENT_MSG_UID_CONN_REQ_ANSWER_1,
      MsgBasicDetail(iar.mc.uid, CREATE_MSG_TYPE_CONN_REQ_ANSWER, Option(invitation.connReqId)))
    waitForMsgToBeDelivered(scenario.restartMsgWait)
  }

  private def initIssuerVcx(eas: VerityInstance): Unit = {
    val walletName = "wallet-name"
    val walletKey = "test-password"

    val provisionConfig: JSONObject = new JSONObject()
      .put("agency_url", eas.endpoint.toString)
      .put("agency_did", agencyAdminEnv.enterpriseAgencyAdmin.agencyIdentity.DID)
      .put("agency_verkey", agencyAdminEnv.enterpriseAgencyAdmin.agencyIdentity.verKey)
      .put("wallet_name", walletName)
      .put("wallet_key", walletKey)
      .put("pool_name", UUID.randomUUID().toString)
      .put("name", s"verity-integration-test")
      .put("logo", s"https://robohash.org/${UUID.randomUUID()}.png")
      .put("path", testEnv.ledgerConfig.genesisFilePath)
      .put("protocol_type", "1.0")

    val config = new JSONObject(UtilsApi.vcxAgentProvisionAsync(provisionConfig.toString()).get())

    VcxApi.vcxInitWithConfig(config.toString()).get()
  }
}

object InviteDetailHelper {
  def fromAbbreviated(aid: InviteDetailAbbreviated): InviteDetail = {
    InviteDetail(
      aid.id,
      aid.t,
      SenderAgencyDetail(aid.sa.d, aid.sa.v, aid.sa.e),
      SenderDetail(aid.s.d, aid.s.v,
        aid.s.dp.map(ap => AgentKeyDlgProof(ap.d, ap.k, ap.s)),
        aid.s.n, aid.s.l, aid.s.publicDID
      ),
      aid.sc,
      aid.sm,
      aid.version
    )
  }
}
