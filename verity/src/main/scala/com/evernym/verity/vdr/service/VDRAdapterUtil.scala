package com.evernym.verity.vdr.service

import com.evernym.vdrtools.vdr.VdrParams.CacheOptions
import com.evernym.vdrtools.vdr.VdrResults
import com.evernym.verity.did.{DidStr, VerKeyStr}
import com.evernym.verity.vdr._
import org.json.JSONObject

// implementation specific objects to interface objects converters
object VDRAdapterUtil {

  def buildPreparedTxn(vdrTxn: VdrResults.PreparedTxnResult): PreparedTxn = {
    PreparedTxn(
      vdrTxn.getNamespace,
      vdrTxn.getSignatureSpec,
      vdrTxn.getTxnBytes,
      vdrTxn.getBytesToSign,
      vdrTxn.getEndorsementSpec
    )
  }

  def buildSchema(fqSchemaId: FqSchemaId, vdrSchema: VdrSchema): Schema = {
    Schema(
      fqSchemaId,
      vdrSchema
    )
  }

  def buildCredDef(fqCredDefId: FqCredDefId, vdrCredDef: VdrCredDef): CredDef = {
    val json = new JSONObject(vdrCredDef)
    CredDef(
      fqCredDefId,
      json.getString("schemaId"),
      vdrCredDef
    )
  }

  def buildDidDoc(vdrDidDoc: VdrDid): DidDoc = {
    //TODO: cheqd did will probably have another format
    val json = new JSONObject(vdrDidDoc)
    DidDoc(
      json.getString("id"),
      json.getString("verkey"),
      None
    )
  }

  def buildPingResult(vdrPingResult: Map[Namespace, VdrResults.PingResult]): PingResult = {
    PingResult(vdrPingResult.view.mapValues(e => LedgerStatus(e.isSuccessful)).toMap)
  }
  
  def buildVDRSchemaParams(schemaJson: String,
                           fqSchemaId: FqSchemaId): TxnSpecificParams = {
    val json = new JSONObject(schemaJson)
    json.put("id", fqSchemaId)
    json.toString
  }

  def buildVDRCredDefParams(credDefJson: String,
                            fqCredDefId: FqCredDefId): TxnSpecificParams = {
    val json = new JSONObject(credDefJson)
    json.put("id", fqCredDefId)
    json.toString
  }

  def buildVDRPreparedTxn(txn: PreparedTxn): TxnDataHolder = {
    TxnDataHolder(
      txn.namespace,
      txn.txnBytes,
      txn.signatureSpec
    )
  }

  def buildVDRCache(cache: CacheOption): CacheOptions = {
    new CacheOptions(
      cache.noCache,
      cache.noStore,
      cache.noUpdate,
      cache.minFresh
    )
  }
}

case class TxnDataHolder(namespace: Namespace,
                         txnBytes: Array[Byte],
                         signatureSpec: String)
