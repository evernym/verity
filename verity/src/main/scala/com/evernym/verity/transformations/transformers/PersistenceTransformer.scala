package com.evernym.verity.transformations.transformers

import com.evernym.verity.actor.PersistentData
import com.google.protobuf.ByteString

/**
 * transforms data (event/snapshot) into a generic proto buf message wrapper and vice versa
 *
 * @param transformationId transformation id to be stored in persisted data
 *                         which will be used in reversing/undo the transformation
 */
class PersistenceTransformer(transformationId: Int) extends (Array[Byte] <=> PersistentData) {

  override val execute: Array[Byte] => PersistentData = { msg =>
    PersistentData(transformationId,  ByteString.copyFrom(msg))
  }

  override val undo: PersistentData => Array[Byte] = { pd =>
    pd.data.toByteArray
  }
}
