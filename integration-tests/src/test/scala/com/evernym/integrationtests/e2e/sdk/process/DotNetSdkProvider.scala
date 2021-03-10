package com.evernym.integrationtests.e2e.sdk.process

import com.evernym.integrationtests.e2e.env.SdkConfig
import com.evernym.integrationtests.e2e.sdk.UndefinedInterfaces._
import com.evernym.integrationtests.e2e.sdk.process.ProcessSdkProvider.{InterpreterEnv, MapAsJsonObject, TokenAsJsonObject}
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
import com.evernym.verity.sdk.protocols.updateendpoint.v0_6.UpdateEndpointV0_6
import com.evernym.verity.sdk.protocols.writecreddef.v0_6.{RevocationRegistryConfig, WriteCredentialDefinitionV0_6}
import com.evernym.verity.sdk.protocols.writeschema.v0_6.WriteSchemaV0_6
import com.evernym.verity.sdk.utils.{AsJsonObject, Context}

import java.io.{BufferedWriter, File, FileWriter}
import java.lang
import java.nio.file.{Path, Paths}

class DotNetSdkProvider(val sdkConfig: SdkConfig, val testDir: Path)
  extends ProcessSdkProvider {

  override def convertGoalCode(goal: GoalCode): String = {
    goal match {
      case GoalCode.CREATE_ACCOUNT => "GoalCode.CREATE_ACCOUNT"
      case GoalCode.ISSUE_VC => "GoalCode.ISSUE_VC"
      case GoalCode.P2P_MESSAGING => "GoalCode.P2P_MESSAGING"
      case GoalCode.REQUEST_PROOF => "GoalCode.REQUEST_PROOF"
    }
  }

  override def booleanParam: Boolean => String = _.toString.toLowerCase()

  override def noneParam: String = "null"

  private val oneQuote = "\""

  def jsonEscape: AsJsonObject => String = { j => j.toJson.toString.replace("\"", "\"\"") }
  override def rawJsonParam: AsJsonObject => String = { j => s"""@$oneQuote${jsonEscape(j)}$oneQuote""" }
  override def jsonParam: AsJsonObject => String = { j => s"""(JsonObject)JsonObject.Parse(${rawJsonParam(j)})""" }

  override def mapParam: Map[_, _] => String = {m =>
    s"JsonSerializer.Deserialize<Dictionary<string, string>>(${rawJsonParam(MapAsJsonObject(m))})"
  }

  def toJsonArray(objects: Seq[AsJsonObject]): String =
    s"""@$oneQuote[${objects.map(jsonEscape).mkString(",")}]$oneQuote"""

  override def sdkTypeAbbreviation: String = "dot"
  override def sdkType: String = "DOTNET"
  override def fileSuffix: String = ".csx"

  override def interpreter: ProcessSdkProvider.InterpreterEnv = {

    DotNetSdkProvider.nugetFile(
      interpreterWorkingDir,
    )

    InterpreterEnv(scriptRunnerCmd, interpreterWorkingDir, toProcess = ProcessSdkProvider.File)
  }

  override def buildScript(imports: String, context: String, cmd: String): String =
    DotNetSdkProvider.buildScript(imports, context, cmd, sdkVersion)

  private def scriptRunnerCmd: String = {
    val  cmd = sys.env.get("HOME")
      .map(Paths.get(_))
      .map(_.resolve(".dotnet/tools/dotnet-script"))
      .filter(_.toFile.exists())
      .getOrElse(throw new Exception("Unable to find dot net script runner"))
      .toAbsolutePath
      .toString
    cmd
  }

  def versionToModule(version: String): String = {
    "v" + version.replace('.', '_')
  }

  private def executeCmd(ctx: Context,
                         obj: String,
                         version: String,
                         func: String,
                         constructParams: Seq[Any] = Seq.empty,
                         funcParams: Seq[Any] = Seq.empty): String = {
    executeCmdWithUsing(ctx, obj, obj, version, func, constructParams, funcParams)
  }

  private def executeCmdWithUsing(ctx: Context,
                                  using: String,
                                  obj: String,
                                  version: String,
                                  func: String,
                                  constructParams: Seq[Any] = Seq.empty,
                                  funcParams: Seq[Any] = Seq.empty): String = {

    val versionConversion = versionToModule(version)

    val _params = mkParams(constructParams)
    val params = _params.replace("[", "").replace("]", "")

    var fParams = ""
    if (funcParams.isEmpty) {
      fParams = "context"
    } else {
      fParams = Seq("context", mkParams(funcParams)).mkString(",")
    }

    executeOneLine(
      ctx,
      s"using VeritySDK.Protocols.$using;",
      s"$obj.$versionConversion($params).$func($fParams)"
    )
  }

  private def executeCmdForWriteCredentialDefinition(ctx: Context,
                                                     version: String,
                                                     name: String,
                                                     schemaId: String,
                                                     tag: Option[String],
                                                     revocationDetails: Option[RevocationRegistryConfig]
                                                    ): String =
  {
    val versionConversion = versionToModule(version)

    var rev_s = ""
    val rev = revocationDetails.orNull
    if (rev != null) {
      val rev_json = s"""JsonObject.Parse(@$oneQuote${rev.toJson.toString.replace("\"", "\"\"")}$oneQuote)"""
      rev_s = "new RevocationRegistryConfig((JsonObject)" + rev_json + ")"
    }

    executeOneLine(
      ctx,
      s"using VeritySDK.Protocols.WriteCredDef;",
      s"WriteCredentialDefinition.$versionConversion(${stringParam(name)}, ${stringParam(schemaId)}, ${stringParam(tag.orNull)}, $rev_s).write(context)"
    )
  }


  override def available(): Unit = {
    val out =
      s"""#! "netstandard2.0"
    #r "nuget: Evernym.Verity.SDK, $sdkVersion"
    using VeritySDK;
    Console.WriteLine();""".execute()
    logger.debug(s"available script output: $out")
  }

  override def provision_0_7: ProvisionV0_7 = {
    new UndefinedProvision_0_7 {
      override def provision(ctx: Context): Context = {
        val newContext = executeOneLine(
          ctx,
          "using VeritySDK.Protocols.Provision;",
          "Console.WriteLine((Provision.v0_7().provision(context)).toJson().ToString());"
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
          "using VeritySDK.Protocols.Provision;",
          s"""Console.WriteLine((Provision.v0_7(${rawJsonParam(tokenObj)}).provision(context)).toJson().ToString());"""
        )
        ctx
          .toContextBuilder
          .json(newContext)
          .build()
      }
    }
  }

  override def updateEndpoint_0_6: UpdateEndpointV0_6 = new UndefinedUpdateEndpoint_0_6() {
    override def update(ctx: Context): Unit =
      executeCmd(ctx, "UpdateEndpoint", this.version, "update")
  }

  override def updateConfigs_0_6(name: String, logoUrl: String): UpdateConfigsV0_6 = new UndefinedUpdateConfigs_0_6 {
    override def update(ctx: Context): Unit =
      executeCmd(ctx, "UpdateConfigs", this.version, "update", Seq(name, logoUrl))
  }

  override def updateConfigs_0_6(): UpdateConfigsV0_6 = new UndefinedUpdateConfigs_0_6 {
    override def status(ctx: Context): Unit =
      executeCmd(ctx, "UpdateConfigs", this.version, "status")
  }

  override def issuerSetup_0_6: IssuerSetupV0_6 = {
    new UndefinedIssuerSetup_0_6 {
      override def create(ctx: Context): Unit =
        executeCmd(ctx, "IssuerSetup", this.version, "create")

      override def currentPublicIdentifier(ctx: Context): Unit =
        executeCmd(ctx, "IssuerSetup", this.version, "currentPublicIdentifier")
    }
  }

  override def writeSchema_0_6(name: String, ver: String, attrs: String*): WriteSchemaV0_6 = {
    new UndefinedWriteSchema_0_6 {
      override def write(ctx: Context): Unit =
        executeCmd(ctx, "WriteSchema", this.version, "write", Seq(name, ver, attrs.toSeq))
    }
  }

  override def writeCredDef_0_6(name: String, schemaId: String, tag: Option[String], revocationDetails: Option[RevocationRegistryConfig]): WriteCredentialDefinitionV0_6 = {
    new UndefinedWriteCredentialDefinition_0_6 {
      override def write(ctx: Context): Unit =
        executeCmdForWriteCredentialDefinition(ctx, this.version, name, schemaId, tag, revocationDetails)
    }
  }

  override def committedAnswer_1_0(forRelationship: DID, questionText: String, questionDescription: String, validResponses: Seq[String], requireSig: Boolean): CommittedAnswerV1_0 = new UndefinedCommittedAnswer_1_0 {
    override def ask(ctx: Context): Unit = {
      val vR = s"new string[] {${mkParams(validResponses)}}"

      executeOneLine(
        ctx,
        s"using VeritySDK.Protocols.QuestionAnswer;",
        s"CommittedAnswer.${versionToModule(this.version)}(${stringParam(forRelationship)}, ${stringParam(questionText)}, ${stringParam(questionDescription)}, $vR, ${booleanParam(requireSig)}).ask(context)"
      )
    }
  }

  override def committedAnswer_1_0(forRelationship: DID, threadId: String, answerStr: String): CommittedAnswerV1_0 = {
    new UndefinedCommittedAnswer_1_0 {
      override def answer(ctx: Context): Unit =
        executeCmdWithUsing(
          ctx,
          "QuestionAnswer",
          "CommittedAnswer",
          this.version,
          "answer",
          Seq(forRelationship, threadId, answerStr)
        )

      override def status(ctx: Context): Unit =
        executeCmdWithUsing(
          ctx,
          "QuestionAnswer",
          "CommittedAnswer",
          this.version,
          "status",
          Seq(forRelationship, threadId, answerStr)
        )

    }
  }

  override def committedAnswer_1_0(forRelationship: DID, threadId: String): CommittedAnswerV1_0 = new UndefinedCommittedAnswer_1_0 {
    override def status(ctx: Context): Unit = {
      executeCmdWithUsing(
        ctx,
        "QuestionAnswer",
        "CommittedAnswer",
        this.version,
        "status",
        Seq(forRelationship, threadId)
      )
    }
  }

  override def connecting_1_0(sourceId: String, label: String, base64InviteURL: String): ConnectionsV1_0 =
    throw new NotImplementedError

  override def connectingWithOutOfBand_1_0(sourceId: String, label: String, base64InviteURL: String): ConnectionsV1_0 =
    throw new NotImplementedError

  override def outOfBand_1_0(forRelationship: String, inviteURL: String): OutOfBandV1_0 = new UndefinedOutOfBand_1_0 {
    override def handshakeReuse(ctx: Context): Unit =
      executeCmd(ctx, "OutOfBand", version, "handshakeReuse", Seq(forRelationship, inviteURL))
  }

  override def relationship_1_0(label: String): RelationshipV1_0 = new UndefinedRelationship_1_0 {
    override def create(ctx: Context): Unit =
      executeCmd(ctx, "Relationship", version, "create", Seq(label))
  }

  override def relationship_1_0(forRelationship: String,
                                threadId: String): RelationshipV1_0 = new UndefinedRelationship_1_0 {
    override def connectionInvitation(ctx: Context, shortInvite: lang.Boolean): Unit =
      executeCmd(
        ctx,
        "Relationship",
        version,
        "connectionInvitation",
        Seq(forRelationship, threadId),
        Seq(shortInvite)
      )
    override def outOfBandInvitation(ctx: Context, shortInvite: lang.Boolean, goal: GoalCode): Unit =
      executeCmd(
        ctx,
        "Relationship",
        version,
        "outOfBandInvitation",
        Seq(forRelationship, threadId),
        Seq(shortInvite, goal)
      )
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
          "offerCredential",
          Seq(forRelationship, credDefId, credValues, comment, price, autoIssue, byInvitation)
        )
      }
    }
  }

  override def issueCredential_1_0(forRelationship: String, threadId: String): IssueCredentialV1_0 = {
    new UndefinedIssueCredential_1_0 {
      override def issueCredential(ctx: Context): Unit =
        executeCmd(ctx, "IssueCredential", this.version, "issueCredential", Seq(forRelationship, threadId))

      override def status(ctx: Context): Unit =
        executeCmd(ctx, "IssueCredential", this.version, "status", Seq(forRelationship, threadId))
    }
  }

  override def issueCredentialComplete_1_0(): Unit = throw new NotImplementedError

  override def presentProof_1_0(forRelationship: DID,
                                name: String,
                                proofAttrs: Array[Attribute],
                                proofPredicate: Array[Predicate],
                                byInvitation: Boolean = false): PresentProofV1_0 = new UndefinedPresentProof_1_0 {
    override def request(ctx: Context): Unit = {

      val jsonProofAttrs = toJsonArray(proofAttrs)
      val jsonProofPredicate = toJsonArray(proofPredicate)

      var cmd_proofAttrs = ""
      var cmd_proofAttrsArr = "null"
      if (proofAttrs.length != 0)
      {
        cmd_proofAttrs = s"""
            var jsonDataProofAttrs = (JsonArray)JsonObject.Parse($jsonProofAttrs);
            var _proofAttrs = new List<VeritySDK.Protocols.PresentProof.Attribute>();
            foreach (var el in jsonDataProofAttrs)
            {
                var e = (JsonObject)el;
                if (e.ContainsKey("names"))
                {
                    var names = new List<string>();
                    foreach (var s in e["names"])
                    {
                        names.Add(s.ToString().Replace("\\"", ""));
                    }

                    var rest = e["restrictions"];
                    var restr = new List<Restriction>();
                    foreach (var r in rest)
                    {
                        restr.Add(new Restriction((JsonObject)r));
                    }

                    _proofAttrs.Add(new VeritySDK.Protocols.PresentProof.Attribute(names.ToArray(), restr.ToArray()));
                };
                if (e.ContainsKey("name"))
                {
                    var name = e["name"];

                    var rest = e["restrictions"];
                    var restr = new List<Restriction>();
                    foreach (var r in rest)
                    {
                        restr.Add(new Restriction((JsonObject)r));
                    }
                    _proofAttrs.Add(new VeritySDK.Protocols.PresentProof.Attribute(name, restr.ToArray()));
                };
            };
        """
        cmd_proofAttrsArr = "_proofAttrs.ToArray()"
      }


      var cmd_proofPredicate = ""
      var cmd_proofPredicateArr = "null"
      if (proofPredicate.length != 0)
      {
        // Tests scenarios don`t have data for Predicate. This code may be wrong! Need control later!
        cmd_proofPredicate = s"""
            var jsonDataProofPredicate = (JsonArray)JsonObject.Parse($jsonProofPredicate);
            var _proofPredicate = new List<VeritySDK.Protocols.PresentProof.Predicate>();
            foreach (var el in jsonDataProofPredicate)
            {
                var e = (JsonObject)el;

                var name = e["name"];
                var value = e["value"];

                var rest = e["restrictions"];
                var restr = new List<Restriction>();
                foreach (var r in rest)
                {
                    restr.Add(new Restriction((JsonObject)r));
                }
                _proofPredicate.Add(new VeritySDK.Protocols.PresentProof.Predicate(name, value, restr.ToArray()));
            };
        """
        cmd_proofPredicateArr = "_proofPredicate.ToArray()"
      }

      val cmd = s"""
            $cmd_proofAttrs
            $cmd_proofPredicate
            PresentProof.${versionToModule(this.version)}(${stringParam(forRelationship)}, ${stringParam(name)}, $cmd_proofAttrsArr, $cmd_proofPredicateArr, ${booleanParam(byInvitation)}).request(context);
      """

      executeOneLine(ctx,s"using VeritySDK.Protocols.PresentProof;", cmd)
    }
  }

  override def presentProof_1_0(forRelationship: String,
                                proofAttrs: Array[ProposedAttribute],
                                proofPredicates: Array[ProposedPredicate]
                               ): PresentProofV1_0 = throw new NotImplementedError

  override def presentProof_1_0(forRelationship: DID,
                                threadId: String): PresentProofV1_0 = new UndefinedPresentProof_1_0 {
    override def status(ctx: Context): Unit = {
      executeCmd(ctx, "PresentProof", this.version, "status", Seq(forRelationship, threadId))
    }

    override def acceptProposal(ctx: Context): Unit = {
      executeCmd(ctx, "PresentProof", this.version, "acceptProposal", Seq(forRelationship, threadId))
    }
  }

  override def basicMessage_1_0(forRelationship: DID, content: String, sentTime: String, localization: String): BasicMessageV1_0 = {
    new UndefinedBasicMessage_1_0 {
      override def message(ctx: Context): Unit =
        executeCmd(
          ctx,
          "BasicMessage",
          this.version,
          "message",
          Seq(forRelationship, content, sentTime, localization)
        )
    }
  }
}

