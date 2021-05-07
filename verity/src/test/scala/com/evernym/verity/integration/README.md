This package contains integration test which spins up verity platform (with http server)
and tests the complete flow (including agent actors and protocol actors).

Notes:
* The 'LocalVerity' used in these specs uses:
  * mock 'in-memory' ledger
  * file based wallet storage

<br/>

* The 'Sdk' used in these specs:
  * just mimics sdk operations (it is not a real SDK and hence doesn't test any real SDK)