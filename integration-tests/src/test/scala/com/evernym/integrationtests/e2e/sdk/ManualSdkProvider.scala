package com.evernym.integrationtests.e2e.sdk

import com.evernym.integrationtests.e2e.env.SdkConfig
import com.evernym.integrationtests.e2e.sdk.UndefinedInterfaces._
import com.evernym.verity.did.DidStr
import com.evernym.verity.sdk.protocols.basicmessage.v1_0.BasicMessageV1_0
import com.evernym.verity.sdk.protocols.connecting.v1_0.ConnectionsV1_0
import com.evernym.verity.sdk.protocols.issuecredential.v1_0.IssueCredentialV1_0
import com.evernym.verity.sdk.protocols.issuersetup.v0_6.IssuerSetupV0_6
import com.evernym.verity.sdk.protocols.outofband.v1_0.OutOfBandV1_0
import com.evernym.verity.sdk.protocols.presentproof.common.{Attribute, Predicate, ProposedAttribute, ProposedPredicate}
import com.evernym.verity.sdk.protocols.presentproof.v1_0.PresentProofV1_0
import com.evernym.verity.sdk.protocols.provision.v0_7.ProvisionV0_7
import com.evernym.verity.sdk.protocols.questionanswer.v1_0.CommittedAnswerV1_0
import com.evernym.verity.sdk.protocols.relationship.v1_0.RelationshipV1_0
import com.evernym.verity.sdk.protocols.updateconfigs.v0_6.UpdateConfigsV0_6
import com.evernym.verity.sdk.protocols.updateendpoint.v0_6.UpdateEndpointV0_6
import com.evernym.verity.sdk.protocols.writecreddef.v0_6.{RevocationRegistryConfig, WriteCredentialDefinitionV0_6}
import com.evernym.verity.sdk.protocols.writeschema.v0_6.WriteSchemaV0_6
import com.evernym.verity.sdk.utils.Context
import net.glxn.qrgen.QRCode
import org.json.JSONObject

import java.io.{File, FileOutputStream}
import scala.concurrent.duration.Duration

