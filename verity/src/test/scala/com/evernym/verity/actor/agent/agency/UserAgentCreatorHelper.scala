package com.evernym.verity.actor.agent.agency

import java.util.UUID

import com.evernym.verity.Base64Encoded
import com.evernym.verity.actor.agent.SponsorRel
import com.evernym.verity.actor.agent.agency.agent_provisioning.AgencyAgentPairwiseSpecBase
import com.evernym.verity.actor.agent.msghandler.incoming.ProcessPackedMsg
import com.evernym.verity.actor.wallet.{CreateNewKey, NewKeyCreated, PackedMsg, SignMsg, SignedMsg}
import com.evernym.verity.actor.{AgencyPublicDid, agentRegion}
import com.evernym.verity.protocol.engine.{DID, VerKey}
import com.evernym.verity.protocol.protocols.agentprovisioning.v_0_7.AgentProvisioningMsgFamily.{ProvisionToken, RequesterKeys}
import com.evernym.verity.testkit.TestWallet
import com.evernym.verity.testkit.mock.agent.MockEdgeAgent
import com.evernym.verity.testkit.mock.agent.MockEnvUtil._
import com.evernym.verity.util.{Base64Util, TimeUtil}
import com.evernym.verity.vault.KeyParam
import com.typesafe.config.{Config, ConfigFactory}

