package com.evernym.verity.protocol.container.asyncapis.wallet

import com.evernym.verity.actor.wallet._
import com.evernym.verity.config.CommonConfig.SALT_WALLET_NAME
import com.evernym.verity.libindy.wallet.operation_executor.{AnoncredsWalletOpExecutor, FutureConverter}
import com.evernym.verity.protocol.engine.DID
import com.evernym.verity.protocol.engine.asyncapi.wallet.AnonCredRequests
import com.evernym.verity.util.HashAlgorithm.SHA256
import com.evernym.verity.util.HashUtil
import com.evernym.verity.util.HashUtil._
import com.evernym.verity.vault.WalletAPIParam
import com.evernym.verity.vault.service.AsyncToSync
import org.hyperledger.indy.sdk.anoncreds.Anoncreds.issuerCreateSchema
import org.hyperledger.indy.sdk.anoncreds.DuplicateMasterSecretNameException

import scala.util.{Failure, Success, Try}

trait AnonCredRequestsAPI
  extends AnonCredRequests
    with FutureConverter
    with AsyncToSync { this: WalletAccessAPI  =>

  implicit def wap: WalletAPIParam

  lazy val masterSecretId: String = {

    val salt = appConfig.getConfigStringReq(SALT_WALLET_NAME)
    val msIdHex = HashUtil.hash(SHA256)(selfParticipantId + salt).hex
    //TODO: may want to optimize this (for now, every time a cred request is sent, it will do below check)
    Try(DEPRECATED_convertToSyncReq(walletApi.executeAsync[String](CreateMasterSecret(msIdHex)))) match {
      case Success(msId) if msId == msIdHex => msIdHex
      case Failure(_: DuplicateMasterSecretNameException) => msIdHex    //already created
      case Failure(_: RuntimeException) => throw new RuntimeException("error during master secret creation")
    }
  }

  override def createSchema(issuerDID: DID, name:String, version: String, data: String)
                           (handler: Try[SchemaCreated] => Unit): Unit = {
    withAsyncOpExecutorActor(
      { implicit ec =>
        issuerCreateSchema(issuerDID, name, version, data).map { result =>
          SchemaCreated(result.getSchemaId, result.getSchemaJson)
        }
      },
      handler
    )
  }

  override def createCredDef(issuerDID: DID,
                             schemaJson: String,
                             tag: String,
                             sigType: Option[String]=None,
                             revocationDetails: Option[String]=None)
                            (handler: Try[CredDefCreated] => Unit): Unit =
    withAsyncOpRunner(
      { walletApi.tell(
        CreateCredDef(issuerDID, schemaJson, tag, sigType, revocationDetails))
      },
      handler
    )

  override def createCredOffer(credDefId: String)(handler: Try[CredOfferCreated] => Unit): Unit = {
    withAsyncOpRunner({walletApi.tell(CreateCredOffer(credDefId))}, handler)
  }

  override def createCredReq(credDefId: String, proverDID: DID, credDefJson: String, credOfferJson: String)
                            (handler: Try[CredReqCreated] => Unit): Unit =
    withAsyncOpRunner(
      { walletApi.tell(CreateCredReq(credDefId, proverDID,
        credDefJson, credOfferJson, masterSecretId))},
      handler
    )

  override def createCred(credOfferJson: String, credReqJson: String, credValuesJson: String,
                          revRegistryId: String, blobStorageReaderHandle: Int)
                         (handler: Try[CredCreated] => Unit): Unit = {
    withAsyncOpRunner(
      {walletApi.tell(CreateCred(credOfferJson, credReqJson, credValuesJson,
        revRegistryId, blobStorageReaderHandle))},
      handler
    )
  }

  override def storeCred(credId: String, credReqMetadataJson: String, credJson: String,
                         credDefJson: String, revRegDefJson: String)
                        (handler: Try[CredStored] => Unit): Unit = {
    withAsyncOpRunner(
      {walletApi.tell(StoreCred(credId, credReqMetadataJson, credJson, credDefJson, revRegDefJson))},
      handler
    )
  }

  override def credentialsForProofReq(proofRequest: String)
                                     (handler: Try[CredForProofReqCreated] => Unit): Unit =
    withAsyncOpRunner({walletApi.tell(CredForProofReq(proofRequest))}, handler)

  override def createProof(proofRequest: String, usedCredentials: String,
                           schemas: String, credentialDefs: String, revStates: String)
                          (handler: Try[ProofCreated] => Unit): Unit =
    withAsyncOpRunner(
      {walletApi.tell(
        CreateProof(proofRequest, usedCredentials, schemas, credentialDefs, masterSecretId, revStates))},
      handler
    )

  override def verifyProof(proofRequest: String, proof: String, schemas: String, credentialDefs: String,
                           revocRegDefs: String, revocRegs: String)
                          (handler: Try[ProofVerifResult] => Unit): Unit = {
    withAsyncOpExecutorActor(
      { implicit ec => AnoncredsWalletOpExecutor.verifyProof(
          proofRequest, proof, schemas, credentialDefs, revocRegDefs, revocRegs)},
      handler
    )
  }

}

case class SchemaCreated(schemaId: String, schemaJson: String)