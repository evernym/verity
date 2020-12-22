package com.evernym.integrationtests.e2e.sdk

import java.nio.file.Path

import com.evernym.integrationtests.e2e.env.SdkConfig
import com.evernym.verity.protocol.engine.{DID, ThreadId}
import com.evernym.verity.sdk.protocols.basicmessage.BasicMessage
import com.evernym.verity.sdk.protocols.basicmessage.v1_0.BasicMessageV1_0
import com.evernym.verity.sdk.protocols.connecting.v1_0.ConnectionsV1_0
import com.evernym.verity.sdk.protocols.issuecredential.IssueCredential
import com.evernym.verity.sdk.protocols.issuecredential.v1_0.IssueCredentialV1_0
import com.evernym.verity.sdk.protocols.issuersetup.IssuerSetup
import com.evernym.verity.sdk.protocols.issuersetup.v0_6.IssuerSetupV0_6
import com.evernym.verity.sdk.protocols.outofband.v1_0.OutOfBandV1_0
import com.evernym.verity.sdk.protocols.presentproof.PresentProof
import com.evernym.verity.sdk.protocols.presentproof.common.{Attribute, Predicate, ProposedAttribute, ProposedPredicate}
import com.evernym.verity.sdk.protocols.presentproof.v1_0.PresentProofV1_0
import com.evernym.verity.sdk.protocols.provision.Provision
import com.evernym.verity.sdk.protocols.provision.v0_7.ProvisionV0_7
import com.evernym.verity.sdk.protocols.questionanswer.CommittedAnswer
import com.evernym.verity.sdk.protocols.questionanswer.v1_0.CommittedAnswerV1_0
import com.evernym.verity.sdk.protocols.relationship.Relationship
import com.evernym.verity.sdk.protocols.relationship.v1_0.RelationshipV1_0
import com.evernym.verity.sdk.protocols.updateconfigs.UpdateConfigs
import com.evernym.verity.sdk.protocols.updateconfigs.v0_6.UpdateConfigsV0_6
import com.evernym.verity.sdk.protocols.updateendpoint.UpdateEndpoint
import com.evernym.verity.sdk.protocols.updateendpoint.v0_6.UpdateEndpointV0_6
import com.evernym.verity.sdk.protocols.writecreddef.WriteCredentialDefinition
import com.evernym.verity.sdk.protocols.writecreddef.v0_6.{RevocationRegistryConfig, WriteCredentialDefinitionV0_6}
import com.evernym.verity.sdk.protocols.writeschema.WriteSchema
import com.evernym.verity.sdk.protocols.writeschema.v0_6.WriteSchemaV0_6
import com.evernym.verity.sdk.utils.Context

import scala.collection.JavaConverters._

class JavaSdkProvider(val sdkConfig: SdkConfig, val testDir: Option[Path] = None)
  extends BaseSdkProvider
    with ListeningSdkProvider {

  override def sdkType: String = "JAVA"

  override def available(): Unit = classOf[Context].getName

  override def provision_0_7: ProvisionV0_7 = Provision.v0_7()

  override def updateEndpoint_0_6: UpdateEndpointV0_6 = UpdateEndpoint.v0_6()

  override def updateConfigs_0_6(name: String, logoUrl: String): UpdateConfigsV0_6 =
    UpdateConfigs.v0_6(name, logoUrl)

  override def updateConfigs_0_6(): UpdateConfigsV0_6 = UpdateConfigs.v0_6()

  override def issuerSetup_0_6: IssuerSetupV0_6 = IssuerSetup.v0_6()

  override def writeSchema_0_6(name: String, version: String, attrs: String*): WriteSchemaV0_6 =
    WriteSchema.v0_6(name, version, attrs.toArray: _*)

  override def writeCredDef_0_6(name: String,
                                schemaId: String,
                                tag: Option[String],
                                revocationDetails: Option[RevocationRegistryConfig]): WriteCredentialDefinitionV0_6 =
    WriteCredentialDefinition.v0_6(name, schemaId, tag.orNull, revocationDetails.orNull)

  override def basicMessage_1_0(forRelationship: DID,
                                content: String,
                                sentTime: String,
                                localization: String): BasicMessageV1_0 =
    BasicMessage.v1_0(forRelationship, content, sentTime, localization)

  override def committedAnswer_1_0(forRelationship: DID,
                                   questionText: String,
                                   questionDescription: String,
                                   validResponses: Seq[String],
                                   requireSig: Boolean): CommittedAnswerV1_0 =
    CommittedAnswer.v1_0(forRelationship, questionText, questionDescription, validResponses.toArray, requireSig)

  override def committedAnswer_1_0(forRelationship: DID, threadId: String, answer: String): CommittedAnswerV1_0 =
    CommittedAnswer.v1_0(forRelationship, threadId, answer)

  override def committedAnswer_1_0(forRelationship: DID, threadId: String): CommittedAnswerV1_0 =
    CommittedAnswer.v1_0(forRelationship, threadId)

  override def connecting_1_0(sourceId: String, label: String, base64InviteURL: String): ConnectionsV1_0 = ???

  override def connectingWithOutOfBand_1_0(sourceId: String,
                                           label: String,
                                           base64InviteURL: String): ConnectionsV1_0 = ???

  override def outOfBand_1_0(forRelationship: String, inviteURL: String): OutOfBandV1_0 = ???

  override def relationship_1_0(label: String): RelationshipV1_0 = Relationship.v1_0(label)

  override def relationship_1_0(forRelationship: String, threadId: String): RelationshipV1_0 =
    Relationship.v1_0(forRelationship, threadId)

  override def issueCredential_1_0(forRelationship: String,
                                   credDefId: String,
                                   credValues: Map[String, String],
                                   comment: String,
                                   price: String = "0",
                                   autoIssue: Boolean = false,
                                   byInvitation: Boolean = false): IssueCredentialV1_0 =
    IssueCredential.v1_0(forRelationship, credDefId, credValues.asJava, comment, price, autoIssue, byInvitation)

  override def issueCredential_1_0(forRelationship: String, threadId: String): IssueCredentialV1_0 =
    IssueCredential.v1_0(forRelationship, threadId)

  override def issueCredentialComplete_1_0(): Unit = ???

  override   def presentProof_1_0(forRelationship: String,
                                  name: String,
                                  proofAttrs: Array[Attribute],
                                  proofPredicate: Array[Predicate],
                                  byInvitation: Boolean = false): PresentProofV1_0 =
    PresentProof.v1_0(forRelationship, name, proofAttrs, proofPredicate, byInvitation)

  override def presentProof_1_0(forRelationship: DID, threadId: String): PresentProofV1_0 =
    PresentProof.v1_0(forRelationship, threadId)

  override def presentProof_1_0(forRelationship: String, proofAttrs: Array[ProposedAttribute], proofPredicates: Array[ProposedPredicate]): PresentProofV1_0 =
    PresentProof.v1_0(forRelationship, proofAttrs, proofPredicates)
}
