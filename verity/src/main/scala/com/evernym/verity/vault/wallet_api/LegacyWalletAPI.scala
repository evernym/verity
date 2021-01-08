package com.evernym.verity.vault.wallet_api

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.ExecutionException

import com.evernym.verity.Exceptions
import com.evernym.verity.Exceptions.{BadRequestErrorException, InternalServerErrorException}
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.Status._
import com.evernym.verity.actor.wallet.{GetVerKeyOpt, _}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.constants.LogKeyConstants._
import com.evernym.verity.ledger.LedgerPoolConnManager
import com.evernym.verity.libindy.wallet.operation_executor.DidOpExecutor
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.metrics.CustomMetrics._
import com.evernym.verity.metrics.MetricsWriter
import com.evernym.verity.protocol.engine.{DID, VerKey}
import com.evernym.verity.util.Util._
import com.evernym.verity.vault.WalletUtil.generateWalletParamSync
import com.evernym.verity.vault._
import com.evernym.verity.vault.service.{WalletMsgHandler, WalletMsgParam, WalletParam}
import com.typesafe.scalalogging.Logger
import org.hyperledger.indy.sdk.anoncreds.Anoncreds
import org.hyperledger.indy.sdk.anoncreds.AnoncredsResults.IssuerCreateSchemaResult
import org.hyperledger.indy.sdk.crypto.Crypto
import org.hyperledger.indy.sdk.did.{Did, DidJSONParameters}
import org.hyperledger.indy.sdk.wallet.{WalletItemAlreadyExistsException, WalletItemNotFoundException}
import org.hyperledger.indy.sdk.{InvalidParameterException, InvalidStructureException}

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}


