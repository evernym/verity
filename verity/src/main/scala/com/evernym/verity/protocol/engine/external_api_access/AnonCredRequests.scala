package com.evernym.verity.protocol.engine.external_api_access

import com.evernym.verity.actor.wallet.CreatedCredReq
import com.evernym.verity.protocol.engine.DID

import scala.util.Try

trait AnonCredRequests {

  def createSchema(issuerDID: DID,
                   name: String,
                   version: String,
                   data: String)
                  (handler: Try[(String, String)] => Unit): Unit

  def createCredDef(issuerDID: DID,
                    schemaJson: String,
                    tag: String,
                    sigType: Option[String]=None,
                    revocationDetails: Option[String]=None)
                   (handler: Try[(String, String)] => Unit): Unit

  def createCredOffer(credDefId: String)(handler: Try[String] => Unit): Unit

  def createCredReq(credDefId: String,
                    proverDID: DID,
                    credDefJson: String,
                    credOfferJson: String)
                   (handler: Try[CreatedCredReq] => Unit): Unit

  def createCred(credOfferJson: String,
                 credReqJson: String,
                 credValuesJson: String,
                 revRegistryId: String,
                 blobStorageReaderHandle: Int)
                (handler: Try[String] => Unit): Unit

  def storeCred(credId: String,
                credDefJson: String,
                credReqMetadataJson: String,
                credJson: String,
                revRegDefJson: String)
               (handler: Try[String] => Unit): Unit

  def credentialsForProofReq(proofRequest: String)
                            (handler: Try[String] => Unit): Unit

  def createProof(proofRequest: String,
                  usedCredentials: String,
                  schemas: String,
                  credentialDefs: String,
                  revStates: String)
                 (handler: Try[String] => Unit): Unit

  def verifyProof(proofRequest: String,
                  proof: String,
                  schemas: String,
                  credentialDefs: String,
                  revocRegDefs: String,
                  revocRegs: String)
                 (handler: Try[Boolean] => Unit): Unit

}
