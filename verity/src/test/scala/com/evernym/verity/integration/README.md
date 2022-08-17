This package contains integration tests which spins up verity platform (with http server)
and tests the complete flow (including agent actors and protocol actors).

Notes:
* The 'LocalVerity' used in these specs uses:
  * `leveldb` for 
    * event journal
    * segment storage  
  * `local storage` snapshot store  
  * `file based` wallet storage
  * `in-memory` mocked ledger service
  * mocked url shortener (it returns the same long url)
  * mocked endorser service
<br/>


* It does not support below mentioned services (unless we add some mocked support or something):
  * SMS service
<br/>

* The 'Sdk' used in these specs:
  * just mimics sdk operations (it is not a real SDK and hence doesn't test any real SDK)

<br/>

To be aware of:
  * **For multi node cluster testing (more than 1 node)**
    * load balancing of sdk requests to different available verity nodes is handled on 
      sdk side to choose verity url in a way that each time it has to send a request 
      it selects a new available verity node url.
    * when more than one actor system tries to use same 
      leveldb/snapshot storage, it causes few issues (like file locking etc). 
        * To avoid those, we are using _PersistencePluginProxy_ to share the leveldb journal/snapshot storage across multiple nodes.
        * At this point, we don't know any known issues around it which may create issues in writing tests against it in the future.
    * there are few global singleton objects which may/will cause some issues sooner or later.
