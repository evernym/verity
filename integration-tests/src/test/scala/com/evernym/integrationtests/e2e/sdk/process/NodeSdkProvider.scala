package com.evernym.integrationtests.e2e.sdk.process

import com.evernym.integrationtests.e2e.env.SdkConfig
import com.evernym.integrationtests.e2e.sdk.UndefinedInterfaces._
import com.evernym.integrationtests.e2e.sdk.process.ProcessSdkProvider.{InterpreterEnv, MapAsJsonObject, TokenAsJsonObject, sdkErrExitCode}
import com.evernym.verity.protocol.engine.DID
import com.evernym.verity.sdk.protocols.basicmessage.v1_0.BasicMessageV1_0
import com.evernym.verity.sdk.protocols.connecting.v1_0.ConnectionsV1_0
import com.evernym.verity.sdk.protocols.issuecredential.v1_0.IssueCredentialV1_0
import com.evernym.verity.sdk.protocols.issuersetup.v0_6.IssuerSetupV0_6
import com.evernym.verity.sdk.protocols.outofband.v1_0.OutOfBandV1_0
import com.evernym.verity.sdk.protocols.presentproof.common.{Attribute, Predicate, ProposedAttribute, ProposedPredicate}
import com.evernym.verity.sdk.protocols.presentproof.v1_0.PresentProofV1_0
import com.evernym.verity.sdk.protocols.provision.v0_7.ProvisionV0_7
import com.evernym.verity.sdk.protocols.questionanswer.v1_0.CommittedAnswerV1_0
import com.evernym.verity.sdk.protocols.relationship.v1_0.{GoalCode, RelationshipV1_0}
import com.evernym.verity.sdk.protocols.updateconfigs.v0_6.UpdateConfigsV0_6
import com.evernym.verity.sdk.protocols.updateendpoint.UpdateEndpoint
import com.evernym.verity.sdk.protocols.updateendpoint.v0_6.UpdateEndpointV0_6
import com.evernym.verity.sdk.protocols.writecreddef.v0_6.{RevocationRegistryConfig, WriteCredentialDefinitionV0_6}
import com.evernym.verity.sdk.protocols.writeschema.v0_6.WriteSchemaV0_6
import com.evernym.verity.sdk.utils.{AsJsonObject, Context}

import java.lang
import java.nio.file.Path
import scala.sys.process.Process

