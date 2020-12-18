package com.evernym.verity.actor.wallet


import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.ExecutionException

import akka.actor.Actor
import com.evernym.verity.Exceptions
import com.evernym.verity.Exceptions.{BadRequestErrorException, InternalServerErrorException}
import com.evernym.verity.Status.{ALREADY_EXISTS, INVALID_VALUE, SIGNATURE_VERIF_FAILED, UNHANDLED}
import com.evernym.verity.actor.ActorMessageClass
import com.evernym.verity.actor.agent.SpanUtil.runWithInternalSpan
import com.evernym.verity.actor.persistence.BaseNonPersistentActor
import com.evernym.verity.config.{AppConfig, AppConfigWrapper, ConfigUtil}
import com.evernym.verity.constants.LogKeyConstants.LOG_KEY_ERR_MSG
import com.evernym.verity.ledger.LedgerRequest
import com.evernym.verity.libindy.LibIndyWalletProvider
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.metrics.CustomMetrics.{AS_SERVICE_LIBINDY_WALLET_DURATION, AS_SERVICE_LIBINDY_WALLET_FAILED_COUNT, AS_SERVICE_LIBINDY_WALLET_SUCCEED_COUNT}
import com.evernym.verity.metrics.MetricsWriter
import com.evernym.verity.protocol.engine.{DID, VerKey}
import com.evernym.verity.util.Util.jsonArray
import com.evernym.verity.vault.WalletUtil.{buildWalletConfig, getWalletKeySeed, getWalletName}
import com.evernym.verity.vault.{CreateNewKeyParam, GetVerKeyByDIDParam, GetVerKeyByKeyInfoParam, KeyInfo, NewKeyCreated, SignMsgParam, StoreTheirKeyParam, SubmitReqParam, TheirKeyCreated, VerifResult, VerifySigByKeyInfoParam, VerifySigByVerKeyParam, WalletAccessParam, WalletAlreadyOpened, WalletConfig, WalletDoesNotExist, WalletExt, WalletProvider}
import com.typesafe.scalalogging.Logger
import org.hyperledger.indy.sdk.{InvalidParameterException, InvalidStructureException}
import org.hyperledger.indy.sdk.anoncreds.Anoncreds
import org.hyperledger.indy.sdk.anoncreds.AnoncredsResults.{IssuerCreateAndStoreCredentialDefResult, IssuerCreateSchemaResult}
import org.hyperledger.indy.sdk.crypto.Crypto
import org.hyperledger.indy.sdk.did.Did
import org.hyperledger.indy.sdk.ledger.Ledger.signRequest
import org.hyperledger.indy.sdk.wallet.WalletItemAlreadyExistsException

import scala.compat.java8.FutureConverters
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration

case class CreateWallet(walletName: String, encryptionKey: String) extends ActorMessageClass
case class SignLedgerRequest(submitReqParam: SubmitReqParam) extends ActorMessageClass
case class GenerateWalletKey(seedOpt: Option[String] = None) extends ActorMessageClass
case class CreateDID(keyType: String) extends ActorMessageClass
case class StoreTheirKey(storeTheirKeyParam: StoreTheirKeyParam, ignoreIfAlreadyExists: Boolean=false) extends ActorMessageClass
case class GetVerKeyFromDetail(verKeyDetail: Either[VerKey, GetVerKeyByDIDParam]) extends ActorMessageClass
case class GetVerKeyFromWallet(verKeyDetail: Either[VerKey, GetVerKeyByDIDParam]) extends ActorMessageClass // Do we need it?
case class GetVerKey(keyInfo: KeyInfo) extends ActorMessageClass
case class SignMsg(keyInfo: KeyInfo, msg: Array[Byte]) extends ActorMessageClass
case class VerifySig(keyInfo: KeyInfo, challenge: Array[Byte], signature: Array[Byte]) extends ActorMessageClass
case class VerifySigWithVerKey(verKey: VerKey, challenge: Array[Byte], signature: Array[Byte]) extends ActorMessageClass
case class PackMessage(msg: String, recipVerKeys: Vector[VerKey], senderVerKey: Option[VerKey]) extends ActorMessageClass
case class UnpackMessage(msg: Array[Byte]) extends ActorMessageClass
case class CleanDIDMetaData(did: DID) extends ActorMessageClass
case class CreateMasterSecret(masterSecretId: String) extends ActorMessageClass


// It's strange to have this commands here, but most of them need wallet data
case class CreateSchema(issuerDID: DID, name:String, version: String, data: String) extends ActorMessageClass // don't need wallet!
case class CreateCredDef(issuerDID: DID,
                         schemaJson: String,
                         tag: String,
                         sigType: Option[String],
                         revocationDetails: Option[String]) extends ActorMessageClass
