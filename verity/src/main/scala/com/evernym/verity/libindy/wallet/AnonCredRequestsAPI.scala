package com.evernym.verity.libindy.wallet

import com.evernym.verity.ExecutionContextProvider.walletFutureExecutionContext
import com.evernym.verity.actor.wallet._
import com.evernym.verity.config.CommonConfig.SALT_WALLET_NAME
import com.evernym.verity.libindy.wallet.operation_executor.{AnoncredsWalletOpExecutor, FutureConverter}
import com.evernym.verity.protocol.engine.DID
import com.evernym.verity.protocol.engine.external_api_access.AnonCredRequests
import com.evernym.verity.util.HashAlgorithm.SHA256
import com.evernym.verity.util.HashUtil
import com.evernym.verity.util.HashUtil.byteArray2RichBytes
import com.evernym.verity.vault.WalletAPIParam
import org.hyperledger.indy.sdk.anoncreds.Anoncreds.issuerCreateSchema
import org.hyperledger.indy.sdk.anoncreds.DuplicateMasterSecretNameException

import scala.util.{Failure, Success, Try}

trait AnonCredRequestsAPI
  extends AnonCredRequests
    with FutureConverter { this: WalletAccessAPI  =>

  implicit def wap: WalletAPIParam

  lazy val masterSecretId: String = {

    val salt = appConfig.getConfigStringReq(SALT_WALLET_NAME)
    val msIdHex = HashUtil.hash(SHA256)(selfParticipantId + salt).hex
    //TODO: may want to optimize this (for now, every time a cred request is sent, it will do below check)
    Try(walletApi.executeSync[String](CreateMasterSecret(msIdHex))) match {
      case Success(msId) if msId == msIdHex => msIdHex
      case Failure(_: DuplicateMasterSecretNameException) => msIdHex    //already created
      case Failure(_: RuntimeException) => throw new RuntimeException("error during master secret creation")
    }
  }

  override def createSchema(issuerDID: DID, name:String, version: String, data: String)
                           (handler: Try[(String, String)] => Unit): Unit = {
    executeAsyncOperation{
      issuerCreateSchema(issuerDID, name, version, data)
    }{ result =>
      handler(result.map(r => (r.getSchemaId, r.getSchemaJson)))
    }
  }

  override def createCredDef(issuerDID: DID,
                             schemaJson: String,
                             tag: String,
                             sigType: Option[String]=None,
                             revocationDetails: Option[String]=None)
                            (handler: Try[(String, String)] => Unit): Unit =
    executeAsyncWalletApi[CreatedCredDef](
      CreateCredDef(issuerDID, schemaJson, tag, sigType, revocationDetails)
    ) { result =>
      handler(result.map(r => (r.credDefId, r.credDefJson)))
    }

  override def createCredOffer(credDefId: String)(handler: Try[String] => Unit): Unit =
    executeAsyncWalletApi[String](CreateCredOffer(credDefId))(handler)

  override def createCredReq(credDefId: String, proverDID: DID, credDefJson: String, credOfferJson: String)
                            (handler: Try[CreatedCredReq] => Unit): Unit =
    executeAsyncWalletApi[CreatedCredReq](
      CreateCredReq(credDefId, proverDID, credDefJson, credOfferJson, masterSecretId)
    )(handler)

  override def createCred(credOfferJson: String, credReqJson: String, credValuesJson: String,
                          revRegistryId: String, blobStorageReaderHandle: Int)
                         (handler: Try[String] => Unit): Unit = {
    executeAsyncWalletApi[String](
      CreateCred(credOfferJson, credReqJson, credValuesJson, revRegistryId, blobStorageReaderHandle)
    )(handler)
  }

  override def storeCred(credId: String, credReqMetadataJson: String, credJson: String,
                         credDefJson: String, revRegDefJson: String)
                        (handler: Try[String] => Unit): Unit = {
    executeAsyncWalletApi[String](
      StoreCred(credId, credReqMetadataJson, credJson, credDefJson, revRegDefJson)
    )(handler)
  }

  override def credentialsForProofReq(proofRequest: String)
                                     (handler: Try[String] => Unit): Unit =
    executeAsyncWalletApi[String](CredForProofReq(proofRequest))(handler)

  override def createProof(proofRequest: String, usedCredentials: String,
                           schemas: String, credentialDefs: String, revStates: String)
                          (handler: Try[String] => Unit): Unit =
    executeAsyncWalletApi[String](
      CreateProof(proofRequest, usedCredentials, schemas, credentialDefs, masterSecretId, revStates)
    )(handler)

  override def verifyProof(proofRequest: String, proof: String, schemas: String, credentialDefs: String,
                           revocRegDefs: String, revocRegs: String)
                          (handler: Try[Boolean] => Unit): Unit = {
    executeAsyncOperation{
      AnoncredsWalletOpExecutor.verifyProof(
        proofRequest, proof, schemas, credentialDefs, revocRegDefs, revocRegs
      )
    }(handler)
  }

}
