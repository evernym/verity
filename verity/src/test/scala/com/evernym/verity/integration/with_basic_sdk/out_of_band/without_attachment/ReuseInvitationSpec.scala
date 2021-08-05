package com.evernym.verity.integration.with_basic_sdk.out_of_band.without_attachment

import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.agent.{Thread => MsgThread}
import com.evernym.verity.integration.base.sdk_provider.SdkProvider
import com.evernym.verity.integration.base.{CAS, VAS, VerityProviderBaseSpec}
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Msg.OfferCred
import com.evernym.verity.protocol.protocols.relationship.v_1_0.Signal.Invitation
import com.evernym.verity.protocol.protocols.writeCredentialDefinition.{v_0_6 => writeCredDef0_6}
import com.evernym.verity.protocol.protocols.writeSchema.{v_0_6 => writeSchema0_6}
import com.evernym.verity.util.TestExecutionContextProvider

import scala.concurrent.ExecutionContext


//Holder1 connects with Issuer via an OOB invitation and responds (optionally responding with attachment).
//Then Holder2 tries to re-use the same OOB invitation and expectation is that it should fail

class ReuseInvitationSpec
  extends VerityProviderBaseSpec
    with SdkProvider {

  lazy val ecp = TestExecutionContextProvider.ecp
  lazy val executionContext: ExecutionContext = ecp.futureExecutionContext
  lazy val issuerVerityEnv = VerityEnvBuilder.default().build(VAS)
  lazy val holderVerityEnv = VerityEnvBuilder.default().build(CAS)

  lazy val issuerSDK = setupIssuerSdk(issuerVerityEnv, executionContext, ecp.walletFutureExecutionContext)
  lazy val holderSDK1 = setupHolderSdk(holderVerityEnv, defaultSvcParam.ledgerTxnExecutor, executionContext, ecp.walletFutureExecutionContext)
  lazy val holderSDK2 = setupHolderSdk(holderVerityEnv, defaultSvcParam.ledgerTxnExecutor, executionContext, ecp.walletFutureExecutionContext)

  val oobIssuerHolderConn1 = "connId1"
  val oobIssuerHolderConn2 = "connId2"

  var schemaId: SchemaId = _
  var credDefId: CredDefId = _
  var offerCred: OfferCred = _

  var lastReceivedThread: Option[MsgThread] = None
  var oobInvite: Option[Invitation] = None

  override def beforeAll(): Unit = {
    super.beforeAll()
    provisionEdgeAgent(issuerSDK)
    provisionCloudAgent(holderSDK1)
    provisionCloudAgent(holderSDK2)

    setupIssuer(issuerSDK)
    schemaId = writeSchema(issuerSDK, writeSchema0_6.Write("name", "1.0", Seq("name", "age")))
    credDefId = writeCredDef(issuerSDK, writeCredDef0_6.Write("name", schemaId, None, None))
  }

  "IssuerSDK creating first OOB cred offer" - {
    "when created new relationship" - {
      "should be successful" in {
        val receivedMsg = issuerSDK.sendCreateRelationship(oobIssuerHolderConn1)
        lastReceivedThread = receivedMsg.threadOpt
      }
    }

    "when created new OOB invitation" - {
      "should be successful" in {
        oobInvite = Option(issuerSDK.sendCreateOOBInvitation(oobIssuerHolderConn1, lastReceivedThread))
      }
    }
  }

  "HolderSDK1" - {
    "as there is no previous connection with the issuer" - {
      "when tried to accept the OOB invitation first time" - {
        "should be successful" in {
          holderSDK1.sendCreateNewKey(oobIssuerHolderConn1)
          val invite = oobInvite.get
          holderSDK1.sendConnReqForInvitation(oobIssuerHolderConn1, invite)
          issuerSDK.expectConnectionComplete(oobIssuerHolderConn1)
        }
      }
    }
  }

  "HolderSDK2" - {
    "as there is no previous connection with the issuer" - {
      "when tried to accept the OOB invitation first time" - {
        "should be successful" in {
          holderSDK2.sendCreateNewKey(oobIssuerHolderConn1)
          val invite = oobInvite.get
          val ex = intercept[IllegalArgumentException] {
            holderSDK2.sendConnReqForInvitation(oobIssuerHolderConn1, invite)
          }
          ex.getMessage.contains("unauthorized")
        }
      }
    }
  }

  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = executionContext

  override def executionContextProvider: ExecutionContextProvider = ecp
}