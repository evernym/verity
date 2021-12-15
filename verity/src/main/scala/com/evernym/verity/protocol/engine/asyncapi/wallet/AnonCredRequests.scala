package com.evernym.verity.protocol.engine.asyncapi.wallet

import com.evernym.verity.did.DidStr

import scala.util.Try

trait AnonCredRequests {

  def createSchema(issuerDID: DidStr,
                   name: String,
                   version: String,
                   data: String)
                  (handler: Try[SchemaCreatedResult] => Unit): Unit

  def createCredDef(issuerDID: DidStr,
                    schemaJson: String,
                    tag: String,
                    sigType: Option[String]=None,
                    revocationDetails: Option[String]=None)
                   (handler: Try[CredDefCreatedResult] => Unit): Unit

  def createCredOffer(credDefId: String)(handler: Try[CredOfferCreatedResult] => Unit): Unit

  def createCredReq(credDefId: String,
                    proverDID: DidStr,
                    credDefJson: String,
                    credOfferJson: String)
                   (handler: Try[CredReqCreatedResult] => Unit): Unit

  def createCred(credOfferJson: String,
                 credReqJson: String,
                 credValuesJson: String,
                 revRegistryId: String,
                 blobStorageReaderHandle: Int)
                (handler: Try[CredCreatedResult] => Unit): Unit

  def storeCred(credId: String,
                credDefJson: String,
                credReqMetadataJson: String,
                credJson: String,
                revRegDefJson: String)
               (handler: Try[CredStoredResult] => Unit): Unit

  def credentialsForProofReq(proofRequest: String)
                            (handler: Try[CredForProofResult] => Unit): Unit

  def createProof(proofRequest: String,
                  usedCredentials: String,
                  schemas: String,
                  credentialDefs: String,
                  revStates: String)
                 (handler: Try[ProofCreatedResult] => Unit): Unit

  def verifyProof(proofRequest: String,
                  proof: String,
                  schemas: String,
                  credentialDefs: String,
                  revocRegDefs: String,
                  revocRegs: String)
                 (handler: Try[ProofVerificationResult] => Unit): Unit

}
