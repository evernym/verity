package com.evernym.verity.integration.with_basic_sdk

import akka.http.scaladsl.model.StatusCodes.BadRequest
import com.evernym.verity.integration.base.{CAS, VAS, VerityProviderBaseSpec}
import com.evernym.verity.integration.base.sdk_provider.{HolderSdk, IssuerSdk, SdkProvider}
import com.evernym.verity.protocol.engine.ThreadId
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Ctl.Offer
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Msg.OfferCred
import com.evernym.verity.protocol.protocols.writeSchema.{v_0_6 => writeSchema0_6}
import com.evernym.verity.protocol.protocols.writeCredentialDefinition.{v_0_6 => writeCredDef0_6}
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.util.TestExecutionContextProvider
import org.json.JSONObject

import scala.concurrent.{Await, ExecutionContext}


class IssueCredOfferFailureSpec
  extends VerityProviderBaseSpec
    with SdkProvider {

  lazy val ecp = TestExecutionContextProvider.ecp
  lazy val executionContext: ExecutionContext = ecp.futureExecutionContext

  var issuerSDK: IssuerSdk = _
  var holderSDK: HolderSdk = _

  val issuerHolderConn = "connId1"

  var schemaId: SchemaId = _
  var credDefId: CredDefId = _
  var offerCred: OfferCred = _

  var lastReceivedThreadId: Option[ThreadId] = None

  override def beforeAll(): Unit = {
    super.beforeAll()

    val issuerVerityEnv = VerityEnvBuilder.default().buildAsync(VAS)
    val holderVerityEnv = VerityEnvBuilder.default().buildAsync(CAS)

    val issuerSDKFut = setupIssuerSdkAsync(issuerVerityEnv, executionContext)
    val holderSDKFut = setupHolderSdkAsync(holderVerityEnv, defaultSvcParam.ledgerTxnExecutor, executionContext)

    issuerSDK = Await.result(issuerSDKFut, SDK_BUILD_TIMEOUT)
    holderSDK = Await.result(holderSDKFut, SDK_BUILD_TIMEOUT)

    provisionEdgeAgent(issuerSDK)
    provisionCloudAgent(holderSDK)

    setupIssuer(issuerSDK)
    schemaId = writeSchema(issuerSDK, writeSchema0_6.Write("name", "1.0", Seq("name", "age")))
    credDefId = writeCredDef(issuerSDK, writeCredDef0_6.Write("name", schemaId, None, None))

    establishConnection(issuerHolderConn, issuerSDK, holderSDK)
  }

  lazy val offerMsg = Offer(
    credDefId,
    Map("name" -> "Alice", "age" -> "20")
  )

  "IssuerSDK" - {
    "sends 'offer' (issue-credential 1.0) message" - {

      "with invalid cred attribute name" - {
        "should fail" in {
          val jsonModifier: String => String = { msg =>
            val jsonObject = new JSONObject(msg)
            jsonObject.put("credential_values", """{null:"Alice"}""").toString
          }
          sendInvalidOfferMsg(offerMsg, jsonModifier)
        }
      }

      "with invalid cred attribute value" - {
        "should fail" in {
          val jsonModifier: String => String = { msg =>
            val jsonObject = new JSONObject(msg)
            jsonObject.put("credential_values", """{"name":null}""").toString
          }
          sendInvalidOfferMsg(offerMsg, jsonModifier)
        }
      }

      "with empty cred values" - {
        "should fail" in {
          val jsonModifier: String => String = { msg =>
            val jsonObject = new JSONObject(msg)
            jsonObject.put("credential_values", """""").toString
          }
          sendInvalidOfferMsg(offerMsg, jsonModifier)
        }
      }

    }
  }

  private def sendInvalidOfferMsg(offerMsg: Offer, jsonModifier: String => String): Unit = {
    val resp = issuerSDK.sendMsgForConn(issuerHolderConn, offerMsg,
      applyToJsonMsg = jsonModifier, expectedRespStatus = BadRequest)
    val msg = issuerSDK.parseHttpResponseAsString(resp)
    msg.contains("error decoding object type") shouldBe true
  }

  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = executionContext

  override def executionContextProvider: ExecutionContextProvider = ecp
}
