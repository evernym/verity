This package contains integration test which spins up verity platform (with http server)
and tests the complete flow (including agent actors and protocol actors).

Notes:
* The 'LocalVerity' used in these specs uses:
  * leveldb for 
    * event journal
    * segment storage (mock for S3)  
  * local storage for snapshot  
  * file based wallet storage
  * 'in-memory' mocked ledger  
<br/>

* It may not suppose these services (unless we add some mocked support or something):
  * SMS service 
  * UrlShortening service  
<br/>

* The 'Sdk' used in these specs:
  * just mimics sdk operations (it is not a real SDK and hence doesn't test any real SDK)

<br/>

To be aware of:
  * **For multi node cluster**
    * load balancing of sdk requests to different available verity nodes is handled on 
      sdk side to choose verity url in a way that each time it has to send request, 
      it selects a new available verity node url
    * when more than one actor system tries to use same 
      leveldb storage, it causes few issues (like file locking etc). 
        * To avoid those, we are using _PersistencePluginProxy_ to share the leveldb journal/snapshot storage across multiple nodes.
        * At this point, we don't know any known issues around it which may create issues in writing tests against it in the future.
        
  * There is a support (in _VerityProviderBaseSpec_) to spin up multi node cluster (more than one node), 
    although so far we don't have tests to prove that `graceful shutdown of a node` and/or 
    `restart of a node` doesn't make the cluster unresponsive.
      
    