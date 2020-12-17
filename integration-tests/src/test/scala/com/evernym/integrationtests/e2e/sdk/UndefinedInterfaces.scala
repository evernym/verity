package com.evernym.integrationtests.e2e.sdk

import java.lang

import com.evernym.verity.sdk.protocols.connecting.v1_0.ConnectionsV1_0
import com.evernym.verity.sdk.protocols.issuecredential.v1_0.IssueCredentialV1_0
import com.evernym.verity.sdk.protocols.issuersetup.v0_6.IssuerSetupV0_6
import com.evernym.verity.sdk.protocols.outofband.v1_0.OutOfBandV1_0
import com.evernym.verity.sdk.protocols.presentproof.v1_0.PresentProofV1_0
import com.evernym.verity.sdk.protocols.provision.v0_7.ProvisionV0_7
import com.evernym.verity.sdk.protocols.basicmessage.v1_0.BasicMessageV1_0
import com.evernym.verity.sdk.protocols.questionanswer.v1_0.CommittedAnswerV1_0
import com.evernym.verity.sdk.protocols.relationship.v1_0.{GoalCode, RelationshipV1_0}
import com.evernym.verity.sdk.protocols.updateconfigs.v0_6.UpdateConfigsV0_6
import com.evernym.verity.sdk.protocols.updateendpoint.v0_6.UpdateEndpointV0_6
import com.evernym.verity.sdk.protocols.writecreddef.v0_6.WriteCredentialDefinitionV0_6
import com.evernym.verity.sdk.protocols.writeschema.v0_6.WriteSchemaV0_6
import com.evernym.verity.sdk.utils.Context
import org.json.JSONObject


object UndefinedInterfaces {

  class UndefinedProvision_0_7 extends ProvisionV0_7 {
    override def provision(context: Context): Context = throw new NotImplementedError
    override def provisionMsg(context: Context): JSONObject = throw new NotImplementedError
    override def provisionMsgPacked(context: Context): Array[Byte] = throw new NotImplementedError

    override def getThreadId: String = throw new NotImplementedError
  }

  class UndefinedUpdateEndpoint_0_6 extends UpdateEndpointV0_6 {
    override def update(context: Context): Unit = throw new NotImplementedError
    override def updateMsg(context: Context): JSONObject = throw new NotImplementedError
    override def updateMsgPacked(context: Context): Array[Byte] = throw new NotImplementedError

    override def getThreadId: String = throw new NotImplementedError
  }

  class UndefinedUpdateConfigs_0_6 extends UpdateConfigsV0_6 {
    override def update(context: Context): Unit = throw new NotImplementedError
    override def updateMsg(context: Context): JSONObject = throw new NotImplementedError
    override def updateMsgPacked(context: Context): Array[Byte] = throw new NotImplementedError

    override def status(context: Context): Unit = throw new NotImplementedError
    override def statusMsg(context: Context): JSONObject = throw new NotImplementedError
    override def statusMsgPacked(context: Context): Array[Byte] = throw new NotImplementedError

    override def getThreadId: String = throw new NotImplementedError
  }

  class UndefinedIssuerSetup_0_6 extends IssuerSetupV0_6 {
    override def create(context: Context): Unit = throw new NotImplementedError
    override def createMsg(context: Context): JSONObject = throw new NotImplementedError
    override def createMsgPacked(context: Context): Array[Byte] = throw new NotImplementedError

    override def currentPublicIdentifier(context: Context): Unit = throw new NotImplementedError
    override def currentPublicIdentifierMsg(context: Context): JSONObject = throw new NotImplementedError
    override def currentPublicIdentifierMsgPacked(context: Context): Array[Byte] = throw new NotImplementedError

    override def getThreadId: String = throw new NotImplementedError
  }

  class UndefinedWriteSchema_0_6 extends WriteSchemaV0_6 {
    override def write(context: Context): Unit = throw new NotImplementedError
    override def writeMsg(context: Context): JSONObject = throw new NotImplementedError
    override def writeMsgPacked(context: Context): Array[Byte] = throw new NotImplementedError

    override def getThreadId: String = throw new NotImplementedError
  }

  class UndefinedWriteCredentialDefinition_0_6 extends WriteCredentialDefinitionV0_6 {
    override def write(context: Context): Unit = throw new NotImplementedError
    override def writeMsg(context: Context): JSONObject = throw new NotImplementedError
    override def writeMsgPacked(context: Context): Array[Byte] = throw new NotImplementedError

