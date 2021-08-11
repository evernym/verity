package com.evernym.verity.integration.with_rest_api

import akka.http.scaladsl.model.StatusCodes.BadRequest
import com.evernym.verity.util2.Status.INVALID_VALUE
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.http.route_handlers.open.RestErrorResponse
import com.evernym.verity.integration.base.{CAS, VAS, VerityProviderBaseSpec}
import com.evernym.verity.integration.base.sdk_provider.SdkProvider
import com.evernym.verity.protocol.engine.ThreadId
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Ctl.Offer
import com.evernym.verity.protocol.protocols.writeSchema.{v_0_6 => writeSchema0_6}
import com.evernym.verity.protocol.protocols.writeCredentialDefinition.{v_0_6 => writeCredDef0_6}
import com.evernym.verity.util.TestExecutionContextProvider
import com.typesafe.config.{Config, ConfigFactory}
import org.json.JSONObject

import scala.concurrent.ExecutionContext


class IncorrectPayloadSpec
  extends VerityProviderBaseSpec
    with SdkProvider {

  lazy val ecp = TestExecutionContextProvider.ecp
  lazy val executionContext: ExecutionContext = ecp.futureExecutionContext

  lazy val issuerVerityEnv = VerityEnvBuilder.default().withConfig(VAS_OVERRIDE_CONFIG).build(VAS)
  lazy val holderVerityEnv = VerityEnvBuilder.default().build(CAS)

  lazy val issuerRestSDK = setupIssuerRestSdk(issuerVerityEnv, executionContext, ecp.walletFutureExecutionContext)
  lazy val holderSDK = setupHolderSdk(holderVerityEnv, defaultSvcParam.ledgerTxnExecutor, executionContext, ecp.walletFutureExecutionContext)

  val issuerHolderConn = "connId1"
  var schemaId: SchemaId = _
  var credDefId: CredDefId = _

  var lastReceivedThreadId: Option[ThreadId] = None

  override def beforeAll(): Unit = {
    super.beforeAll()
    provisionEdgeAgent(issuerRestSDK)
    provisionCloudAgent(holderSDK)
    setupIssuer(issuerRestSDK)

    schemaId = writeSchema(issuerRestSDK, writeSchema0_6.Write("name", "1.0", Seq("name", "age")))
    credDefId = writeCredDef(issuerRestSDK, writeCredDef0_6.Write("name", schemaId, None, None))

    establishConnection(issuerHolderConn, issuerRestSDK, holderSDK)
  }

  "IssuerSdk" - {
    "when tried to send cred-offer with incorrect payload" - {
      "should respond with failure" in {
        val credOffer = Offer(
          credDefId,
          Map("name" -> "Alice", "age" -> "20")
        )
        val jsonModifier: (String => String) = { msg =>
          val jsonObject = new JSONObject(msg)
          jsonObject.put("auto_issue", "true").toString   //sending string "true" instead of boolean true
        }
        val httpResp = issuerRestSDK.sendMsgForConn(issuerHolderConn, credOffer, applyToJsonMsg = jsonModifier, expectedRespStatus = BadRequest)
        val restErrorResp = issuerRestSDK.parseHttpResponseAs[RestErrorResponse](httpResp)
        restErrorResp shouldBe RestErrorResponse(INVALID_VALUE.statusCode, "field 'auto_issue' has invalid value")
      }
    }
  }

  val VAS_OVERRIDE_CONFIG: Config =
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
