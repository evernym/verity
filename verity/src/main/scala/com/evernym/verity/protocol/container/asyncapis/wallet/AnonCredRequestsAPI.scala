package com.evernym.verity.protocol.container.asyncapis.wallet

import com.evernym.verity.actor.wallet._
import com.evernym.verity.config.ConfigConstants.SALT_WALLET_NAME
import com.evernym.verity.vault.operation_executor.{AnoncredsWalletOpExecutor, FutureConverter}
import com.evernym.verity.did.DID
import com.evernym.verity.protocol.engine.asyncapi.wallet.AnonCredAsyncOps
import com.evernym.verity.util.HashAlgorithm.SHA256
import com.evernym.verity.util.{HashUtil, Util}
import com.evernym.verity.util.HashUtil._
import com.evernym.verity.vault.WalletAPIParam
import org.hyperledger.indy.sdk.anoncreds.Anoncreds.issuerCreateSchema
import org.hyperledger.indy.sdk.anoncreds.DuplicateMasterSecretNameException

import scala.util.{Failure, Success, Try}

trait AnonCredRequestsAPI
    extends AnonCredAsyncOps
      with FutureConverter { this: WalletAccessAPI  =>

  implicit def wap: WalletAPIParam

  lazy val masterSecretId: String = {

    val salt = appConfig.getStringReq(SALT_WALLET_NAME)
    val msIdHex = HashUtil.hash(SHA256)(selfParticipantId + salt).hex
    //TODO: may want to optimize this (for now, every time a cred request is sent, it will do below check)
    Try(Util.DEPRECATED_convertToSyncReq(walletApi.executeAsync[MasterSecretCreated](CreateMasterSecret(msIdHex)))) match {
      case Success(msc) if msc.ms == msIdHex => msIdHex
      case Failure(_: DuplicateMasterSecretNameException) => msIdHex    //already created
      case Failure(_: RuntimeException) => throw new RuntimeException("error during master secret creation")
    }
  }

  def runCreateSchema(issuerDID: DID, name:String, version: String, data: String): Unit = {
    withAsyncOpExecutorActor(
      { implicit ec =>
        issuerCreateSchema(issuerDID, name, version, data).map { result =>
          SchemaCreated(result.getSchemaId, result.getSchemaJson)
        }
      }
    )
  }

  def runCreateCredDef(issuerDID: DID,
                       schemaJson: String,
                       tag: String,
                       sigType: Option[String]=None,
                       revocationDetails: Option[String]=None): Unit =
    walletApi.tell(CreateCredDef(issuerDID, schemaJson, tag, sigType, revocationDetails))

  def runCreateCredOffer(credDefId: String): Unit = {
    walletApi.tell(CreateCredOffer(credDefId))
  }

  def runCreateCredReq(credDefId: String, proverDID: DID, credDefJson: String, credOfferJson: String): Unit =
    walletApi.tell(CreateCredReq(credDefId, proverDID,
        credDefJson, credOfferJson, masterSecretId))

  def runCreateCred(credOfferJson: String, credReqJson: String, credValuesJson: String,
                    revRegistryId: String, blobStorageReaderHandle: Int): Unit = {
    walletApi.tell(CreateCred(credOfferJson, credReqJson, credValuesJson,
        revRegistryId, blobStorageReaderHandle))
  }

  def runStoreCred(credId: String,
                   credReqMetadataJson: String,
                   credJson: String,
                   credDefJson: String,
                   revRegDefJson: String): Unit = {
    walletApi.tell(StoreCred(credId, credReqMetadataJson, credJson, credDefJson, revRegDefJson))
  }

  def runCredentialsForProofReq(proofRequest: String): Unit =
    walletApi.tell(CredForProofReq(proofRequest))

  def runCreateProof(proofRequest: String,
                     usedCredentials: String,
                     schemas: String,
                     credentialDefs: String,
                     revStates: String): Unit =
    walletApi.tell(
        CreateProof(proofRequest, usedCredentials, schemas, credentialDefs, masterSecretId, revStates)
    )

  def runVerifyProof(proofRequest: String,
                     proof: String,
                     schemas: String,
                     credentialDefs: String,
                     revocRegDefs: String,
                     revocRegs: String): Unit = {
    withAsyncOpExecutorActor(
      { implicit ec => AnoncredsWalletOpExecutor.verifyProof(
          proofRequest, proof, schemas, credentialDefs, revocRegDefs, revocRegs)
      }
    )
  }

}

case class SchemaCreated(schemaId: String, schemaJson: String)