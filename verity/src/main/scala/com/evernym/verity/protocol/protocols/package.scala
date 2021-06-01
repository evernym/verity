package com.evernym.verity.protocol

import com.evernym.verity.drivers.{AgentProvisioningDriver, TicTacToeAI, WalletBackupDriver, _}
import com.evernym.verity.protocol.container.actor.ActorDriverGenParam
import com.evernym.verity.protocol.engine.PinstIdResolution.{DEPRECATED_V0_1, V0_2}
import com.evernym.verity.protocol.engine.{ProtoDef, ProtoRef, ProtocolRegistry}
import com.evernym.verity.protocol.protocols.agentprovisioning.v_0_5.{AgentProvisioningProtoDef => AgentProvisioningProtoDef_v_0_5}
import com.evernym.verity.protocol.protocols.agentprovisioning.v_0_6.{AgentProvisioningProtoDef => AgentProvisioningProtoDef_v_0_6}
import com.evernym.verity.protocol.protocols.agentprovisioning.v_0_7.{AgentProvisioningDefinition => AgentProvisioningProtoDef_v_0_7}
import com.evernym.verity.protocol.protocols.committedAnswer.v_1_0.CommittedAnswerDefinition
import com.evernym.verity.protocol.protocols.connecting.v_0_5.{ConnectingProtoDef => ConnectingProtoDef_v_0_5}
import com.evernym.verity.protocol.protocols.connecting.v_0_6.{ConnectingProtoDef => ConnectingProtoDef_v_0_6}
import com.evernym.verity.protocol.protocols.connections.v_1_0.ConnectionsDef
import com.evernym.verity.protocol.protocols.deaddrop.DeadDropProtoDef
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.{IssueCredentialProtoDef => IssueCredentialProtocolDef_v_1_0}
import com.evernym.verity.protocol.protocols.issuersetup.v_0_6.IssuerSetupDefinition
import com.evernym.verity.protocol.protocols.outofband.v_1_0.OutOfBandDef
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.PresentProofDef
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.QuestionAnswerDefinition
import com.evernym.verity.protocol.protocols.basicMessage.v_1_0.BasicMessageDefinition
import com.evernym.verity.protocol.protocols.relationship.v_1_0.RelationshipDef
import com.evernym.verity.protocol.protocols.tictactoe.TicTacToeProtoDef
import com.evernym.verity.protocol.protocols.tokenizer.TokenizerDefinition
import com.evernym.verity.protocol.protocols.trustping.v_1_0.TrustPingDefinition
import com.evernym.verity.protocol.protocols.updateConfigs.v_0_6.UpdateConfigsDefinition
import com.evernym.verity.protocol.protocols.walletBackup.WalletBackupProtoDef
import com.evernym.verity.protocol.protocols.writeCredentialDefinition.v_0_6.CredDefDefinition
import com.evernym.verity.protocol.protocols.writeSchema.v_0_6.WriteSchemaDefinition

package object protocols {
  val protocolRegistry: ProtocolRegistry[ActorDriverGenParam] = ProtocolRegistry(
    (AgentProvisioningProtoDef_v_0_5, DEPRECATED_V0_1, { new AgentProvisioningDriver(_) }),
    (AgentProvisioningProtoDef_v_0_6, DEPRECATED_V0_1, { new AgentProvisioningDriver(_) }),
    (AgentProvisioningProtoDef_v_0_7, V0_2, { new AgentProvisioningDriver(_) }),

    (UpdateConfigsDefinition, V0_2, { new UpdateConfigsDriver(_) }),

    (RelationshipDef, V0_2, {new RelationshipDriver(_)}),

    (OutOfBandDef, V0_2, {new OutOfBandDriver(_)}),

    (BasicMessageDefinition, V0_2, { new BasicMessageDriver(_) }),

    (ConnectingProtoDef_v_0_5, DEPRECATED_V0_1, {new ConnectingDriver(_)}),
    (ConnectingProtoDef_v_0_6, DEPRECATED_V0_1, {new ConnectingDriver(_)}),
    (ConnectionsDef, V0_2, {new ConnectionsDriver(_)}),

    (WalletBackupProtoDef, DEPRECATED_V0_1, { new WalletBackupDriver(_) }),
    (DeadDropProtoDef, DEPRECATED_V0_1),

    (WriteSchemaDefinition, V0_2, { new WriteSchemaDriver(_) }),
    (CredDefDefinition, V0_2, { new WriteCredDefDriver(_) }),

    (IssuerSetupDefinition, V0_2, { new IssuerSetupDriver(_) }),

    (IssueCredentialProtocolDef_v_1_0, V0_2, { new IssueCredentialDriver(_) }),

    (PresentProofDef, V0_2, { new PresentProofDriver(_) }),

    (QuestionAnswerDefinition, V0_2, { new QuestionAnswerDriver(_) }),
    (CommittedAnswerDefinition, V0_2, { new CommittedAnswerDriver(_) }),

    (TokenizerDefinition, V0_2, { new TokenizerDriver(_) }),

    (TicTacToeProtoDef, V0_2, {new TicTacToeAI(_)}),

    (TrustPingDefinition, V0_2, {new TrustPingDriver(_)})

  )

  def protoDef(protoRef: ProtoRef): ProtoDef = {
    protocolRegistry
      .entries
      .find(_.protoDef.msgFamily.protoRef == protoRef)
      .getOrElse { throw new NoSuchElementException("protocol def not found for proto ref: " + protoRef) }
      .protoDef
  }
}
