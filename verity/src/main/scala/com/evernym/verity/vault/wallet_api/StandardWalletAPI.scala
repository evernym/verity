package com.evernym.verity.vault.wallet_api

import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor.wallet.{WalletCreated, _}
import com.evernym.verity.libindy.wallet.operation_executor.{CryptoOpExecutor, FutureConverter}
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.protocol.engine.{DID, VerKey}
import com.evernym.verity.vault.service._
import com.evernym.verity.vault.{KeyParam, WalletAPIParam}
import com.typesafe.scalalogging.Logger
import org.hyperledger.indy.sdk.anoncreds.Anoncreds
import org.hyperledger.indy.sdk.anoncreds.AnoncredsResults.IssuerCreateSchemaResult

import scala.concurrent.Future
import scala.language.implicitConversions


class StandardWalletAPI(walletService: WalletService)
  extends WalletAPI
    with FutureConverter
    with AsyncToSync {

  val logger: Logger = getLoggerByClass(classOf[WalletAPI])

  def createWallet(wap: WalletAPIParam): WalletCreated.type = {
    walletService.executeSync[WalletCreated.type](wap.walletId, CreateWallet)
  }

  def createNewKey(cnk: CreateNewKey=CreateNewKey())(implicit wap: WalletAPIParam):
  NewKeyCreated = {
    walletService.executeSync[NewKeyCreated](wap.walletId, cnk)
  }

  def createDID(keyType: String)(implicit wap: WalletAPIParam): NewKeyCreated = {
    walletService.executeSync[NewKeyCreated](wap.walletId, CreateDID(keyType))
  }

  def storeTheirKey(stk: StoreTheirKey)(implicit wap: WalletAPIParam): TheirKeyStored = {
    walletService.executeSync[TheirKeyStored](wap.walletId, stk)
  }

  def getVerKeyOption(gvkOpt: GetVerKeyOpt)(implicit wap: WalletAPIParam): Option[VerKey] = {
    walletService.executeSync[Option[VerKey]](wap.walletId, gvkOpt)
  }

  def getVerKey(gvk: GetVerKey)(implicit wap: WalletAPIParam): VerKey = {
    walletService.executeSync[VerKey](wap.walletId, gvk)
  }

  def signMsg(sm: SignMsg)(implicit wap: WalletAPIParam): Array[Byte] = {
    walletService.executeSync[Array[Byte]](wap.walletId, sm)
  }

  def verifySig(vs: VerifySigByKeyParam)(implicit wap: WalletAPIParam): VerifySigResult = {
    walletService.executeSync[VerifySigResult](wap.walletId, vs)
  }

  def LEGACY_packMsg(msg: Array[Byte], recipVerKeyParams: Set[KeyParam], senderVerKeyParam: Option[KeyParam])
                    (implicit wap: WalletAPIParam): PackedMsg = {
    walletService.executeSync[PackedMsg](wap.walletId, LegacyPackMsg(msg, recipVerKeyParams, senderVerKeyParam))
  }

  def LEGACY_unpackMsg(msg: Array[Byte], fromKeyParamOpt: Option[KeyParam], isAnonCryptedMsg: Boolean)
                      (implicit wap: WalletAPIParam): UnpackedMsg = {
    walletService.executeSync[UnpackedMsg](wap.walletId, LegacyUnpackMsg(msg, fromKeyParamOpt, isAnonCryptedMsg))
  }

  def packMsg(msg: Array[Byte], recipVerKeyParams: Set[KeyParam], senderVerKeyParam: Option[KeyParam])
             (implicit wap: WalletAPIParam): PackedMsg = {
    walletService.executeSync[PackedMsg](wap.walletId, PackMsg(msg, recipVerKeyParams, senderVerKeyParam))
  }

  def unpackMsg(msg: Array[Byte])(implicit wap: WalletAPIParam): UnpackedMsg = {
    walletService.executeSync[UnpackedMsg](wap.walletId, UnpackMsg(msg))
  }

  def createMasterSecret(masterSecretId: String)(implicit wap: WalletAPIParam): String = {
    walletService.executeSync[String](wap.walletId, CreateMasterSecret(masterSecretId))
  }

  def createCredDef(issuerDID: DID,
                    schemaJson: String,
                    tag: String,
                    sigType: Option[String],
                    revocationDetails: Option[String])
                   (implicit wap: WalletAPIParam): CreatedCredDef = {
    walletService.executeSync[CreatedCredDef](wap.walletId,
      CreateCredDef(issuerDID, schemaJson, tag, sigType, revocationDetails))
  }

  def createCredOffer(credDefId: String)(implicit wap: WalletAPIParam): String = {
    walletService.executeSync[String](wap.walletId, CreateCredOffer(credDefId))
  }

  def createCredReq(credDefId: String, proverDID: DID, credDefJson: String, credOfferJson: String, masterSecretId: String)
                   (implicit wap: WalletAPIParam): String = {
    walletService.executeSync[String](wap.walletId,
      CreateCredReq(credDefId, proverDID, credDefJson, credOfferJson, masterSecretId))
  }

  def createCred(credOfferJson: String, credReqJson: String, credValuesJson: String,
                 revRegistryId: String, blobStorageReaderHandle: Int)
                (implicit wap: WalletAPIParam): String = {
    walletService.executeSync[String](wap.walletId,
      CreateCred(credOfferJson, credReqJson, credValuesJson,
        revRegistryId, blobStorageReaderHandle))
  }

  def credentialsForProofReq(proofRequest: String)(implicit wap: WalletAPIParam): String = {
    walletService.executeSync[String](wap.walletId, CredForProofReq(proofRequest))
  }

  def createProof(proofRequest: String, usedCredentials: String, schemas: String,
                  credentialDefs: String, revStates: String, masterSecret: String)
                 (implicit wap: WalletAPIParam): String= {
    walletService.executeSync[String](wap.walletId,
      CreateProof(proofRequest, usedCredentials, schemas,
        credentialDefs, revStates, masterSecret))
  }

  //no wallet needed
  def verifySigWithVerKey(vs: VerifySigByVerKey): VerifySigResult = {
    convertToSyncReq(CryptoOpExecutor.verifySig(vs.verKey, vs.challenge, vs.signature))
  }

  //no wallet needed
  def createSchema(issuerDID: DID, name:String, version: String, data: String): IssuerCreateSchemaResult = {
    convertToSyncReq {
      Anoncreds.issuerCreateSchema(issuerDID, name, version, data)
    }
  }

  //no wallet needed
  def verifyProof(proofRequest: String, proof: String, schemas: String, credentialDefs: String,
                  revocRegDefs: String, revocRegs: String): Boolean = {
    convertToSyncReq {
      Anoncreds.verifierVerifyProof(proofRequest, proof, schemas, credentialDefs, revocRegDefs, revocRegs)
      .map(_.booleanValue())
    }
  }

  def executeAsync[T](cmd: Any)(implicit wap: WalletAPIParam): Future[T] = {
    walletService.executeAsync(wap.walletId, cmd)
  }
}


