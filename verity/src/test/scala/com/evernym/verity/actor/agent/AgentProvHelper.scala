package com.evernym.verity.actor.agent

import java.util.UUID
import akka.testkit.TestKitBase
import com.evernym.verity.util2.Base64Encoded
import com.evernym.verity.util2.HasExecutionContextProvider
import com.evernym.verity.actor.agent.agency.GetLocalAgencyIdentity
import com.evernym.verity.actor.agent.msghandler.incoming.ProcessPackedMsg
import com.evernym.verity.actor.{AgencyPublicDid, agentRegion}
import com.evernym.verity.actor.testkit.checks.{UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog}
import com.evernym.verity.actor.testkit.{AgentSpecHelper, PersistentActorSpec}
import com.evernym.verity.actor.wallet.{CreateNewKey, NewKeyCreated, PackedMsg, SignMsg, SignedMsg}
import com.evernym.verity.did.{DidStr, VerKeyStr}
import com.evernym.verity.protocol.protocols.agentprovisioning.v_0_7.AgentProvisioningMsgFamily.{ProvisionToken, RequesterKeys}
import com.evernym.verity.testkit.{BasicSpec, TestWallet}
import com.evernym.verity.testkit.mock.agent.MockEdgeAgent
import com.evernym.verity.testkit.mock.agent.MockEnvUtil.buildMockEdgeAgent
import com.evernym.verity.util.{Base64Util, TimeUtil}
import com.evernym.verity.vault.KeyParam
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.concurrent.Eventually


trait AgentProvHelper
  extends BasicSpec
    with PersistentActorSpec
    with AgentSpecHelper
    with TestKitBase
    with Eventually
    with HasExecutionContextProvider {

  override def beforeAll(): Unit = {
    super.beforeAll()
    setupAgency()   //setup agency agent
  }

  s"when sent GetLocalAgencyDIDDetail command" - {
    "should respond with agency DID detail" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
      aa ! GetLocalAgencyIdentity()
      val dd = expectMsgType[AgencyPublicDid]
      mockEdgeAgent.handleFetchAgencyKey(dd)
    }
  }

  override lazy val mockEdgeAgent: MockEdgeAgent =
    buildMockEdgeAgent(mockAgencyAdmin, futureExecutionContext)

  def getNonce: String = UUID.randomUUID().toString

  def setAgencyAgent(ma: MockEdgeAgent): Unit = {
    mockAgencyAdmin.agencyPublicDid.foreach(ma.handleFetchAgencyKey)
  }

  val sponsorWallet = new TestWallet(futureExecutionContext, createWallet = true)

  def sponsorKeys(seed: String="000000000000000000000000Trustee1"): NewKeyCreated =
    sponsorWallet.executeSync[NewKeyCreated](CreateNewKey(seed=Some(seed)))

  def sponsorSig(nonce: String, id: String, sponsorId: String, vk: VerKeyStr, timestamp: String): Base64Encoded = {
    val signedMsg = sponsorWallet.executeSync[SignedMsg](
      SignMsg(KeyParam.fromVerKey(vk), (nonce + timestamp + id + sponsorId).getBytes())
    )
    Base64Util.getBase64Encoded(signedMsg.msg)
  }

  def newEdgeAgent(admin: MockEdgeAgent = mockAgencyAdmin): MockEdgeAgent = {
    buildMockEdgeAgent(admin, futureExecutionContext)
  }

  private def sendCreateAgent(sponsorRel: SponsorRel,
                              sponsorVk: VerKeyStr,
                              nonce: String,
                              agent: MockEdgeAgent,
                              timestamp: String,
                              isEdgeAgent: Boolean): SendCreateAgent = {
    setAgencyAgent(agent)
    val aa = agentRegion(agencyAgentEntityId, agencyAgentRegion)
    val requesterKeys = RequesterKeys(agent.myDIDDetail.did, agent.myDIDDetail.verKey)
    val requesterDetails = Some(ProvisionToken(
      sponsorRel.sponseeId,
      sponsorRel.sponsorId,
      nonce,
      timestamp,
      sponsorSig(nonce, id=sponsorRel.sponseeId, sponsorId=sponsorRel.sponsorId, vk=sponsorVk, timestamp),
      sponsorVk
    ))
    val createFn: (DidStr, RequesterKeys, Option[ProvisionToken]) => PackedMsg =
      if (isEdgeAgent) agent.v_0_7_req.prepareCreateEdgeAgentMsg
      else agent.v_0_7_req.prepareCreateAgentMsg

    val msg = createFn(
      agent.agencyAgentDetailReq.DID, requesterKeys, requesterDetails
    )
    aa ! ProcessPackedMsg(msg, reqMsgContext)
    SendCreateAgent(expectMsgType[PackedMsg], requesterKeys)
  }

  def sendCreateCloudAgent(sponsorRel: SponsorRel,
                           sponsorVk: VerKeyStr,
                           nonce: String,
                           agent: MockEdgeAgent,
                           timestamp: String): SendCreateAgent = {
    sendCreateAgent(sponsorRel, sponsorVk, nonce, agent, timestamp, isEdgeAgent = false)
  }

  def sendCreateEdgeAgent(sponsorRel: SponsorRel,
                          sponsorVk: VerKeyStr,
                          nonce: String,
                          agent: MockEdgeAgent,
                          timestamp: String): SendCreateAgent = {
    sendCreateAgent(sponsorRel, sponsorVk, nonce, agent, timestamp, isEdgeAgent = true)
  }


  private def createAgent(sponsorRel: SponsorRel,
                          sponsorVk: VerKeyStr,
                          nonce: String,
                          agent: MockEdgeAgent = newEdgeAgent(),
                          timestamp: String = TimeUtil.nowDateString,
                          isEdgeAgent: Boolean): DidStr = {
    val sentCreateMsg = sendCreateAgent(sponsorRel, sponsorVk, nonce, agent, timestamp, isEdgeAgent)
    val agentCreated = agent.v_0_7_resp.handleAgentCreatedResp(sentCreateMsg.msg)
    agentCreated.selfDID
  }

  def createCloudAgent(sponsorRel: SponsorRel,
                       sponsorVk: VerKeyStr,
                       nonce: String,
                       agent: MockEdgeAgent = newEdgeAgent(),
                       timestamp: String = TimeUtil.nowDateString): DidStr = {
    createAgent(sponsorRel, sponsorVk, nonce, agent, timestamp, isEdgeAgent = false)
  }

  def createEdgeAgent(sponsorRel: SponsorRel,
                      sponsorVk: VerKeyStr,
                      nonce: String,
                      agent: MockEdgeAgent = newEdgeAgent(),
                      timestamp: String = TimeUtil.nowDateString): DidStr = {
    createAgent(sponsorRel, sponsorVk, nonce, agent, timestamp, isEdgeAgent = true)
  }

  def overrideSpecificConfig: Option[Config] = None

  final override def overrideConfig: Option[Config] = Option {
    overrideSpecificConfig match {
      case Some(sc) => baseConfig.withFallback(sc)
      case None     => baseConfig
    }
  }


  def baseConfig: Config =
    ConfigFactory parseString {
      s"""
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

case class SendCreateAgent(msg: PackedMsg, requesterKeys: RequesterKeys)