case class CreateCredOffer(credDefId: String) extends ActorMessageClass
case class CreateCredReq(proverDID: DID, credDefJson: String, credOfferJson: String, masterSecretId: String) extends ActorMessageClass
case class CreateCred(credOfferJson: String, credReqJson: String, credValuesJson: String,
                      revRegistryId: String, blobStorageReaderHandle: Int) extends ActorMessageClass
case class CredentialsForProofReq(proofRequest: String) extends ActorMessageClass
case class CreateProof(proofRequest: String, usedCredentials: String, schemas: String,
                       credentialDefs: String, revStates: String, masterSecret: String) extends ActorMessageClass
case class VerifyProof(proofRequest: String) extends ActorMessageClass // don't need wallet!


case object CreateNewKey extends ActorMessageClass
case object DeleteWallet extends ActorMessageClass
case object WalletDeleted extends ActorMessageClass

class WalletActor(appConfig: AppConfig)
  extends Actor {

  val logger: Logger = getLoggerByClass(classOf[WalletActor])
  def entityId: String = self.path.name
  def entityName: String = self.path.parent.name
  var walletProvider: WalletProvider = new LibIndyWalletProvider(appConfig)
  var walletExt: WalletExt = null
  val encryptionKey = generateWalletKey()
  val walletConfig = buildWalletConfig(appConfig)
  val walletName = getWalletName(entityId, appConfig)


  final override def receive = {
    case DeleteWallet => {
      deleteWallet()
      sender ! WalletDeleted
      context.stop(self)
    }
    case CreateWallet(walletName, encryptionKey) => createWallet(walletName, encryptionKey)
    case SignLedgerRequest(submitReqParam) => signLedgerRequest(submitReqParam)
    case GenerateWalletKey(seedOpt) => generateWalletKey(seedOpt)
    case CreateDID(keyType) => createDID(keyType)
    case StoreTheirKey(storeTheirKeyParam, ignoreIfAlreadyExists) => storeTheirKey(storeTheirKeyParam, ignoreIfAlreadyExists)
    //    case GetVerKeyFromDetail(verKeyDetail) => getVerKeyFromDetail(verKeyDetail)
    //    case GetVerKeyFromWallet(verKeyDetail) => getVerKeyFromWallet(verKeyDetail)
    //    case GetVerKey(verKeyDetail) => getVerKey(verKeyDetail)
    //    case SignMsg(keyInfo, msg) => signMsg(keyInfo, msg)
    //    case VerifySig(keyInfo, challenge, signature) => verifySig(keyInfo, challenge, signature)
    case VerifySigWithVerKey(verKey, challenge, signature) => verifySigWithVerKey(verKey, challenge, signature)
    case PackMessage(msg, recipVerKeys, senderVerKey) => packMessage(msg, recipVerKeys, senderVerKey)
    case UnpackMessage(msg) => unpackMessage(msg)
    case CleanDIDMetaData(did) => cleanDIDMetaData(did)
    case CreateMasterSecret(masterSecretId) => createMasterSecret(masterSecretId)

    case CreateSchema(issuerDID, name, version, data) => createSchema(issuerDID, name, version, data)
    case CreateCredDef(issuerDID, schemaJson, tag, sigType, revocationDetails) => createCredDef(issuerDID, schemaJson, tag, sigType, revocationDetails)
    case CreateCredReq(proverDID, credDefJson, credOfferJson, masterSecretId) => createCredReq(proverDID, credDefJson, credOfferJson, masterSecretId)
    case CreateCred(credOfferJson, credReqJson, credValuesJson, revRegistryId, blobStorageReaderHandle) => createCred(credOfferJson, credReqJson, credValuesJson, revRegistryId, blobStorageReaderHandle)
    case CredentialsForProofReq(proofRequest) => credentialsForProofReq(proofRequest)
    case CreateProof(proofRequest, usedCredentials, schemas, credentialDefs, revStates, masterSecret) => createProof(proofRequest, usedCredentials, schemas, credentialDefs, revStates, masterSecret)
    case VerifyProof(proofRequest) => VerifyProof(proofRequest)
    case CreateNewKey => createNewKey()
  }

  @throws(classOf[Exception])
  //#lifecycle-hooks
  override def preStart(): Unit = {
    // TODO: remove
    openWallet()
  }

  @throws(classOf[Exception])
  //#lifecycle-hooks
  override def postStop(): Unit = {
    closeWallet()
  }



  def createWallet(walletName: String, encryptionKey: String): Unit = {
    runWithInternalSpan(s"createWallet", "WalletActor") {
      walletProvider.create(walletName, encryptionKey, walletConfig)
    }
  }

  def openWallet(): Unit = {
    runWithInternalSpan(s"openWallet", "WalletActor") {
      try {
        walletExt = walletProvider.open(walletName, encryptionKey, walletConfig)
      } catch {
        case e: WalletAlreadyOpened => logger.debug(e.message)
        case _: WalletDoesNotExist =>
          walletProvider.createAndOpen(walletName, encryptionKey, walletConfig)
      }
    }
  }

  def closeWallet(): Unit = {
    if (walletExt == null) {
      logger.debug("WalletActor try to close not opened wallet")
      return
    }
    runWithInternalSpan(s"closeWallet", "WalletActor") {
      walletProvider.close(walletExt)
    }
  }

  def deleteWallet(): Unit = walletProvider.delete(walletName, encryptionKey, walletConfig)

  def generateWalletKey(): String =
    walletProvider.generateKey(Option(getWalletKeySeed(entityId, appConfig)))


  private def _executeOpWithWallet[T](opContext: String, op: WalletExt => T): T = {
    val startTime = LocalDateTime.now
    logger.debug(s"libindy api call started ($opContext)")
    val result = op(walletExt)
    val curTime = LocalDateTime.now
    val millis = ChronoUnit.MILLIS.between(startTime, curTime)
    logger.debug(s"libindy api call finished ($opContext), time taken (in millis): $millis")
    MetricsWriter.gaugeApi.increment(AS_SERVICE_LIBINDY_WALLET_DURATION, millis)
    result
  }

  def executeOpWithWalletInfo[T](opContext: String, openWalletIfNotExists: Boolean, op: WalletExt => T): T = {
    runWithInternalSpan(s"executeOpWithWalletInfo($opContext)", "WalletAPI") {
      _executeOpWithWallet(opContext, op)
    }
  }

  def signLedgerRequest(sr: SubmitReqParam): Future[LedgerRequest] = {
    executeOpWithWalletInfo(
      "ledger op",
      openWalletIfNotExists=false,
      { we: WalletExt =>
        FutureConverters.toScala(
          signRequest(
            we.wallet,
            sr.submitterDetail.did,
            sr.reqDetail.req
          )
        )
      }
    ).map(sr.reqDetail.prepared)
  }

  def generateWalletKey(seedOpt: Option[String] = None): String =
    walletProvider.generateKey(seedOpt)

  def createNewKey(cnk: CreateNewKeyParam=CreateNewKeyParam()):
  NewKeyCreated = {
    executeOpWithWalletInfo("create new key", openWalletIfNotExists=true, { we: WalletExt =>
      try {
        val result = we.createNewKey(cnk.DID, cnk.seed)
        MetricsWriter.gaugeApi.increment(AS_SERVICE_LIBINDY_WALLET_SUCCEED_COUNT)
        NewKeyCreated(result.did, result.verKey)
      } catch {
        case e: ExecutionException =>
          e.getCause match {
            case e: InvalidStructureException =>
              throw new BadRequestErrorException(INVALID_VALUE.statusCode, Option(e.getMessage))
            case e: Exception =>
              logger.error("could not create new key", (LOG_KEY_ERR_MSG, Exceptions.getErrorMsg(e)))
              MetricsWriter.gaugeApi.increment(AS_SERVICE_LIBINDY_WALLET_FAILED_COUNT)
              throw new InternalServerErrorException(
                UNHANDLED.statusCode, Option("unhandled error while creating new key"))
          }
        case e: Exception =>
          logger.error("could not create new key", (LOG_KEY_ERR_MSG, Exceptions.getErrorMsg(e)))
          MetricsWriter.gaugeApi.increment(AS_SERVICE_LIBINDY_WALLET_FAILED_COUNT)
          throw new BadRequestErrorException(UNHANDLED.statusCode, Option("unhandled error while creating new key"))
      }
    })
  }

  def createDID(keyType: String): NewKeyCreated = {
    executeOpWithWalletInfo("open wallet", openWalletIfNotExists=false, { we: WalletExt =>
      val didJson = s"""{"crypto_type": "$keyType"}"""
      val created = Did.createAndStoreMyDid(we.wallet, didJson).get()
      NewKeyCreated(created.getDid, created.getVerkey)
    })
  }

  def storeTheirKey(stk: StoreTheirKeyParam, ignoreIfAlreadyExists: Boolean=false): TheirKeyCreated = {
    executeOpWithWalletInfo("store their key", openWalletIfNotExists=false, { we: WalletExt =>
      try {
        logger.debug("about to store their key => wallet name: " + walletName + ", key: " + stk)
        val tkc = we.storeTheirDID(stk.theirDID, stk.theirDIDVerKey)
        logger.debug("their key stored => wallet name: " + walletName + ", tkc: " + tkc)
        MetricsWriter.gaugeApi.increment(AS_SERVICE_LIBINDY_WALLET_SUCCEED_COUNT)
        tkc
      } catch {
        case e: Exception if ignoreIfAlreadyExists && e.getCause.isInstanceOf[WalletItemAlreadyExistsException] =>
          TheirKeyCreated(stk.theirDID, stk.theirDIDVerKey)
        case e: Exception if e.getCause.isInstanceOf[WalletItemAlreadyExistsException] =>
          logger.error("error while storing their key: " + Exceptions.getErrorMsg(e))
          throw new BadRequestErrorException(
            ALREADY_EXISTS.statusCode, Option("'their' pw keys are already in the wallet"))
        case e: Exception =>
          logger.error("could not store their key", ("their_did", stk.theirDID),
            (LOG_KEY_ERR_MSG, Exceptions.getErrorMsg(e)))
          MetricsWriter.gaugeApi.increment(AS_SERVICE_LIBINDY_WALLET_FAILED_COUNT)
          throw new InternalServerErrorException(
            UNHANDLED.statusCode, Option("unhandled error while storing their key"))
      }
    })
  }
//
//  def getVerKeyFromDetail(verKeyDetail: Either[VerKey, GetVerKeyByDIDParam]): Future[VerKey] = {
//    verKeyDetail.fold (
//      l => Future.successful(l),
//      r => {
//        executeOpWithWalletInfo("get ver key", openWalletIfNotExists=true, { we: WalletExt =>
//          logger.debug("about to get DID ver key => wallet name: " + walletName + ", DID: " + r.did)
//          util.getVerKey(r.did, we, r.getKeyFromPool, ledgerPoolManager)
//        })
//      }
//    )
//  }
//
//  def getVerKeyFromWallet(verKeyDetail: Either[VerKey, GetVerKeyByDIDParam]): Future[VerKey] = {
//    verKeyDetail.fold (
//      l => Future.successful(l),
//      r => {
//        util.getVerKey(r.did, we, r.getKeyFromPool, ledgerPoolManager)
//      }
//    )
//  }
//
//  def getVerKey(keyInfo: KeyInfo): VerKey = {
//    val verKeyFuture = getVerKeyFromDetail(keyInfo.verKeyDetail)
//    Await.result(verKeyFuture, Duration.Inf) // fixme ve2028 testing
//  }
//
//  def signMsg(keyInfo: KeyInfo, msg: Array[Byte]): Array[Byte] = {
//    executeOpWithWalletInfo("sign msg", openWalletIfNotExists=false, { we: WalletExt =>
//      val verKeyFuture = getVerKeyFromWallet(keyInfo.verKeyDetail)
//      val verKey = Await.result(verKeyFuture, Duration.Inf) // fixme ve2028 testing
//      Crypto.cryptoSign(we.wallet, verKey, msg).get
//    })
//  }
//
//  def verifySig(keyInfo: KeyInfo, challenge: Array[Byte], signature: Array[Byte]): VerifResult = {
//    val verKeyFuture = getVerKeyFromDetail(keyInfo.verKeyDetail)
//    val verKey = Await.result(verKeyFuture, Duration.Inf) // fixme ve2028 testing
//
//    coreVerifySig(verKey, challenge, signature)
//  }

  private def coreVerifySig(verKey: VerKey, challenge: Array[Byte], signature: Array[Byte]): VerifResult = {
    val detail = s"challenge: '$challenge', signature: '$signature'"
    try {
      VerifResult(Crypto.cryptoVerify(verKey, challenge, signature).get)
    } catch {
      case e: ExecutionException =>
        e.getCause match {
          case _@ (_:InvalidStructureException |_: InvalidParameterException) =>
            throw new BadRequestErrorException(SIGNATURE_VERIF_FAILED.statusCode,
              Option("signature verification failed"), Option(detail))
          case _: Exception => throw new BadRequestErrorException(SIGNATURE_VERIF_FAILED.statusCode,
            Option("unhandled error"), Option(detail))
        }
      case _: Exception => throw new BadRequestErrorException(SIGNATURE_VERIF_FAILED.statusCode,
        Option("unhandled error"), Option(detail))
    }
  }

  def verifySigWithVerKey(verKey: VerKey, challenge: Array[Byte], signature: Array[Byte]): VerifResult = {
    coreVerifySig(verKey, challenge, signature)
  }

  def packMessage(msg: String, recipVerKeys: Vector[VerKey], senderVerKey: Option[VerKey]): Array[Byte] = {
    // Question: Should JSON validation happen for msg happen here or is it left to libindy?
    // Question: Since libindy expects bytes, should msg be bytes and not string. This will
    // make API of pack and unpack consistent (pack takes input what unpack outputs)
    executeOpWithWalletInfo("pack msg", openWalletIfNotExists=false, { we: WalletExt =>
      val verkeys = jsonArray(recipVerKeys.toSet)
      Crypto.packMessage(we.wallet, verkeys, senderVerKey.getOrElse(""), msg.getBytes).get
    })
  }

  def unpackMessage(msg: Array[Byte]): Array[Byte] = {
    executeOpWithWalletInfo("unpack msg", openWalletIfNotExists=false, { we: WalletExt =>
      Crypto.unpackMessage(we.wallet, msg).get
    })
  }

  def cleanDIDMetaData(did: DID): Unit = {
    executeOpWithWalletInfo("set metadata", openWalletIfNotExists=false, { we: WalletExt =>
      Did.setDidMetadata(we.wallet, did, """{}""").get
    })
  }


  def createMasterSecret(masterSecretId: String): String = {
    executeOpWithWalletInfo("set metadata", openWalletIfNotExists=false, { we: WalletExt =>
      Anoncreds.proverCreateMasterSecret(we.wallet, masterSecretId).get
    })
  }

  def createSchema(issuerDID: DID, name:String, version: String, data: String): IssuerCreateSchemaResult = {
    Anoncreds.issuerCreateSchema(issuerDID, name, version, data).get
  }

  def createCredDef(issuerDID: DID,
                    schemaJson: String,
                    tag: String,
                    sigType: Option[String],
                    revocationDetails: Option[String]): IssuerCreateAndStoreCredentialDefResult = {
    executeOpWithWalletInfo("set metadata", openWalletIfNotExists=false, { we: WalletExt =>
      val configJson = revocationDetails.getOrElse(""""{"support_revocation": false}"""")
      Anoncreds.issuerCreateAndStoreCredentialDef(
        we.wallet,
        issuerDID,
        schemaJson,
        tag,
        sigType.getOrElse("CL"),
        configJson
      ).get
    })
  }

  def createCredOffer(credDefId: String): String = {
    executeOpWithWalletInfo("set metadata", openWalletIfNotExists=false, { we: WalletExt =>
      Anoncreds.issuerCreateCredentialOffer(we.wallet, credDefId).get
    })
  }

  def createCredReq(proverDID: DID, credDefJson: String, credOfferJson: String, masterSecretId: String): String = {
    executeOpWithWalletInfo("cred request", openWalletIfNotExists=false, { we: WalletExt =>
      Anoncreds.proverCreateCredentialReq(we.wallet, proverDID, credOfferJson, credDefJson, masterSecretId)
        .get
        .getCredentialRequestJson
    })
  }

  def createCred(credOfferJson: String, credReqJson: String, credValuesJson: String,
                 revRegistryId: String, blobStorageReaderHandle: Int): String = {
    executeOpWithWalletInfo("cred", openWalletIfNotExists=false, { we: WalletExt =>
      Anoncreds.issuerCreateCredential(we.wallet, credOfferJson, credReqJson, credValuesJson, revRegistryId, blobStorageReaderHandle)
        .get.getCredentialJson
    })
  }

  def credentialsForProofReq(proofRequest: String): String = {
    executeOpWithWalletInfo("cred for proof req", openWalletIfNotExists=false, { we: WalletExt =>
      Anoncreds.proverGetCredentialsForProofReq(we.wallet, proofRequest).get
    })
  }

  def createProof(proofRequest: String, usedCredentials: String, schemas: String,
                  credentialDefs: String, revStates: String, masterSecret: String): String= {
    executeOpWithWalletInfo("create proof", openWalletIfNotExists=false, { we: WalletExt =>
      Anoncreds.proverCreateProof(we.wallet, proofRequest, usedCredentials, masterSecret,
        schemas, credentialDefs, revStates).get
    })
  }

  def verifyProof(proofRequest: String, proof: String, schemas: String, credentialDefs: String,
                  revocRegDefs: String, revocRegs: String): Boolean = {
    Anoncreds.verifierVerifyProof(proofRequest, proof, schemas, credentialDefs, revocRegDefs, revocRegs).get
  }

}