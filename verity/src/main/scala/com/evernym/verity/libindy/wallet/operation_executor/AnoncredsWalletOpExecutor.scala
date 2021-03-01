package com.evernym.verity.libindy.wallet.operation_executor

import com.evernym.verity.vault.WalletExt
import com.evernym.verity.actor.wallet._
import com.evernym.verity.ExecutionContextProvider.walletFutureExecutionContext
import org.hyperledger.indy.sdk.anoncreds.Anoncreds

import scala.concurrent.Future

object AnoncredsWalletOpExecutor extends OpExecutorBase {

  def handleCreateMasterSecret(cms: CreateMasterSecret)(implicit we: WalletExt): Future[MasterSecretCreated] = {
    Anoncreds.proverCreateMasterSecret(we.wallet, cms.masterSecretId)
      .map(ms => MasterSecretCreated(ms))
  }

  def handleCreateCredDef(ccd: CreateCredDef)(implicit we: WalletExt): Future[CredDefCreated] = {
    val configJson = ccd.revocationDetails.getOrElse(""""{"support_revocation": false}"""")
    Anoncreds.issuerCreateAndStoreCredentialDef(
      we.wallet,
      ccd.issuerDID,
      ccd.schemaJson,
      ccd.tag,
      ccd.sigType.getOrElse("CL"),
      configJson)
      .map(r => CredDefCreated(r.getCredDefId, r.getCredDefJson))
  }

  def handleCreateCredOffer(cco: CreateCredOffer)(implicit we: WalletExt): Future[CredOfferCreated] = {
    Anoncreds.issuerCreateCredentialOffer(we.wallet, cco.credDefId).map(co => CredOfferCreated(co))
  }

  def handleCreateCredReq(ccr: CreateCredReq)(implicit we: WalletExt): Future[CredReqCreated] = {
    Anoncreds.proverCreateCredentialReq(
        we.wallet, ccr.proverDID, ccr.credOfferJson, ccr.credDefJson, ccr.masterSecretId)
      .map(r => CredReqCreated(r.getCredentialRequestJson, r.getCredentialRequestMetadataJson))
  }

  def handleCreateCred(cc: CreateCred)(implicit we: WalletExt): Future[CredCreated] = {
    Anoncreds.issuerCreateCredential(we.wallet,
      cc.credOfferJson, cc.credReqJson, cc.credValuesJson, cc.revRegistryId, cc.blobStorageReaderHandle)
      .map(r => CredCreated(r.getCredentialJson))
  }

  def handleStoreCred(sc: StoreCred)(implicit we: WalletExt): Future[CredStored] = {
    Anoncreds.proverStoreCredential(we.wallet,
      sc.credId, sc.credReqMetadataJson, sc.credJson, sc.credDefJson, sc.revRegDefJson)
      .map (c => CredStored(c))
  }

  def handleCredForProofReq(cfpr: CredForProofReq)(implicit we: WalletExt): Future[CredForProofReqCreated] = {
    Anoncreds.proverGetCredentialsForProofReq(we.wallet, cfpr.proofRequest)
      .map(c => CredForProofReqCreated(c))
  }

  def handleCreateProof(cp: CreateProof)(implicit we: WalletExt): Future[ProofCreated] = {
    Anoncreds.proverCreateProof(we.wallet,
      cp.proofRequest, cp.requestedCredentials, cp.masterSecret,
      cp.schemas, cp.credentialDefs, cp.revStates)
      .map(p => ProofCreated(p))
  }

  def verifyProof(proofRequest: String, proof: String, schemas: String, credentialDefs: String,
                  revocRegDefs: String, revocRegs: String): Future[ProofVerifResult] = {
    Anoncreds.verifierVerifyProof(proofRequest, proof, schemas, credentialDefs, revocRegDefs, revocRegs)
      .map(r => ProofVerifResult(r.booleanValue()))
  }
}
