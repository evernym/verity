
### General

* one protocol per container
* state is represented by an immutable object.
* the only way into a protocol is through messages
* the only way out of a protocol is through messages, and calls to Protocol Services
* protocol services has only two services: Records Events, Sends Messages (includes driver updates)
* protocols only deal with native messages (no encryption/decryption, packing/unpacking, serialization/deserialization).

### protocol containers
* We have two types of containers to begin with: Simple Container and Actor Based container.
* We should try to move common code between these two into a base classes

### self contained
* protocol should be self contained.
* we should be able to drop any protocol during runtime without requiring any other changes.
* so, basically, protocol code should not refer anything outside of the protocol directory, 
below are few such known concerns which we'll have to solve before we claim our protocols are self contained:
  * protocol events serialization and dependency of adding the event code mapping in DefaultEventSerializer.
  * protocol code references to constants, exceptions or any other utility code outside   