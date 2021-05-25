package com.evernym.verity.DID.DidException

trait DidException

class InvalidDidKeyFormatException(keyStr: String)
  extends RuntimeException(s"unable to parse received key: $keyStr") with DidException
