package com.evernym.verity.vault.wallet_api

import com.evernym.verity.actor.wallet.{CreateNewKey, CreatedCredDef, GetVerKey, GetVerKeyOpt, NewKeyCreated, PackedMsg, SignMsg, StoreTheirKey, TheirKeyStored, UnpackedMsg, VerifySigByKeyParam, VerifySigByVerKey, VerifySigResult, WalletCreated}
import com.evernym.verity.protocol.engine.{DID, VerKey}
import com.evernym.verity.vault.{KeyParam, WalletAPIParam}
import org.hyperledger.indy.sdk.anoncreds.AnoncredsResults.IssuerCreateSchemaResult

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
  def verifySig(vs: VerifySigByKeyParam)(implicit wap: WalletAPIParam): VerifySigResult
  def verifySigWithVerKey(vs: VerifySigByVerKey): VerifySigResult
  def LEGACY_packMsg(msg: Array[Byte], recipKeyParams: Set[KeyParam], senderKeyParam: Option[KeyParam])
                    (implicit wap: WalletAPIParam): PackedMsg
  def packMsg(msg: Array[Byte], recipKeyParams: Set[KeyParam], senderKeyParam: Option[KeyParam])
             (implicit wap: WalletAPIParam): PackedMsg
  def LEGACY_unpackMsg(msg: Array[Byte], fromKeyParamOpt: Option[KeyParam], isAnonCryptedMsg: Boolean)
                      (implicit wap: WalletAPIParam): UnpackedMsg
  def unpackMsg(msg: Array[Byte])(implicit wap: WalletAPIParam): UnpackedMsg
  def createMasterSecret(masterSecretId: String)(implicit wap: WalletAPIParam): String
  def createSchema(issuerDID: DID, name:String, version: String, data: String): IssuerCreateSchemaResult
  def createCredDef(issuerDID: DID,
                    schemaJson: String,
                    tag: String,
                    sigType: Option[String],
                    revocationDetails: Option[String])
                   (implicit wap: WalletAPIParam): CreatedCredDef
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

  def executeAsync[T](cmd: Any)(implicit wap: WalletAPIParam): Future[T]

}

