There are different types of tests in this verity package.

1. General unit/functional tests 
    * Most of the packages inside `com.evernym.verity` contains unit/functional tests.


2. Protocol tests (just tests the protocol)
    * InMemoryProtocolContainer (`com.evernym.verity.protocol.protocols`)
    * ActorProtocolContainer (`com.evernym.verity.protocol.container.actor`)


3. Integration tests (`com.evernym.verity.integration`)
    * Uses 'LocalVerity' to do integration testing which uses routing actors, 
      agent actors, protocol actors etc.
    * We can spin-up multiple verity instances and test message flow between them.  
    
