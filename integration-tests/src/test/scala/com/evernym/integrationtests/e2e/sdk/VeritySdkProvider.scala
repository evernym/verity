package com.evernym.integrationtests.e2e.sdk

import java.nio.file.Path
import java.util.UUID

import com.evernym.integrationtests.e2e.env.{SdkConfig, SdkType}
import com.evernym.integrationtests.e2e.sdk.process.{NodeSdkProvider, PythonSdkProvider}
import com.evernym.integrationtests.e2e.sdk.vcx.VcxSdkProvider


import com.evernym.verity.protocol.engine.DID
import com.evernym.verity.sdk.protocols.connecting.v1_0.ConnectionsV1_0
import com.evernym.verity.sdk.protocols.issuecredential.v1_0.IssueCredentialV1_0
import com.evernym.verity.sdk.protocols.issuersetup.v0_6.IssuerSetupV0_6
import com.evernym.verity.sdk.protocols.outofband.v1_0.OutOfBandV1_0
import com.evernym.verity.sdk.protocols.presentproof.common.{Attribute, Predicate}
import com.evernym.verity.sdk.protocols.presentproof.v1_0.PresentProofV1_0
import com.evernym.verity.sdk.protocols.provision.v0_7.ProvisionV0_7
import com.evernym.verity.sdk.protocols.questionanswer.v1_0.CommittedAnswerV1_0
import com.evernym.verity.sdk.protocols.basicmessage.v1_0.BasicMessageV1_0
import com.evernym.verity.sdk.protocols.relationship.v1_0.RelationshipV1_0
import com.evernym.verity.sdk.protocols.updateconfigs.v0_6.UpdateConfigsV0_6
import com.evernym.verity.sdk.protocols.updateendpoint.v0_6.UpdateEndpointV0_6
import com.evernym.verity.sdk.protocols.writecreddef.v0_6.{RevocationRegistryConfig, WriteCredentialDefinitionV0_6}
import com.evernym.verity.sdk.protocols.writeschema.v0_6.WriteSchemaV0_6
import com.evernym.verity.sdk.utils.Context
import com.evernym.verity.sdk.wallet.{DefaultWalletConfig, WalletConfig}
import org.json.JSONObject


case class RelData(alias: String, owningDID: DID, otherDID: Option[DID])

trait VeritySdkProvider {
  def unsupportedProtocol(protocolName: String, msg: String): Nothing = {
    val error = s"Protocol - $protocolName - is not supported by the ${this.getClass.getSimpleName} provider -- $msg"
    throw new UnsupportedOperationException(error)
  }
  implicit val shouldPrintDebug = false

  var publicDID: Option[String] = None

  def sdkConfig: SdkConfig

  def sdkType: String

  def context: Context

  def updateContext(context: Context): Unit

  def data(key: String): Option[String]

  def data_!(key: String): String = data(key)
    .getOrElse(throw new Exception("expected data held by sdk was not found!"))

  def updateData(key: String, value: String)

  def relationship(id: String): Option[RelData]

  def relationship_!(id: String): RelData = relationship(id)
    .getOrElse(throw new Exception("expected relationship held by sdk was not found!"))

  def updateRelationship(id: String, data: RelData): Unit
  def updateRelationship(id: String, accepted: JSONObject, invite: JSONObject): Unit

  /**
    * Check that the sdk is available (ex. on class path, installed or whatever)
    */
  def available(): Unit

  def clean(): Unit = {}

  def walletConfig: WalletConfig

  def walletConfig(path: String): WalletConfig


  //protocol apis

  def provision_0_7: ProvisionV0_7

  def issuerSetup_0_6: IssuerSetupV0_6

  def updateEndpoint_0_6: UpdateEndpointV0_6

  def updateConfigs_0_6(name: String, logoUrl: String): UpdateConfigsV0_6

  def updateConfigs_0_6(): UpdateConfigsV0_6

  def writeSchema_0_6(name: String, version: String, attrs: String*): WriteSchemaV0_6

  def writeCredDef_0_6(name: String,
                       schemaId: String,
                       tag: Option[String] = None,
                       revocationDetails: Option[RevocationRegistryConfig] = Some(WriteCredentialDefinitionV0_6.disabledRegistryConfig())
                  ): WriteCredentialDefinitionV0_6

