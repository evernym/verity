package com.evernym.verity.transformations.transformers.legacy

import com.evernym.verity.actor.DeprecatedEventMsg
import com.evernym.verity.transformations.transformers.<=>
import com.google.protobuf.ByteString

/**
 * transforms given data into a generic proto buf message wrapper (PersistentEventMsg) and vice versa
 *
 * @param transformationId transformation id to be stored in persisted data
 *                         which will be used in reversing/undo the transformation
 */
class LegacyEventPersistenceTransformer(transformationId: Int)
  extends (TransParam[Array[Byte]] <=> DeprecatedEventMsg) {

  override val execute: TransParam[Array[Byte]] => DeprecatedEventMsg = { param =>
    DeprecatedEventMsg(transformationId, param.codeReq, ByteString.copyFrom(param.msg))
  }

  override val undo: DeprecatedEventMsg => TransParam[Array[Byte]] = { param =>
    val byteArray = param.data.toByteArray
    TransParam(byteArray, Option(param.typeCode))
  }

}
