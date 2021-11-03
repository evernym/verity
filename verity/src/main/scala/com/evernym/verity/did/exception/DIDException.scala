package com.evernym.verity.did.exception

trait DIDException

class InvalidDidKeyFormatException(keyStr: String)
  extends RuntimeException(s"unable to parse received string: $keyStr") with DIDException

class InvalidDidSovFormatException(did: String)
  extends RuntimeException(s"unable to parse received string: $did into valid sovrin did") with DIDException

class UnableToIdentifyDIDMethodException(did: String)
  extends RuntimeException(s"Unable to identify DID method for did string: $did") with DIDException

class UnrecognizedDIDMethodException(did: String, method: String)
  extends RuntimeException(s"DID Method $method from DID $did is not recognized") with DIDException

class SubNameSpacesUnsupportedException(did: String)
  extends RuntimeException(s"Sub namespaces are not currently supported in Verity did:sov class. Received did: $did") with DIDException