There are different types of tests in this verity package.

1. General unit/functional tests 
    * Most of the packages inside `com.evernym.verity` belongs to this category. 

<br/>

2. Specific unit/functional tests which depends on existing data (events/snapshots).
   * All tests inside `com.evernym.verity.actor.persistence.recovery` belongs to this category  

<br/>

3. Protocol tests (just tests the protocol code with below mentioned containers)
    * InMemoryProtocolContainer (`com.evernym.verity.protocol.protocols`)
    * ActorProtocolContainer (`com.evernym.verity.protocol.container.actor`)  

<br/>

4. Integration tests
    * All tests inside `com.evernym.verity.integration` belongs to this category. 
    * Uses 'LocalVerity' to do integration testing which uses routing actors, 
      agent actors, protocol actors etc.
    * We can spin-up multiple verity instances and test message flow between them.  
    
