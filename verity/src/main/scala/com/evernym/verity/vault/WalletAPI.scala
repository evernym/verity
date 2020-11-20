package com.evernym.verity.vault

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.ExecutionException

import com.evernym.verity.actor.agent.SpanUtil.runWithInternalSpan
import com.evernym.verity.constants.LogKeyConstants._
import com.evernym.verity.Exceptions.{BadRequestErrorException, InternalServerErrorException}
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.Status._
import com.evernym.verity.ledger.{LedgerPoolConnManager, LedgerRequest}
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.metrics.CustomMetrics.{AS_SERVICE_LIBINDY_WALLET_DURATION, AS_SERVICE_LIBINDY_WALLET_FAILED_COUNT, AS_SERVICE_LIBINDY_WALLET_SUCCEED_COUNT}
import com.evernym.verity.metrics.MetricsWriter
import com.evernym.verity.protocol.engine.{DID, VerKey}
import com.evernym.verity.util.Util._
import com.evernym.verity.util._
import com.evernym.verity.Exceptions
import com.typesafe.scalalogging.Logger
import org.hyperledger.indy.sdk.anoncreds.Anoncreds
import org.hyperledger.indy.sdk.anoncreds.AnoncredsResults.{IssuerCreateAndStoreCredentialDefResult, IssuerCreateSchemaResult}
import org.hyperledger.indy.sdk.crypto.Crypto
import org.hyperledger.indy.sdk.did.Did
import org.hyperledger.indy.sdk.ledger.Ledger._
import org.hyperledger.indy.sdk.wallet.WalletItemAlreadyExistsException
import org.hyperledger.indy.sdk.{InvalidParameterException, InvalidStructureException}

import scala.compat.java8.FutureConverters
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}


