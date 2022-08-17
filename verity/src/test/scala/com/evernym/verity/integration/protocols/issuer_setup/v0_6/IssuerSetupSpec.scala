package com.evernym.verity.integration.protocols.issuer_setup.v0_6

import com.evernym.verity.agentmsg.msgfamily.ConfigDetail
import com.evernym.verity.agentmsg.msgfamily.configs.UpdateConfigReqMsg
import com.evernym.verity.integration.base.sdk_provider.SdkProvider
import com.evernym.verity.integration.base.{VAS, VerityProviderBaseSpec}
import com.evernym.verity.protocol.protocols.issuersetup.v_0_6._
import com.evernym.verity.protocol.protocols.writeCredentialDefinition.{v_0_6 => writeCredDef0_6}
import com.evernym.verity.protocol.protocols.writeSchema.{v_0_6 => writeSchema0_6}

import scala.concurrent.duration._
import scala.language.postfixOps

class IssuerSetupSpec
  extends VerityProviderBaseSpec
    with SdkProvider  {

  lazy val issuerVerityEnv = VerityEnvBuilder().build(VAS)
  lazy val issuerSDK = setupIssuerSdk(issuerVerityEnv, executionContext)

  override def beforeAll(): Unit = {
    super.beforeAll()
    issuerSDK.fetchAgencyKey()
    issuerSDK.provisionVerityEdgeAgent()
    issuerSDK.registerWebhook()
    issuerSDK.sendUpdateConfig(UpdateConfigReqMsg(Set(ConfigDetail("name", "issuer-name"), ConfigDetail("logoUrl", "issuer-logo-url"))))
  }

  var schemaId = ""

  "IssuerSdk" - {

    "when sent 'current-public-identifier' (issuer-setup 0.6) message" - {
      "should receive 'problem-report'" in {
        issuerSDK.sendMsg(CurrentPublicIdentifier())
        val receivedMsg = issuerSDK.expectMsgOnWebhook[ProblemReport]()
        receivedMsg.msg.message shouldBe "Issuer Identifier has not been created yet"
      }
    }

    "when sent 'create' (issuer-setup 0.6) message" - {
      "should respond with 'public-identifier-created'" in {
        issuerSDK.sendMsg(Create())
        val receivedMsg = issuerSDK.expectMsgOnWebhook[PublicIdentifierCreated]()
        val pic = receivedMsg.msg
        pic.identifier.did.isEmpty shouldBe false
        pic.identifier.verKey.isEmpty shouldBe false
      }
    }

    "when sent 'current-public-identifier' (issuer-setup 0.6) message" - {
      "should receive 'public-identifier'" in {
        issuerSDK.sendMsg(CurrentPublicIdentifier())
        val receivedMsg = issuerSDK.expectMsgOnWebhook[PublicIdentifier]()
        val pi = receivedMsg.msg
        pi.did.isEmpty shouldBe false
        pi.verKey.isEmpty shouldBe false
      }
    }

    "when sent 'write' (write-schema 0.6) message" - {
      "should receive 'status-report'" in {
        issuerSDK.sendMsg(writeSchema0_6.Write("name", "1.0", Seq("name", "age")))
        val receivedMsg = issuerSDK.expectMsgOnWebhook[writeSchema0_6.StatusReport]()
        receivedMsg.msg.schemaId.nonEmpty shouldBe true
        schemaId = receivedMsg.msg.schemaId
      }
    }
  }

  "Verity Admin" - {
    "when restarted verity apps" - {
      "should be successful" in {
        issuerVerityEnv.restartAllNodes()
        issuerSDK.fetchAgencyKey()
      }
    }
  }

  "IssuerSdk" - {
    "when tried to sent 'write (write-cred-def 0.6) message" - {
      "should be successful" in {
        issuerSDK.sendMsg(writeCredDef0_6.Write("name", schemaId, None, None))
        val receivedMsg = issuerSDK.expectMsgOnWebhook[writeCredDef0_6.StatusReport](
          2 minutes
        )
        receivedMsg.msg.credDefId.nonEmpty shouldBe true
      }
    }
  }
}
