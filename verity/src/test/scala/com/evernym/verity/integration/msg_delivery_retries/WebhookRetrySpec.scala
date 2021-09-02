package com.evernym.verity.integration.msg_delivery_retries

import akka.http.scaladsl.model.StatusCodes.{Accepted, InternalServerError, OK}
import com.evernym.verity.did.didcomm.v1.{Thread => MsgThread}
import com.evernym.verity.integration.base.sdk_provider.SdkProvider
import com.evernym.verity.integration.base.{VAS, VerityProviderBaseSpec}
import com.evernym.verity.protocol.protocols.relationship.v_1_0.Ctl.Create
import com.evernym.verity.protocol.protocols.relationship.v_1_0.Signal.{Created, Invitation}
import com.evernym.verity.protocol.protocols.updateConfigs.v_0_6.Ctl.Update
import com.evernym.verity.protocol.protocols.updateConfigs.v_0_6.Sig.ConfigResult
import com.evernym.verity.protocol.protocols.updateConfigs.v_0_6.{Config => AgentConfig}
import com.evernym.verity.util.TestExecutionContextProvider
import com.evernym.verity.util2.ExecutionContextProvider
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Seconds, Span}

import java.util.UUID
import scala.concurrent.ExecutionContext

class WebhookRetrySpec
  extends VerityProviderBaseSpec
    with SdkProvider
    with Eventually {

  lazy val ecp = TestExecutionContextProvider.ecp
  lazy val executionContext: ExecutionContext = ecp.futureExecutionContext

  lazy val issuerVerityEnv = VerityEnvBuilder.default().withConfig(REST_API_CONFIG).build(VAS)

  lazy val issuerRestSDK = setupIssuerRestSdk(issuerVerityEnv, executionContext, ecp.walletFutureExecutionContext)

  override def beforeAll(): Unit = {
    super.beforeAll()
    issuerRestSDK.fetchAgencyKey()
    issuerRestSDK.provisionVerityEdgeAgent()    //this sends a packed message (not REST api call)
    issuerRestSDK.registerWebhook()
  }

  var lastReceivedThread: Option[MsgThread] = None
  val firstConn = "connId1"
  var firstInvitation: Invitation = _

  "IssuerRestSdk" - {
    "when sent POST update (update config 0.6) message" - {
      "should be successful" in {
        val lastThreadId = Option(MsgThread(Option(UUID.randomUUID().toString)))
        val msg = Update(Set(AgentConfig("name", "env-name"), AgentConfig("logoUrl", "env-logo-url")))
        val response = issuerRestSDK.sendMsg(msg, lastThreadId)
        response.status shouldBe Accepted
        val receivedMsgParam = issuerRestSDK.expectMsgOnWebhook[ConfigResult]()
        receivedMsgParam.msg.configs.size shouldBe 2
      }
    }

    "when sent POST create (relationship 1.0) message" - {
      "should be successful" in {
        issuerRestSDK.msgListener.setResponseCode(InternalServerError.copy(500)
        ("purposeful failure", "purposefully throwing exception (testing webhook failed msg retries)"))
        val resp = issuerRestSDK.sendMsg(Create(None, None))
        resp.status shouldBe Accepted
        eventually(timeout(Span(20, Seconds)), interval(Span(1, Seconds))) {
          issuerRestSDK.msgListener.getResponseCodeCount(InternalServerError) shouldBe 1
        }
        issuerRestSDK.msgListener.setResponseCode(OK)   //webhook will purposefully send 200 OK
        issuerRestSDK.expectMsgOnWebhook[Created]()
      }
    }
  }

  val REST_API_CONFIG: Config =
    ConfigFactory.parseString(
      """
         verity.rest-api.enabled = true
        """.stripMargin
    )

  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = executionContext

  override def executionContextProvider: ExecutionContextProvider = ecp
}
