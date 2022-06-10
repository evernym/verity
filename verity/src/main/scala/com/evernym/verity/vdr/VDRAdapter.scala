package com.evernym.verity.vdr

import com.evernym.verity.did.VerKeyStr
import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccess.SIGN_ED25519
import org.json.JSONObject

import scala.concurrent.Future
import scala.util.{Success, Try}

//interface to be used by verity code to interact with VDR/Ledger services
trait VDRAdapter {

  def ping(namespaces: List[Namespace]): Future[PingResult]

  def prepareDidTxn(didJson: String,
                    submitterDID: VdrDid,
                    endorser: Option[String]): Future[PreparedTxn]

  def prepareSchemaTxn(schemaJson: String,
                       fqSchemaId: FqSchemaId,
                       submitterDID: VdrDid,
                       endorser: Option[String]): Future[PreparedTxn]

  def prepareCredDefTxn(credDefJson: String,
                        fqCredDefId: FqCredDefId,
                        submitterDID: VdrDid,
                        endorser: Option[String]): Future[PreparedTxn]

  def submitTxn(preparedTxn: PreparedTxn,
                signature: Array[Byte],
                endorsement: Array[Byte]): Future[SubmittedTxn]

  def resolveSchema(schemaId: FqSchemaId, cacheOption: Option[CacheOption]=None): Future[Schema]

  def resolveCredDef(credDefId: FqCredDefId, cacheOption: Option[CacheOption]=None): Future[CredDef]

  def resolveDID(fqDid: FqDID, cacheOption: Option[CacheOption]=None): Future[DidDoc]
}


case class LedgerStatus(reachable: Boolean)

case class PingResult(status: Map[Namespace, LedgerStatus])

case class PreparedTxn(namespace: Namespace,
                       signatureSpec: SignatureSpec,
                       txnBytes: Array[Byte],
                       bytesToSign: Array[Byte],
                       endorsementSpec: EndorsementSpec) {

  def signatureType: String = if (signatureSpec == "") SIGN_ED25519 else signatureSpec

  def isEndorsementSpecTypeIndy: Boolean = endorsementSpecType == Success("Indy")

  def endorsementSpecType: Try[String] = Try{
    val jsonObject = new JSONObject(endorsementSpec)
    jsonObject.getString("type")
  }
}

case class SubmittedTxn(response: String)

case class Schema(fqId: FqSchemaId, json: String)

case class CredDef(fqId: FqCredDefId, fqSchemaId: FqSchemaId, json: String)

case class DidDoc(fqId: FqDID, verKey: VerKeyStr, endpoint: Option[String])

/**
 * see details here: https://gitlab.com/evernym/verity/vdr-tools/-/blob/main/libvdrtools/include/indy_cache.h#L22-25
 *
 * @param noCache Skip usage of cache
 * @param noUpdate Use only cached data, do not try to update
 * @param noStore Skip storing fresh data if updated,
 * @param minFresh Return cached data if not older than this many seconds. -1 means do not check age
 */
case class CacheOption(noCache: Boolean, noUpdate: Boolean, noStore: Boolean, minFresh: Int)

object CacheOption {

  //TODO (VE-3368): finalize the default values
  def default: CacheOption = CacheOption(noCache = true, noUpdate = true, noStore = true, minFresh = -1)
}