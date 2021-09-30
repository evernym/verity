## Glossary
  * VDR: [Verifiable Data Registry](https://gitlab.com/evernym/verity/vdr-tools)
  
# VDRAdapter interfaces
  * to be used by verity code to interact with VDR Tools API
  * works with only fully qualified DIDs
  * all the input/output parameter would be verity specific (should **NOT depend** on VDR wrapper api objects at the interface level) 
  * see more detail about input/output parameter at the end of this file(Input/Output param details)
  * input/output **may change** until finalized on VDR side


### Read APIs (To be finalized once corresponding api in VDRTools is finalized)
* **resolveDID**(fqdid: DidStr, cacheOpts: CacheOpt): **Future[DIDDoc]**
  * Purpose
    * To retrieve/resolve DID Doc for given FQ DID from corresponding ledger
  * Input
    * fqdid: Fully qualified DID
    * cacheOpts: CacheOpt
  * Output
    * **DIDDoc**

<br/>

* **resolveSchema**(fqs, cacheOpts): **Future[Schema]**
  * Purpose
    * To retrieve schema for given FQ schema Id
  * Input
    * fqs: Fully qualified schema id
    * cacheOpts: cache options
  * Output
    * **Schema**

<br/>

* **resolveCredDef**(fqc, cacheOpts): **Future[CredDef]**
  * Purpose
    * To retrieve cred def for given FQ cred def Id
  * Input
    * fqc: Fully qualified cred definition id
    * cacheOpts: cache options
  * Output
      * **CredDef**


### Write APIs (more or less finalized)

* **prepareDidTxn**(didDocJson: String, submitterDID: DidStr, endorser: Option[String]): **Future[PreparedTxn]**
  * Purpose
    * To build/prepare schema transaction
  * Input
    * didDocJson: didDoc json string
    * submitterDID: transaction author DID (issuer DID)
    * endorser: optional, a mechanism to bind transaction with endorser
                (for indy: it would be endorser's DID, for cheqd: not applicable now)
  * Output
    * **PreparedTxn**: VDR prepared transaction

<br/>

* **prepareSchemaTxn**(schemaJson: String, fqSchemaId: String, submitterDID: DidStr, endorser: Option[String]): **Future[PreparedTxn]**
  * Purpose
    * To build/prepare schema transaction
  * Input
    * schemaJson: schema json string
    * fqSchemaId: fully qualified schema id
    * submitterDID: transaction author DID (issuer DID) 
    * endorser: optional, a mechanism to bind transaction with endorser 
                (for indy: it would be endorser's DID, for cheqd: not applicable now)
  * Output
    * **PreparedTxn**: VDR prepared transaction
    
<br/>

* **prepareCredDefTxn**(credDefJson: String, fqCredDefId: String, submitterDID: DidStr, endorser: Option[String]): **Future[PreparedTxn]**
  * Purpose
    * To build, sign and submit the 'write' schema transaction
  * Input
    * credDefJson: cred def json
    * fqCredDefId: fully qualified cred def id
    * submitterDID: transaction author DID (issuer DID)
    * endorser: optional, a mechanism to bind transaction with endorser
                (for indy: it would be endorser's DID, for cheqd: not applicable now)
  * Output
    * **PreparedTxn**: VDR prepared transaction

<br/>

* **submitTxn**(preparedTxn: PreparedTxn, signature: Array[Byte], endorsement: Array[Byte]): **Future[Status]**
  * Purpose
    * Submits the given transaction
  * Input
    * preparedTxn: same prepared txn returned by `prepare...Txn` apis
    * signature: bytes representation of the author's signature
    * endorsement: can be empty array otherwise it would be ledger specific
  * Output
    * **Status** (VDR txn status)


#### Input/Output param details
* **DidStr**: Fully qualified DID string


<br/>

* **DIDDoc** (To be finalized)


<br/>

* **CacheOpt**(noCache: Boolean, noUpdate: Boolean, noStore: Boolean, minFresh: Int)
  * noCache: Skip usage of cache (false by default)
  * noUpdate: Use only cached data, do not update the cache (false by default)
  * noStore: Skip storing fresh data if updated (false by default)
  * minFresh: Return cached data if not older than this many seconds, -1 means do not check age (-1 by default)

<br/>

* **Schema**(fqname: String, type: String, payload: Array[Byte])
  * fqname: Fully qualified schema name (for example: did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM:2:npdb:4.3.4)
  * type: type of the schema (for example: Indy, W3C etc.?)
  * payload: schema bytes
  
<br/>

* **CredDef**(fqname: String, payload: Array[Byte], fqschema: String)
  * fqname: Fully qualified cred def name (for example: did:indy:sovrin:5nDyJVP1NrcPAttP3xwMB9:3:CL:56495:npdb)
  * payload: cred def bytes
  * fqschema: Fully qualified schema name (for example: did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM:2:npdb:4.3.4) 

<br/>

* **PreparedTxn**(context: String, signatureSpec: SignatureSpec, bytesToSign: Array[Byte], endorsementSpec: EndorsementSpec)
  * context: opaque string, for now this most likely will contain just ledger id
  * signatureSpec: specifications about signing process (eg. what algorithm to use, length of signature 
                   and any other information to generalize the signing process). This should be agnostic to ledger 
  * bytesToSign: actual data that needs to be signed
  * endorsementSpec: specification about endorsing process, which is ledger type specific 
                     It may also contain type None

<br/>

* **Endorsement**
  * Indy: just endorser signature
  * Cheqd: payment parameters and account signature
