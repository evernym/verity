package com.evernym.verity.integration.multi_verity.base

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest, HttpResponse}
import com.evernym.verity.actor.agent.DidPair
import com.evernym.verity.actor.agent.MsgPackFormat.MPF_INDY_PACK
import com.evernym.verity.actor.wallet._
import com.evernym.verity.actor.{AgencyPublicDid, ComMethodUpdated, Platform}
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.agentmsg.msgcodec.jackson.JacksonMsgCodec
import com.evernym.verity.agentmsg.msgfamily.ConfigDetail
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil.{MSG_FAMILY_CONFIGS, MSG_TYPE_UPDATE_COM_METHOD}
import com.evernym.verity.agentmsg.msgfamily.configs.{ComMethod, ComMethodPackaging, UpdateComMethodReqMsg, UpdateConfigReqMsg}
import com.evernym.verity.agentmsg.msgpacker.AgentMsgPackagingUtil
import com.evernym.verity.constants.Constants.COM_METHOD_TYPE_HTTP_ENDPOINT
import com.evernym.verity.libindy.wallet.LibIndyWalletProvider
import com.evernym.verity.protocol.engine.Constants.MFV_0_6
import com.evernym.verity.protocol.engine.MsgFamily.{EVERNYM_QUALIFIER, typeStrFromMsgType}
import com.evernym.verity.protocol.engine.{DID, MsgFamily, VerKey}
import com.evernym.verity.protocol.protocols.agentprovisioning.v_0_7.AgentProvisioningMsgFamily
import com.evernym.verity.protocol.protocols.agentprovisioning.v_0_7.AgentProvisioningMsgFamily.{AgentCreated, CreateCloudAgent, CreateEdgeAgent, RequesterKeys}
import com.evernym.verity.protocol.protocols.updateConfigs.v_0_6.ConfigResult
import com.evernym.verity.testkit.LegacyWalletAPI
import com.evernym.verity.vault.{KeyParam, WalletAPIParam}
import org.json.JSONObject

import java.util.UUID
import scala.concurrent.Await
import scala.concurrent.duration.{Duration, SECONDS}
import scala.reflect.ClassTag
import scala.util.Random

trait DomainProvider {

  def setupMyDomain(platform: Platform): MockDomain = {
    MockDomain(platform)
  }
}

/**
 * a domain contains several agents (edge, cloud etc)
 * @param myVerityPlatform
 */
