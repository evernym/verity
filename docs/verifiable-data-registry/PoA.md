# Plan of Attack

## Config changes
Add new config section for VDR tools api integration
Once we are live with it, we'll remove the old/unused configuration/code

```
verity {
    ...

    # vdr-tools implementation specific config
    vdr-tools {
        library-dir-location = "/usr/lib"
        wallet {
            type = "mysql"
        }
    }

    # vdrs (ledgers) specific configs (irrespective of which vdr-tools implementation gets used)
    vdrs: [
        # there can be more than one ledger of same 'type'
        # it is just that the namespaces should be unique among all VDR entries in this section 
        {
            type = "indy-ledger"
            namespaces = ["indy:sovrin", "sov"]
            ...
        }
    ]
    ...
}
```


## Code changes

### Assumptions
1. VDRAdapter interface's input and output parameters **should NOT depend** on VDRTools api 
   input/output objects (like PreparedTxn, Status etc.)

### High level changes
1. Create `VDRAdapter` interface as defined in [vdr-interface.md](vdr-interface.md) file.

<br/>

2. Create `VDRActorAdapter` (implements `VDRAdapter` interface) in Platform
   * Constructor parameters
     * vdrToolsFactory: VDRToolsFactory
     * vdrToolsConfig: VDRToolsConfig
     * actorSystem: supplied from Platform
   * Implementation detail
     * creates instance of VDRActor as part of its initialization
     * should be a very thin layer which
       * asks/sends appropriate command to `VDRActor`
       * map actor's response to interface's response

<br/>

3. Create `VDRActor`, which
   * as part of initialization
     * uses the provided `vdrToolsFactory: VDRToolsFactory` and `vdrToolsConfig: VDRToolsConfig` to initialize VDRTools library 
     * registers all required ledgers
   * post initialization
     * it should be ready to serve all read/write commands

### Implementation detail

1. Create a typed actor/behavior `VDRActor` which will talk to the **given VDRTools**
   1. At the time of actor creation, it will initialize the VDRTools library and register all required ledgers.
   2. Define proper supervision strategy with appropriate logging.
   3. Post initialization, it will support following Read and Write commands for various objects (DidDoc, Schema, CredDef etc)

   ```
   sealed trait Cmd
       
   object Commands {
     sealed trait ReadCmd extends Cmd
     case class ResolveDID(fqd: DidStr, cacheOpt: CacheOpts, replyTo: ActorRef[Replies.ResolveDIDResp]) extends ReadCmd
     case class ResolveSchema(fqs: String, cacheOpt: CacheOpts, replyTo: ActorRef[Replies.ResolveSchemaResp]) extends ReadCmd
     case class ResolveCredDef(fqc: String, cacheOpt: CacheOpts, replyTo: ActorRef[Replies.ResolveCredDefResp]) extends ReadCmd
   
     sealed trait PrepareTxnCmd extends Cmd
     case class PrepareDidTxn(didDocJson: String, submitterDID: DidStr, endorser: Option[String], replyTo: ActorRef[Replies.PrepareTxnResp]) extends PrepareTxnCmd 
     case class PrepareSchemaTxn(schemaJson: String, fqSchemaId: String, submitterDID: DidStr, endorser: Option[String], replyTo: ActorRef[Replies.PrepareTxnResp]) extends PrepareTxnCmd
     case class PrepareCredDefTxn(credDefJson: String, fqCredDefId: String, submitterDID: DidStr, endorser: Option[String], replyTo: ActorRef[Replies.PrepareTxnResp]) extends PrepareTxnCmd
       
     case class SubmitTxn(preparedTxn: PreparedTxn, signature: Array[Byte], endorsement: Array[Byte], replyTo: ActorRef[Replies.SubmitTxnResp]) extends Cmd
   }
       
   trait Reply
   object Replies {
     case class PrepareTxnResp(resp: Try[PreparedTxn]) extends Reply
     case class SubmitTxnResp(resp: Try[Status]) extends Reply
       
     case class ResolveDIDResp(resp: Try[DidDoc]) extends Reply
     case class ResolveSchemaResp(resp: Try[Schema]) extends Reply
     case class ResolveCredDefResp(resp: Try[CredDef]) extends Reply
   }
   ```

2. Create `VDRActorAdapter`
   ```
   class VDRActorAdapter(vdrToolsFactory: VDRToolsFactory, 
                         vdrToolsConfig: VDRToolsConfig)
                        (implicit ec: ExecutionContext, system: ActorSystem) 
     extends VDRAdapter {
      val vdrActorRef = <spawn actor here>
   }
   
   type VDRToolsFactory = VDRToolsFactoryParam => VDRTools
   case class VDRToolsFactoryParam(libDirLocation: String)
   
   # below VDR trait will have two implementations:
   #   a. Really thin wrapper around VDRTools API for production code
   #   b. Some mock implementation for unit testing

   trait VDR {         //VDR apis interface
      def registerIndyLedger(...)
      def ping(...)
   
      def resolveDid(...)
      def resolveSchema(...)
      def resolveCredDef(...)
   
      def prepareDidTxn(...)
      def prepareSchemaTxn(...)
      def prepareCredDefTxn(...)
      def submitTxn(...)
   }

   case class VDRToolsConfig(libDirLocation: String, ledgers: List[Ledger])   
   sealed trait Ledger
   case class IndyLedger(namespaces: List[Namespace], genesisTxnFilePath: String, taaConfig: Option[TAAConfig]) 
     extends Ledger
   ```

3. In `platform`, create instance of VDRActorAdapter and inject it into 
   1. `AgentActors` 
   2. `ProtocolActors`
   3. `any other objects` who has to talk to VDR

4. In `AgentActors` and `ProtocolActors`, use supplied `vdrAdapter` for any ledger interaction purposes.

5. Migrate all existing ledger calls in verity code to use this new `vdrAdapter` 
   object in all `AgentActors` and `ProtocolActors` (and anywhere else too).