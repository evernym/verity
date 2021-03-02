package com.evernym.verity.protocol.engine.asyncapi.wallet

import com.evernym.verity.actor.wallet.{CredCreated, CredDefCreated, CredForProofReqCreated, CredOfferCreated, CredReqCreated, CredStored, ProofCreated, ProofVerifResult}
import com.evernym.verity.protocol.container.asyncapis.wallet.SchemaCreated
import com.evernym.verity.protocol.engine.DID

import scala.util.Try

trait AnonCredRequests {

  def createSchema(issuerDID: DID,
                   name: String,
                   version: String,
                   data: String)
                  (handler: Try[SchemaCreated] => Unit): Unit

  def createCredDef(issuerDID: DID,
                    schemaJson: String,
                    tag: String,
                    sigType: Option[String]=None,
                    revocationDetails: Option[String]=None)
                   (handler: Try[CredDefCreated] => Unit): Unit

  def createCredOffer(credDefId: String)(handler: Try[CredOfferCreated] => Unit): Unit

  def createCredReq(credDefId: String,
                    proverDID: DID,
                    credDefJson: String,
                    credOfferJson: String)
                   (handler: Try[CredReqCreated] => Unit): Unit

  def createCred(credOfferJson: String,
                 credReqJson: String,
                 credValuesJson: String,
                 revRegistryId: String,
                 blobStorageReaderHandle: Int)
                (handler: Try[CredCreated] => Unit): Unit

  def storeCred(credId: String,
                credDefJson: String,
                credReqMetadataJson: String,
                credJson: String,
                revRegDefJson: String)
               (handler: Try[CredStored] => Unit): Unit

  def credentialsForProofReq(proofRequest: String)
                            (handler: Try[CredForProofReqCreated] => Unit): Unit

  def createProof(proofRequest: String,
                  usedCredentials: String,
                  schemas: String,
                  credentialDefs: String,
                  revStates: String)
                 (handler: Try[ProofCreated] => Unit): Unit

  def verifyProof(proofRequest: String,
                  proof: String,
                  schemas: String,
                  credentialDefs: String,
                  revocRegDefs: String,
                  revocRegs: String)
                 (handler: Try[ProofVerifResult] => Unit): Unit

}
