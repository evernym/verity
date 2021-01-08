package com.evernym.verity.libindy.wallet.operation_executor

import com.evernym.verity.vault.WalletExt
import com.evernym.verity.actor.wallet._
import com.evernym.verity.ExecutionContextProvider.walletFutureExecutionContext
import org.hyperledger.indy.sdk.anoncreds.Anoncreds

import scala.concurrent.Future

object AnoncredsWalletOpExecutor extends OpExecutorBase {

  def handleCreateMasterSecret(cms: CreateMasterSecret)(implicit we: WalletExt): Future[String] = {
    Anoncreds.proverCreateMasterSecret(we.wallet, cms.masterSecretId)
  }

  def handleCreateCredDef(ccd: CreateCredDef)(implicit we: WalletExt): Future[CreatedCredDef] = {
    val configJson = ccd.revocationDetails.getOrElse(""""{"support_revocation": false}"""")
    Anoncreds.issuerCreateAndStoreCredentialDef(
      we.wallet,
      ccd.issuerDID,
      ccd.schemaJson,
      ccd.tag,
      ccd.sigType.getOrElse("CL"),
      configJson)
      .map(r => CreatedCredDef(r.getCredDefId, r.getCredDefJson))
  }

  def handleCreateCredOffer(cco: CreateCredOffer)(implicit we: WalletExt): Future[String] = {
    Anoncreds.issuerCreateCredentialOffer(we.wallet, cco.credDefId)
  }

  def handleCreateCredReq(ccr: CreateCredReq)(implicit we: WalletExt): Future[CreatedCredReq] = {
    Anoncreds.proverCreateCredentialReq(
        we.wallet, ccr.proverDID, ccr.credOfferJson, ccr.credDefJson, ccr.masterSecretId)
      .map(r => CreatedCredReq(r.getCredentialRequestJson, r.getCredentialRequestMetadataJson))
  }

  def handleCreateCred(cc: CreateCred)(implicit we: WalletExt): Future[String] = {
    Anoncreds.issuerCreateCredential(we.wallet,
      cc.credOfferJson, cc.credReqJson, cc.credValuesJson, cc.revRegistryId, cc.blobStorageReaderHandle)
      .map(_.getCredentialJson)
  }

  def handleStoreCred(sc: StoreCred)(implicit we: WalletExt): Future[String] = {
    Anoncreds.proverStoreCredential(we.wallet,
      sc.credId, sc.credReqMetadataJson, sc.credJson, sc.credDefJson, sc.revRegDefJson)
  }

  def handleCredForProofReq(cfpr: CredForProofReq)(implicit we: WalletExt): Future[String] = {
    Anoncreds.proverGetCredentialsForProofReq(we.wallet, cfpr.proofRequest)
  }

  def handleCreateProof(cp: CreateProof)(implicit we: WalletExt): Future[String] = {
    Anoncreds.proverCreateProof(we.wallet,
      cp.proofRequest, cp.usedCredentials, cp.masterSecret,
      cp.schemas, cp.credentialDefs, cp.revStates)
  }
}
