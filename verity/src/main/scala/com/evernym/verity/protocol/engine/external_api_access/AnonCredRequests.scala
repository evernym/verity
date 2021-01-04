package com.evernym.verity.protocol.engine.external_api_access

import com.evernym.verity.actor.wallet.CreatedCredReq
import com.evernym.verity.protocol.engine.DID

import scala.util.Try

trait AnonCredRequests {

  def createSchema(issuerDID: DID,
                   name: String,
                   version: String,
                   data: String): Try[(String, String)]

  def createCredDef(issuerDID: DID,
                    schemaJson: String,
                    tag: String,
                    sigType: Option[String]=None,
                    revocationDetails: Option[String]=None): Try[(String, String)]

  def createCredOffer(credDefId: String): Try[String]

  def createCredReq(credDefId: String,
                    proverDID: DID,
                    credDefJson: String,
                    credOfferJson: String): Try[CreatedCredReq]

  def createCred(credOfferJson: String,
                 credReqJson: String,
                 credValuesJson: String,
                 revRegistryId: String,
                 blobStorageReaderHandle: Int): Try[String]

  def storeCred(credId: String,
                credReqMetadataJson: String,
                credJson: String,
                credDefJson: String,
                revRegDefJson: String): Try[String]

  def credentialsForProofReq(proofRequest: String): Try[String]

  def createProof(proofRequest: String,
                  usedCredentials: String,
                  schemas: String,
                  credentialDefs: String,
                  revStates: String): Try[String]

  def verifyProof(proofRequest: String,
                  proof: String,
                  schemas: String,
                  credentialDefs: String,
                  revocRegDefs: String,
                  revocRegs: String): Try[Boolean]
}
