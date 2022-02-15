package com.evernym.verity.vdr.service

import com.evernym.vdrtools.vdr.VdrParams.CacheOptions
import com.evernym.vdrtools.vdr.VdrResults
import com.evernym.verity.vdr._
import org.json.JSONObject

//currently only contains data transformation related functions
// todo : add fq names conversion
object VDRAdapterUtil {

  //implementation specific objects to interface objects converters

  def buildPreparedTxn(vdrTxn: VdrResults.PreparedTxnResult): PreparedTxn = {
    PreparedTxn(
      vdrTxn.getNamespace,
      vdrTxn.getSignatureSpec,
      vdrTxn.getTxnBytes,
      vdrTxn.getBytesToSign,
      vdrTxn.getEndorsementSpec
    )
  }

  def buildSchema(fqSchemaId: FQSchemaId, vdrSchema: VdrSchema): Schema = {
    //TODO: why the "id" field inside the `vdrSchema' json string is not fully qualified?
    Schema(
      fqSchemaId,
      vdrSchema
    )
  }

  def buildCredDef(fqCredDefId: FQCredDefId, vdrCredDef: VdrCredDef): CredDef = {
    //TODO: check if "id" field inside the `vdrCredDef' json string is fully qualified?
    //TODO: check if "schemaId" field inside the `vdrCredDef' json string is fully qualified?

    val json = new JSONObject(vdrCredDef)
    CredDef(
      fqCredDefId,
      json.getString("schemaId"),
      vdrCredDef
    )
  }

  def buildDidDoc(vdrDidDoc: VdrDid): DidDoc = {
    // todo cheqd did will probably have another format
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
                           fqSchemaId: FQSchemaId): TxnSpecificParams = {
    val json = new JSONObject(schemaJson)
    json.put("id", fqSchemaId)
    json.toString
  }

  def buildVDRCredDefParams(credDefJson: String,
                            fqCredDefId: FQCredDefId): TxnSpecificParams = {
    val json = new JSONObject(credDefJson)
    json.put("id", fqCredDefId)
    json.toString
  }

  //interface objects to implementation specific objects converters

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
