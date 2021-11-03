package com.evernym.verity.protocol.engine

import scala.util.Try

package object asyncapi {
  type AsyncOpCallbackHandler[T] = Try[T] => Unit
}
