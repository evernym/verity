package com.evernym.verity.protocol.protocols.issuersetup

import com.evernym.verity.actor.agent.MsgPackFormat.MPF_INDY_PACK
import com.evernym.verity.actor.agent.user.UserAgentSpecScaffolding
import com.evernym.verity.actor.agentRegion
import com.evernym.verity.actor.base.Done
import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreLog
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil._
import com.evernym.verity.protocol.engine.Constants.{MFV_0_6, MTV_1_0}
import com.evernym.verity.protocol.engine.MsgFamily.EVERNYM_QUALIFIER
import com.evernym.verity.protocol.engine.{DID, MsgType, VerKey}
import com.evernym.verity.testkit.agentmsg
import com.evernym.verity.testkit.agentmsg.AgentMsgPackagingContext

class IssuerSetupActorSpec extends UserAgentSpecScaffolding  {

  implicit val msgPackagingContext: AgentMsgPackagingContext =
    agentmsg.AgentMsgPackagingContext(MPF_INDY_PACK, MTV_1_0, packForAgencyRoute = false)

  override def beforeAll(): Unit = {
    super.beforeAll()
    val dd = setupAgency()
    mockEdgeAgent.handleFetchAgencyKey(dd)
  }

  userAgentBaseSpecs()
  updateComMethodSpecs()

  def alltests(ua: agentRegion, userDID: DID, userDIDVerKey: VerKey): Unit = {

    val msgIdOpt = Option(getNewMsgUniqueId)

    "An issuer" - {
      currentSetupTests("before identifier creation", MsgType(EVERNYM_QUALIFIER, MSG_FAMILY_ISSUER_SETUP, MFV_0_6, "problem-report"))
      createSetupTests()
      currentSetupTests("after identifier creation", MsgType(EVERNYM_QUALIFIER, MSG_FAMILY_ISSUER_SETUP, MFV_0_6, "public-identifier"))
    }

    def createSetupTests(): Unit = {
      "when sent Create message" - {
        "should receive PublicIdentifierCreated async response message" taggedAs (UNSAFE_IgnoreLog) in {
          val (resp, httpMsgOpt) = withExpectNewMsgAtRegisteredEndpoint {
            val wpm = mockEdgeAgent.prepareCreateDIDMsgForAgent(msgIdOpt)
            ua ! wrapAsPackedMsgParam(wpm)
            expectMsg(Done)
          }
          httpMsgOpt.isDefined shouldBe true
          val agentMsg = mockEdgeAgent.handleReceivedAgentMsg(httpMsgOpt.map(_.msg).get)
          agentMsg.headAgentMsgType shouldBe MsgType(EVERNYM_QUALIFIER, MSG_FAMILY_ISSUER_SETUP, MFV_0_6, "public-identifier-created")

        }
      }
    }

    def currentSetupTests(context: String, expectedRespMsg: MsgType): Unit = {
      s"when sent CurrentPublicIdentifier message ($context)" - {
        "should receive PublicIdentifier async response message" taggedAs (UNSAFE_IgnoreLog) in {
          val (resp, httpMsgOpt) = withExpectNewMsgAtRegisteredEndpoint {
            val wpm = mockEdgeAgent.prepareCurrentPublicIdentifierMsgForAgent(msgIdOpt)
            ua ! wrapAsPackedMsgParam(wpm)
            expectMsg(Done)
          }
          httpMsgOpt.isDefined shouldBe true
          val agentMsg = mockEdgeAgent.handleReceivedAgentMsg(httpMsgOpt.map(_.msg).get)
          agentMsg.headAgentMsgType shouldBe expectedRespMsg

        }
      }
    }
  }
}
