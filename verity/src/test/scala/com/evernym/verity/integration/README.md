This package contains integration test which spins up verity platform (with http server)
and does test complete flow (including agent actors and protocol actors).

Notes:
* The 'LocalVerity' used in these specs uses:
  * mock 'in-memory' ledger
  * file based wallet storage
    

* The 'Sdk' used in these specs:
  * just mimics sdk operations (it doesn't test any real SDK)