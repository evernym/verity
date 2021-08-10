package com.evernym.verity.did.exception

trait DidException

class InvalidDidKeyFormatException(keyStr: String)
  extends RuntimeException(s"unable to parse received key: $keyStr") with DidException