trait UserAgentCreatorHelper
  extends AgencyAgentPairwiseSpecBase {

  lazy val aap: agentRegion = agentRegion(agencyAgentPairwiseEntityId, agencyAgentPairwiseRegion)

  val sponsorWallet = new TestWallet(createWallet = true)

  def sponsorKeys(seed: String="000000000000000000000000Trustee1"): NewKeyCreated =
    sponsorWallet.executeSync[NewKeyCreated](CreateNewKey(seed=Some(seed)))

  def sponsorSig(nonce: String, id: String, sponsorId: String, vk: VerKey, timestamp: String): Base64Encoded = {
    val signedMsg = sponsorWallet.executeSync[SignedMsg](
      SignMsg(KeyParam.fromVerKey(vk), (nonce + timestamp + id + sponsorId).getBytes())
    )
    Base64Util.getBase64Encoded(signedMsg.msg)
  }

  override def beforeAll(): Unit = {
    super.beforeAll()
    setupAgency()
  }

  override def overrideConfig: Option[Config] = Option {
    ConfigFactory parseString {
      s"""
      verity.metrics {
        protocol {
          tags {
            uses-sponsor = true
            uses-sponsee = true
          }
        }
        activity-tracking {
          active-user {
            time-windows = ["15 d", "30 d", "7 d", "1 d", "9 min", "2 d", "3 d"]
            monthly-window = true
            enabled = true
          }
          active-relationships {
            time-windows = ["7 d"]
            monthly-window = true
            enabled = true
          }
        }
      }
      verity.provisioning {
        sponsors = [
          {
            name = "inactive"
            id = "inactive"
            keys = [{"verKey": "GJ1SzoWzavQYfNL9XkaJdrQejfztN4XqdsiV4ct3LXKL"}]
            endpoint = "localhost:3456/json-msg"
            active = false
          },
          {
            name = "evernym-test-sponsor1.1"
            id = "sponsor1.1"
            keys = [{"verKey": "GJ1SzoWzavQYfNL9XkaJdrQejfztN4XqdsiV4ct3LXKL"}]
            endpoint = "localhost:3456/json-msg"
            active = true
          },
          {
            name = "evernym-test-sponsor"
            id = "sponsor1"
            keys = [{"verKey": "GJ1SzoWzavQYfNL9XkaJdrQejfztN4XqdsiV4ct3LXKL"}]
            endpoint = "localhost:3456/json-msg"
            active = true
          },
          {
            name = "evernym-test-sponsor2"
            id = "sponsor2"
            keys = [{"verKey": "GJ1SzoWzavQYfNL9XkaJdrQejfztN4XqdsiV4ct3LXKL"}]
            endpoint = "localhost:3456/json-msg"
            active = true
          },
          {
            name = "evernym-test-sponsor3"
            id = "sponsor3"
            keys = [{"verKey": "GJ1SzoWzavQYfNL9XkaJdrQejfztN4XqdsiV4ct3LXKL"}]
            endpoint = "localhost:3456/json-msg"
            active = true
          },
          {
            name = "evernym-test-sponsor4"
            id = "sponsor4"
            keys = [{"verKey": "GJ1SzoWzavQYfNL9XkaJdrQejfztN4XqdsiV4ct3LXKL"}]
            endpoint = "localhost:3456/json-msg"
            active = true
          },
          {
            name = "evernym-test-sponsor4"
            id = "sponsor5"
            keys = [{"verKey": "GJ1SzoWzavQYfNL9XkaJdrQejfztN4XqdsiV4ct3LXKL"}]
            endpoint = "localhost:3456/json-msg"
            active = true
          },
          {
            name = "evernym-test-sponsor4"
            id = "sponsor6"
            keys = [{"verKey": "GJ1SzoWzavQYfNL9XkaJdrQejfztN4XqdsiV4ct3LXKL"}]
            endpoint = "localhost:3456/json-msg"
            active = true
          },
          {
            name = "evernym-test-sponsor4"
            id = "sponsor7"
            keys = [{"verKey": "GJ1SzoWzavQYfNL9XkaJdrQejfztN4XqdsiV4ct3LXKL"}]
            endpoint = "localhost:3456/json-msg"
            active = true
          },
          {
            name = "evernym-test-sponsor4"
            id = "sponsor8"
            keys = [{"verKey": "GJ1SzoWzavQYfNL9XkaJdrQejfztN4XqdsiV4ct3LXKL"}]
            endpoint = "localhost:3456/json-msg"
            active = true
          },
        ]
        sponsor-required = true
        token-window = 10 minute
        cache-used-tokens = true
    }
    """
    }
  }

  def getNonce: String = UUID.randomUUID().toString



  def newEdgeAgent(admin: MockEdgeAgent = mockAgencyAdmin): MockEdgeAgent = buildMockEdgeAgent(admin)

  def agencyAgentPairwiseSetup(edgeAgent: MockEdgeAgent): Unit = {
    var pairwiseDID: DID = null

    aa ! GetLocalAgencyIdentity()
    val dd = expectMsgType[AgencyPublicDid]
    edgeAgent.handleFetchAgencyKey(dd)
    val msg = edgeAgent.v_0_6_req.prepareConnectCreateKey(
      edgeAgent.myDIDDetail.did, edgeAgent.myDIDDetail.verKey, edgeAgent.agencyAgentDetailReq.DID
    )
    aa ! ProcessPackedMsg(msg, reqMsgContext)
    val pm = expectMsgType[PackedMsg]
    val resp = edgeAgent.v_0_6_resp.handleConnectKeyCreatedResp(pm)
    pairwiseDID = resp.withPairwiseDID
    setPairwiseEntityId(pairwiseDID)
  }

  def sendCreateAgent(sponsorRel: SponsorRel,
                      sponsorVk: VerKey,
                      nonce: String,
                      agent: MockEdgeAgent,
                      timestamp: String,
                      isEdgeAgent: Boolean=false): SendCreateAgent = {
    agencyAgentPairwiseSetup(agent)
    val aap = agentRegion(agencyAgentPairwiseEntityId, agencyAgentPairwiseRegion)
    val requesterKeys = RequesterKeys(agent.myDIDDetail.did, agent.myDIDDetail.verKey)
    val requesterDetails = Some(ProvisionToken(
      sponsorRel.sponseeId,
      sponsorRel.sponsorId,
      nonce,
      timestamp,
      sponsorSig(nonce, id=sponsorRel.sponseeId, sponsorId=sponsorRel.sponsorId, vk=sponsorVk, timestamp),
      sponsorVk
    ))

    val createFn: (DID, RequesterKeys, Option[ProvisionToken]) => PackedMsg =
      if(isEdgeAgent) agent.v_0_7_req.prepareCreateEdgeAgentMsg
      else agent.v_0_7_req.prepareCreateAgentMsg

    val msg = createFn(
      agent.agencyPairwiseAgentDetailReq.DID, requesterKeys, requesterDetails
    )
    aap ! ProcessPackedMsg(msg, reqMsgContext)
    SendCreateAgent(expectMsgType[PackedMsg], requesterKeys)
  }

  def createCloudAgent(sponsorRel: SponsorRel,
                       sponsorVk: VerKey,
                       nonce: String,
                       agent: MockEdgeAgent=newEdgeAgent(),
                       timestamp: String=TimeUtil.nowDateString,
                       isEdgeAgent: Boolean=false): DID = {
    val sentCreateMsg = sendCreateAgent(sponsorRel, sponsorVk, nonce, agent, timestamp, isEdgeAgent)
    val agentCreated = agent.v_0_7_resp.handleAgentCreatedResp(sentCreateMsg.msg)
    agentCreated.selfDID
  }

  case class SendCreateAgent(msg: PackedMsg, requesterKeys: RequesterKeys)
}