  def relationship_1_0(label: String): RelationshipV1_0

  def relationship_1_0(forRelationship: String, threadId: String): RelationshipV1_0

  def connecting_1_0(sourceId: String, label: String, inviteURL: String): ConnectionsV1_0

  def connectingWithOutOfBand_1_0(sourceId: String, label: String, inviteURL: String): ConnectionsV1_0

  def outOfBand_1_0(forRelationship:String, inviteUrl: String): OutOfBandV1_0

  def issueCredential_1_0(forRelationship: String,
                          credDefId: String,
                          credValues: Map[String, String],
                          comment: String,
                          price: String = "0",
                          autoIssue: Boolean = false,
                          byInvitation: Boolean = false): IssueCredentialV1_0

  def issueCredential_1_0(forRelationship: String, threadId: String): IssueCredentialV1_0

  def issueCredentialComplete_1_0(): Unit

  def presentProof_1_0(forRelationship: String,
                       name: String,
                       proofAttrs: Array[Attribute],
                       proofPredicate: Array[Predicate],
                       byInvitation: Boolean = false): PresentProofV1_0

  def presentProof_1_0(forRelationship: DID,
                       threadId: String): PresentProofV1_0

  def committedAnswer_1_0(forRelationship: DID,
                          questionText: String,
                          questionDescription: String,
                          validResponses: Seq[String],
                          requireSig: Boolean): CommittedAnswerV1_0

  def committedAnswer_1_0(forRelationship: DID,
                          threadId: String,
                          answer: String): CommittedAnswerV1_0

  def committedAnswer_1_0(forRelationship: DID,
                          threadId: String): CommittedAnswerV1_0

  def basicMessage_1_0(forRelationship: DID,
                       content: String,
                       sentTime: String,
                       localization: String): BasicMessageV1_0
}

trait DataHolderSdkProvider {

  var heldContext: Context = _
  var heldData: Map[String, String] = Map.empty
  var heldRelationships: Map[String, RelData] = Map.empty

  def context: Context = heldContext
  def updateContext(context: Context): Unit = {heldContext = context}

  def data(key: String): Option[String] = heldData.get(key)
  def updateData(key: String, value: String): Unit = heldData = heldData + (key -> value)

  def relationship(id: String): Option[RelData] = heldRelationships.get(id)
  def relationships: Map[String, RelData] = heldRelationships

  def updateRelationship(id: String, data: RelData): Unit = heldRelationships = heldRelationships + (id -> data)

  def updateRelationship(id: String, accepted: JSONObject, invite: JSONObject): Unit = {
    val otherDID = invite.getJSONObject("senderDetail").getString("DID")
    val owningDID = accepted.getString("did")

    val data = RelData(id, owningDID, Some(otherDID))

    heldRelationships = heldRelationships + (id -> data)
  }
}

trait WalletedSdkProvider {
  val defaultPassword = "test-password"

  def sdkConfig: SdkConfig

  def walletName = {
    s"${sdkConfig.name}-wallet-${UUID.randomUUID().toString}"
  }

  def walletConfig: WalletConfig = DefaultWalletConfig.build(walletName, "test-password")
  def walletConfig(path: String): WalletConfig = DefaultWalletConfig.build(
    walletName,
    defaultPassword,
    path
  )
}

abstract class BaseSdkProvider
  extends VeritySdkProvider
     with DataHolderSdkProvider
     with WalletedSdkProvider

object VeritySdkProvider {
  def fromSdkConfig(c: SdkConfig, testDir: Path): VeritySdkProvider = {
    c.sdkType match {
      case SdkType.Java   => new JavaSdkProvider(c, Option(testDir))
      case SdkType.Vcx    => new VcxSdkProvider(c)
      case SdkType.Python => new PythonSdkProvider(c, testDir)
      case SdkType.Node   => new NodeSdkProvider(c, testDir)
      case SdkType.Manual => new ManualSdkProvider(c)
      case SdkType.Rest   => new RestSdkProvider(c)
      case _ => throw new Exception("Unknown SDK type, must by a known type")
    }
  }

  def debugPrintln(x: Any)(implicit shouldPrint: Boolean): Unit = {
    if(shouldPrint) println(x)
  }
}
