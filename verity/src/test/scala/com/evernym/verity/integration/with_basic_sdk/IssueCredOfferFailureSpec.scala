package com.evernym.verity.integration.with_basic_sdk

import akka.http.scaladsl.model.StatusCodes.BadRequest
import com.evernym.verity.integration.base.{CAS, VAS, VerityProviderBaseSpec}
import com.evernym.verity.integration.base.sdk_provider.{HolderSdk, IssuerSdk, SdkProvider}
import com.evernym.verity.protocol.engine.ThreadId
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Ctl.Offer
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Msg.{OfferCred, ProblemReport}
import com.evernym.verity.protocol.protocols.writeSchema.{v_0_6 => writeSchema0_6}
import com.evernym.verity.protocol.protocols.writeCredentialDefinition.{v_0_6 => writeCredDef0_6}
import com.evernym.verity.testkit.util.HttpUtil
import org.json.JSONObject

import scala.concurrent.Await


class IssueCredOfferFailureSpec
  extends VerityProviderBaseSpec
    with SdkProvider {

  var issuerSDK: IssuerSdk = _
  var holderSDK: HolderSdk = _

  val issuerHolderConn = "connId1"

  var schemaId: SchemaId = _
  var credDefId: CredDefId = _
  var offerCred: OfferCred = _

  var lastReceivedThreadId: Option[ThreadId] = None

  override def beforeAll(): Unit = {
    super.beforeAll()

    val issuerVerityEnv = VerityEnvBuilder().buildAsync(VAS)
    val holderVerityEnv = VerityEnvBuilder().buildAsync(CAS)

    val issuerSDKFut = setupIssuerSdkAsync(issuerVerityEnv, executionContext)
    val holderSDKFut = setupHolderSdkAsync(holderVerityEnv, defaultSvcParam.ledgerTxnExecutor, defaultSvcParam.vdrTools, executionContext)

    issuerSDK = Await.result(issuerSDKFut, SDK_BUILD_TIMEOUT)
    holderSDK = Await.result(holderSDKFut, SDK_BUILD_TIMEOUT)

    provisionEdgeAgent(issuerSDK)
    provisionCloudAgent(holderSDK)

    setupIssuer_v0_6(issuerSDK)
    schemaId = writeSchema_v0_6(issuerSDK, writeSchema0_6.Write("name", "1.0", Seq("name", "age")))
    credDefId = writeCredDef_v0_6(issuerSDK, writeCredDef0_6.Write("name", schemaId, None, None))

    establishConnection(issuerHolderConn, issuerSDK, holderSDK)
  }

  lazy val offerMsg = Offer(
    credDefId,
    Map("name" -> "Alice", "age" -> "20")
  )

  "IssuerSDK" - {
    "sends 'offer' (issue-credential 1.0) message" - {

      "with non existent cre def id" - {
        "should fail" in {
          val offerMsg = Offer(
            "did:indy:sovrin:NzUByWvXNEh7FAx8B8axJz/anoncreds/v0/CLAIM_DEF/0/latest",
            Map("name" -> "Alice", "age" -> "20")
          )
          issuerSDK.sendMsgForConn(issuerHolderConn, offerMsg)
          val receivedMsg = issuerSDK.expectMsgOnWebhook[ProblemReport]().msg
          receivedMsg.resolveDescription shouldBe "unable to create credential offer - cred def 'did:indy:sovrin:NzUByWvXNEh7FAx8B8axJz/anoncreds/v0/CLAIM_DEF/0/latest' not found in wallet"
        }
      }

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
    val msg = HttpUtil.parseHttpResponseAsString(resp)(futureExecutionContext)
    msg.contains("error decoding object type") shouldBe true
  }
}