class NodeSdkProvider(val sdkConfig: SdkConfig, val testDir: Path)
  extends ProcessSdkProvider {
  import NodeSdkProvider._

  override def sdkType: String = "NODE"
  override def sdkTypeAbbreviation: String = "js"
  override def fileSuffix: String = ".js"

  override def booleanParam: Boolean => String = _.toString
  override def noneParam: String = "null"
  override def rawJsonParam: AsJsonObject => String = { json => s"""`${json.toJson.toString()}`""" }
  override def jsonParam: AsJsonObject => String = { j => s"""JSON.parse(${rawJsonParam(j)})""" }
  override def mapParam: Map[_, _] => String = {m => jsonParam(MapAsJsonObject(m))}

  val scopedModule = "@evernym/verity-sdk"

  override lazy val interpreter: InterpreterEnv = {
    npmrcFile(interpreterWorkingDir)
    Process(
      Seq(
        "npm",
        "install",
        s"$scopedModule@$sdkVersion"
      ),
      interpreterWorkingDir.toFile,
    ).!

    InterpreterEnv("node", interpreterWorkingDir)
  }

  override def buildScript(imports: String, context: String, cmd: String): String =
    buildNodeScript(imports, context, cmd)

  private def executeCmd(ctx: Context,
                         obj: String,
                         version: String,
                         func: String,
                         constructParams: Seq[Any] = Seq.empty,
                         funcParams: Seq[Any] = Seq.empty): String = {

    val versionConversion = ProcessSdkProvider.versionToModule(version)
    val params = mkParams(constructParams)
    val fParams = Seq("context", mkParams(funcParams)).mkString(",")
    executeOneLine(
      ctx,
      s"""require('$scopedModule')""",
      s"await new sdk.protocols.$versionConversion.$obj($params).$func($fParams)"
    )
  }

  override def available(): Unit = {
    s"""const sdk = require('$scopedModule')""".execute()
  }

  override def provision_0_7: ProvisionV0_7 = {
    new UndefinedProvision_0_7 {
      override def provision(ctx: Context): Context = {
        val newContext = executeOneLine(
          ctx,
          s"""require('$scopedModule')""",
          "console.log(JSON.stringify(await new sdk.protocols.v0_7.Provision().provision(context)))"
        )

        ctx
          .toContextBuilder
          .json(newContext)
          .build()
      }
    }
  }

  override def provision_0_7(token: String): ProvisionV0_7 = {
    new UndefinedProvision_0_7 {
      override def provision(ctx: Context): Context = {
        val tokenObj = TokenAsJsonObject(token)
        val newContext = executeOneLine(
          ctx,
          s"""require('$scopedModule')""",
          s"""console.log(JSON.stringify(await new sdk.protocols.v0_7.Provision(null, ${rawJsonParam(tokenObj)}).provision(context)))"""
        )

        ctx
          .toContextBuilder
          .json(newContext)
          .build()
      }
    }
  }


  override def issuerSetup_0_6: IssuerSetupV0_6 = {
    new UndefinedIssuerSetup_0_6 {
      override def create(ctx: Context): Unit = {
        executeCmd(ctx, "IssuerSetup", this.version, "create")
      }

      override def currentPublicIdentifier(ctx: Context): Unit =
        executeCmd(ctx, "IssuerSetup", this.version, "currentPublicIdentifier")
    }
  }

  override def updateEndpoint_0_6: UpdateEndpointV0_6 =
    new UndefinedUpdateEndpoint_0_6() {
      override def update(ctx: Context): Unit = {
        val msg = UpdateEndpoint.v0_6().updateMsg(ctx)
        val bytes = UpdateEndpoint.v0_6().updateMsgPacked(ctx)
        executeCmd(ctx, "UpdateEndpoint", this.version, "update")
      }
    }

  override def updateConfigs_0_6(name: String, logoUrl: String): UpdateConfigsV0_6 =
    new UndefinedUpdateConfigs_0_6 {
      override def update(ctx: Context): Unit =
        executeCmd(ctx, "UpdateConfigs", this.version, "update", Seq(name, logoUrl))
    }

  override def updateConfigs_0_6(): UpdateConfigsV0_6 =
    new UndefinedUpdateConfigs_0_6 {
      override def status(ctx: Context): Unit =
        executeCmd(ctx, "UpdateConfigs", this.version, "status")
    }

  override def writeSchema_0_6(name: String, ver: String, attrs: String*): WriteSchemaV0_6 =
    new UndefinedWriteSchema_0_6 {
      override def write(ctx: Context): Unit =
        executeCmd(ctx, "WriteSchema", this.version, "write", Seq(name, ver, attrs.toSeq, None))
    }

  override def writeCredDef_0_6(name: String,
                                schemaId: String,
                                tag: Option[String],
                                revocationDetails: Option[RevocationRegistryConfig]): WriteCredentialDefinitionV0_6 =
    new UndefinedWriteCredentialDefinition_0_6 {
      override def write(ctx: Context): Unit =
        executeCmd(
          ctx,
          "WriteCredentialDefinition",
          this.version,
          "write",
          Seq(name, schemaId, tag, revocationDetails.orNull, None))
    }

  override def basicMessage_1_0(forRelationship: DID,
                                content: String,
                                sentTime: String,
                                localization: String): BasicMessageV1_0 = {
    new UndefinedBasicMessage_1_0 {
      override def message(ctx: Context): Unit =
        executeCmd(
          ctx,
          "BasicMessage",
          this.version,
          "message",
          Seq(forRelationship, None, content, sentTime, localization)
        )
    }
  }


  override def committedAnswer_1_0(forRelationship: DID,
                                   questionText: String,
                                   questionDescription: String,
                                   validResponses: Seq[String],
                                   requireSig: Boolean): CommittedAnswerV1_0 =
    new UndefinedCommittedAnswer_1_0 {
      override def ask(ctx: Context): Unit =
        executeCmd(
          ctx,
          "CommittedAnswer",
          this.version,
          "ask",
          Seq(forRelationship, None, questionText, None, questionDescription, validResponses, requireSig)
        )
    }


  override def committedAnswer_1_0(forRelationship: DID, threadId: String, answerStr: String): CommittedAnswerV1_0 =
    new UndefinedCommittedAnswer_1_0 {
      override def answer(ctx: Context): Unit =
        executeCmd(
          ctx,
          "CommittedAnswer",
          this.version,
          "answer",
          Seq(forRelationship, threadId, None, answerStr, None, None, None)
        )

      override def status(ctx: Context): Unit =
        executeCmd(
          ctx,
          "CommittedAnswer",
          this.version,
          "status",
          Seq(forRelationship, threadId, None, answerStr, None, None, None)
        )
    }

  override def committedAnswer_1_0(forRelationship: DID, threadId: String): CommittedAnswerV1_0 =
    new UndefinedCommittedAnswer_1_0 {
      override def status(ctx: Context): Unit =
        executeCmd(
          ctx,
          "CommittedAnswer",
          this.version,
          "status",
          Seq(forRelationship, threadId, None, None, None, None, None)
        )
    }

  override def connecting_1_0(sourceId: String, label: String, base64InviteURL: String): ConnectionsV1_0 = ???

  override def connectingWithOutOfBand_1_0(sourceId: String,
                                           label: String,
                                           base64InviteURL: String): ConnectionsV1_0 = ???

  override def outOfBand_1_0(forRelationship: String, inviteURL: String): OutOfBandV1_0 = new UndefinedOutOfBand_1_0 {
    override def handshakeReuse(ctx: Context): Unit =
      executeCmd(ctx, "OutOfBand", version, "handshakeReuse", Seq(None, None, forRelationship, inviteURL))
  }


  override def relationship_1_0(label: String): RelationshipV1_0 = new UndefinedRelationship_1_0 {
    override def create(ctx: Context): Unit =
      executeCmd(ctx, "Relationship", version, "create", Seq(None, None, label))
  }

  override def relationship_1_0(forRelationship: String, threadId: String): RelationshipV1_0 = new UndefinedRelationship_1_0 {
    override def connectionInvitation(ctx: Context, shortInvite: lang.Boolean): Unit =
      executeCmd(ctx, "Relationship", version, "connectionInvitation", Seq(forRelationship, threadId), Seq(shortInvite))
    override def outOfBandInvitation(ctx: Context, shortInvite: lang.Boolean, goal: GoalCode): Unit =
      executeCmd(ctx, "Relationship", version, "outOfBandInvitation", Seq(forRelationship, threadId), Seq(shortInvite, goal))
  }

  override def issueCredential_1_0(forRelationship: String,
                                   credDefId: String,
                                   credValues: Map[String, String],
                                   comment: String,
                                   price: String = "0",
                                   autoIssue: Boolean = false,
                                   byInvitation: Boolean = false): IssueCredentialV1_0 = new UndefinedIssueCredential_1_0 {
    override def offerCredential(ctx: Context): Unit = {
      executeCmd(
        ctx,
        "IssueCredential",
        this.version,
        "offerCredential",
        Seq(forRelationship, None, credDefId, credValues, comment, price, autoIssue, byInvitation)
      )
    }

  }

  override def issueCredential_1_0(forRelationship: String, threadId: String): IssueCredentialV1_0 =
    new UndefinedIssueCredential_1_0 {
    override def issueCredential(ctx: Context): Unit =
      executeCmd(ctx, "IssueCredential", this.version, "issueCredential", Seq(forRelationship, threadId))

    override def status(ctx: Context): Unit =
      executeCmd(ctx, "IssueCredential", this.version, "status", Seq(forRelationship, threadId))
  }

  override def issueCredentialComplete_1_0(): Unit = ???

  override def presentProof_1_0(forRelationship: String,
                                name: String,
                                proofAttrs: Array[Attribute],
                                proofPredicate: Array[Predicate],
                                byInvitation: Boolean = false): PresentProofV1_0 =  {
    new UndefinedPresentProof_1_0 {
      override def request(ctx: Context): Unit = {
        executeCmd(
          ctx,
          "PresentProof",
          this.version,
          "request",
          Seq(forRelationship, None, name, proofAttrs.toSeq, proofPredicate.toSeq, byInvitation)
        )
      }
    }
  }

  override def presentProof_1_0(forRelationship: DID, threadId: String): PresentProofV1_0 = {
    new UndefinedPresentProof_1_0 {
      override def status(ctx: Context): Unit = {
        executeCmd(ctx, "PresentProof", this.version, "status", Seq(forRelationship, threadId))
      }

      override def acceptProposal(ctx: Context): Unit =
        executeCmd(ctx, "PresentProof", this.version, "acceptProposal", Seq(forRelationship, threadId))
    }
  }

  override def presentProof_1_0(forRelationship: String, proofAttrs: Array[ProposedAttribute], proofPredicates: Array[ProposedPredicate]): PresentProofV1_0 = ???

  override def convertGoalCode(goal: GoalCode): String = {
    val prefix = "sdk.protocols.v1_0."
    goal match {
      case GoalCode.CREATE_ACCOUNT => prefix + "GoalCodes.CREATE_ACCOUNT()"
      case GoalCode.ISSUE_VC => prefix + "GoalCodes.ISSUE_VC()"
      case GoalCode.P2P_MESSAGING => prefix + "GoalCodes.P2P_MESSAGING()"
      case GoalCode.REQUEST_PROOF => prefix + "GoalCodes.REQUEST_PROOF()"
    }
  }

}

object NodeSdkProvider {
  def npmrcFile(cwd: Path): Unit = {
    val fileContent = s"@evernym:registry=https://gitlab.com/api/v4/packages/npm/"

    ProcessSdkProvider.writeConfigFile(cwd, ".npmrc", fileContent)
  }

  def buildNodeScript(imports: String, context: String, cmd: String): String = {
    s"""
'use strict'
// ==== IMPORTS ====
const sdk = $imports

// =================

// ==== CONTEXT JSON STRING ====
const contextStr = `$context`

// =============================

async function main () {
    const context = await sdk.Context.createWithConfig(contextStr)
    try {
    // ==== COMMAND ====
    $cmd
    // =================
    }
    catch(ex) {
      console.error(ex.error)
      process.exitCode = $sdkErrExitCode
    }
    finally {
        context.closeWallet()
    }
}

main()
"""
  }
}
