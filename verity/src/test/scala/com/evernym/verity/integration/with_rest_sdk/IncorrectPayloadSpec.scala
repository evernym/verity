package com.evernym.verity.integration.with_rest_sdk

import akka.http.scaladsl.model.StatusCodes.BadRequest
import com.evernym.verity.Status.INVALID_VALUE
import com.evernym.verity.http.route_handlers.open.RestErrorResponse
import com.evernym.verity.integration.base.VerityProviderBaseSpec
import com.evernym.verity.integration.base.sdk_provider.SdkProvider
import com.evernym.verity.protocol.engine.ThreadId
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Ctl.Offer
import com.evernym.verity.protocol.protocols.writeSchema.{v_0_6 => writeSchema0_6}
import com.evernym.verity.protocol.protocols.writeCredentialDefinition.{v_0_6 => writeCredDef0_6}
import com.typesafe.config.{Config, ConfigFactory}
import org.json.JSONObject


class IncorrectPayloadSpec
  extends VerityProviderBaseSpec
    with SdkProvider {

  lazy val issuerVerityEnv = setupNewVerityEnv(overriddenConfig = VAS_OVERRIDE_CONFIG)
  lazy val holderVerityEnv = setupNewVerityEnv()

  lazy val issuerRestSDK = setupIssuerRestSdk(issuerVerityEnv)
  lazy val holderSDK = setupHolderSdk(holderVerityEnv, defaultSvcParam.ledgerSvcParam.ledgerTxnExecutor)

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
          jsonObject.put("auto_issue", "true").toString
        }
        val httpResp = issuerRestSDK.sendMsgForConn(issuerHolderConn, credOffer, applyToJsonMsg = jsonModifier, expectedRespStatus = BadRequest)
        val restErrorResp = issuerRestSDK.parseHttpResponseAs[RestErrorResponse](httpResp)
        restErrorResp shouldBe RestErrorResponse(INVALID_VALUE.statusCode, "field 'auto_issue' has invalid value")
      }
    }
  }

  val VAS_OVERRIDE_CONFIG: Option[Config] = Option {
    ConfigFactory.parseString(
      """
       verity.rest-api.enabled = true
      """.stripMargin
    )
  }
}
