package com.evernym.verity.vault.wallet_api

import com.evernym.verity.actor.wallet.{CreateNewKey, GetVerKey, GetVerKeyOpt, NewKeyCreated, PackedMsg, SignLedgerRequest, SignMsg, StoreTheirKey, TheirKeyStored, UnpackedMsg, VerifySigByKeyInfo, VerifySigByVerKey, VerifySigResult, WalletCreated}
import com.evernym.verity.ledger.LedgerRequest
import com.evernym.verity.protocol.engine.{DID, VerKey}
import com.evernym.verity.vault.{KeyInfo, WalletAPIParam}
import org.hyperledger.indy.sdk.anoncreds.AnoncredsResults.{IssuerCreateAndStoreCredentialDefResult, IssuerCreateSchemaResult}

import scala.concurrent.Future

trait WalletAPI {

  //sync apis
  def createWallet(wap: WalletAPIParam): WalletCreated.type
  def createNewKey(cnk: CreateNewKey = CreateNewKey())(implicit wap: WalletAPIParam): NewKeyCreated
  def createDID(keyType: String)(implicit wap: WalletAPIParam): NewKeyCreated
  def storeTheirKey(stk: StoreTheirKey)(implicit wap: WalletAPIParam): TheirKeyStored
  def getVerKeyOption(gvk: GetVerKeyOpt)(implicit wap: WalletAPIParam): Option[VerKey]
  def getVerKey(gvk: GetVerKey)(implicit wap: WalletAPIParam): VerKey
  def signMsg(sm: SignMsg)(implicit wap: WalletAPIParam): Array[Byte]
  def verifySig(vs: VerifySigByKeyInfo)(implicit wap: WalletAPIParam): VerifySigResult
  def verifySigWithVerKey(vs: VerifySigByVerKey): VerifySigResult
  def LEGACY_pack(msg: Array[Byte], recipKeys: Set[KeyInfo], senderKey: Option[KeyInfo])
                 (implicit wap: WalletAPIParam): PackedMsg
  def packMessage(msg: Array[Byte], recipKeys: Set[KeyInfo], senderKey: Option[KeyInfo])
                 (implicit wap: WalletAPIParam): PackedMsg
  def LEGACY_unpack(msg: Array[Byte], fromKeyInfo: Option[KeyInfo], isAnonCryptedMsg: Boolean)
                   (implicit wap: WalletAPIParam): UnpackedMsg
  def unpackMessage(msg: Array[Byte])(implicit wap: WalletAPIParam): UnpackedMsg
  def createMasterSecret(masterSecretId: String)(implicit wap: WalletAPIParam): String
  def createSchema(issuerDID: DID, name:String, version: String, data: String): IssuerCreateSchemaResult
  def createCredDef(issuerDID: DID,
                    schemaJson: String,
                    tag: String,
                    sigType: Option[String],
                    revocationDetails: Option[String])
                   (implicit wap: WalletAPIParam): IssuerCreateAndStoreCredentialDefResult
  def createCredOffer(credDefId: String)(implicit wap: WalletAPIParam): String
  def createCredReq(credDefId: String, proverDID: DID, credDefJson: String, credOfferJson: String, masterSecretId: String)
                   (implicit wap: WalletAPIParam): String
  def createCred(credOfferJson: String, credReqJson: String, credValuesJson: String,
                 revRegistryId: String, blobStorageReaderHandle: Int)
                (implicit wap: WalletAPIParam): String
  def credentialsForProofReq(proofRequest: String)(implicit wap: WalletAPIParam): String
  def createProof(proofRequest: String, usedCredentials: String, schemas: String,
                  credentialDefs: String, revStates: String, masterSecret: String)
                 (implicit wap: WalletAPIParam): String
  def verifyProof(proofRequest: String, proof: String, schemas: String, credentialDefs: String,
                  revocRegDefs: String, revocRegs: String): Boolean

  //async apis
  def signLedgerRequest(sr: SignLedgerRequest): Future[LedgerRequest]
  def unpackMessageAsync(msg: Array[Byte])(implicit wap: WalletAPIParam): Future[UnpackedMsg]
  def LEGACY_unpackAsync(msg: Array[Byte], fromKeyInfo: Option[KeyInfo], isAnonCryptedMsg: Boolean)
                        (implicit wap: WalletAPIParam): Future[UnpackedMsg]

}

