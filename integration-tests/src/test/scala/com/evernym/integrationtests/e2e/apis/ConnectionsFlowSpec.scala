//package com.evernym.integrationtests.e2e.apis
//
//import com.evernym.verity.config.ConfigWrapperBase
//import com.evernym.verity.protocol.engine.DID
//import com.evernym.verity.protocol.protocols.connections.v_1_0.Signal.{Complete, ConnRequestReceived, ConnRequestSent, ConnResponseSent}
//import com.evernym.integrationtests.e2e.flow._
//import com.evernym.integrationtests.e2e.scenario.Scenario.isRunScenario
//
//class ConnectionsFlowSpec
//  extends TestAgentSDKProvisioningFlow {
//
//  // We want to capture output from these tests during CI/CD pipeline builds
//  override def deleteFiles: Boolean = false
//
//  override lazy val config: ConfigWrapperBase = testEnv.config
//
//  var inviteUrl: String = _
//  var inviterPairwiseDID: DID = _
//  var inviteePairwiseDID: DID = _
//
//  if ( isRunScenario("Connections Workflow") ) {
//
//    "connections protocol" - {
//      "inviter prepares and sends invitation" in {
//        inviteUrl = inviterVerityEdgeAgent.sendPrepareWithKey()
//        //assumes invite is sent out of band
//        inviterVerityEdgeAgent.sendInvitedWithKeyNotif()
//      }
//
//      "invitee accepts invitation" in {
//        inviteeConsumerEdgeAgent.sendValidateInviteUrl(inviteUrl, inviterVerityEdgeAgent.connectionsThreadId)
//        inviteeConsumerEdgeAgent.acceptInvite("invitee")
//        val exchangeReqSent = inviteeConsumerEdgeAgent.expectMsgType(classOf[ConnRequestSent])
//        Option(exchangeReqSent.req).isDefined shouldBe true
//      }
//
//      "inviter received ExchangeRequest" in {
//        val exchangeReq = inviterVerityEdgeAgent.expectMsgType(classOf[ConnRequestReceived])
//        Option(exchangeReq.conn).isDefined shouldBe true
//      }
//
//      "inviter processed ExchangeRequest and sent ExchangeResponse" in {
//        val exchangeRespSent = inviterVerityEdgeAgent.expectMsgType(classOf[ConnResponseSent])
//        Option(exchangeRespSent.resp).isDefined shouldBe true
//      }
//
//      "invitee received and processed Exchange Response" in {
//        val complete = inviteeConsumerEdgeAgent.expectMsgType(classOf[Complete])
//        inviterPairwiseDID = complete.theirDid
//        Option(complete.theirDid).isDefined shouldBe true
//      }
//
//      "inviter received an acknowledgement" in {
//        val complete = inviterVerityEdgeAgent.expectMsgType(classOf[Complete])
//        inviteePairwiseDID = complete.theirDid
//        Option(complete.theirDid).isDefined shouldBe true
//      }
//    }
//  }
//}