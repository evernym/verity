package com.evernym.verity.protocol.engine.asyncapi.wallet

import com.evernym.verity.protocol.engine.DID

trait AnonCredAsyncOps {

  def runCreateSchema(issuerDID: DID, name: String, version: String, data: String): Unit

  def runCreateCredDef(issuerDID: DID,
                       schemaJson: String,
                       tag: String,
                       sigType: Option[String] = None,
                       revocationDetails: Option[String] = None): Unit

  def runCreateCredOffer(credDefId: String): Unit

  def runCreateCredReq(credDefId: String, proverDID: DID, credDefJson: String, credOfferJson: String): Unit

  def runCreateCred(credOfferJson: String, credReqJson: String, credValuesJson: String,
                    revRegistryId: String, blobStorageReaderHandle: Int): Unit

  def runStoreCred(credId: String,
                   credReqMetadataJson: String,
                   credJson: String,
                   credDefJson: String,
                   revRegDefJson: String): Unit

  def runCredentialsForProofReq(proofRequest: String): Unit

  def runCreateProof(proofRequest: String,
                     usedCredentials: String,
                     schemas: String,
                     credentialDefs: String,
                     revStates: String): Unit

  def runVerifyProof(proofRequest: String,
                     proof: String,
                     schemas: String,
                     credentialDefs: String,
                     revocRegDefs: String,
                     revocRegs: String): Unit
}