    override def getThreadId: String = throw new NotImplementedError
  }


  class UndefinedConnections_1_0 extends ConnectionsV1_0 {
    override def status(context: Context): Unit = throw new NotImplementedError
    override def statusMsg(context: Context): JSONObject = throw new NotImplementedError
    override def statusMsgPacked(context: Context): Array[Byte] = throw new NotImplementedError

    override def accept(context: Context): Unit = throw new NotImplementedError
    override def acceptMsg(context: Context): JSONObject = throw new NotImplementedError
    override def acceptMsgPacked(context: Context): Array[Byte] = throw new NotImplementedError

    override def getThreadId: String = throw new NotImplementedError
  }

  class UndefinedOutOfBand_1_0 extends OutOfBandV1_0 {
    override def handshakeReuse(context: Context): Unit = throw new NotImplementedError
    override def handshakeReuseMsg(context: Context): JSONObject = throw new NotImplementedError
    override def handshakeReuseMsgPacked(context: Context): Array[Byte] = throw new NotImplementedError

    override def getThreadId: String = throw new NotImplementedError
  }

  class UndefinedPresentProof_1_0 extends PresentProofV1_0 {
    override def request(context: Context): Unit = throw new NotImplementedError
    override def requestMsg(context: Context): JSONObject = throw new NotImplementedError
    override def requestMsgPacked(context: Context): Array[Byte] = throw new NotImplementedError

    override def acceptRequest(context: Context): Unit = throw new NotImplementedError
    override def acceptRequestMsg(context: Context): JSONObject = throw new NotImplementedError
    override def acceptRequestMsgPacked(context: Context): Array[Byte] = throw new NotImplementedError

    override def status(context: Context): Unit = throw new NotImplementedError
    override def statusMsg(context: Context): JSONObject = throw new NotImplementedError
    override def statusMsgPacked(context: Context): Array[Byte] = throw new NotImplementedError

    override def reject(context: Context, s: String): Unit = throw new NotImplementedError
    override def rejectMsg(context: Context, s: String): JSONObject = throw new NotImplementedError
    override def rejectMsgPacked(context: Context, s: String): Array[Byte] = throw new NotImplementedError

    override def propose(context: Context): Unit = throw new NotImplementedError
    override def proposeMsg(context: Context): JSONObject = throw new NotImplementedError
    override def proposeMsgPacked(context: Context): Array[Byte] = throw new NotImplementedError

    override def acceptProposal(context: Context): Unit = throw new NotImplementedError
    override def acceptProposalMsg(context: Context): JSONObject = throw new NotImplementedError
    override def acceptProposalMsgPacked(context: Context): Array[Byte] = throw new NotImplementedError

    override def getThreadId: String = throw new NotImplementedError
  }

  class UndefinedBasicMessage_1_0 extends BasicMessageV1_0 {
    override def message(context: Context): Unit = throw new NotImplementedError
    override def messageMsg(context: Context): JSONObject = throw new NotImplementedError
    override def messageMsgPacked(context: Context): Array[Byte] = throw new NotImplementedError

    override def getThreadId: String = throw new NotImplementedError
  }

  class UndefinedCommittedAnswer_1_0 extends CommittedAnswerV1_0 {
    override def ask(context: Context): Unit = throw new NotImplementedError
    override def askMsg(context: Context): JSONObject = throw new NotImplementedError
    override def askMsgPacked(context: Context): Array[Byte] = throw new NotImplementedError

    override def answer(context: Context): Unit = throw new NotImplementedError
    override def answerMsg(context: Context): JSONObject = throw new NotImplementedError
    override def answerMsgPacked(context: Context): Array[Byte] = throw new NotImplementedError

    override def status(context: Context): Unit = throw new NotImplementedError
    override def statusMsg(context: Context): JSONObject = throw new NotImplementedError
    override def statusMsgPacked(context: Context): Array[Byte] = throw new NotImplementedError

    override def getThreadId: String = throw new NotImplementedError
  }

  class UndefinedIssueCredential_1_0 extends IssueCredentialV1_0 {
    override def proposeCredential(context: Context): Unit = throw new NotImplementedError
    override def proposeCredentialMsg(context: Context): JSONObject = throw new NotImplementedError
    override def proposeCredentialMsgPacked(context: Context): Array[Byte] = throw new NotImplementedError

