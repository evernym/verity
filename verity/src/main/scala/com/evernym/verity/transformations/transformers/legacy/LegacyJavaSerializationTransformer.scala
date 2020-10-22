package com.evernym.verity.transformations.transformers.legacy

import com.evernym.verity.actor.serializers.JavaSerializer
import com.evernym.verity.transformations.transformers.<=>

/**
 * serializes/deserializes given object/data (java serialization)
 */
object LegacyJavaSerializationTransformer
  extends (TransParam[Any] <=> TransParam[Array[Byte]]) {

  override val execute: TransParam[Any] => TransParam[Array[Byte]] = { param =>
    param.copy(msg = JavaSerializer.serialise(param.msg))
  }

  override val undo: TransParam[Array[Byte]] => TransParam[Any] = { param =>
    param.copy(msg = JavaSerializer.deserialise(param.msg))
  }
}
