package com.evernym.verity.transformations.transformers.legacy

import com.evernym.verity.actor.PersistentStateMsg
import com.evernym.verity.transformations.transformers.<=>
import com.google.protobuf.ByteString

/**
 * transforms state (snapshot) into a generic proto buf message wrapper and vice versa
 *
 * @param transformationId transformation id to be stored in persisted data
 *                         which will be used in reversing/undo the transformation
 */
class LegacyStatePersistenceTransformer(transformationId: Int)
  extends (TransParam[Array[Byte]] <=> PersistentStateMsg) {

  override val execute: TransParam[Array[Byte]] => PersistentStateMsg = { param =>
    PersistentStateMsg(transformationId, param.codeReq, ByteString.copyFrom(param.msg))
  }

  override val undo: PersistentStateMsg => TransParam[Array[Byte]] = { param =>
    val byteArray = param.data.toByteArray
    TransParam(byteArray, Option(param.typeCode))
  }

}
