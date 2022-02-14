package com.evernym.integrationtests.e2e.sdk.process

import com.evernym.integrationtests.e2e.env.SdkConfig
import com.evernym.integrationtests.e2e.sdk.UndefinedInterfaces._
import com.evernym.integrationtests.e2e.sdk.process.ProcessSdkProvider.{InterpreterEnv, MapAsJsonObject, TokenAsJsonObject, sdkErrExitCode}
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
import com.evernym.verity.sdk.protocols.relationship.v1_0.{GoalCode, RelationshipV1_0}
import com.evernym.verity.sdk.protocols.updateconfigs.v0_6.UpdateConfigsV0_6
import com.evernym.verity.sdk.protocols.updateendpoint.v0_6.UpdateEndpointV0_6
import com.evernym.verity.sdk.protocols.writecreddef.v0_6.{RevocationRegistryConfig, WriteCredentialDefinitionV0_6}
import com.evernym.verity.sdk.protocols.writeschema.v0_6.WriteSchemaV0_6
import com.evernym.verity.sdk.utils.{AsJsonObject, Context}

import java.lang
import java.nio.file.Path
import java.util.UUID
import scala.annotation.tailrec
import scala.language.postfixOps
import scala.sys.process.Process

class PythonSdkProvider(val sdkConfig: SdkConfig, val testDir: Path)
  extends ProcessSdkProvider {

  override def sdkType: String = "PYTHON"
  override def sdkTypeAbbreviation: String = "py"
  override def fileSuffix: String = ".py"

  override lazy val interpreter: InterpreterEnv = {
    Process(Seq("virtualenv", interpreterWorkingDir.toString)).!

    val curPath = sys.env.get("PATH")
    val envVars: Map[String, String] = Map(
      "VIRTUAL_ENV" -> interpreterWorkingDir.toString,
      "PATH" -> s"${interpreterWorkingDir.toString}/bin:${curPath.getOrElse("")}"
    )
    Process(
      Seq(
        s"$interpreterWorkingDir/bin/pip3",
        "install",
        "--extra-index-url",
        "https://gitlab.com/api/v4/projects/26760306/packages/pypi/simple",
        s"verity-sdk==$sdkVersion"
      ),
      interpreterWorkingDir.toFile,
      envVars.toArray: _*
    ).!

    InterpreterEnv(s"${interpreterWorkingDir}/bin/python3", interpreterWorkingDir, envVars=envVars)
  }

  override def booleanParam: Boolean => String = _.toString.capitalize
  override def noneParam: String = "None"
  private val tripleQuote = "\"\"\""
  override def rawJsonParam: AsJsonObject => String = { json => s"""$tripleQuote${json.toJson.toString()}$tripleQuote"""}

  override def jsonParam: AsJsonObject => String = { j =>
    s"""json.loads(${rawJsonParam(j)})"""
  }
  override def mapParam: Map[_, _] => String = {m => jsonParam(MapAsJsonObject(m))}

  override def buildScript(imports: String, context: String, cmd: String): String =
    PythonSdkProvider.buildPythonScript(imports, context, cmd)


  override def available(): Unit ={
    val out = """import verity_sdk;print(verity_sdk.__file__)""".execute()
    logger.debug(s"Python Sdk availability script output: $out")
  }

  private def executeCmd(ctx: Context,
                         obj: String,
                         version: String,
                         func: String,
                         constructParams: Seq[Any] = Seq.empty,
                         funcParams: Seq[Any] = Seq.empty,
                         injectThreadId: Option[String] = None): String = {

    val versionConversion = ProcessSdkProvider.versionToModule(version)
    val params = mkParams(constructParams)
    val fParams = Seq("context", mkParams(funcParams)).mkString(",")

    val cmd: String = injectThreadId match {
      case Some(injectThreadId) =>
        s"""
        pp = $obj($params)
        pp.thread_id = ${stringParam(injectThreadId)}
        await pp.$func($fParams)
        """
      case None => s"await $obj($params).$func($fParams)"
    }


    executeOneLine(
      ctx,
      s"from verity_sdk.protocols.$versionConversion.$obj import $obj",
      cmd
    )
  }

  override def provision_0_7: ProvisionV0_7 = {
    new UndefinedProvision_0_7 {
      override def provision(ctx: Context): Context = {
        val newContext = executeOneLine(
          ctx,
          "from verity_sdk.protocols.v0_7.Provision import Provision",
          "print((await Provision().provision(context)).to_json())"
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
          "from verity_sdk.protocols.v0_7.Provision import Provision",
          s"""print((await Provision(${rawJsonParam(tokenObj)}).provision(context)).to_json())"""
        )

        ctx
          .toContextBuilder
          .json(newContext)
          .build()
      }
    }
  }

  override def updateEndpoint_0_6: UpdateEndpointV0_6 =
    new UndefinedUpdateEndpoint_0_6() {
      override def update(ctx: Context): Unit =
        executeCmd(ctx, "UpdateEndpoint", this.version, "update")
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

  override def issuerSetup_0_6: IssuerSetupV0_6 = {
    new UndefinedIssuerSetup_0_6 {
      override def create(ctx: Context): Unit =
        executeCmd(ctx, "IssuerSetup", this.version, "create")

      override def currentPublicIdentifier(ctx: Context): Unit =
        executeCmd(ctx, "IssuerSetup", this.version, "current_public_identifier")
    }
  }

  override def writeSchema_0_6(name: String, ver: String, attrs: String*): WriteSchemaV0_6 = {
    new UndefinedWriteSchema_0_6 {
      override def write(ctx: Context): Unit =
        executeCmd(ctx, "WriteSchema", this.version, "write", Seq(name, ver, attrs.toSeq), Seq.empty, Some(myThreadId))
      override def write(ctx: Context, endorserDid: String): Unit =
        executeCmd(ctx, "WriteSchema", this.version, "write", Seq(name, ver, attrs.toSeq), Seq(endorserDid), Some(myThreadId))
    }
  }

  override def writeCredDef_0_6(name: String,
                                schemaId: String,
                                tag: Option[String],
                                revocationDetails: Option[RevocationRegistryConfig]): WriteCredentialDefinitionV0_6 = {
    new UndefinedWriteCredentialDefinition_0_6 {
      override def write(ctx: Context): Unit =
        executeCmd(ctx, "WriteCredentialDefinition", this.version, "write", Seq(name, schemaId, tag, revocationDetails.orNull), Seq.empty, Some(myThreadId))
      override def write(ctx: Context, endorserDid: String): Unit =
        executeCmd(ctx, "WriteCredentialDefinition", this.version, "write", Seq(name, schemaId, tag, revocationDetails.orNull), Seq(endorserDid), Some(myThreadId))
    }
  }

  override def basicMessage_1_0(forRelationship: DidStr, content: String, sentTime: String, localization: String): BasicMessageV1_0 = {
    new UndefinedBasicMessage_1_0{
      override def message(ctx: Context): Unit = {
        executeCmd(
          ctx,
          "BasicMessage",
          this.version,
          "message",
          Seq(forRelationship, None, content, sentTime, localization),
          Seq.empty,
          Some(myThreadId
        ))
      }
    }
  }

  override def committedAnswer_1_0(forRelationship: DidStr,
                                   questionText: String,
                                   questionDescription: String,
                                   validResponses: Seq[String],
                                   requireSig: Boolean): CommittedAnswerV1_0 =
    new UndefinedCommittedAnswer_1_0 {
      override def ask(ctx: Context): Unit = {
        executeCmd(
          ctx,
          "CommittedAnswer",
          this.version,
          "ask",
          Seq(forRelationship, None, questionText, questionDescription, validResponses, requireSig),
          Seq.empty,
          Some(myThreadId)
        )
      }
    }

  override def committedAnswer_1_0(forRelationship: DidStr,
                                   threadId: String,
                                   answerStr: String): CommittedAnswerV1_0 = throw new NotImplementedError

  override def committedAnswer_1_0(forRelationship: DidStr,
                                   threadId: String): CommittedAnswerV1_0 =
    new UndefinedCommittedAnswer_1_0 {
      override val myThreadId: String = threadId

      override def status(ctx: Context): Unit = {
        executeCmd(
          ctx,
          "CommittedAnswer",
          this.version,
          "status",
          Seq(forRelationship, myThreadId)
        )
      }
    }

  override def connecting_1_0(sourceId: String, label: String, base64InviteURL: String): ConnectionsV1_0 = ???

  override def connectingWithOutOfBand_1_0(sourceId: String,
                                           label: String,
                                           base64InviteURL: String): ConnectionsV1_0 = ???

  override def outOfBand_1_0(forRelationship: String, inviteURL: String): OutOfBandV1_0 = new UndefinedOutOfBand_1_0 {
    override def handshakeReuse(ctx: Context): Unit =
      executeCmd(ctx, "OutOfBand", version, "handshake_reuse", Seq(None, None, forRelationship, inviteURL), Seq.empty, Some(myThreadId))
  }

  override def relationship_1_0(label: String): RelationshipV1_0 = new UndefinedRelationship_1_0 {
    override def create(ctx: Context): Unit =
      executeCmd(ctx, "Relationship", version, "create", Seq(None, None, label), Seq.empty, Some(myThreadId))
  }

  override def relationship_1_0(forRelationship: String, threadId: String): RelationshipV1_0 = new UndefinedRelationship_1_0 {
    override val myThreadId: String = threadId

    override def connectionInvitation(ctx: Context, shortInvite: lang.Boolean): Unit =
      executeCmd(ctx, "Relationship", version, "connection_invitation", Seq(forRelationship, myThreadId), Seq(shortInvite))
    override def outOfBandInvitation(ctx: Context, shortInvite: lang.Boolean, goal: GoalCode): Unit =
      executeCmd(ctx, "Relationship", version, "out_of_band_invitation", Seq(forRelationship, myThreadId), Seq(shortInvite, goal))
  }

  override def issueCredential_1_0(forRelationship: String,
                                   credDefId: String,
                                   credValues: Map[String, String],
                                   comment: String,
                                   price: String = "0",
                                   autoIssue: Boolean = false,
                                   byInvitation: Boolean = false): IssueCredentialV1_0 = {
    new UndefinedIssueCredential_1_0 {
      override def offerCredential(ctx: Context): Unit = {
        executeCmd(ctx,
          "IssueCredential",
          this.version,
          "offer_credential",
          Seq(forRelationship, None, credDefId, credValues, comment, price, autoIssue, byInvitation),
          Seq.empty,
          Some(myThreadId)
        )
      }
    }
  }

  override def issueCredential_1_0(forRelationship: String, threadId: String): IssueCredentialV1_0 = {
    new UndefinedIssueCredential_1_0 {
      override val myThreadId: String = threadId

      override def issueCredential(ctx: Context): Unit =
        executeCmd(ctx, "IssueCredential", this.version, "issue_credential", Seq(forRelationship, myThreadId))

      override def status(ctx: Context): Unit =
        executeCmd(ctx, "IssueCredential", this.version, "status", Seq(forRelationship, myThreadId))
    }
  }


  override def issueCredentialComplete_1_0(): Unit = ???

  override def presentProof_1_0(forRelationship: String,
                                name: String,
                                proofAttrs: Array[Attribute],
                                proofPredicate: Array[Predicate],
                                byInvitation: Boolean = false): PresentProofV1_0 =
    new UndefinedPresentProof_1_0 {
    override def request(ctx: Context): Unit =
      executeCmd(ctx,
        "PresentProof",
        this.version,
        "request",
        Seq(forRelationship, None, name, proofAttrs.toSeq, proofPredicate.toSeq, byInvitation),
        Seq.empty,
        Some(myThreadId)
      )
  }

  override def presentProof_1_0(forRelationship: DidStr, threadId: String): PresentProofV1_0 =
    new UndefinedPresentProof_1_0 {
      override val myThreadId: String = threadId

      override def status(ctx: Context): Unit =
        executeCmd(ctx, "PresentProof", this.version, "status", Seq(forRelationship, myThreadId))

      override def acceptProposal(ctx: Context): Unit =
        executeCmd(ctx, "PresentProof", this.version, "accept_proposal", Seq(forRelationship, myThreadId))
    }

  override def presentProof_1_0(forRelationship: String, proofAttrs: Array[ProposedAttribute], proofPredicates: Array[ProposedPredicate]): PresentProofV1_0 = ???

  override def convertGoalCode(goal: GoalCode): String = {
    goal match {
      case GoalCode.CREATE_ACCOUNT => "GoalsList.CREATE_ACCOUNT"
      case GoalCode.ISSUE_VC => "GoalsList.ISSUE_VC"
      case GoalCode.P2P_MESSAGING => "GoalsList.P2P_MESSAGING"
      case GoalCode.REQUEST_PROOF => "GoalsList.REQUEST_PROOF"
    }
  }

}

object PythonSdkProvider {
  def buildPythonScript(imports: String, context: String, cmd: String): String = {
    s"""
import asyncio
import json
from verity_sdk.utils.Context import Context
from verity_sdk.protocols.v1_0.Relationship import GoalsList
from sys import stderr, exit

# ==== IMPORTS ====
$imports

# =================

# ==== CONTEXT JSON STRING ====
context_str = \"\"\"$context\"\"\"

# =============================


async def main():
    context = await Context.create_with_config(context_str)
    try:
        # ==== COMMAND ====
        $cmd
        # =================
    except Exception as e:
        print(e.response.content, file=stderr)
        exit($sdkErrExitCode)
    finally:
        await context.close_wallet()


if __name__ == '__main__':
    loop = asyncio.get_event_loop()
    loop.run_until_complete(main())
"""
  }
}
