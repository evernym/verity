package com.evernym.integrationtests.e2e.apis.legacy.vcx

import com.evernym.sdk.vcx.vcx.VcxApi
import com.evernym.verity.protocol.protocols.basicMessage.v_1_0.Msg.Message


class VcxFlowSpec
  extends BaseVcxFlowSpec {

  runIssuerHolderFlowSpec("1.0", "1.0")
  runIssuerHolderFlowSpec("1.0", "2.0")
  runIssuerHolderFlowSpec("2.0", "1.0")
  runIssuerHolderFlowSpec("2.0", "2.0")

  def runIssuerHolderFlowSpec(issuerProtocolVersion: String, holderProtocolVersion: String): Unit = {
    val issuer = "Issuer"
    val holder = "Holder"
    val issuerHolderConn1 = "connId1"
    var invitation: String = null

    s"Issuer: $issuerProtocolVersion, Holder: $holderProtocolVersion" - {

      "Issuer" - {
        "when tried to provision" - {
          "should be successful" in {
            provisionIssuer(issuer, eas, agencyAdminEnv.enterpriseAgencyAdmin, issuerProtocolVersion)
          }
        }

        "when tried to setup" - {
          "should be successful" in {
            setupIssuer(
              issuer,
              issuerHolderConn1,
              CreateSchemaParam(
                s"degree-schema-v${issuerProtocolVersion}_$holderProtocolVersion",
                getRandomSchemaVersion,
                """["first-name","last-name","age"]"""
              ),
              CreateCredDefParam(s"degree-v${issuerProtocolVersion}_$holderProtocolVersion")
            )
          }
        }
      }

      "Issuer" - {
        "when tried to create invitation for holder" - {
          "should be successful" in {
            invitation = createConnection(issuer, issuerHolderConn1)
          }
        }
      }

      "Holder" - {
        "when tried to provision" - {
          "should be successful" in {
            provisionHolder(holder, cas, agencyAdminEnv.consumerAgencyAdmin, holderProtocolVersion)
          }
        }
        "when tried to accept invitation" - {
          "should be successful" in {
            acceptInvitation(holder, issuerHolderConn1, invitation)
          }
        }
      }

      "Issuer" - {
        "when checking for invite answer message" - {
          "should be successful" in {
            checkConnectionAccepted(issuer, issuerHolderConn1)
          }
        }
      }

      "Issuer" - {
        "when tried to send basic message" - {
          "should be successful" in {
            sendMessage(issuer, issuerHolderConn1, Message(sent_time = "", content = "How are you?"))
          }
        }
      }

      "Holder" - {
        "when tried to get new received message" - {
          "should find message sent from issuer" in {
            val msg = expectMsg[Message](holder, issuerHolderConn1)
            msg.content shouldBe "How are you?"
          }
        }

        "when tried to send reply message" - {
          "should be successful" in {
            sendMessage(holder, issuerHolderConn1, Message(sent_time = "", content = "I am fine"))
          }
        }
      }

      "Issuer" - {
        "when tried to get new received message" - {
          "should find message sent from holder" in {
            val msg = expectMsg[Message](issuer, issuerHolderConn1)
            msg.content shouldBe "I am fine"
          }
        }
      }

      "Cleanup" - {
        "should be successful" in {
          VcxApi.vcxShutdown(true)
        }
      }
    }
  }
}
