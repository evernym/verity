package com.evernym.verity.integration.with_basic_sdk

import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.StatusCodes.BadRequest
import com.evernym.verity.Status.{INVALID_VALUE, MISSING_REQ_FIELD}
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.agentmsg.msgfamily.ConfigDetail
import com.evernym.verity.agentmsg.msgfamily.configs.UpdateConfigReqMsg
import com.evernym.verity.http.common.StatusDetailResp
import com.evernym.verity.integration.base.{VerityEnv, VerityProviderBaseSpec}
import com.evernym.verity.integration.base.sdk_provider.{IssuerSdk, SdkProvider}
import com.evernym.verity.protocol.engine.{DID, ThreadId}
import com.evernym.verity.protocol.protocols.issuersetup.v_0_6.{CurrentPublicIdentifier, PublicIdentifier, PublicIdentifierCreated, Create => CreatePublicIdentifier}
import com.evernym.verity.protocol.protocols.relationship.v_1_0.Ctl.{SMSConnectionInvitation, SMSOutOfBandInvitation}

import java.nio.charset.Charset

class RelationshipFailureSpec
  extends VerityProviderBaseSpec
    with SdkProvider  {

  lazy val issuerVerityApp: VerityEnv = setupNewVerityEnv()
  lazy val issuerSDK: IssuerSdk = setupIssuerSdk(issuerVerityApp)
  val connId = "connId1"

  var threadId: Option[ThreadId] = None
  var pairwiseDID: DID = ""

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
    threadId = resp.threadIdOpt
    pairwiseDID = resp.msg.did
  }

  "IssuerSdk" - {
    "SMS Connection Invitation" - {
      "with null phone number" - {
        "should receive BadRequest response" in {
          val resp = DefaultMsgCodec.fromJson[StatusDetailResp](
            issuerSDK.sendMsgForConn(connId, SMSConnectionInvitation(null), threadId, expectedRespStatus=BadRequest)
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
            issuerSDK.sendMsgForConn(connId, SMSConnectionInvitation(""), threadId, expectedRespStatus=BadRequest)
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
            issuerSDK.sendMsgForConn(connId, SMSConnectionInvitation("4045943696"), threadId, expectedRespStatus=BadRequest)
              .entity.asInstanceOf[HttpEntity.Strict].getData().decodeString(Charset.defaultCharset())
          )
          resp shouldBe StatusDetailResp(
            INVALID_VALUE.statusCode,
            "invalid value given for field: 'phoneNumber' (Phone number provided is not in valid international format.)",
            None
          )
        }
      }

      "with too short phone number in international format" - {
        "should receive BadRequest response" in {
          val resp = DefaultMsgCodec.fromJson[StatusDetailResp](
            issuerSDK.sendMsgForConn(connId, SMSConnectionInvitation("+140459"), threadId, expectedRespStatus=BadRequest)
              .entity.asInstanceOf[HttpEntity.Strict].getData().decodeString(Charset.defaultCharset())
          )
          resp shouldBe StatusDetailResp(
            INVALID_VALUE.statusCode,
            "invalid value given for field: 'phoneNumber' (Phone number provided is not in valid international format.)",
            None
          )
        }
      }

      "with phone number with spaces" - {
        "should receive BadRequest response" in {
          val resp = DefaultMsgCodec.fromJson[StatusDetailResp](
            issuerSDK.sendMsgForConn(connId, SMSConnectionInvitation("+1 404 5943696"), threadId, expectedRespStatus=BadRequest)
              .entity.asInstanceOf[HttpEntity.Strict].getData().decodeString(Charset.defaultCharset())
          )
          resp shouldBe StatusDetailResp(
            INVALID_VALUE.statusCode,
            "invalid value given for field: 'phoneNumber' (Phone number provided is not in valid international format.)",
            None
          )
        }
      }

      "with phone number with dashes" - {
        "should receive BadRequest response" in {
          val resp = DefaultMsgCodec.fromJson[StatusDetailResp](
            issuerSDK.sendMsgForConn(connId, SMSConnectionInvitation("+1-404-5943696"), threadId, expectedRespStatus=BadRequest)
              .entity.asInstanceOf[HttpEntity.Strict].getData().decodeString(Charset.defaultCharset())
          )
          resp shouldBe StatusDetailResp(
            INVALID_VALUE.statusCode,
            "invalid value given for field: 'phoneNumber' (Phone number provided is not in valid international format.)",
            None
          )
        }
      }

      "with phone number with parentheses" - {
        "should receive BadRequest response" in {
          val resp = DefaultMsgCodec.fromJson[StatusDetailResp](
            issuerSDK.sendMsgForConn(connId, SMSConnectionInvitation("+1(404)5943696"), threadId, expectedRespStatus=BadRequest)
              .entity.asInstanceOf[HttpEntity.Strict].getData().decodeString(Charset.defaultCharset())
          )
          resp shouldBe StatusDetailResp(
            INVALID_VALUE.statusCode,
            "invalid value given for field: 'phoneNumber' (Phone number provided is not in valid international format.)",
            None
          )
        }
      }

      "with phone number with letters" - {
        "should receive BadRequest response" in {
          val resp = DefaultMsgCodec.fromJson[StatusDetailResp](
            issuerSDK.sendMsgForConn(connId, SMSConnectionInvitation("+1404myPhone"), threadId, expectedRespStatus=BadRequest)
              .entity.asInstanceOf[HttpEntity.Strict].getData().decodeString(Charset.defaultCharset())
          )
          resp shouldBe StatusDetailResp(
            INVALID_VALUE.statusCode,
            "invalid value given for field: 'phoneNumber' (Phone number provided is not in valid international format.)",
            None
          )
        }
      }
    }

    "SMS Out Of Band Invitation" - {
      "with null phone number" - {
        "should receive BadRequest response" in {
          val resp = DefaultMsgCodec.fromJson[StatusDetailResp](
            issuerSDK.sendMsgForConn(connId, SMSOutOfBandInvitation(null, None, None), threadId, expectedRespStatus=BadRequest)
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
            issuerSDK.sendMsgForConn(connId, SMSOutOfBandInvitation("", None, None), threadId, expectedRespStatus=BadRequest)
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
            issuerSDK.sendMsgForConn(connId, SMSOutOfBandInvitation("4045943696", None, None), threadId, expectedRespStatus=BadRequest)
              .entity.asInstanceOf[HttpEntity.Strict].getData().decodeString(Charset.defaultCharset())
          )
          resp shouldBe StatusDetailResp(
            INVALID_VALUE.statusCode,
            "invalid value given for field: 'phoneNumber' (Phone number provided is not in valid international format.)",
            None
          )
        }
      }

      "with too short phone number in international format" - {
        "should receive BadRequest response" in {
          val resp = DefaultMsgCodec.fromJson[StatusDetailResp](
            issuerSDK.sendMsgForConn(connId, SMSOutOfBandInvitation("+140459", None, None), threadId, expectedRespStatus=BadRequest)
              .entity.asInstanceOf[HttpEntity.Strict].getData().decodeString(Charset.defaultCharset())
          )
          resp shouldBe StatusDetailResp(
            INVALID_VALUE.statusCode,
            "invalid value given for field: 'phoneNumber' (Phone number provided is not in valid international format.)",
            None
          )
        }
      }

      "with phone number with spaces" - {
        "should receive BadRequest response" in {
          val resp = DefaultMsgCodec.fromJson[StatusDetailResp](
            issuerSDK.sendMsgForConn(connId, SMSOutOfBandInvitation("+1 404 5943696", None, None), threadId, expectedRespStatus=BadRequest)
              .entity.asInstanceOf[HttpEntity.Strict].getData().decodeString(Charset.defaultCharset())
          )
          resp shouldBe StatusDetailResp(
            INVALID_VALUE.statusCode,
            "invalid value given for field: 'phoneNumber' (Phone number provided is not in valid international format.)",
            None
          )
        }
      }

      "with phone number with dashes" - {
        "should receive BadRequest response" in {
          val resp = DefaultMsgCodec.fromJson[StatusDetailResp](
            issuerSDK.sendMsgForConn(connId, SMSOutOfBandInvitation("+1-404-5943696", None, None), threadId, expectedRespStatus=BadRequest)
              .entity.asInstanceOf[HttpEntity.Strict].getData().decodeString(Charset.defaultCharset())
          )
          resp shouldBe StatusDetailResp(
            INVALID_VALUE.statusCode,
            "invalid value given for field: 'phoneNumber' (Phone number provided is not in valid international format.)",
            None
          )
        }
      }

      "with phone number with parentheses" - {
        "should receive BadRequest response" in {
          val resp = DefaultMsgCodec.fromJson[StatusDetailResp](
            issuerSDK.sendMsgForConn(connId, SMSOutOfBandInvitation("+1(404)5943696", None, None), threadId, expectedRespStatus=BadRequest)
              .entity.asInstanceOf[HttpEntity.Strict].getData().decodeString(Charset.defaultCharset())
          )
          resp shouldBe StatusDetailResp(
            INVALID_VALUE.statusCode,
            "invalid value given for field: 'phoneNumber' (Phone number provided is not in valid international format.)",
            None
          )
        }
      }

      "with phone number with letters" - {
        "should receive BadRequest response" in {
          val resp = DefaultMsgCodec.fromJson[StatusDetailResp](
            issuerSDK.sendMsgForConn(connId, SMSOutOfBandInvitation("+1404myPhone", None, None), threadId, expectedRespStatus=BadRequest)
              .entity.asInstanceOf[HttpEntity.Strict].getData().decodeString(Charset.defaultCharset())
          )
          resp shouldBe StatusDetailResp(
            INVALID_VALUE.statusCode,
            "invalid value given for field: 'phoneNumber' (Phone number provided is not in valid international format.)",
            None
          )
        }
      }
    }
  }
}