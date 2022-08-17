package com.evernym.verity.integration.protocols.relationship.v1_0

import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.StatusCodes.BadRequest
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.agentmsg.msgfamily.ConfigDetail
import com.evernym.verity.agentmsg.msgfamily.configs.UpdateConfigReqMsg
import com.evernym.verity.did.DidStr
import com.evernym.verity.did.didcomm.v1.{Thread => MsgThread}
import com.evernym.verity.http.common.models.StatusDetailResp
import com.evernym.verity.integration.base.sdk_provider.{IssuerSdk, SdkProvider}
import com.evernym.verity.integration.base.verity_provider.VerityEnv
import com.evernym.verity.integration.base.{VAS, VerityProviderBaseSpec}
import com.evernym.verity.protocol.protocols.issuersetup.v_0_6.{CurrentPublicIdentifier, PublicIdentifier, PublicIdentifierCreated, Create => CreatePublicIdentifier}
import com.evernym.verity.protocol.protocols.relationship.v_1_0.Ctl.{SMSConnectionInvitation, SMSOutOfBandInvitation}
import com.evernym.verity.util2.Status.{INVALID_VALUE, MISSING_REQ_FIELD}

import java.nio.charset.Charset


class RelationshipFailureSpec
  extends VerityProviderBaseSpec
    with SdkProvider  {

  lazy val issuerVerityApp: VerityEnv = VerityEnvBuilder().build(VAS)
  lazy val issuerSDK: IssuerSdk = setupIssuerSdk(issuerVerityApp, executionContext)
  val connId = "connId1"

  var thread: Option[MsgThread] = None
  var pairwiseDID: DidStr = ""

  override def beforeAll(): Unit = {
    super.beforeAll()

    issuerSDK.fetchAgencyKey()
    issuerSDK.provisionVerityEdgeAgent()
    issuerSDK.registerWebhook()
    issuerSDK.sendUpdateConfig(UpdateConfigReqMsg(Set(ConfigDetail("name", "issuer-name"), ConfigDetail("logoUrl", "issuer-logo-url"))))
    issuerSDK.sendMsg(CreatePublicIdentifier())
    issuerSDK.expectMsgOnWebhook[PublicIdentifierCreated]()
    issuerSDK.sendMsg(CurrentPublicIdentifier())
    issuerSDK.expectMsgOnWebhook[PublicIdentifier]()
    issuerSDK.sendCreateRelationship(connId)
    val resp = issuerSDK.sendCreateRelationship(connId)
    thread = resp.threadOpt
    pairwiseDID = resp.msg.did
  }

  "IssuerSdk" - {
    "SMS Connection Invitation" - {
      "with null phone number" - {
        "should receive BadRequest response" in {
          val resp = DefaultMsgCodec.fromJson[StatusDetailResp](
            issuerSDK.sendMsgForConn(connId, SMSConnectionInvitation(null), thread, expectedRespStatus=BadRequest)
              .entity.asInstanceOf[HttpEntity.Strict].getData().decodeString(Charset.defaultCharset())
          )
          resp shouldBe StatusDetailResp(
            MISSING_REQ_FIELD.statusCode,
            "required attribute not found (missing/empty/null): 'phoneNumber'",
            None
          )
        }
      }

      "with empty phone number" - {
        "should receive BadRequest response" in {
          val resp = DefaultMsgCodec.fromJson[StatusDetailResp](
            issuerSDK.sendMsgForConn(connId, SMSConnectionInvitation(""), thread, expectedRespStatus=BadRequest)
              .entity.asInstanceOf[HttpEntity.Strict].getData().decodeString(Charset.defaultCharset())
          )
          resp shouldBe StatusDetailResp(
            MISSING_REQ_FIELD.statusCode,
            "required attribute not found (missing/empty/null): 'phoneNumber'",
            None
          )
        }
      }

      "with phone number in national format" - {
        "should receive BadRequest response" in {
          val resp = DefaultMsgCodec.fromJson[StatusDetailResp](
            issuerSDK.sendMsgForConn(connId, SMSConnectionInvitation("4045943696"), thread, expectedRespStatus=BadRequest)
              .entity.asInstanceOf[HttpEntity.Strict].getData().decodeString(Charset.defaultCharset())
          )
          resp shouldBe StatusDetailResp(
            INVALID_VALUE.statusCode,
            "field 'phoneNumber' has invalid value: Phone number provided is not in valid international format.",
            None
          )
        }
      }

      "with too short phone number in international format" - {
        "should receive BadRequest response" in {
          val resp = DefaultMsgCodec.fromJson[StatusDetailResp](
            issuerSDK.sendMsgForConn(connId, SMSConnectionInvitation("+140459"), thread, expectedRespStatus=BadRequest)
              .entity.asInstanceOf[HttpEntity.Strict].getData().decodeString(Charset.defaultCharset())
          )
          resp shouldBe StatusDetailResp(
            INVALID_VALUE.statusCode,
            "field 'phoneNumber' has invalid value: Phone number provided is not in valid international format.",
            None
          )
        }
      }

      "with phone number with spaces" - {
        "should receive BadRequest response" in {
          val resp = DefaultMsgCodec.fromJson[StatusDetailResp](
            issuerSDK.sendMsgForConn(connId, SMSConnectionInvitation("+1 404 5943696"), thread, expectedRespStatus=BadRequest)
              .entity.asInstanceOf[HttpEntity.Strict].getData().decodeString(Charset.defaultCharset())
          )
          resp shouldBe StatusDetailResp(
            INVALID_VALUE.statusCode,
            "field 'phoneNumber' has invalid value: Phone number provided is not in valid international format.",
            None
          )
        }
      }

      "with phone number with dashes" - {
        "should receive BadRequest response" in {
          val resp = DefaultMsgCodec.fromJson[StatusDetailResp](
            issuerSDK.sendMsgForConn(connId, SMSConnectionInvitation("+1-404-5943696"), thread, expectedRespStatus=BadRequest)
              .entity.asInstanceOf[HttpEntity.Strict].getData().decodeString(Charset.defaultCharset())
          )
          resp shouldBe StatusDetailResp(
            INVALID_VALUE.statusCode,
            "field 'phoneNumber' has invalid value: Phone number provided is not in valid international format.",
            None
          )
        }
      }

      "with phone number with parentheses" - {
        "should receive BadRequest response" in {
          val resp = DefaultMsgCodec.fromJson[StatusDetailResp](
            issuerSDK.sendMsgForConn(connId, SMSConnectionInvitation("+1(404)5943696"), thread, expectedRespStatus=BadRequest)
              .entity.asInstanceOf[HttpEntity.Strict].getData().decodeString(Charset.defaultCharset())
          )
          resp shouldBe StatusDetailResp(
            INVALID_VALUE.statusCode,
            "field 'phoneNumber' has invalid value: Phone number provided is not in valid international format.",
            None
          )
        }
      }

      "with phone number with letters" - {
        "should receive BadRequest response" in {
          val resp = DefaultMsgCodec.fromJson[StatusDetailResp](
            issuerSDK.sendMsgForConn(connId, SMSConnectionInvitation("+1404myPhone"), thread, expectedRespStatus=BadRequest)
              .entity.asInstanceOf[HttpEntity.Strict].getData().decodeString(Charset.defaultCharset())
          )
          resp shouldBe StatusDetailResp(
            INVALID_VALUE.statusCode,
            "field 'phoneNumber' has invalid value: Phone number provided is not in valid international format.",
            None
          )
        }
      }
    }

    "SMS Out Of Band Invitation" - {
      "with null phone number" - {
        "should receive BadRequest response" in {
          val resp = DefaultMsgCodec.fromJson[StatusDetailResp](
            issuerSDK.sendMsgForConn(connId, SMSOutOfBandInvitation(null, None, None), thread, expectedRespStatus=BadRequest)
              .entity.asInstanceOf[HttpEntity.Strict].getData().decodeString(Charset.defaultCharset())
          )
          resp shouldBe StatusDetailResp(
            MISSING_REQ_FIELD.statusCode,
            "required attribute not found (missing/empty/null): 'phoneNumber'",
            None
          )
        }
      }

      "with empty phone number" - {
        "should receive BadRequest response" in {
          val resp = DefaultMsgCodec.fromJson[StatusDetailResp](
            issuerSDK.sendMsgForConn(connId, SMSOutOfBandInvitation("", None, None), thread, expectedRespStatus=BadRequest)
              .entity.asInstanceOf[HttpEntity.Strict].getData().decodeString(Charset.defaultCharset())
          )
          resp shouldBe StatusDetailResp(
            MISSING_REQ_FIELD.statusCode,
            "required attribute not found (missing/empty/null): 'phoneNumber'",
            None
          )
        }
      }

      "with phone number in national format" - {
        "should receive BadRequest response" in {
          val resp = DefaultMsgCodec.fromJson[StatusDetailResp](
            issuerSDK.sendMsgForConn(connId, SMSOutOfBandInvitation("4045943696", None, None), thread, expectedRespStatus=BadRequest)
              .entity.asInstanceOf[HttpEntity.Strict].getData().decodeString(Charset.defaultCharset())
          )
          resp shouldBe StatusDetailResp(
            INVALID_VALUE.statusCode,
            "field 'phoneNumber' has invalid value: Phone number provided is not in valid international format.",
            None
          )
        }
      }

      "with too short phone number in international format" - {
        "should receive BadRequest response" in {
          val resp = DefaultMsgCodec.fromJson[StatusDetailResp](
            issuerSDK.sendMsgForConn(connId, SMSOutOfBandInvitation("+140459", None, None), thread, expectedRespStatus=BadRequest)
              .entity.asInstanceOf[HttpEntity.Strict].getData().decodeString(Charset.defaultCharset())
          )
          resp shouldBe StatusDetailResp(
            INVALID_VALUE.statusCode,
            "field 'phoneNumber' has invalid value: Phone number provided is not in valid international format.",
            None
          )
        }
      }

      "with phone number with spaces" - {
        "should receive BadRequest response" in {
          val resp = DefaultMsgCodec.fromJson[StatusDetailResp](
            issuerSDK.sendMsgForConn(connId, SMSOutOfBandInvitation("+1 404 5943696", None, None), thread, expectedRespStatus=BadRequest)
              .entity.asInstanceOf[HttpEntity.Strict].getData().decodeString(Charset.defaultCharset())
          )
          resp shouldBe StatusDetailResp(
            INVALID_VALUE.statusCode,
            "field 'phoneNumber' has invalid value: Phone number provided is not in valid international format.",
            None
          )
        }
      }

      "with phone number with dashes" - {
        "should receive BadRequest response" in {
          val resp = DefaultMsgCodec.fromJson[StatusDetailResp](
            issuerSDK.sendMsgForConn(connId, SMSOutOfBandInvitation("+1-404-5943696", None, None), thread, expectedRespStatus=BadRequest)
              .entity.asInstanceOf[HttpEntity.Strict].getData().decodeString(Charset.defaultCharset())
          )
          resp shouldBe StatusDetailResp(
            INVALID_VALUE.statusCode,
            "field 'phoneNumber' has invalid value: Phone number provided is not in valid international format.",
            None
          )
        }
      }

      "with phone number with parentheses" - {
        "should receive BadRequest response" in {
          val resp = DefaultMsgCodec.fromJson[StatusDetailResp](
            issuerSDK.sendMsgForConn(connId, SMSOutOfBandInvitation("+1(404)5943696", None, None), thread, expectedRespStatus=BadRequest)
              .entity.asInstanceOf[HttpEntity.Strict].getData().decodeString(Charset.defaultCharset())
          )
          resp shouldBe StatusDetailResp(
            INVALID_VALUE.statusCode,
            "field 'phoneNumber' has invalid value: Phone number provided is not in valid international format.",
            None
          )
        }
      }

      "with phone number with letters" - {
        "should receive BadRequest response" in {
          val resp = DefaultMsgCodec.fromJson[StatusDetailResp](
            issuerSDK.sendMsgForConn(connId, SMSOutOfBandInvitation("+1404myPhone", None, None), thread, expectedRespStatus=BadRequest)
              .entity.asInstanceOf[HttpEntity.Strict].getData().decodeString(Charset.defaultCharset())
          )
          resp shouldBe StatusDetailResp(
            INVALID_VALUE.statusCode,
            "field 'phoneNumber' has invalid value: Phone number provided is not in valid international format.",
            None
          )
        }
      }
    }
  }
}