class ManualSdkProvider(val sdkConfig: SdkConfig)
  extends VeritySdkProvider
  with MsgReceiver
  with WalletedSdkProvider {
  override def sdkType: String = "MANUAL"

  override def shouldExpectMsg(): Boolean = false
  override def shouldCheckMsg(): Boolean = false

  override def expectMsg(max: Duration): JSONObject = {
    new JSONObject()
  }

  override def context: Context = null
  override def updateContext(context: Context): Unit = {}

  override def data(key: String): Option[String] = None
  override def updateData(key: String, value: String): Unit = {}

  override def relationship(id: String): Option[RelData] = Some(RelData("", "", Some("")))

  override def updateRelationship(id: String, data: RelData): Unit = {}
  override def updateRelationship(id: String, accepted: JSONObject, invite: JSONObject): Unit = {}

  override def available(): Unit = {}

  def printProvisionInstructions() = {
    val agencyURL: String = sdkConfig.verityInstance.endpoint.url
    println("===========================================")
    println("==               MANUAL STEPS            ==")
    println("===========================================")
    println("1) Start with a fresh installation of Connect.Me and enter Developer Mode")
    println(s"2) Enter ${agencyURL.replace("/agency/msg", "")} as AGENCY URL")
  }

  override def provision_0_7: ProvisionV0_7 = new UndefinedProvision_0_7() {
    override def provision(context: Context): Context = {
      printProvisionInstructions()

      context
    }
  }

  override def provision_0_7(token: String): ProvisionV0_7 = new UndefinedProvision_0_7() {
    override def provision(context: Context): Context = {
      printProvisionInstructions()

      context
    }
  }

  override def updateEndpoint_0_6: UpdateEndpointV0_6 = throw new NotImplementedError

  override def updateConfigs_0_6(name: String, logoUrl: String): UpdateConfigsV0_6 = throw new NotImplementedError

  override def updateConfigs_0_6(): UpdateConfigsV0_6 = throw new NotImplementedError

  override def issuerSetup_0_6: IssuerSetupV0_6 = throw new NotImplementedError

  override def writeSchema_0_6(name: String,
                               version: String,
                               attrs: String*): WriteSchemaV0_6 = throw new NotImplementedError

  override def writeCredDef_0_6(name: String,
                                schemaId: String,
                                tag: Option[String],
                                revocationDetails: Option[RevocationRegistryConfig]): WriteCredentialDefinitionV0_6 =
    throw new NotImplementedError

  override def basicMessage_1_0(forRelationship: DidStr,
                                content: String,
                                sentTime: String,
                                localization: String): BasicMessageV1_0 = throw new NotImplementedError

  override def committedAnswer_1_0(forRelationship: DidStr,
                                   questionText: String,
                                   questionDescription: String,
                                   validResponses: Seq[String],
                                   requireSig: Boolean): CommittedAnswerV1_0 = throw new NotImplementedError

  override def committedAnswer_1_0(forRelationship: DidStr,
                                   threadId: String,
                                   answerStr: String): CommittedAnswerV1_0 = {
    new UndefinedCommittedAnswer_1_0(){
      override def answer(context: Context): Unit = {
        println("===========================================")
        println("==               MANUAL STEPS            ==")
        println("===========================================")
        println(s"1) Answer with '$answerStr' in Connect.Me")
      }
    }
  }

  override def committedAnswer_1_0(forRelationship: DidStr,
                                   threadId: String): CommittedAnswerV1_0 = throw new NotImplementedError

  override def connecting_1_0(sourceId: String, label: String, inviteUrl: String): ConnectionsV1_0 =
    new UndefinedConnections_1_0() {
      override def accept(context: Context): Unit = {
        println("===========================================")
        println("==               MANUAL STEPS            ==")
        println("===========================================")

        println("Invite:" + inviteUrl)
        QRCode.from(inviteUrl).withSize(500, 500)
          .writeTo(new FileOutputStream(new File("/tmp/qrcode.png")))

        println("Please do the following:")
        println("1) Scan QR code at '/tmp/qrcode.png'")
        println("2) Accept connection.")
      }
    }

  override def connectingWithOutOfBand_1_0(sourceId: String, label: String, inviteUrl: String): ConnectionsV1_0 =
    new UndefinedConnections_1_0() {
      override def accept(context: Context): Unit = {
        println("===========================================")
        println("==               MANUAL STEPS            ==")
        println("===========================================")

        println("Invite:" + inviteUrl)
        QRCode.from(inviteUrl).withSize(500, 500)
          .writeTo(new FileOutputStream(new File("/tmp/qrcode.png")))

        println("Please do the following:")
        println("1) Scan QR code at '/tmp/qrcode.png'")
        println("2) Accept connection.")
      }
    }

  override def outOfBand_1_0(forRelationship: String, inviteURL: String): OutOfBandV1_0 = new UndefinedOutOfBand_1_0() {
    override def handshakeReuse(context: Context): Unit = {}
  }

  override def relationship_1_0(label: String): RelationshipV1_0 = ???

  override def relationship_1_0(forRelationship: String, threadId: String): RelationshipV1_0 = ???

  override def issueCredential_1_0(forRelationship: String,
                                   credDefId: String,
                                   credValues: Map[String, String],
                                   comment: String,
                                   price: String,
                                   autoIssue: Boolean,
                                   byInvitation: Boolean): IssueCredentialV1_0 = ???

  override def issueCredential_1_0(forRelationship: String, threadId: String): IssueCredentialV1_0 =
    new UndefinedIssueCredential_1_0() {
      override def requestCredential(context: Context): Unit = {
        println("===========================================")
        println("==               MANUAL STEPS            ==")
        println("===========================================")
        println("1) Accept Credential Offer in Connect.Me")
      }
    }

  override def issueCredentialComplete_1_0(): Unit = {
    println("===========================================")
    println("==               MANUAL STEPS            ==")
    println("===========================================")
    println("1) Check if credential received/stored in Connect.Me")
  }

  override def presentProof_1_0(forRelationship: String,
                                name: String,
                                proofAttrs: Array[Attribute],
                                proofPredicate: Array[Predicate],
                                byInvitation: Boolean = false): PresentProofV1_0 = ???

  override def presentProof_1_0(forRelationship: DidStr,
                                threadId: String): PresentProofV1_0 = {
    new UndefinedPresentProof_1_0 {
      override def acceptRequest(context: Context): Unit = {
        println("===========================================")
        println("==               MANUAL STEPS            ==")
        println("===========================================")
        println("1) Accept proof request in Connect.Me")
      }
    }
  }

  override def presentProof_1_0(forRelationship: String, proofAttrs: Array[ProposedAttribute], proofPredicates: Array[ProposedPredicate]): PresentProofV1_0 = ???
}

object ManualSdkProvider {

}
