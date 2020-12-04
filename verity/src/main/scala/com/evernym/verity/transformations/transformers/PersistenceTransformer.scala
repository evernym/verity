package com.evernym.verity.transformations.transformers

import com.evernym.verity.actor.PersistentMsg
import com.google.protobuf.ByteString

/**
 * transforms data (event/snapshot) into a generic proto buf message wrapper and vice versa
 * generally there shouldn't be a need to version this transformer.
 *
 *  NOTE: assumption is that this transformer won't change ever
 *
 * @param transformationId transformation id to be stored in persisted data
 *                         which will be used in reversing/undo the transformation
 */
class PersistenceTransformer(transformationId: Int) extends (Array[Byte] <=> PersistentMsg) {

  override val execute: Array[Byte] => PersistentMsg = { msg =>
    PersistentMsg(transformationId,  ByteString.copyFrom(msg))
  }

  override val undo: PersistentMsg => Array[Byte] = { pd =>
    pd.data.toByteArray
  }
}
