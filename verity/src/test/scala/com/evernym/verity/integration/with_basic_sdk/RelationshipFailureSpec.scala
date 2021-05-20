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
import com.evernym.verity.protocol.protocols.issuersetup.v_0_6.{CurrentPublicIdentifier, PublicIdentifier, PublicIdentifierCreated, Create => CreatePublicIdentifier}
import com.evernym.verity.protocol.protocols.relationship.v_1_0.Ctl.{SMSConnectionInvitation, SMSOutOfBandInvitation, Create => CreateRelationship}
import com.evernym.verity.protocol.protocols.relationship.v_1_0.Signal.{ProblemReport, Created => RelationshipCreated}

import java.nio.charset.Charset

class RelationshipFailureSpec
  extends VerityProviderBaseSpec
    with SdkProvider  {

//  override val defaultSvcParam: ServiceParam = ServiceParam(LedgerSvcParam(ledgerTxnExecutor = new DummyLedgerTxnExecutor()))

  lazy val issuerVerityApp: VerityEnv = setupNewVerityEnv()
  lazy val issuerSDK: IssuerSdk = setupIssuerSdk(issuerVerityApp)

  override def beforeAll(): Unit = {
    super.beforeAll()

    issuerSDK.fetchAgencyKey()
    issuerSDK.provisionVerityEdgeAgent()
    issuerSDK.registerWebhook()
    issuerSDK.sendUpdateConfig(UpdateConfigReqMsg(Set(ConfigDetail("name", "issuer-name"), ConfigDetail("logoUrl", "issuer-logo-url"))))
    issuerSDK.sendControlMsg(CreatePublicIdentifier())
    issuerSDK.expectMsgOnWebhook[PublicIdentifierCreated]()
    issuerSDK.sendControlMsg(CurrentPublicIdentifier())
    issuerSDK.expectMsgOnWebhook[PublicIdentifier]()
    issuerSDK.sendControlMsg(CreateRelationship(None, None))
    issuerSDK.expectMsgOnWebhook[RelationshipCreated]()
  }

  "IssuerSdk" - {
    "SMS Connection Invitation" - {
      "with null phone number" - {
        "should receive BadRequest response" in {
          val resp = DefaultMsgCodec.fromJson[StatusDetailResp](
            issuerSDK.sendControlMsg(SMSConnectionInvitation(null), BadRequest)
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
            issuerSDK.sendControlMsg(SMSConnectionInvitation(""), BadRequest)
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
            issuerSDK.sendControlMsg(SMSConnectionInvitation("4045943696"), BadRequest)
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
            issuerSDK.sendControlMsg(SMSConnectionInvitation("+140459"), BadRequest)
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
            issuerSDK.sendControlMsg(SMSConnectionInvitation("+1 404 5943696"), BadRequest)
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
            issuerSDK.sendControlMsg(SMSConnectionInvitation("+1-404-5943696"), BadRequest)
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
            issuerSDK.sendControlMsg(SMSConnectionInvitation("+1(404)5943696"), BadRequest)
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
            issuerSDK.sendControlMsg(SMSConnectionInvitation("+1404myPhone"), BadRequest)
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
            issuerSDK.sendControlMsg(SMSOutOfBandInvitation(null, None, None), BadRequest)
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
            issuerSDK.sendControlMsg(SMSOutOfBandInvitation("", None, None), BadRequest)
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
            issuerSDK.sendControlMsg(SMSOutOfBandInvitation("4045943696", None, None), BadRequest)
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
            issuerSDK.sendControlMsg(SMSOutOfBandInvitation("+140459", None, None), BadRequest)
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
            issuerSDK.sendControlMsg(SMSOutOfBandInvitation("+1 404 5943696", None, None), BadRequest)
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
            issuerSDK.sendControlMsg(SMSOutOfBandInvitation("+1-404-5943696", None, None), BadRequest)
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
            issuerSDK.sendControlMsg(SMSOutOfBandInvitation("+1(404)5943696", None, None), BadRequest)
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
            issuerSDK.sendControlMsg(SMSOutOfBandInvitation("+1404myPhone", None, None), BadRequest)
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