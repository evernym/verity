package com.evernym.verity.libindy.wallet

import com.evernym.verity.actor.agent.SpanUtil.runWithInternalSpan
import com.evernym.verity.actor.wallet.{CreateCred, CreateCredDef, CreateCredOffer, CreateCredReq, CreateMasterSecret, CreateProof, CreatedCredDef, CreatedCredReq, CredForProofReq, StoreCred}
import com.evernym.verity.config.CommonConfig.SALT_WALLET_NAME
import com.evernym.verity.libindy.wallet.operation_executor.AnoncredsWalletOpExecutor
import com.evernym.verity.protocol.engine.DID
import com.evernym.verity.protocol.engine.external_api_access.AnonCredRequests
import com.evernym.verity.util.HashAlgorithm.SHA256
import com.evernym.verity.util.HashUtil
import com.evernym.verity.util.HashUtil.byteArray2RichBytes
import com.evernym.verity.vault.WalletAPIParam
import com.evernym.verity.vault.service.AsyncToSync
import org.hyperledger.indy.sdk.anoncreds.Anoncreds.issuerCreateSchema
import org.hyperledger.indy.sdk.anoncreds.DuplicateMasterSecretNameException

import scala.util.{Failure, Success, Try}

trait AnonCredRequestsAPI
  extends AnonCredRequests
    with AsyncToSync { this: WalletAccessAPI  =>

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

  override def createSchema(issuerDID: DID, name:String, version: String, data: String): Try[(String, String)] = {
    runWithInternalSpan("createSchema", "AnonCredRequestsApi") {
      val createSchemaResult = issuerCreateSchema(issuerDID, name, version, data)
      Try(createSchemaResult.get.getSchemaId, createSchemaResult.get.getSchemaJson)
    }
  }

  override def createCredDef(issuerDID: DID,
                             schemaJson: String,
                             tag: String,
                             sigType: Option[String]=None,
                             revocationDetails: Option[String]=None): Try[(String, String)] = {
    runWithInternalSpan("createCredDef", "AnonCredRequestsApi") {
      val createSchemaResult = walletApi.executeSync[CreatedCredDef](
        CreateCredDef(issuerDID, schemaJson, tag, sigType, revocationDetails))
      Try(createSchemaResult.credDefId, createSchemaResult.credDefJson)
    }
  }

  override def createCredOffer(credDefId: String): Try[String] = {
    Try(walletApi.executeSync[String](CreateCredOffer(credDefId)))
  }

  override def createCredReq(credDefId: String, proverDID: DID, credDefJson: String, credOfferJson: String): Try[CreatedCredReq] = {
    Try(walletApi.executeSync[CreatedCredReq](CreateCredReq(credDefId, proverDID, credDefJson, credOfferJson, masterSecretId)))
  }

  override def createCred(credOfferJson: String, credReqJson: String, credValuesJson: String,
                 revRegistryId: String, blobStorageReaderHandle: Int): Try[String] = {
    Try(walletApi.executeSync[String](CreateCred(credOfferJson, credReqJson, credValuesJson,
      revRegistryId, blobStorageReaderHandle)))
  }

  override def storeCred(credId: String, credReqMetadataJson: String, credJson: String,
                         credDefJson: String, revRegDefJson: String): Try[String] = {
    Try(walletApi.executeSync[String](StoreCred(credId, credReqMetadataJson, credJson, credDefJson, revRegDefJson)))
  }

  override def credentialsForProofReq(proofRequest: String): Try[String] =
    Try(walletApi.executeSync[String](CredForProofReq(proofRequest)))

  override def createProof(proofRequest: String, usedCredentials: String,
                           schemas: String, credentialDefs: String, revStates: String): Try[String] =
    Try(walletApi.executeSync[String](CreateProof(proofRequest, usedCredentials, schemas,
      credentialDefs, masterSecretId, revStates)))

  override def verifyProof(proofRequest: String, proof: String, schemas: String, credentialDefs: String,
                           revocRegDefs: String, revocRegs: String): Try[Boolean] =
    Try(convertToSyncReq(
      AnoncredsWalletOpExecutor.verifyProof(proofRequest, proof, schemas, credentialDefs, revocRegDefs, revocRegs))
    )

}