    override def offerCredential(context: Context): Unit = throw new NotImplementedError
    override def offerCredentialMsg(context: Context): JSONObject = throw new NotImplementedError
    override def offerCredentialMsgPacked(context: Context): Array[Byte] = throw new NotImplementedError

    override def requestCredential(context: Context): Unit = throw new NotImplementedError
    override def requestCredentialMsg(context: Context): JSONObject = throw new NotImplementedError
    override def requestCredentialMsgPacked(context: Context): Array[Byte] = throw new NotImplementedError

    override def issueCredential(context: Context): Unit = throw new NotImplementedError
    override def issueCredentialMsg(context: Context): JSONObject = throw new NotImplementedError
    override def issueCredentialMsgPacked(context: Context): Array[Byte] = throw new NotImplementedError

    override def reject(context: Context): Unit = throw new NotImplementedError
    override def rejectMsg(context: Context): JSONObject = throw new NotImplementedError
    override def rejectMsgPacked(context: Context): Array[Byte] = throw new NotImplementedError

    override def status(context: Context): Unit = throw new NotImplementedError
    override def statusMsg(context: Context): JSONObject = throw new NotImplementedError
    override def statusMsgPacked(context: Context): Array[Byte] = throw new NotImplementedError

    override def getThreadId: String = throw new NotImplementedError
  }

  class UndefinedRelationship_1_0 extends RelationshipV1_0 {
    override def getThreadId: String = throw new NotImplementedError

    override def create(context: Context): Unit = throw new NotImplementedError
    override def createMsg(context: Context): JSONObject = throw new NotImplementedError
    override def createMsgPacked(context: Context): Array[Byte] = throw new NotImplementedError

    override def connectionInvitation(context: Context): Unit = throw new NotImplementedError
    override def connectionInvitation(context: Context, shortInvite: lang.Boolean): Unit = throw new NotImplementedError
    override def connectionInvitationMsg(context: Context): JSONObject = throw new NotImplementedError
    override def connectionInvitationMsg(context: Context, shortInvite: lang.Boolean): JSONObject = throw new NotImplementedError
    override def connectionInvitationMsgPacked(context: Context): Array[Byte] = throw new NotImplementedError
    override def connectionInvitationMsgPacked(context: Context, shortInvite: lang.Boolean): Array[Byte] = throw new NotImplementedError

    override def outOfBandInvitation(context: Context): Unit = throw new NotImplementedError
    override def outOfBandInvitation(context: Context, shortInvite: lang.Boolean): Unit = throw new NotImplementedError
    override def outOfBandInvitation(context: Context, shortInvite: lang.Boolean, goal: GoalCode): Unit = throw new NotImplementedError
    override def outOfBandInvitationMsg(context: Context): JSONObject = throw new NotImplementedError
    override def outOfBandInvitationMsg(context: Context, shortInvite: lang.Boolean): JSONObject = throw new NotImplementedError
    override def outOfBandInvitationMsg(context: Context, shortInvite: lang.Boolean, goal: GoalCode): JSONObject = throw new NotImplementedError
    override def outOfBandInvitationMsgPacked(context: Context): Array[Byte] = throw new NotImplementedError
    override def outOfBandInvitationMsgPacked(context: Context, shortInvite: lang.Boolean): Array[Byte] = throw new NotImplementedError
    override def outOfBandInvitationMsgPacked(context: Context, shortInvite: lang.Boolean, goal: GoalCode): Array[Byte] = throw new NotImplementedError

    override def smsConnectionInvitation(context: Context): Unit = throw new NotImplementedError
    override def smsConnectionInvitationMsg(context: Context): JSONObject = throw new NotImplementedError
    override def smsConnectionInvitationMsgPacked(context: Context): Array[Byte] = throw new NotImplementedError

    override def smsOutOfBandInvitation(context: Context): Unit = throw new NotImplementedError
    override def smsOutOfBandInvitation(context: Context, goal: GoalCode): Unit = throw new NotImplementedError
    override def smsOutOfBandInvitationMsg(context: Context): JSONObject = throw new NotImplementedError

    override def smsOutOfBandInvitationMsg(context: Context, goal: GoalCode): JSONObject = throw new NotImplementedError
    override def smsOutOfBandInvitationMsgPacked(context: Context): Array[Byte] = throw new NotImplementedError
    override def smsOutOfBandInvitationMsgPacked(context: Context, goal: GoalCode): Array[Byte] = throw new NotImplementedError
  }
}