object DotNetSdkProvider {
  def nugetFile(cwd: Path): Unit = {
    val fileContent = s"""<?xml version="1.0" encoding="utf-8"?>
                         |<configuration>
                         |  <packageSources>
                         |    <add key="public" value="https://api.nuget.org/v3/index.json" />
                         |    <add key="EvernymDev" value="https://gitlab.corp.evernym.com/api/v4/projects/419/packages/nuget/index.json" />
                         |  </packageSources>
                         |  <packageSourceCredentials>
                         |    <EvernymDev>
                         |      <add key="Username" value="%VERITY_SDK_PACKAGE_REGISTRY_USERNAME%" />
                         |      <add key="ClearTextPassword" value="%VERITY_SDK_PACKAGE_REGISTRY_PASSWORD%" />
                         |    </EvernymDev>
                         |  </packageSourceCredentials>
                         |</configuration>
                         |""".stripMargin


    val file = new File(cwd.resolve("NuGet.Config").toString)
    val bw = new BufferedWriter(new FileWriter(file))
    bw.write(fileContent)
    bw.close()
  }

  def buildScript(imports: String, context: String, cmd: String, sdkVersion: String): String = {
    s"""#! "netstandard2.0"
    #r "nuget: Evernym.Verity.SDK, $sdkVersion"

    using System.Text;
    using System.Text.Json;
    using System.Json;
    using VeritySDK.Exceptions;
    using VeritySDK.Handler;
    using VeritySDK.Utils;
    $imports

    var context_str = @\"${context.replace("\"", "\"\"")}\";
    var context = ContextBuilder.fromJson(context_str).build();
    try {
        $cmd;
    }
    finally {
        context.CloseWallet();
    };
    """
  }
}
