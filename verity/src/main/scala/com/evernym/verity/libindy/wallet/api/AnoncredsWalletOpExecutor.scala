package com.evernym.verity.libindy.wallet.api

import com.evernym.verity.libindy.wallet.api.LedgerWalletOpExecutor.asScalaFuture
import com.evernym.verity.vault.WalletExt
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor.wallet._
import org.hyperledger.indy.sdk.anoncreds.Anoncreds
import org.hyperledger.indy.sdk.anoncreds.AnoncredsResults.IssuerCreateAndStoreCredentialDefResult

import scala.concurrent.Future

object AnoncredsWalletOpExecutor {

  def handleCreateMasterSecret(cms: CreateMasterSecret)(implicit we: WalletExt): Future[String] = {
    asScalaFuture {
      Anoncreds.proverCreateMasterSecret(we.wallet, cms.masterSecretId)
    }
  }

  def handleCreateCredDef(ccd: CreateCredDef)(implicit we: WalletExt): Future[IssuerCreateAndStoreCredentialDefResult] = {
    asScalaFuture {
      val configJson = ccd.revocationDetails.getOrElse(""""{"support_revocation": false}"""")
      Anoncreds.issuerCreateAndStoreCredentialDef(
        we.wallet,
        ccd.issuerDID,
        ccd.schemaJson,
        ccd.tag,
        ccd.sigType.getOrElse("CL"),
        configJson
      )
    }
  }

  def handleCreateCredOffer(cco: CreateCredOffer)(implicit we: WalletExt): Future[String] = {
    asScalaFuture {
      Anoncreds.issuerCreateCredentialOffer(we.wallet, cco.credDefId)
    }
  }

  def handleCreateCredReq(ccr: CreateCredReq)(implicit we: WalletExt): Future[String] = {
    asScalaFuture {
      Anoncreds.proverCreateCredentialReq(
        we.wallet, ccr.proverDID, ccr.credOfferJson, ccr.credDefJson, ccr.masterSecretId)
    }.map(_.getCredentialRequestJson)
  }

  def handleCreateCred(cc: CreateCred)(implicit we: WalletExt): Future[String] = {
    asScalaFuture {
      Anoncreds.issuerCreateCredential(we.wallet,
        cc.credOfferJson, cc.credReqJson, cc.credValuesJson, cc.revRegistryId, cc.blobStorageReaderHandle)
    }.map(_.getCredentialJson)
  }

  def handleCredForProofReq(cfpr: CredForProofReq)(implicit we: WalletExt): Future[String] = {
    asScalaFuture {
      Anoncreds.proverGetCredentialsForProofReq(we.wallet, cfpr.proofRequest)
    }
  }

  def handleCreateProof(cp: CreateProof)(implicit we: WalletExt): Future[String] = {
    asScalaFuture {
      Anoncreds.proverCreateProof(we.wallet,
        cp.proofRequest, cp.usedCredentials, cp.masterSecret,
        cp.schemas, cp.credentialDefs, cp.revStates)
    }
  }
}