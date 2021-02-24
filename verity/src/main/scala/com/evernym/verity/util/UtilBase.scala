package com.evernym.verity.util

import java.net.URLEncoder
import java.time._
import java.util
import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorSystem}
import akka.util.Timeout
import com.evernym.verity.constants.Constants._
import com.evernym.verity.Exceptions.{HandledErrorException, _}
import com.evernym.verity.Status._
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.protocol.engine.{DID, VerKey}
import com.evernym.verity.protocol.protocols.connecting.common.AgentKeyDlgProof
import com.evernym.verity.util.HashAlgorithm.SHA256
import com.evernym.verity.util.HashUtil.byteArray2RichBytes
import com.evernym.verity.util.TimeZoneUtil.getCurrentUTCZonedDateTime
import com.evernym.verity.vault._
import com.evernym.verity.UrlParam
import com.evernym.verity.actor.wallet.SignMsg
import com.evernym.verity.vault.service.AsyncToSync
import com.evernym.verity.vault.wallet_api.WalletAPI
import com.fasterxml.jackson.core.JsonParseException
import com.typesafe.scalalogging.Logger
import org.apache.commons.codec.digest.DigestUtils
import org.velvia.MsgPack
import org.velvia.MsgPackUtils.unpackMap


// TODO should not be needed here, should remove utils that use it
import com.evernym.verity.agentmsg.DefaultMsgCodec

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.language.implicitConversions
import scala.util.{Failure, Left, Success}


case class PackedMsgWrapper(msg: Array[Byte], reqMsgContext: ReqMsgContext) extends ActorMessage


trait UtilBase extends AsyncToSync {
  val logger: Logger = getLoggerByClass(classOf[UtilBase])

  def replaceVariables(str: String, map: Map[String, String]): String ={
    val encodedStr = map.foldLeft(str)((s:String, x:(String,String)) => ( "#\\{" + x._1 + "\\}" ).r.
      replaceAllIn(s, java.net.URLEncoder.encode(x._2,"utf-8")))
    try {
      java.net.URLDecoder.decode(encodedStr,"utf-8")
    } catch {
      case _:IllegalArgumentException => encodedStr
    }
  }

  def checkRequiredField(fieldName: String, fieldValue: Option[String]): Unit = {
    try {
      require(fieldValue.isDefined, s"missing required field: '$fieldName'")
    } catch {
      case e: IllegalArgumentException if e.getMessage.startsWith("requirement failed: ") =>
        throw new MissingReqFieldException(Option(e.getMessage),
          Option("missing required field: " + fieldName))
    }
  }

  def getOptionalField(fieldName: String, map: Map[String, Any]): Option[String] = {
    map.get(fieldName).map(_.toString).filter(_.trim.nonEmpty)
  }

  def getRequiredField(fieldName: String, map: Map[String, Any]): String = {
    val fieldValue = try {
      getOptionalField(fieldName, map)
    } catch {
      case e: NullPointerException =>
        throw new MissingReqFieldException(Option(e.getMessage))
    }
    checkRequiredField(fieldName, fieldValue)
    fieldValue.orNull
  }

  def getSeed(seedOpt: Option[String] = None): String =
    seedOpt.getOrElse(UUID.randomUUID().toString.replaceAll("-",""))

  def getJsonStringFromMap(data: Map[String, Any]): String = DefaultMsgCodec.toJson(data)

  def getMapWithStringValueFromJsonString(msg: String): Map[String, String] = {
    try {
      // TODO we should remove this method
      DefaultMsgCodec.fromJson[Map[String,String]](msg)
    } catch {
      case _: JsonParseException => throw new InvalidJsonException(Option(s"invalid json: $msg"))
    }
  }

  def getMapWithAnyValueFromJsonString(msg: String): Map[String, Any] = {
    try {
      // TODO we should remove this method
      DefaultMsgCodec.fromJson[Map[String,Any]](msg)
    } catch {
      case _: JsonParseException => throw new InvalidJsonException(Option(s"invalid json: $msg"))
    }
  }

  def checkIfDIDIsValid(did: DID): Unit = {
    checkIfDIDLengthIsValid(did)
    checkIfDIDIsBase58(did)
  }

  def checkIfDIDLengthIsValid(did: DID): Unit = {
    val isInvalidLength = ! VALID_IDENTIFIER_LENGTH_RANGE.contains(did.length)
    if (isInvalidLength) throw new InvalidValueException(
      Option(s"actual length of DID ($did) is: ${did.length}, " +
      s"expected length is: $VALID_IDENTIFIER_LENGTH_RANGE"))
  }

  def checkIfDIDIsBase58(did: DID): Unit = {
    val decodedDid = Base58Util.decode(did)
    decodedDid match {
      case Success(_) =>
      case Failure(e) =>
        throw new InvalidValueException(Option(s"DID ($did) is not a Base58 string. Reason: ${e.getMessage}"))
    }
  }