case class MockDomain(myVerityPlatform: Platform) extends Unpacker {
  import LocalVerityUtil._

  def fetchAgencyKey(): Unit = {
    val resp = sendGET("")
    val json = parseHttpResponse(resp)
    val apd = JacksonMsgCodec.fromJson[AgencyPublicDid](json)
    storeTheirKey(apd.didPair)
    agencyPublicDidOpt = Option(apd)
  }

  def provisionVerityEdgeAgent(): AgentCreated = {
    provisionVerityAgentBase(CreateEdgeAgent(localAgentDidPair.verKey, None))
  }

  def provisionVerityCloudAgent(): AgentCreated = {
    val reqKeys = RequesterKeys(localAgentDidPair.DID, localAgentDidPair.verKey)
    provisionVerityAgentBase(CreateCloudAgent(reqKeys, None))
  }

  private def provisionVerityAgentBase(createAgentMsg: Any): AgentCreated = {
    val createAgentJsonMsg = createJson(AgentProvisioningMsgFamily, createAgentMsg)
    val packedMsg = packFromLocalAgentKey(createAgentJsonMsg, Set(KeyParam.fromVerKey(agencyVerKey)))
    val routedPackedMsg = prepareFwdMsg(agencyDID, agencyDID, packedMsg)
    val verityAgent = parseAndUnpackResponse[AgentCreated](sendPOST(routedPackedMsg))
    verityAgentDidPairOpt = Option(DidPair(verityAgent.selfDID, verityAgent.agentVerKey))
    storeTheirKey(DidPair(verityAgent.selfDID, verityAgent.agentVerKey))
    verityAgent
  }

  def registerLocalAgentWebhook(): ComMethodUpdated = {
    val packaging = Option(ComMethodPackaging(MPF_INDY_PACK.toString, Option(Set(myLocalAgentVerKey))))
    val updateComMethod = UpdateComMethodReqMsg(
      ComMethod("1", COM_METHOD_TYPE_HTTP_ENDPOINT, msgListener.endpoint, packaging)
    )
    val typeStr = typeStrFromMsgType(EVERNYM_QUALIFIER, MSG_FAMILY_CONFIGS, MFV_0_6, MSG_TYPE_UPDATE_COM_METHOD)
    val updateComMethodJson = createJson(typeStr, updateComMethod)
    val routedPackedMsg = packForVerityAgent(updateComMethodJson)
    parseAndUnpackResponse[ComMethodUpdated](checkOKResponse(sendPOST(routedPackedMsg)))
  }

  def updateConfig(): ConfigResult = {
    val updateConfig = UpdateConfigReqMsg(
      Set(ConfigDetail("name", "issuer-name"), ConfigDetail("logoUrl", "issuer-logo-url"))
    )
    val typeStr = typeStrFromMsgType(EVERNYM_QUALIFIER, "update-configs", MFV_0_6, "update")
    val updateConfigJson = createJson(typeStr, updateConfig)
    val routedPackedMsg = packForVerityAgent(updateConfigJson)
    checkOKResponse(sendPOST(routedPackedMsg))
    expectMsgOnWebhook[ConfigResult]
  }

  //------------------------------------------------------------------------

  lazy val msgListener = new MsgListener(
    8000 + Random.nextInt(1000), this
  )(myVerityPlatform.appConfig, system)

  def expectMsgOnWebhook[T: ClassTag]: T = {
    msgListener.expectMsg(Duration(10, SECONDS))
  }

  private def parseAndUnpackResponse[T: ClassTag](resp: HttpResponse): T = {
    val packedMsg = parseHttpResponse(resp)
    unpackMsg[T](packedMsg.getBytes)
  }

  private def parseHttpResponse(resp: HttpResponse): String = {
    Await.result(
      resp.entity.dataBytes.runReduce(_ ++ _).map(_.utf8String),
      Duration(10, SECONDS)
    )
  }

  private def packForConn(connId: String, msg: String): Array[Byte] = {
    val forRelMsg = addForRel(connId, msg)
    val cloudPackedMsg = packFromLocalAgentKey(forRelMsg, Set(KeyParam.fromVerKey(verityAgentDidPair.verKey)))
    prepareFwdMsg(agencyDID, verityAgentDidPair.DID, cloudPackedMsg)
  }

  private def addForRel(connId: String, msg: String): String = {
    val relationship = myRelationships(connId)
    val jsonObject = new JSONObject(msg)
    jsonObject.put("~for_relationship", relationship.cloudAgentDidPair.get.DID)
    jsonObject.toString
  }

  private def packForVerityAgent(msg: String): Array[Byte] = {
    val packedMsgForVerityAgent = packFromLocalAgentKey(msg, Set(KeyParam.fromVerKey(verityAgentDidPair.verKey)))
    prepareFwdMsg(agencyDID, verityAgentDidPair.DID, packedMsgForVerityAgent)
  }

  /**
   *
   * @param recipDID recipient of fwd msg
   * @param fwdToDID destination of the given 'msg'
   * @param msg the message to be sent
   * @return
   */
  private def prepareFwdMsg(recipDID: DID, fwdToDID: DID, msg: Array[Byte]): Array[Byte] = {
    val fwdJson = AgentMsgPackagingUtil.buildFwdJsonMsg(MPF_INDY_PACK, fwdToDID, msg)
    val senderKey = if (recipDID == agencyDID) None else Option(KeyParam.fromVerKey(myLocalAgentVerKey))
    packMsg(fwdJson, Set(KeyParam.fromDID(recipDID)), senderKey)
  }

  private def packFromLocalAgentKey(msg: String, recipVerKeyParams: Set[KeyParam]): Array[Byte] = {
    packMsg(msg, recipVerKeyParams, Option(KeyParam.fromVerKey(myLocalAgentVerKey)))
  }

  private def packMsg(msg: String,
                      recipVerKeyParams: Set[KeyParam],
                      senderKeyParam: Option[KeyParam]): Array[Byte] = {
    val msgBytes = msg.getBytes()
    val pm = testWalletAPI.executeSync[PackedMsg](
      PackMsg(msgBytes, recipVerKeyParams, senderKeyParam))
    pm.msg
  }

  override def unpackMsg[T: ClassTag](msg: Array[Byte]): T = {
    val json = testWalletAPI.executeSync[UnpackedMsg](UnpackMsg(msg)).msgString
    val jsonObject = new JSONObject(json)
    DefaultMsgCodec.fromJson[T](jsonObject.getString("message"))
  }

  private def createJson(msgFamily: MsgFamily, msg: Any): String = {
    val msgType = msgFamily.msgType(msg.getClass)
    val typeStr = MsgFamily.typeStrFromMsgType(msgType)
    createJson(typeStr, msg)
  }

  private def createJson(typeStr: String, msg: Any): String = {
    val coreJson = createCoreJson(msg)
    coreJson.put("@type", typeStr).toString
  }

//  private def createJson(typeOb: JSONObject, msg: Any): String = {
//    val coreJson = createCoreJson(msg)
//    coreJson.put("@type", typeOb).toString
//  }

  private def createCoreJson(msg: Any): JSONObject = {
    val coreJson = DefaultMsgCodec.toJson(msg)
    new JSONObject(coreJson)
  }

  private def legacyType(name: String, ver: String): JSONObject = {
    val jsonObj = new JSONObject()
    jsonObj.put("name", name)
    jsonObj.put("ver", ver)
    jsonObj
  }

  private def checkOKResponse(resp: HttpResponse): HttpResponse = {
    require(resp.status.intValue() == OK.intValue,
      s"http response was not OK: ${resp.status}")
    resp
  }

  private def sendPOST(payload: Array[Byte]): HttpResponse = {
    Await.result(
      Http().singleRequest(
        HttpRequest(
          method=HttpMethods.POST,
          uri = platformAgencyMsgUrl(myVerityPlatform),
          entity = HttpEntity(
            ContentTypes.`application/octet-stream`,
            payload
          )
        )
      ),
      Duration(10, SECONDS)
    )
  }

  private def sendGET(pathSuffix: String): HttpResponse = {
    val actualPath = platformAgencyUrl(myVerityPlatform) + s"/$pathSuffix"
    Await.result(
      Http().singleRequest(
        HttpRequest(
          method=HttpMethods.GET,
          uri = actualPath,
          entity = HttpEntity.Empty
        )
      ),
      Duration(10, SECONDS)
    )
  }

  type ConnId = String

  implicit val walletAPIParam: WalletAPIParam = WalletAPIParam(UUID.randomUUID().toString)
  implicit val system: ActorSystem = myVerityPlatform.actorSystem

  var agencyPublicDidOpt: Option[AgencyPublicDid] = None

  //local agent (on client side)
  val localAgentDidPair: DidPair = createNewKey()

  //verity agent (edge/cloud)
  var verityAgentDidPairOpt: Option[DidPair] = None

  var myRelationships: Map[ConnId, Relationship] = Map.empty

  def createNewKey(): DidPair = {
    testWalletAPI.executeSync[NewKeyCreated](CreateNewKey()).didPair
  }

  def storeTheirKey(didPair: DidPair): Unit = {
    testWalletAPI.executeSync[TheirKeyStored](StoreTheirKey(didPair.DID, didPair.verKey))
  }

  def agencyPublicDid: AgencyPublicDid = agencyPublicDidOpt.getOrElse(
    throw new RuntimeException("agency key is not yet fetched")
  )
  def verityAgentDidPair: DidPair = verityAgentDidPairOpt.getOrElse(
    throw new RuntimeException("verity agent not yet created")
  )
  def agencyDID: DID = agencyPublicDid.DID
  def agencyVerKey: VerKey = agencyPublicDid.verKey
  def myLocalAgentVerKey: VerKey = localAgentDidPair.verKey

  private lazy val testWalletAPI: LegacyWalletAPI = {
    val walletProvider = new LibIndyWalletProvider(myVerityPlatform.appConfig)
    val walletAPI = new LegacyWalletAPI(myVerityPlatform.appConfig, walletProvider, None)
    walletAPI.executeSync[WalletCreated.type](CreateWallet())
    walletAPI
  }

}

trait Unpacker {
  def unpackMsg[T: ClassTag](msg: Array[Byte]): T
}

case class Relationship(myDidPair: DidPair, cloudAgentDidPair: Option[DidPair]=None)