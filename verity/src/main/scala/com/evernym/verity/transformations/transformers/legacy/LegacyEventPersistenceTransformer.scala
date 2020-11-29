package com.evernym.verity.transformations.transformers.legacy

import com.evernym.verity.actor.PersistentEventMsg
import com.evernym.verity.transformations.transformers.<=>
import com.google.protobuf.ByteString

/**
 * transforms event into a generic proto buf message wrapper and vice versa
 *
 * @param transformationId transformation id to be stored in persisted data
 *                         which will be used in reversing/undo the transformation
 */
class LegacyEventPersistenceTransformer(transformationId: Int)
  extends (TransParam[Array[Byte]] <=> PersistentEventMsg) {

  override val execute: TransParam[Array[Byte]] => PersistentEventMsg = { param =>
    PersistentEventMsg(transformationId, param.codeReq, ByteString.copyFrom(param.msg))
  }

  override val undo: PersistentEventMsg => TransParam[Array[Byte]] = { param =>
    val byteArray = param.data.toByteArray
    TransParam(byteArray, Option(param.typeCode))
  }

}