  def checkIfHashedUrlLengthIsValid(hashedUrl: String): Unit = {
    val isInvalidLength = ! VALID_HASHED_URL_LENGTH_RANGE.contains(hashedUrl.length)
    if (isInvalidLength) throw new InvalidValueException(
      Option(s"actual length of given hashed url ($hashedUrl) is: ${hashedUrl.length}, " +
      s"expected length is: $VALID_HASHED_URL_LENGTH_RANGE"))
  }

  //TODO: this method is blocking, we should use some alternative of this
  def getActorRefFromSelection(path: String, actorSystem: ActorSystem,
                               resolveTimeoutOpt: Option[Timeout] = None)
                              (implicit appConfig: AppConfig): ActorRef = {
    val awaitDuration = buildDuration(
      appConfig,
      TIMEOUT_ACTOR_REF_RESOLVE_TIMEOUT_IN_SECONDS,
      DEFAULT_ACTOR_REF_RESOLVE_TIMEOUT_IN_SECONDS)
    val resolveTimeout = resolveTimeoutOpt.getOrElse(Timeout(awaitDuration))
    Await.result(actorSystem.actorSelection(path).resolveOne()(resolveTimeout), awaitDuration)
  }

  def isAllDigits(x: String): Boolean = x.matches("^\\d+$")

  def getNormalizedPhoneNumber(ph: String): String = {

    val nph =
      ph.replace("-","").
        replace(" ","").
        replace("(","").
        replace(")","").trim

    if ( !isAllDigits(if (nph.startsWith("+")) nph.substring(1) else nph) ) {
      throw new BadRequestErrorException(INVALID_VALUE.statusCode, Option("invalid phone number"))
    }

    nph
  }

  // E.164 international standard format
  // Format: +[Country code (1-3 digits)][Subscriber number (max 12 digits)]
  def isPhoneNumberInValidFormat(num: String): Boolean = num.matches("^\\+\\d{8,15}$")

  def camelToDashSeparated(name: String): String = "[A-Z\\d]".r.replaceAllIn(name, {m =>
    "-" + m.group(0).toLowerCase
  })

  def camelToCapitalize(name: String): String = {
    "[A-Z\\d]".r.replaceAllIn(name, {m =>
      " " + m.group(0).toUpperCase
    }).capitalize
  }

  //TODO: need to confirm if sha256 ok for hashing urls
  def getSHA256HashedShortUrl(url: String): String = sha256Hex(url, length = Option(8))

  def sha256Hex(text: String, length: Option[Int] = None): String = {
    val res = DigestUtils.sha256Hex(text)
    length.map { l =>
      res.substring(0, l)
    }.getOrElse(res)
  }

  def getEventEncKey(secret: String, appConfig: AppConfig): String = {
    //NOTE: This logic should not be changed unless we know its impact
    val salt = appConfig.getConfigStringReq(SALT_EVENT_ENCRYPTION)
    DigestUtils.sha512Hex(secret + salt)
  }

  def packMsgByMsgPackLib(map: Map[String, Any]): Array[Byte] = {
    MsgPack.pack(map)
  }

  def unpackMsgByMsgPackLib(byteArray: Array[Byte]): Map[String, Any] = {
    unpackMap(byteArray).asInstanceOf[Map[String, Any]]
  }

  def isExpired(createdDateTime: ZonedDateTime, expiryTimeInSeconds: Int): Boolean = {
    val curTime = getCurrentUTCZonedDateTime
    val expiryTime = createdDateTime.plusSeconds(expiryTimeInSeconds)
    curTime.isAfter(expiryTime)
  }

  def buildAgencyEndpoint(appConfig: AppConfig): UrlParam = {
    val host = appConfig.getConfigStringReq(VERITY_ENDPOINT_HOST)
    val port = appConfig.getConfigIntReq(VERITY_ENDPOINT_PORT)
    val pathPrefix = Option(appConfig.getConfigStringReq(VERITY_ENDPOINT_PATH_PREFIX))
    UrlParam(host, port, pathPrefix)
  }

  def checkIfDIDBelongsToVerKey(did: DID, verKey: VerKey): Unit = {
    val verifKey = Base58Util.decode(verKey).get
    val didFromVerKey = Base58Util.encode(verifKey.take(16))
    if (did != didFromVerKey) {
      throw new InvalidValueException(Option(s"DID and verKey not belonging to each other (DID: $did, verKey: $verKey)"))
    }
  }

  def getStringFromByteArray(ba: Array[Byte]): String = {
    util.Arrays.toString(ba)
  }

  def getNewEntityId: String = UUID.randomUUID.toString

  def getNewActorId: String = getNewEntityId

