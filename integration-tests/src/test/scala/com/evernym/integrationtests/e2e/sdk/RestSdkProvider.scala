package com.evernym.integrationtests.e2e.sdk

import java.lang
import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import com.evernym.verity.constants.Constants._
import com.evernym.verity.protocol.engine.Constants._
import com.evernym.verity.protocol.engine.{DID, ProtoRef}
import com.evernym.verity.testkit.listener.Listener
import com.evernym.verity.util.Base58Util
import com.evernym.integrationtests.e2e.env.SdkConfig
import com.evernym.integrationtests.e2e.sdk.UndefinedInterfaces._
import com.evernym.verity.sdk.exceptions.WalletException
import com.evernym.verity.sdk.protocols.connecting.v1_0.ConnectionsV1_0
import com.evernym.verity.sdk.protocols.issuecredential.v1_0.IssueCredentialV1_0
import com.evernym.verity.sdk.protocols.issuersetup.v0_6.IssuerSetupV0_6
import com.evernym.verity.sdk.protocols.outofband.OutOfBand
import com.evernym.verity.sdk.protocols.outofband.v1_0.OutOfBandV1_0
import com.evernym.verity.sdk.protocols.presentproof.common.Attribute
import com.evernym.verity.sdk.protocols.presentproof.v1_0.PresentProofV1_0
import com.evernym.verity.sdk.protocols.provision.Provision
import com.evernym.verity.sdk.protocols.provision.v0_7.ProvisionV0_7
import com.evernym.verity.sdk.protocols.questionanswer.v1_0.CommittedAnswerV1_0
import com.evernym.verity.sdk.protocols.relationship.v1_0.{GoalCode, RelationshipV1_0}
import com.evernym.verity.sdk.protocols.updateconfigs.v0_6.UpdateConfigsV0_6
import com.evernym.verity.sdk.protocols.updateendpoint.v0_6.UpdateEndpointV0_6
import com.evernym.verity.sdk.protocols.writecreddef.v0_6.{RevocationRegistryConfig, WriteCredentialDefinitionV0_6}
import com.evernym.verity.sdk.protocols.writeschema.v0_6.WriteSchemaV0_6
import com.evernym.verity.sdk.utils.{Context, JsonUtil, Util}
import org.hyperledger.indy.sdk.crypto.Crypto
import org.json.{JSONArray, JSONObject}

import scala.concurrent.Await
import scala.concurrent.duration.FiniteDuration

class HandlersForBothResponseTypes(handler: JSONObject => Unit)(implicit debugPrint: Boolean){
  import VeritySdkProvider._

  def handleMessage(context: Context, rawMessage: Array[Byte]): Unit = {
    val message: JSONObject = try {
      val msg = Util.unpackMessage(context, rawMessage)
      debugPrintln(s"Received PACKED msg: $msg")
      throw new Exception("error")
    } catch {
      case _ : WalletException =>
        debugPrintln(s"Received PLAIN REST msg: ${new JSONObject(new String(rawMessage))}")
        new JSONObject(new String(rawMessage))
    }
    handler(message)
  }
}