class WalletAPI(walletProvider: WalletProvider,
                util: UtilBase,
                ledgerPoolManager: LedgerPoolConnManager) {

  val logger: Logger = getLoggerByClass(classOf[WalletAPI])
  var wallets: Map[String, WalletExt] = Map.empty

  private def _openWallet(implicit wap: WalletAccessParam): WalletExt = {
    walletProvider.open(wap.walletName, wap.encryptionKey, wap.walletConfig)
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

  private def addToOpenedWalletIfReq(wap: WalletAccessParam, w: WalletExt): Unit = {
    if (! wallets.contains(wap.getUniqueKey) && ! wap.closeAfterUse) {
      wallets += wap.getUniqueKey -> w
    }
  }

  private def _executeOpWithWalletAccessDetail[T](opContext: String, openWalletIfNotExists: Boolean, op: WalletExt => T)
                                                 (implicit wap: WalletAccessParam): T = {

    //for multi-node environment, there would scenarios where a wallet got opened on one node
    // but other/further operations are getting executed on other node (for example which belongs to a pairwise actors or any protocol actors which may spinned up on different nodes)
    // where that wallet is not yet opened. Long term solution would be a architecture change and will take time
    // this is a short term solution to just check if wallet is already opened, if not, open it.
    implicit val w: WalletExt = if (! wallets.contains(wap.getUniqueKey)) {
      val ow = _openWallet
      addToOpenedWalletIfReq(wap, ow)
      ow
    } else wallets(wap.getUniqueKey)

    try {
      _executeOpWithWallet(opContext, op)
    } finally {
      if (! wallets.contains(wap.getUniqueKey) && wap.closeAfterUse) {
        walletProvider.close(w)
        logger.debug(s"wallet successfully closed (detail => wallet-name: ${wap.walletName}, " +
          s"thread-id: ${Thread.currentThread().getId})")
      }
    }
  }

  def executeOpWithWalletInfo[T](opContext: String, openWalletIfNotExists: Boolean, op: WalletExt => T)
                                (implicit wap: WalletAccessParam): T = {
    runWithInternalSpan(s"executeOpWithWalletInfo($opContext)", "WalletAPI") {
      _executeOpWithWalletAccessDetail(opContext, openWalletIfNotExists, op)(wap)
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
    )(sr.submitterDetail.wap.getOrElse(throw new Exception("Signed Requests require Wallet Info"))) // TODO make a better exception
    .map(sr.reqDetail.prepared)
  }

  def openWallet(implicit wap: WalletAccessParam): WalletExt = {
    executeOpWithWalletInfo("open wallet", openWalletIfNotExists=true, { we: WalletExt =>
      we
    })
  }

  def createWallet(wap: WalletAccessParam): Unit = {
    walletProvider.create(wap.walletName, wap.encryptionKey, wap.walletConfig)
  }

  def createAndOpenWallet(wap: WalletAccessParam): WalletExt = {
    runWithInternalSpan("createAndOpenWallet", "WalletAPI") {
      val startTime = LocalDateTime.now
      logger.debug(s"libindy api call started (create and open wallet)")
      val we = walletProvider.createAndOpen(wap.walletName, wap.encryptionKey, wap.walletConfig)
      addToOpenedWalletIfReq(wap, we)
      val curTime = LocalDateTime.now
      val millis = ChronoUnit.MILLIS.between(startTime, curTime)
      logger.debug(s"libindy api call finished (create and open wallet), time taken (in millis): $millis")
      we
    }
  }

  def generateWalletKey(seedOpt: Option[String] = None): String =
    walletProvider.generateKey(seedOpt)

  def checkIfWalletExists(wap: WalletAccessParam): Boolean = {
    wallets.contains(wap.getUniqueKey) ||
      walletProvider.checkIfWalletExists(wap.walletName, wap.encryptionKey, wap.walletConfig)
  }

  def createNewKey(cnk: CreateNewKeyParam=CreateNewKeyParam())(implicit wap: WalletAccessParam):
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

  def createDID(keyType: String)(implicit wap: WalletAccessParam): NewKeyCreated = {
    executeOpWithWalletInfo("open wallet", openWalletIfNotExists=false, { we: WalletExt =>
      val didJson = s"""{"crypto_type": "$keyType"}"""
      val created = Did.createAndStoreMyDid(we.wallet, didJson).get()
      NewKeyCreated(created.getDid, created.getVerkey)
    })
  }

  def storeTheirKey(stk: StoreTheirKeyParam, ignoreIfAlreadyExists: Boolean=false)
                   (implicit wap: WalletAccessParam): TheirKeyCreated = {
    executeOpWithWalletInfo("store their key", openWalletIfNotExists=false, { we: WalletExt =>
      try {
        logger.debug("about to store their key => wallet name: " + wap.walletName + ", key: " + stk)
        val tkc = we.storeTheirDID(stk.theirDID, stk.theirDIDVerKey)
        logger.debug("their key stored => wallet name: " + wap.walletName + ", tkc: " + tkc)
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

  def getVerKeyOption(ki: KeyInfo)(implicit wap: WalletAccessParam): Option[VerKey] = {
    try {
      Option(getVerKey(GetVerKeyByKeyInfoParam(ki)))
    } catch {
      case _: ExecutionException => None
    }
  }

  def getVerKeyFromDetail(verKeyDetail: Either[VerKey, GetVerKeyByDIDParam])(implicit wap: WalletAccessParam): Future[VerKey] = {
    verKeyDetail.fold (
      l => Future.successful(l),
      r => {
        executeOpWithWalletInfo("get ver key", openWalletIfNotExists=true, { we: WalletExt =>
          logger.debug("about to get DID ver key => wallet name: " + wap.walletName + ", DID: " + r.did)
          util.getVerKey(r.did, we, r.getKeyFromPool, ledgerPoolManager)
        })
      }
    )
  }

  def getVerKeyFromWallet(verKeyDetail: Either[VerKey, GetVerKeyByDIDParam])(implicit we: WalletExt): Future[VerKey] = {
    verKeyDetail.fold (
      l => Future.successful(l),
      r => {
        util.getVerKey(r.did, we, r.getKeyFromPool, ledgerPoolManager)
      }
    )
  }

  def getVerKey(gvk: GetVerKeyByKeyInfoParam)(implicit wap: WalletAccessParam): VerKey = {
    val verKeyFuture = getVerKeyFromDetail(gvk.keyInfo.verKeyDetail)
    Await.result(verKeyFuture, Duration.Inf) // fixme ve2028 testing
  }

  def signMsg(sm: SignMsgParam)(implicit wap: WalletAccessParam): Array[Byte] = {
    executeOpWithWalletInfo("sign msg", openWalletIfNotExists=false, { we: WalletExt =>
      val verKeyFuture = getVerKeyFromWallet(sm.keyInfo.verKeyDetail)(we)
      val verKey = Await.result(verKeyFuture, Duration.Inf) // fixme ve2028 testing
      Crypto.cryptoSign(we.wallet, verKey, sm.msg).get
    })
  }

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

  def verifySig(vs: VerifySigByKeyInfoParam)(implicit wap: WalletAccessParam): VerifResult = {
    val verKeyFuture = getVerKeyFromDetail(vs.keyInfo.verKeyDetail)
    val verKey = Await.result(verKeyFuture, Duration.Inf) // fixme ve2028 testing

    coreVerifySig(verKey, vs.challenge, vs.signature)
  }

  def verifySigWithVerKey(vs: VerifySigByVerKeyParam): VerifResult = {
    coreVerifySig(vs.verKey, vs.challenge, vs.signature)
  }

  def packMessage(msg: String, recipVerKeys: Vector[VerKey], senderVerKey: Option[VerKey])(implicit wap: WalletAccessParam): Array[Byte] = {
    // Question: Should JSON validation happen for msg happen here or is it left to libindy?
    // Question: Since libindy expects bytes, should msg be bytes and not string. This will
    // make API of pack and unpack consistent (pack takes input what unpack outputs)
    executeOpWithWalletInfo("pack msg", openWalletIfNotExists=false, { we: WalletExt =>
      val verkeys = jsonArray(recipVerKeys.toSet)
      Crypto.packMessage(we.wallet, verkeys, senderVerKey.getOrElse(""), msg.getBytes).get
    })
  }

  def unpackMessage(msg: Array[Byte])(implicit wap: WalletAccessParam): Array[Byte] = {
    executeOpWithWalletInfo("unpack msg", openWalletIfNotExists=false, { we: WalletExt =>
      Crypto.unpackMessage(we.wallet, msg).get
    })
  }

  def cleanDIDMetaData(did: DID)(implicit wap: WalletAccessParam): Unit = {
    executeOpWithWalletInfo("set metadata", openWalletIfNotExists=false, { we: WalletExt =>
      Did.setDidMetadata(we.wallet, did, """{}""").get
    })
  }

  def deleteWallet(id: String, encryptionKey: String, walletConfig: WalletConfig): Unit = {
    walletProvider.delete(id, encryptionKey, walletConfig)
  }

  private def encodeBytesToString(encBytes: Array[Byte]): String = {
    //TODO: this is temporary, need to think about what encoding/decoding we should use
    Base64Util.getBase64Encoded(encBytes)
  }

  private def decodeBytesFromString(msg: String): Array[Byte] = {
    //TODO: this is temporary, need to think about what encoding/decoding we should use
    Base64Util.getBase64Decoded(msg)
  }

  def createMasterSecret(masterSecretId: String)(implicit wap: WalletAccessParam): String = {
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
                    revocationDetails: Option[String])
                   (implicit wap: WalletAccessParam): IssuerCreateAndStoreCredentialDefResult = {
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

  def createCredOffer(credDefId: String)(implicit wap: WalletAccessParam): String = {
    executeOpWithWalletInfo("set metadata", openWalletIfNotExists=false, { we: WalletExt =>
      Anoncreds.issuerCreateCredentialOffer(we.wallet, credDefId).get
    })
  }

  def createCredReq(credDefId: String, proverDID: DID, credDefJson: String, credOfferJson: String, masterSecretId: String)
                   (implicit wap: WalletAccessParam): String = {
    executeOpWithWalletInfo("cred request", openWalletIfNotExists=false, { we: WalletExt =>
      Anoncreds.proverCreateCredentialReq(we.wallet, proverDID, credOfferJson, credDefJson, masterSecretId)
        .get
        .getCredentialRequestJson
    })
  }

  def createCred(credOfferJson: String, credReqJson: String, credValuesJson: String,
                 revRegistryId: String, blobStorageReaderHandle: Int)
                (implicit wap: WalletAccessParam): String = {
    executeOpWithWalletInfo("cred", openWalletIfNotExists=false, { we: WalletExt =>
      Anoncreds.issuerCreateCredential(we.wallet, credOfferJson, credReqJson, credValuesJson, revRegistryId, blobStorageReaderHandle)
        .get.getCredentialJson
    })
  }

  def credentialsForProofReq(proofRequest: String)(implicit wap: WalletAccessParam): String = {
    executeOpWithWalletInfo("cred for proof req", openWalletIfNotExists=false, { we: WalletExt =>
      Anoncreds.proverGetCredentialsForProofReq(we.wallet, proofRequest).get
    })
  }

  def createProof(proofRequest: String, usedCredentials: String, schemas: String,
                  credentialDefs: String, revStates: String, masterSecret: String)
                 (implicit wap: WalletAccessParam): String= {
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
