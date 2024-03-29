package com.evernym.verity.integration.features.webhook

import com.evernym.verity.agentmsg.msgfamily.configs.{ComMethod, ComMethodAuthentication, UpdateComMethodReqMsg}
import com.evernym.verity.constants.Constants.COM_METHOD_TYPE_HTTP_ENDPOINT
import com.evernym.verity.integration.base.sdk_provider.{HolderSdk, IssuerSdk, SdkProvider, V1OAuthParam}
import com.evernym.verity.integration.base.{CAS, VAS, VerityProviderBaseSpec}
import com.evernym.verity.protocol.protocols.relationship.v_1_0.Signal.Invitation

import scala.concurrent.Await
import scala.concurrent.duration._


class ComMethodUpdateSpec
  extends VerityProviderBaseSpec
    with SdkProvider {

  var issuerSDK: IssuerSdk = _
  var holderSDK: HolderSdk = _

  val firstConn = "connId1"
  var firstInvitation: Invitation = _

  override protected def beforeAll(): Unit = {
    super.beforeAll()

    val issuerVerityEnv = VerityEnvBuilder().buildAsync(VAS)
    val holderVerityEnv = VerityEnvBuilder().buildAsync(CAS)

    val issuerSDKFut = setupIssuerSdkAsync(issuerVerityEnv, executionContext, Option(V1OAuthParam(5.seconds)))
    val holderSDKFut = setupHolderSdkAsync(holderVerityEnv, V1OAuthParam(5.seconds), executionContext)

    issuerSDK = Await.result(issuerSDKFut, SDK_BUILD_TIMEOUT)
    holderSDK = Await.result(holderSDKFut, SDK_BUILD_TIMEOUT)
  }

  "IssuerSDK" - {

    "when tried to setup issuer" - {
      "should be successful" in {
        issuerSDK.fetchAgencyKey()
        issuerSDK.provisionVerityEdgeAgent()
        issuerSDK.registerWebhook()
      }
    }

    "when tried to add com method with unsupported authentication type" - {
      "should respond with error" in {
        val ex = intercept[RuntimeException] {
          issuerSDK.registerWebhook(authentication=
            Option(
              ComMethodAuthentication(
                "OAuth1",
                "v1",
                Map(
                  "url" -> "url",
                  "grant_type" -> "client_credentials",
                  "client_id" -> "client_id",
                  "client_secret" -> "client_secret"
                )
              )
            )
          )
        }
        ex.getMessage.contains("authentication type not supported") shouldBe true
      }
    }

    "when tried to add com method with unsupported authentication version" - {
      "should respond with error" in {
        val ex = intercept[RuntimeException] {
          issuerSDK.registerWebhook(authentication=
            Option(
              ComMethodAuthentication(
                "OAuth2",
                "v3",
                Map(
                  "url" -> "url",
                  "grant_type" -> "client_credentials",
                  "client_id" -> "client_id",
                  "client_secret" -> "client_secret"
                )
              )
            )
          )
        }
        ex.getMessage.contains("authentication version not supported") shouldBe true
      }
    }

    "when tried to add v1 OAuthed com method without sufficient data" - {
      "should respond with error" in {
        val invalidData = Seq(
          Map("grant_type" -> "client_credentials", "client_id" -> "client_id", "client_secret" -> "client_secret"),
          Map("url" -> "url", "client_id" -> "client_id", "client_secret" -> "client_secret"),
          Map("url" -> "url", "grant_type" -> "client_credentials", "client_secret" -> "client_secret"),
          Map("url" -> "url", "grant_type" -> "client_credentials", "client_id" -> "client_id")
        )
        invalidData.foreach { data =>
          val ex = intercept[RuntimeException] {
            issuerSDK.registerWebhook(authentication=
              Option(
                ComMethodAuthentication(
                  "OAuth2",
                  "v1",
                  data
                )
              )
            )
          }
          ex.getMessage.contains("required fields missing") shouldBe true
        }
      }
    }

    "when tried to add v2 OAuthed com method without sufficient data" - {
      "should respond with error" in {
        val invalidData = Seq(
          Map("attr1" -> "value1")
        )
        invalidData.foreach { data =>
          val ex = intercept[RuntimeException] {
            issuerSDK.registerWebhook(authentication=
              Option(
                ComMethodAuthentication(
                  "OAuth2",
                  "v2",
                  data
                )
              )
            )
          }
          ex.getMessage.contains("required fields missing") shouldBe true
        }
      }
    }

    "when tried to add v1 OAuthed com method with empty data for required fields" - {
      "should respond with error" in {
        val invalidData = Seq(
          Map("url" -> "", "grant_type" -> "client_credentials", "client_id" -> "client_id", "client_secret" -> "client_secret"),
          Map("url" -> "url", "grant_type" -> "", "client_id" -> "client_id", "client_secret" -> "client_secret"),
          Map("url" -> "url", "grant_type" -> "client_credentials", "client_id" -> "", "client_secret" -> "client_secret"),
          Map("url" -> "url", "grant_type" -> "client_credentials", "client_id" -> "client_id", "client_secret" -> "")
        )
        invalidData.foreach { data =>
          val ex = intercept[RuntimeException] {
            issuerSDK.registerWebhook(authentication=
              Option(
                ComMethodAuthentication(
                  "OAuth2",
                  "v1",
                  data
                )
              )
            )
          }
          ex.getMessage.contains("required fields with invalid value") shouldBe true
        }
      }
    }

    "when tried to add v2 OAuthed com method with empty data" - {
      "should respond with error" in {
        val invalidData = Seq(
          Map("token" -> "")
        )
        invalidData.foreach { data =>
          val ex = intercept[RuntimeException] {
            issuerSDK.registerWebhook(authentication=
              Option(
                ComMethodAuthentication(
                  "OAuth2",
                  "v2",
                  data
                )
              )
            )
          }
          ex.getMessage.contains("required fields with invalid value") shouldBe true
        }
      }
    }

    "when tried to add v1 OAuthed com method with null data for required fields" - {
      "should respond with error" in {
        val invalidData = Seq(
          Map("url" -> null, "grant_type" -> "client_credentials", "client_id" -> "client_id", "client_secret" -> "client_secret"),
          Map("url" -> "url", "grant_type" -> null, "client_id" -> "client_id", "client_secret" -> "client_secret"),
          Map("url" -> "url", "grant_type" -> "client_credentials", "client_id" -> null, "client_secret" -> "client_secret"),
          Map("url" -> "url", "grant_type" -> "client_credentials", "client_id" -> "client_id", "client_secret" -> null)
        )
        invalidData.foreach { data =>
          val ex = intercept[RuntimeException] {
            issuerSDK.registerWebhook(
              Option("webhook"),
              Option(
                ComMethodAuthentication(
                  "OAuth2",
                  "v1",
                  data
                )
              )
            )
          }
          print(ex.getMessage)
          ex.getMessage.contains("required fields missing") shouldBe true
        }
      }
    }

    "when tried to add v2 OAuthed com method with null data" - {
      "should respond with error" in {
        val invalidData = Seq(
          Map("token" -> null)
        )
        invalidData.foreach { data =>
          val ex = intercept[RuntimeException] {
            issuerSDK.registerWebhook(authentication=
              Option(
                ComMethodAuthentication(
                  "OAuth2",
                  "v2",
                  data
                )
              )
            )
          }
          ex.getMessage.contains("required fields missing") shouldBe true
        }
      }
    }

    "when tried to add valid com method (without auth)" - {
      "should be successful" in {
        issuerSDK.registerWebhookWithoutOAuth(Option("webhook"))
      }
    }

    "when tried to add valid v1 OAuthed com method (with auth)" - {
      "should be successful" in {
        val authData = Map(
          "url"           -> "auth-url",
          "grant_type"    -> "client_credentials",
          "client_id"     -> "client_id",
          "client_secret" -> "client_secret"
        )

        issuerSDK.registerWebhook(
          Option("authwebhook"),
          Option(
            ComMethodAuthentication(
              "OAuth2",
              "v1",
              authData
            )
          )
        )
      }
    }

    "when tried to add valid v2 OAuthed com method (with auth)" - {
      "should be successful" in {
        val authData = Map(
          "token"           -> "fixed-token"
        )

        issuerSDK.registerWebhook(
          Option("authwebhook1"),
          Option(
            ComMethodAuthentication(
              "OAuth2",
              "v2",
              authData
            )
          )
        )
      }
    }

    "when tried to update com method (from oauth to no-auth)" - {
      "should be successful" in {
        issuerSDK.registerWebhook(
          Option("authwebhook")
        )
      }
    }
  }

  "HolderSDK" - {

    "when tried to setup holder" - {
      "should be successful" in {
        holderSDK.fetchAgencyKey()
        holderSDK.provisionVerityCloudAgent()
      }
    }

    //as this authentication feature is only valid for VAS for now
    "when tried to add com method with authentication data" - {
      "should fail with appropriate error" in {
        val authentication =
          ComMethodAuthentication(
            "OAuth2",
            "v1",
            Map(
              "url" -> "http://www.token.webhook.com",
              "grant_type" -> "client_credentials",
              "client_id" -> "client_id",           //dummy data
              "client_secret" -> "client_secret"    //dummy data
            )
          )
        val updateComMethod = UpdateComMethodReqMsg(
          ComMethod("1", COM_METHOD_TYPE_HTTP_ENDPOINT, "http://www.webhook.com", None, Option(authentication)))
        val ex = intercept[IllegalArgumentException] {
          holderSDK.registerWebhook(updateComMethod)
        }
        ex.getMessage.contains("authentication not supported") shouldBe true
      }
    }
  }
}