class LegacyWalletAPI(appConfig: AppConfig,
                      walletProvider: WalletProvider,
                      ledgerPoolManager: Option[LedgerPoolConnManager])
  extends WalletAPI {

  val logger: Logger = getLoggerByClass(getClass)
  var walletParams: Map[String, WalletParam] = Map.empty
  var wallets: Map[String, WalletExt] = Map.empty

  private def executeOpWithWalletInfo[T](opContext: String, op: WalletExt => T)
                                        (implicit wap: WalletAPIParam): T = {
    _executeOpWithWalletParam(opContext, op)(wap)
  }

  private def _executeOpWithWalletParam[T](opContext: String, op: WalletExt => T)
                                          (implicit wap: WalletAPIParam): T = {

    //for multi-node environment, there would scenarios where a wallet got opened on one node
    // but other/further operations are getting executed on other node (for example which belongs
    // to a pairwise actors or any protocol actors which may spinned up on different nodes)
    // where that wallet is not yet opened. Long term solution would be a architecture change and will take time
    // this is a short term solution to just check if wallet is already opened, if not, open it.
    implicit val wp: WalletParam = getWalletParam(wap)

    implicit val w: WalletExt = if (!wallets.contains(wp.getUniqueId)) {
      val ow = _openWallet
      addToOpenedWalletIfReq(ow)
      ow
    } else wallets(wp.getUniqueId)
    _executeOpWithWallet(opContext, op)
  }

  private def _openWallet(implicit wap: WalletParam): WalletExt = {
    walletProvider.openSync(wap.walletName, wap.encryptionKey, wap.walletConfig)
  }

  private def _executeOpWithWallet[T](opContext: String, op: WalletExt => T)
                                     (implicit w: WalletExt): T = {
    val startTime = LocalDateTime.now
    logger.debug(s"libindy api call started ($opContext)")
    val result = op(w)
    val curTime = LocalDateTime.now
    val millis = ChronoUnit.MILLIS.between(startTime, curTime)
    logger.debug(s"libindy api call finished ($opContext), time taken (in millis): $millis")
    MetricsWriter.gaugeApi.increment(AS_SERVICE_LIBINDY_WALLET_DURATION, millis)
    result
  }

  private def addToOpenedWalletIfReq(w: WalletExt)(implicit wp: WalletParam): Unit = synchronized {
    val uniqueKey = wp.getUniqueId
    if (!wallets.contains(uniqueKey)) {
      wallets += uniqueKey -> w
      walletParams += wp.walletId -> wp
    }
  }

  def createWallet(wap: WalletAPIParam): WalletCreated.type = {
    val wp: WalletParam = generateWalletParamSync(wap.walletId, appConfig, walletProvider)
    walletProvider.createSync(wp.walletName, wp.encryptionKey, wp.walletConfig)
    WalletCreated
  }

  def createNewKey(cnk: CreateNewKey = CreateNewKey())(implicit wap: WalletAPIParam):
  NewKeyCreated = {
    executeOpWithWalletInfo("create new key", { we: WalletExt =>
      try {
        val DIDJson = new DidJSONParameters.CreateAndStoreMyDidJSONParameter(
          cnk.DID.orNull, cnk.seed.orNull, null, null)
        val result = Did.createAndStoreMyDid(we.wallet, DIDJson.toJson).get
        MetricsWriter.gaugeApi.increment(AS_SERVICE_LIBINDY_WALLET_SUCCEED_COUNT)
        NewKeyCreated(result.getDid, result.getVerkey)
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

  def createDID(keyType: String)(implicit wap: WalletAPIParam): NewKeyCreated = {
    executeOpWithWalletInfo("open wallet", { we: WalletExt =>
      val didJson = s"""{"crypto_type": "$keyType"}"""
      val created = Did.createAndStoreMyDid(we.wallet, didJson).get()
      NewKeyCreated(created.getDid, created.getVerkey)
    })
  }

  def storeTheirKey(stk: StoreTheirKey)(implicit wap: WalletAPIParam): TheirKeyStored = {
    executeOpWithWalletInfo("store their key", { we: WalletExt =>
      try {
        logger.debug("about to store their key => key: " + stk)
        val DIDJson = s"""{\"did\":\"${stk.theirDID}\",\"verkey\":\"${stk.theirDIDVerKey}\"}"""
        val result = Did.storeTheirDid(we.wallet, DIDJson).get
        logger.debug("their key stored => wallet name: tkc: " + result)
        MetricsWriter.gaugeApi.increment(AS_SERVICE_LIBINDY_WALLET_SUCCEED_COUNT)
        TheirKeyStored(stk.theirDID, stk.theirDIDVerKey)
      } catch {
        case e: Exception if stk.ignoreIfAlreadyExists && e.getCause.isInstanceOf[WalletItemAlreadyExistsException] =>
          TheirKeyStored(stk.theirDID, stk.theirDIDVerKey)
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

  def getVerKeyOption(gvk: GetVerKeyOpt)(implicit wap: WalletAPIParam): Option[VerKey] = {
    try {
      Option(getVerKey(GetVerKey(gvk.keyParam)))
    } catch {
      case _: ExecutionException => None
    }
  }

  private def getVerKeyFromDetail(keyParam: KeyParam)(implicit wap: WalletAPIParam): Future[VerKey] = {
    keyParam.verKeyParam.fold(
      l => Future.successful(l),
      r => {
        executeOpWithWalletInfo("get ver key", { implicit we: WalletExt =>
          logger.debug("about to get DID ver key => wallet name: DID: " + r.did)
          DidOpExecutor.getVerKey(r.did, r.getKeyFromPool, ledgerPoolManager)
        })
      }
    )
  }

  private def getVerKeyFromWallet(keyParam: KeyParam)(implicit we: WalletExt): Future[VerKey] = {
    keyParam.verKeyParam.fold(
      l => Future.successful(l),
      r => {
        DidOpExecutor.getVerKey(r.did, r.getKeyFromPool, ledgerPoolManager)
      }
    )
  }

  def getVerKey(gvk: GetVerKey)(implicit wap: WalletAPIParam): VerKey = {
    val verKeyFuture = getVerKeyFromDetail(gvk.keyParam)
    Await.result(verKeyFuture, Duration.Inf) // fixme ve2028 testing
  }

  def signMsg(sm: SignMsg)(implicit wap: WalletAPIParam): Array[Byte] = {
    executeOpWithWalletInfo("sign msg", { we: WalletExt =>
      val verKeyFuture = getVerKeyFromWallet(sm.keyParam)(we)
      val verKey = Await.result(verKeyFuture, Duration.Inf) // fixme ve2028 testing
      Crypto.cryptoSign(we.wallet, verKey, sm.msg).get
    })
  }

  private def coreVerifySig(verKey: VerKey, challenge: Array[Byte], signature: Array[Byte]): VerifySigResult = {
    val detail = s"challenge: '$challenge', signature: '$signature'"
    try {
      VerifySigResult(Crypto.cryptoVerify(verKey, challenge, signature).get)
    } catch {
      case e: ExecutionException =>
        e.getCause match {
          case _@(_: InvalidStructureException | _: InvalidParameterException) =>
            throw new BadRequestErrorException(SIGNATURE_VERIF_FAILED.statusCode,
              Option("signature verification failed"), Option(detail))
          case _: Exception => throw new BadRequestErrorException(SIGNATURE_VERIF_FAILED.statusCode,
            Option("unhandled error"), Option(detail))
        }
      case _: Exception => throw new BadRequestErrorException(SIGNATURE_VERIF_FAILED.statusCode,
        Option("unhandled error"), Option(detail))
    }
  }

  def verifySig(vs: VerifySigByKeyParam)(implicit wap: WalletAPIParam): VerifySigResult = {
    val verKeyFuture = getVerKeyFromDetail(vs.keyParam)
    val verKey = Await.result(verKeyFuture, Duration.Inf) // fixme ve2028 testing
    coreVerifySig(verKey, vs.challenge, vs.signature)
  }

  def verifySigWithVerKey(vs: VerifySigByVerKey): VerifySigResult = {
    coreVerifySig(vs.verKey, vs.challenge, vs.signature)
  }

  def LEGACY_packMsg(msg: Array[Byte], recipKeyParams: Set[KeyParam], senderKeyParam: Option[KeyParam])
                    (implicit wap: WalletAPIParam): PackedMsg = {
    executeOpWithWalletInfo("legacy pack msg", { we: WalletExt =>

      val senderKeyOpt = senderKeyParam.map({ sk =>
        val future = getVerKeyFromWallet(sk)(we)
        Await.result(future, Duration.Inf) // fixme ve2028 testing
      })

      val future = getVerKeyFromWallet(recipKeyParams.head)(we)
      val recipVerKey = Await.result(future, Duration.Inf) // fixme ve2028 testing
      val cryptoBoxBytes = senderKeyOpt match {
        case None             => Crypto.anonCrypt(recipVerKey, msg).get
        case Some(senderKey)  => Crypto.authCrypt(we.wallet, senderKey, recipVerKey, msg).get
      }
      PackedMsg(cryptoBoxBytes)
    })
  }

  def packMsg(msg: Array[Byte], recipKeyParams: Set[KeyParam], senderKeyParam: Option[KeyParam])
             (implicit wap: WalletAPIParam): PackedMsg = {
    // Question: Should JSON validation happen for msg happen here or is it left to libindy?
    // Question: Since libindy expects bytes, should msg be bytes and not string. This will
    // make API of pack and unpack consistent (pack takes input what unpack outputs)
    executeOpWithWalletInfo("pack msg", { we: WalletExt =>
      val senderKeyOpt = senderKeyParam.map({ sk =>
        val future = getVerKeyFromWallet(sk)(we)
        Await.result(future, Duration.Inf) // fixme ve2028 testing
      })
      val future = getVerKeyFromWallet(recipKeyParams.head)(we)
      val verKey = Await.result(future, Duration.Inf) // fixme ve2028 testing
      val verKeyJsonStr = jsonArray(Set(verKey))
      PackedMsg(Crypto.packMessage(we.wallet, verKeyJsonStr, senderKeyOpt.orNull, msg).get)
    })
  }

  def LEGACY_unpackMsg(msg: Array[Byte], fromKeyParamOpt: Option[KeyParam], isAnonCryptedMsg: Boolean)
                      (implicit wap: WalletAPIParam): UnpackedMsg = {
    executeOpWithWalletInfo("legacy unpack msg", { we: WalletExt =>
      try {
        val fvkFut = getVerKeyFromWallet(fromKeyParamOpt.get)(we)
        val fromVerKey = Await.result(fvkFut, Duration.Inf) // fixme ve2028 testing
        val (decryptedMsg, senderVerKey) = if (isAnonCryptedMsg) {
          val parsedResult = Crypto.anonDecrypt(we.wallet, fromVerKey, msg).get
          (parsedResult, None)
        } else {
          val parsedResult = Crypto.authDecrypt(we.wallet, fromVerKey, msg).get
          (parsedResult.getDecryptedMessage, Option(parsedResult.getVerkey))
        }
        UnpackedMsg(decryptedMsg, senderVerKey, None)
      } catch {
        case e: BadRequestErrorException => throw e
        case e: WalletItemNotFoundException =>
          logger.error("error while legacy unpack1: " + e.getMessage)
          throw new BadRequestErrorException(INVALID_VALUE.statusCode, Option(e.getMessage))
        case _: InvalidStructureException =>
          throw new BadRequestErrorException(INVALID_VALUE.statusCode, Option("invalid packed message"))
        case e: Exception =>
          logger.error("error while legacy unpack2: " + e.getMessage)
          throw new BadRequestErrorException(UNHANDLED.statusCode,
            Option("unhandled error while unpacking message"))
      }
    })
  }

  def unpackMsg(msg: Array[Byte])(implicit wap: WalletAPIParam): UnpackedMsg = {
    executeOpWithWalletInfo("unpack msg", { we: WalletExt =>
      try {
        UnpackedMsg(Crypto.unpackMessage(we.wallet, msg).get, None, None)
      } catch {
        case e: Exception =>
          logger.error("error while unpack: " + e.getMessage)
          throw new BadRequestErrorException(UNHANDLED.statusCode,
            Option("unhandled error while unpacking message"))
      }
    })
  }

  def createMasterSecret(masterSecretId: String)(implicit wap: WalletAPIParam): String = {
    executeOpWithWalletInfo("set metadata", { we: WalletExt =>
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
                    revocationDetails: Option[String])
                   (implicit wap: WalletAPIParam): CreatedCredDef = {
    executeOpWithWalletInfo("set metadata", { we: WalletExt =>
      val configJson = revocationDetails.getOrElse(""""{"support_revocation": false}"""")
      val createdCredDef = Anoncreds.issuerCreateAndStoreCredentialDef(
        we.wallet,
        issuerDID,
        schemaJson,
        tag,
        sigType.getOrElse("CL"),
        configJson
      ).get
      CreatedCredDef(createdCredDef.getCredDefId, createdCredDef.getCredDefJson)
    })
  }

  def createCredOffer(credDefId: String)(implicit wap: WalletAPIParam): String = {
    executeOpWithWalletInfo("set metadata", { we: WalletExt =>
      Anoncreds.issuerCreateCredentialOffer(we.wallet, credDefId).get
    })
  }

  def createCredReq(credDefId: String, proverDID: DID, credDefJson: String, credOfferJson: String, masterSecretId: String)
                   (implicit wap: WalletAPIParam): CreatedCredReq = {
    executeOpWithWalletInfo("cred request", { we: WalletExt =>
      val r = Anoncreds.proverCreateCredentialReq(we.wallet, proverDID, credOfferJson, credDefJson, masterSecretId).get
      CreatedCredReq(r.getCredentialRequestJson, r.getCredentialRequestMetadataJson)
    })
  }

  def createCred(credOfferJson: String, credReqJson: String, credValuesJson: String,
                 revRegistryId: String, blobStorageReaderHandle: Int)
                (implicit wap: WalletAPIParam): String = {
    executeOpWithWalletInfo("create cred", { we: WalletExt =>
      Anoncreds.issuerCreateCredential(we.wallet, credOfferJson, credReqJson, credValuesJson,
        revRegistryId, blobStorageReaderHandle).get.getCredentialJson
    })
  }

  def storeCred(credId: String, credReqMetadataJson: String, credJson: String,
                credDefJson: String, revRegDefJson: String)
               (implicit wap: WalletAPIParam): String = {
    executeOpWithWalletInfo("store cred", { we: WalletExt =>
      Anoncreds.proverStoreCredential(we.wallet, credId, credReqMetadataJson, credJson,
        credDefJson, revRegDefJson).get
    })
  }

  def credentialsForProofReq(proofRequest: String)(implicit wap: WalletAPIParam): String = {
    executeOpWithWalletInfo("cred for proof req", { we: WalletExt =>
      Anoncreds.proverGetCredentialsForProofReq(we.wallet, proofRequest).get
    })
  }

  def createProof(proofRequest: String, usedCredentials: String, schemas: String,
                  credentialDefs: String, revStates: String, masterSecret: String)
                 (implicit wap: WalletAPIParam): String= {
    executeOpWithWalletInfo("create proof", { we: WalletExt =>
      Anoncreds.proverCreateProof(we.wallet, proofRequest, usedCredentials, masterSecret,
        schemas, credentialDefs, revStates).get
    })
  }

  def verifyProof(proofRequest: String, proof: String, schemas: String, credentialDefs: String,
                  revocRegDefs: String, revocRegs: String): Boolean = {
    Anoncreds.verifierVerifyProof(proofRequest, proof, schemas, credentialDefs, revocRegDefs, revocRegs).get
  }

  def getWalletParam(wap: WalletAPIParam): WalletParam = {
    walletParams.getOrElse(wap.walletId, {
      val wp = generateWalletParamSync(wap.walletId, appConfig, walletProvider)
      walletParams += wap.walletId -> wp
      wp
    })
  }

  def executeAsync[T](cmd: Any)(implicit wap: WalletAPIParam): Future[T] = {
    val wp = generateWalletParamSync(wap.walletId, appConfig, walletProvider)
    implicit val wmp: WalletMsgParam = WalletMsgParam(walletProvider, wp, ledgerPoolManager)
    val resp = cmd match {
      case CreateWallet =>
        val walletExt = WalletMsgHandler.handleCreateAndOpenWalletSync()
        addToOpenedWalletIfReq(walletExt)(wp)
        Future(WalletCreated)
      case other        =>
        executeOpWithWalletInfo(cmd.getClass.getSimpleName, { implicit we: WalletExt =>
          WalletMsgHandler.executeAsync[T](other)
        })
    }
    resp.map(_.asInstanceOf[T])
  }

}