class RestSdkProvider(val sdkConfig: SdkConfig)
  extends BaseSdkProvider
    with ListeningSdkProvider {

  import VeritySdkProvider._
  /**
    * Check that the sdk is available (ex. on class path, installed or whatever)
    */

  val system: ActorSystem = ActorSystem.create("rest-integration-test")
  val defaultTimeout: FiniteDuration = FiniteDuration(10, TimeUnit.SECONDS)
  val httpTimeout: FiniteDuration = FiniteDuration(30, TimeUnit.SECONDS)

  override def available(): Unit = classOf[Context].getName

  override def sdkType: String = "REST"

  // TODO: remove this workaround once REST is fully implemented.
  override def startListener(): Unit = {
    val handles = new HandlersForBothResponseTypes({msg: JSONObject =>
      receiveMsg(msg)
    })

    listener = new Listener(port, { encryptMsg  =>
      handles.handleMessage(context, encryptMsg)
    })

    listener.listen()
  }

  // TODO: change after implementing this in rest.
  override def provision_0_7: ProvisionV0_7 = Provision.v0_7()

  override def issuerSetup_0_6: IssuerSetupV0_6 = {
    val createJson = new JSONObject
    createJson.put("@type", "did:sov:123456789abcdefghi1234;spec/issuer-setup/0.6/create")
    createJson.put("@id", UUID.randomUUID.toString)

    val currentPublicIdentifierJson = new JSONObject
    currentPublicIdentifierJson.put("@type",
      "did:sov:123456789abcdefghi1234;spec/issuer-setup/0.6/current-public-identifier")
    currentPublicIdentifierJson.put("@id", UUID.randomUUID.toString)

    new UndefinedIssuerSetup_0_6 {
      override def create(context: Context): Unit = {
        debugPrintln(s"issuer setup json: ${createJson.toString}")
        sendHttpPostReq(context, createJson.toString, ProtoRef("issuer-setup", "0.6"), Option(UUID.randomUUID.toString))
      }
      override def currentPublicIdentifier(context: Context): Unit = {
        debugPrintln(s"issuer setup json: ${currentPublicIdentifierJson.toString}")
        sendHttpPostReq(context, currentPublicIdentifierJson.toString, ProtoRef("issuer-setup", "0.6"),
          Option(UUID.randomUUID.toString))
      }
    }
  }

  override def updateEndpoint_0_6: UpdateEndpointV0_6 = {
    new UndefinedUpdateEndpoint_0_6 {
      override def update(context: Context): Unit = {
        val updateJson = new JSONObject
        updateJson.put("@type", "did:sov:123456789abcdefghi1234;spec/configs/0.6/UPDATE_COM_METHOD")
        updateJson.put("comMethod", {
          val json = new JSONObject
          json.put("id", "webhook")
          json.put("type", 2)
          json.put("value", context.endpointUrl)
          json.put("packaging", {
            val packaging = new JSONObject
            packaging.put("pkgType", "plain")
          })
        })

        sendHttpPostReq(context, updateJson.toString, ProtoRef("configs", "0.6"), None)

        // REST update endpoint also sends message on endpoint.
        val msg = expectMsg(defaultTimeout)

        val msgType = try {
          msg.getString(`@TYPE`)
        } catch {
          case _: Exception => throw new Exception(s"Unable to get message type for -- ${msg.toString()}")
        }

        val expectedName = "COM_METHOD_UPDATED"
        assert (msgType.endsWith(expectedName), s"Unexpected message name -- $msgType is not $expectedName")
      }
    }
  }

  override def updateConfigs_0_6(name: String, logoUrl: String): UpdateConfigsV0_6 = {
    val updateConfigsJson = new JSONObject
    updateConfigsJson.put("@type", "did:sov:123456789abcdefghi1234;spec/update-configs/0.6/update")
    updateConfigsJson.put("@id", UUID.randomUUID.toString)
    val configs = new JSONArray
    val item1 = new JSONObject
    item1.put("name", NAME_KEY)
    item1.put("value", name)
    configs.put(item1)
    val item2 = new JSONObject
    item2.put("name", LOGO_URL_KEY)
    item2.put("value", logoUrl)
    configs.put(item2)
    updateConfigsJson.put("configs", configs)

    new UndefinedUpdateConfigs_0_6 {
      override def update(ctx: Context): Unit = {
        debugPrintln(s"update logo&name json: ${updateConfigsJson.toString}")
        sendHttpPostReq(context, updateConfigsJson.toString, ProtoRef("update-configs", "0.6"),
          Option(UUID.randomUUID.toString))
      }
      override def status(ctx: Context): Unit = {
        debugPrintln(s"get logo&name json")
        sendHttpGetReq(context, ProtoRef("update-configs", "0.6"), None)
      }
    }
  }

  override def updateConfigs_0_6(): UpdateConfigsV0_6 = {
    new UndefinedUpdateConfigs_0_6 {
      override def status(ctx: Context): Unit = {
        debugPrintln(s"get logo&name json")
        sendHttpGetReq(context, ProtoRef("update-configs", "0.6"), None)
      }
    }
  }
  override def writeSchema_0_6(name: String, version: String, attrs: String*): WriteSchemaV0_6 = {
    val writeSchemaJson = new JSONObject
    writeSchemaJson.put("@type", "did:sov:123456789abcdefghi1234;spec/write-schema/0.6/write")
    writeSchemaJson.put("@id", UUID.randomUUID.toString)
    writeSchemaJson.put("name", name)
    writeSchemaJson.put("version", version)
    writeSchemaJson.put("attrNames", attrs.toArray)

    new UndefinedWriteSchema_0_6 {
      override def write(ctx: Context): Unit = {
        debugPrintln(s"write schema json: ${writeSchemaJson.toString}")
        sendHttpPostReq(context, writeSchemaJson.toString, ProtoRef("write-schema", "0.6"), Option(UUID.randomUUID.toString))
      }
    }
  }

  override def writeCredDef_0_6(name: String,
                                schemaId: String,
                                tag: Option[String],
                                revocationDetails: Option[RevocationRegistryConfig]): WriteCredentialDefinitionV0_6 = {

    val writeCredDefJson = new JSONObject
    writeCredDefJson.put("@type", "did:sov:123456789abcdefghi1234;spec/write-cred-def/0.6/write")
    writeCredDefJson.put("@id", UUID.randomUUID.toString)
    writeCredDefJson.put("name", name)
    writeCredDefJson.put("schemaId", schemaId)
    tag.map{ tag => writeCredDefJson.put("tag", tag) }
    revocationDetails.foreach{ d =>
      writeCredDefJson.put("revocationDetails", d.toJson)
    }

    new UndefinedWriteCredentialDefinition_0_6 {
      override def write(ctx: Context): Unit = {
        debugPrintln(s"write cred def json: ${writeCredDefJson.toString}")
        sendHttpPostReq(context, writeCredDefJson.toString, ProtoRef("write-cred-def", "0.6"), Option(UUID.randomUUID.toString))
      }
    }
  }

  override def committedAnswer_1_0(forRelationship: DID,
                                   questionText: String,
                                   questionDescription: String,
                                   validResponses: Seq[String],
                                   requireSig: Boolean): CommittedAnswerV1_0 = {

    val askJson = new JSONObject
    askJson.put("@type", "did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/committedanswer/1.0/ask-question")
    askJson.put("@id", UUID.randomUUID.toString)
    askJson.put("~for_relationship", forRelationship)
    askJson.put("text", questionText)
    askJson.put("detail", questionDescription)
    askJson.put("valid_responses", new JSONArray(validResponses.toArray))
    askJson.put("signature_required", requireSig)

    new UndefinedCommittedAnswer_1_0 {
      override def ask(ctx: Context): Unit = {
        debugPrintln(s"committedanswer ask json: ${askJson.toString}")
        sendHttpPostReq(context, askJson.toString, ProtoRef("committedanswer", "1.0"), Option(UUID.randomUUID.toString))
      }
    }
  }

  override def committedAnswer_1_0(forRelationship: DID, threadId: String, answer: String): CommittedAnswerV1_0 = {
    val answerJson = new JSONObject
    answerJson.put("@type", "did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/committedanswer/1.0/answer-question")
    answerJson.put("@id", UUID.randomUUID.toString)
    answerJson.put("~for_relationship", forRelationship)
    answerJson.put("response", answer)

    new UndefinedCommittedAnswer_1_0 {
      override def answer(ctx: Context): Unit = {
        debugPrintln(s"committedanswer answer json: ${answerJson.toString}")
        sendHttpPostReq(context, answerJson.toString, ProtoRef("committedanswer", "1.0"), Option(threadId))
      }
    }
  }

  override def committedAnswer_1_0(forRelationship: DID, threadId: String): CommittedAnswerV1_0 = {
    new UndefinedCommittedAnswer_1_0 {
      override def status(ctx: Context): Unit = {
        sendHttpGetReq(context, ProtoRef("committedanswer", "1.0"), Option(threadId), Map("~for_relationship" -> forRelationship, "familyQualifier" -> "BzCbsNYhMrjHiqZDTUASHg"))
      }
    }
  }

  def generateAuthHeader(ctx: Context): HttpHeader = {
    val signature = Base58Util.encode(Crypto.cryptoSign(ctx.walletHandle(), ctx.sdkVerKey, ctx.sdkVerKey.getBytes).get)
    RawHeader("X-API-key", s"${ctx.sdkVerKey}:$signature")
  }

  def sendHttpPostReq(ctx: Context, jsonMsg: String, protoRef: ProtoRef, threadId: Option[String]): Unit = {
    val restApiUrlPrefix = s"${context.verityUrl()}/api/${context.domainDID}/${protoRef.msgFamilyName}/${protoRef.msgFamilyVersion}"
    val restApiUrlThreadSuffix = threadId.map(tid => s"/$tid").getOrElse("")
    val restApiUrl: String = restApiUrlPrefix + restApiUrlThreadSuffix
    debugPrintln("# POST rest api url: " + restApiUrl)
    debugPrintln(s"# JSON msg sent: $jsonMsg")
    val result = Http()(system).singleRequest(
      HttpRequest(
        method = HttpMethods.POST,
        uri = restApiUrl,
        entity = HttpEntity(ContentTypes.`application/json`, jsonMsg),
      ).addHeader(generateAuthHeader(ctx))
    )

    val httpResponse = Await.result(result, httpTimeout)
    debugPrintln(s"# POST Response:${httpResponse.status} ${httpResponse.entity}")
  }

  def sendHttpGetReq(ctx: Context, protoRef: ProtoRef, threadId: Option[String], parameters: Map[String, String] = Map.empty): Unit = {
    val restApiUrlPrefix = s"${context.verityUrl()}/api/${context.domainDID}/${protoRef.msgFamilyName}/${protoRef.msgFamilyVersion}"
    val restApiUrlThreadSuffix = threadId.map(tid => s"/$tid").getOrElse("")
    val restApiUrl = restApiUrlPrefix + restApiUrlThreadSuffix + encodeGetParameters(parameters)
    debugPrintln("# GET rest api url: " + restApiUrl)
    val result = Http()(system).singleRequest(
      HttpRequest(
        method = HttpMethods.GET,
        uri = restApiUrl,
      ).addHeader(generateAuthHeader(ctx))
    )

    val httpResponse = Await.result(result, httpTimeout)

    debugPrintln(s"# GET Response:${httpResponse.status} ${httpResponse.entity}")
    receiveMsgFromResponse(httpResponse)
  }

  def encodeGetParameters(parameters: Map[String, String]): String = {
    if (parameters.nonEmpty)
      "?" + parameters.map{ case (key, value) => s"$key=$value"}.mkString("&")
    else
      ""
  }

  def receiveMsgFromResponse(httpResponse: HttpResponse): Unit = {
    if (httpResponse.status == OK) {
      val responseJson = new JSONObject(httpResponse.entity.asInstanceOf[HttpEntity.Strict].getData().utf8String)
      receiveMsg(responseJson.getJSONObject("result"))
    }
  }

  override def relationship_1_0(label: String): RelationshipV1_0 = {
    val createJson = new JSONObject
    createJson.put("@type", "did:sov:123456789abcdefghi1234;spec/relationship/1.0/create")
    createJson.put("@id", UUID.randomUUID.toString)
    createJson.put("label", label)

    new UndefinedRelationship_1_0 {
      override def create(context: Context): Unit = {
        debugPrintln(s"relationship create json: ${createJson.toString}")
        sendHttpPostReq(context, createJson.toString, ProtoRef("relationship", "1.0"), None)
      }
    }
  }

  override def relationship_1_0(forRelationship: String, threadId: String): RelationshipV1_0 = {

    new UndefinedRelationship_1_0 {
      override def connectionInvitation(context: Context, shortInvite: lang.Boolean): Unit = {
        val connInvitation = new JSONObject
        connInvitation.put("@type", "did:sov:123456789abcdefghi1234;spec/relationship/1.0/connection-invitation")
        connInvitation.put("@id", UUID.randomUUID.toString)
        connInvitation.put("~for_relationship", forRelationship)
        connInvitation.put("shortInvite", shortInvite)

        debugPrintln(s"relationship connectionInvitation json: ${connInvitation.toString}")
        sendHttpPostReq(context, connInvitation.toString, ProtoRef("relationship", "1.0"), Option(threadId))
      }
      override def outOfBandInvitation(context: Context, shortInvite: lang.Boolean, goal: GoalCode): Unit = {
        val oobJsonMsg = new JSONObject
        oobJsonMsg.put("@type", "did:sov:123456789abcdefghi1234;spec/relationship/1.0/out-of-band-invitation")
          .put("@id", UUID.randomUUID.toString)
          .put("~for_relationship", forRelationship)
          .put("goalCode", goal.code())
          .put("goal", goal.goalName())
          .put("shortInvite", shortInvite)

        debugPrintln(s"relationship outOfBandInvitation json: ${oobJsonMsg.toString}")
        sendHttpPostReq(context, oobJsonMsg.toString, ProtoRef("relationship", "1.0"), Option(threadId))
      }
    }
  }

  override def connecting_1_0(sourceId: String, label: String, inviteURL: String): ConnectionsV1_0 = ???

  override def connectingWithOutOfBand_1_0(sourceId: String,
                                           label: String,
                                           inviteURL: String): ConnectionsV1_0 = ???

  override def outOfBand_1_0(forRelationship: String, inviteURL: String): OutOfBandV1_0 =
    OutOfBand.v1_0(forRelationship, inviteURL)


  override def issueCredential_1_0(forRelationship: String,
                                   credDefId: String,
                                   values: Map[String, String],
                                   comment: String,
                                   price: String = "0",
                                   autoIssue: Boolean = false,
                                   byInvitation: Boolean = false): IssueCredentialV1_0 = {

    val credValues: JSONObject = new JSONObject
    for ((key, value) <- values) {
      credValues.put(key, value)
    }
    val credOfferJson = new JSONObject
    credOfferJson.put("@type", "did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/issue-credential/1.0/offer")
    credOfferJson.put("@id", UUID.randomUUID.toString)
    credOfferJson.put("~for_relationship", forRelationship)
    credOfferJson.put("cred_def_id", credDefId)
    credOfferJson.put("credential_values", credValues)

    new UndefinedIssueCredential_1_0 {
      override def offerCredential(context: Context): Unit = {
        debugPrintln(s"send credential offer json: ${credOfferJson.toString}")
        sendHttpPostReq(context, credOfferJson.toString, ProtoRef("issue-credential", "1.0"), Option(UUID.randomUUID.toString))
      }
    }
  }

  override def issueCredential_1_0(forRelationship: String, threadId: String): IssueCredentialV1_0 = {
    val issueCredJson = new JSONObject
    issueCredJson.put("@type", "did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/issue-credential/1.0/issue")
    issueCredJson.put("@id", UUID.randomUUID.toString)
    issueCredJson.put("~for_relationship", forRelationship)

    val credStatusJson = new JSONObject
    credStatusJson.put("@type", "did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/issue-credential/1.0/status")
    credStatusJson.put("@id", UUID.randomUUID.toString)
    credStatusJson.put("~for_relationship", forRelationship)

    new UndefinedIssueCredential_1_0 {

      override def issueCredential(ctx: Context): Unit = {
        debugPrintln(s"issue credential json: ${issueCredJson.toString}")
        sendHttpPostReq(context, issueCredJson.toString, ProtoRef("issue-credential", "1.0"), Option(threadId))
      }

      override def status(ctx: Context): Unit = {
        debugPrintln(s"issue credential status json: ${credStatusJson.toString}")
        sendHttpGetReq(context, ProtoRef("issue-credential", "1.0"), Option(threadId),
          Map("~for_relationship" -> forRelationship, "familyQualifier" -> "BzCbsNYhMrjHiqZDTUASHg", "msgName" -> "status"))
      }
    }
  }


  override def issueCredentialComplete_1_0(): Unit = ???

  override def presentProof_1_0(forRelationship: DID, name: String, attrs: Attribute*): PresentProofV1_0 = {
    val proofReqJson = new JSONObject
    proofReqJson.put("@type", "did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/present-proof/1.0/request")
    proofReqJson.put("@id", UUID.randomUUID.toString)
    proofReqJson.put("~for_relationship", forRelationship)
    proofReqJson.put("name", name)
    proofReqJson.put("proof_attrs", JsonUtil.makeArray(attrs.toArray))

    new UndefinedPresentProof_1_0 {
      override def request(ctx: Context): Unit = {
        debugPrintln(s"present proof request json: ${proofReqJson.toString}")
        sendHttpPostReq(context, proofReqJson.toString, ProtoRef("present-proof", "1.0"), Option(UUID.randomUUID.toString))
      }
    }
  }

  override def presentProof_1_0(forRelationship: DID, threadId: String): PresentProofV1_0 = {
    new UndefinedPresentProof_1_0 {
      override def status(ctx: Context): Unit = {
        sendHttpGetReq(context, ProtoRef("present-proof", "1.0"), Option(threadId),
          Map("~for_relationship" -> forRelationship, "familyQualifier" -> "BzCbsNYhMrjHiqZDTUASHg", "msgName" -> "status"))
      }
    }
  }
}