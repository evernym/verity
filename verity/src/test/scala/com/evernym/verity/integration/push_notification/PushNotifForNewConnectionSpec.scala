//package com.evernym.verity.integration.push_notification
//
//import com.evernym.verity.did.didcomm.v1.{Thread => MsgThread}
//import com.evernym.verity.agentmsg.msgfamily.ConfigDetail
//import com.evernym.verity.agentmsg.msgfamily.configs.UpdateConfigReqMsg
//import com.evernym.verity.integration.base.{CAS, VAS, VerityProviderBaseSpec}
//import com.evernym.verity.integration.base.sdk_provider.{HolderSdk, IssuerSdk, SdkProvider, V1OAuthParam}
//import com.evernym.verity.integration.base.verity_provider.VerityEnv
//import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.Ctl.AskQuestion
//import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.Msg.Question
//import com.evernym.verity.protocol.protocols.relationship.v_1_0.Signal.Invitation
//import com.evernym.verity.util.TestExecutionContextProvider
//import com.evernym.verity.util2.ExecutionContextProvider
//
//import scala.concurrent.{Await, ExecutionContext}
//import scala.concurrent.duration._
//
//
//class PushNotifForNewConnectionSpec
//  extends VerityProviderBaseSpec
//  with SdkProvider {
//
//  lazy val ecp = TestExecutionContextProvider.ecp
//  lazy val executionContext: ExecutionContext = ecp.futureExecutionContext
//
//  var issuerSDK: IssuerSdk = _
//  var holderSDK: HolderSdk = _
//  var issuerVerityEnv: VerityEnv = _
//  var holderVerityEnv: VerityEnv = _
//
//  val firstConn = "connId1"
//  var firstInvitation: Invitation = _
//
//  override def beforeAll(): Unit = {
//    super.beforeAll()
//
//
//    val issuerVerityEnvFut = VerityEnvBuilder.default().buildAsync(VAS)
//    val holderVerityEnvFut = VerityEnvBuilder.default().buildAsync(CAS)
//
//    val issuerSDKFut = setupIssuerSdkAsync(issuerVerityEnvFut, executionContext, Option(V1OAuthParam(5.seconds)))
//    val holderSDKFut = setupHolderSdkAsync(holderVerityEnvFut, defaultSvcParam.ledgerTxnExecutor, executionContext)
//
//    issuerVerityEnv = Await.result(issuerVerityEnvFut, ENV_BUILD_TIMEOUT)
//    holderVerityEnv = Await.result(holderVerityEnvFut, ENV_BUILD_TIMEOUT)
//
//    issuerSDK = Await.result(issuerSDKFut, SDK_BUILD_TIMEOUT)
//    holderSDK = Await.result(holderSDKFut, SDK_BUILD_TIMEOUT)
//
//    issuerSDK.resetPlainMsgsCounter.plainMsgsBeforeLastReset shouldBe 0
//    issuerSDK.fetchAgencyKey()
//    issuerSDK.provisionVerityEdgeAgent()
//    issuerSDK.registerWebhookWithoutOAuth()
//    issuerSDK.registerWebhook()
//    issuerSDK.sendUpdateConfig(UpdateConfigReqMsg(Set(ConfigDetail("name", "issuer-name"), ConfigDetail("logoUrl", "issuer-logo-url"))))
//    val receivedMsg = issuerSDK.sendCreateRelationship(firstConn, Option("issuer-name-conn1"))
//    firstInvitation = issuerSDK.sendCreateConnectionInvitation(firstConn, receivedMsg.threadOpt)
//
//    holderSDK.fetchAgencyKey()
//    holderSDK.provisionVerityCloudAgent()
//    holderSDK.sendCreateNewKey(firstConn)
//    holderSDK.sendConnReqForInvitation(firstConn, firstInvitation)
//
//    issuerSDK.expectConnectionComplete(firstConn)
//  }
//
//  override def executionContextProvider: ExecutionContextProvider = ecp
//  override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext
//
//  "IssuerSDK" - {
//    "when tried to send 'ask-question' (questionanswer 1.0) message" - {
//      "should be successful" in {
//        issuerSDK.sendMsgForConn(firstConn, AskQuestion("How are you?", Option("detail"),
//          Vector("I am fine", "I am not fine"), signature_required = false, None))
//      }
//    }
//  }
//
//  var lastReceivedMsgThread: Option[MsgThread] = None
//
//  "HolderSDK" - {
//    "when tried to check push notification" - {
//      "should receive it" in {
//
//      }
//    }
//  }
//}