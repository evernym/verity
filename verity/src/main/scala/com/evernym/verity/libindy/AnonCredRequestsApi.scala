package com.evernym.verity.libindy

import com.evernym.verity.actor.agent.SpanUtil._
import com.evernym.verity.config.CommonConfig.SALT_WALLET_NAME
import com.evernym.verity.protocol.engine.{AnonCredRequests, DID}
import com.evernym.verity.util.HashAlgorithm.SHA256
import com.evernym.verity.util.HashUtil
import com.evernym.verity.util.HashUtil.byteArray2RichBytes
import com.evernym.verity.vault.WalletAccessParam
import org.hyperledger.indy.sdk.anoncreds.Anoncreds.issuerCreateSchema
import org.hyperledger.indy.sdk.anoncreds.DuplicateMasterSecretNameException

import scala.util.{Failure, Success, Try}

trait AnonCredRequestsApi extends AnonCredRequests { this: WalletAccessLibindy  =>

  implicit def wap: WalletAccessParam

  lazy val masterSecretId: String = {

    val salt = appConfig.getConfigStringReq(SALT_WALLET_NAME)
    val msIdHex = HashUtil.hash(SHA256)(selfParticipantId + salt).hex
    //TODO: may want to optimize this (for now, every time a cred request is sent, it will do below check)
    Try(walletApi.createMasterSecret(msIdHex)) match {
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
      val createSchemaResult = walletApi.createCredDef(issuerDID, schemaJson, tag, sigType, revocationDetails)
      Try(createSchemaResult.getCredDefId, createSchemaResult.getCredDefJson)
    }
  }

  override def createCredOffer(credDefId: String): Try[String] = {
    Try(walletApi.createCredOffer(credDefId))
  }

  override def createCredReq(credDefId: String, proverDID: DID, credDefJson: String, credOfferJson: String): Try[String] = {
    Try(walletApi.createCredReq(credDefId, proverDID, credDefJson, credOfferJson, masterSecretId))
  }

  override def createCred(credOfferJson: String, credReqJson: String, credValuesJson: String,
                 revRegistryId: String, blobStorageReaderHandle: Int): Try[String] = {
    Try(walletApi.createCred(credOfferJson, credReqJson, credValuesJson,
      revRegistryId, blobStorageReaderHandle))
  }

  override def credentialsForProofReq(proofRequest: String): Try[String] =
    Try(walletApi.credentialsForProofReq(proofRequest))

  override def createProof(proofRequest: String, usedCredentials: String,
                           schemas: String, credentialDefs: String, revStates: String): Try[String] =
    Try(walletApi.createProof(proofRequest, usedCredentials, schemas, credentialDefs, revStates, masterSecretId))

  override def verifyProof(proofRequest: String, proof: String, schemas: String, credentialDefs: String,
                           revocRegDefs: String, revocRegs: String): Try[Boolean] =
    Try(walletApi.verifyProof(proofRequest, proof, schemas, credentialDefs, revocRegDefs, revocRegs))

}