  def buildUnsupportedVersion(typ:String, unsupportedVer: String, lowerSupportedVersion: String,
                               higherSupportedVer: String): String = {
    if(lowerSupportedVersion == higherSupportedVer)
      s"unsupported version $unsupportedVer for msg $typ, " +
        s"supported version is $lowerSupportedVersion"
    else
      s"unsupported version $unsupportedVer for msg $typ, " +
        s"supported versions are $lowerSupportedVersion to $higherSupportedVer"
  }

  def handleUnsupportedVersion(typ:String, unsupportedVer: String, lowerSupportedVersion: String,
                               higherSupportedVer: String): Exception = {
    val msg = buildUnsupportedVersion(typ, unsupportedVer, lowerSupportedVersion, higherSupportedVer)
    throw new BadRequestErrorException(UNSUPPORTED_MSG_VERSION.statusCode, Option(msg))
  }

  def handleUnsupportedMsgType(typeDetail:String): Exception = {
    throw new BadRequestErrorException(UNSUPPORTED_MSG_TYPE.statusCode, Option(typeDetail))
  }

  def strToBoolean(str:String) :Boolean = if (str.toUpperCase == YES) true else false

  def getLedgerTxnProtocolVersion(appConfig: AppConfig): Int = {
    val supportedVersions = Set(LEDGER_TXN_PROTOCOL_V1, LEDGER_TXN_PROTOCOL_V2)
    appConfig.getConfigIntOption(LIB_INDY_LEDGER_TXN_PROTOCOL_VERSION) match {
      case None => LEDGER_TXN_PROTOCOL_V2
      case Some(v: Int) if supportedVersions.contains(v) => v
      case Some(x) => throw new RuntimeException(s"ledger txn protocol version $x not yet supported")
    }
  }


  def isDID(token: String): Boolean = {
    // NOTE: This may need to change to conform to https://w3c-ccg.github.io/did-spec/
    try {
      checkIfDIDIsValid(token)
      true
    } catch {
      case _: InvalidValueException => false
    }
  }

  def getTimeoutValue(appConfig: AppConfig, confName: String, default: Int): Int = {
    appConfig.getConfigIntOption(confName).getOrElse(default)
  }

  def buildDuration(appConfig: AppConfig, confName: String, default: Int): FiniteDuration = {
    val timeOutInSec = getTimeoutValue(appConfig, confName, default)
    Duration.create(timeOutInSec, TimeUnit.SECONDS)
  }

  def buildTimeout(appConfig: AppConfig, confName: String, default: Int): Timeout = {
    Timeout(buildDuration(appConfig, confName, default))
  }

  def encodedUrl(url: String, enc: String = "UTF-8"): String = URLEncoder.encode(url, enc)

  def buildHandledError(sd: StatusDetail): HandledErrorException = {
    buildHandledError(sd.statusCode, Option(sd.statusMsg))
  }

  def buildHandledError(respCode: String,
                        respMsg: Option[String]=None,
                        respDetail: Option[String]=None,
                        errorDetail: Option[Any]=None): HandledErrorException = {
    try {
      HandledErrorException(respCode, respMsg, respDetail, errorDetail)
    } catch {
      case _: Exception =>
        new InternalServerErrorException(respCode, respMsg, respDetail, errorDetail)
    }
  }

  //TODO: this method is used by protocols and specs
  //when we change protocols to start using async api, we should change this too
  def getAgentKeyDlgProof(signerDIDVerKey: VerKey, pairwiseDID: DID, pairwiseVerKey: VerKey)
                           (implicit walletAPI: WalletAPI, wap: WalletAPIParam): AgentKeyDlgProof = {
    val keyDlgProof = AgentKeyDlgProof(pairwiseDID, pairwiseVerKey, "")
    val sig = DEPRECATED_convertToSyncReq(walletAPI.executeAsync[Array[Byte]](SignMsg(KeyParam(Left(signerDIDVerKey)), keyDlgProof.buildChallenge.getBytes)))
    keyDlgProof.copy(signature=Base64Util.getBase64Encoded(sig))
  }

  def jsonArray(item: String): String = jsonArray(Set(item))

  // Question: Why items is a Set and not a Seq, do we care about uniqueness of items?
  def jsonArray(items: Set[String]): String = {
    JsonUtil.jsonArray(items)
  }

  def makeCache[K, V](capacity: Int): util.Map[K, V] = {
    new util.LinkedHashMap[K, V](capacity, 0.7F, true) {
      private val cacheCapacity = capacity

      override def removeEldestEntry(entry: util.Map.Entry[K, V]): Boolean = {
        this.size() > this.cacheCapacity
      }
    }
  }

  def saltedHashedName(name: String, appConfig: AppConfig): String = {
    //NOTE: This logic should not be changed unless we know its impact
    // TODO we should not concate string before hashing, should use safeMultiHash
    HashUtil.hash(SHA256)(name + appConfig.getConfigStringReq(SALT_WALLET_NAME)).hex
  }
}
