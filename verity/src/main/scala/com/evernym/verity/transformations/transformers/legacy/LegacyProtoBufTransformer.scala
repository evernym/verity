package com.evernym.verity.transformations.transformers.legacy

import com.evernym.verity.actor.persistence.object_code_mapper.{DefaultObjectCodeMapper, DefaultStateCodeMapper, ObjectCodeMapperBase}
import com.evernym.verity.transformations.transformers.<=>
import scalapb.GeneratedMessage

/**
 * serializes/deserializes scala PB generated message
 * @param objectCodeMapper a mapping between the object (scala PB generated message) and an unique code assigned to it
 */
class LegacyProtoBufTransformer(objectCodeMapper: ObjectCodeMapperBase)
  extends (Any <=> TransParam[Any]) {

  override val execute: Any => TransParam[Any] = { msg =>
    val serialized = msg.asInstanceOf[GeneratedMessage].toByteArray
    TransParam(msg = serialized, code = Option(objectCodeMapper.codeFromObject(msg)))
  }

  override val undo: TransParam[Any] => Any = { param =>
    objectCodeMapper.objectFromCode(param.codeReq, param.msg.asInstanceOf[Array[Byte]])
  }

}

object LegacyEventProtoBufTransformer extends LegacyProtoBufTransformer(DefaultObjectCodeMapper)

object LegacyStateProtoBufTransformer extends LegacyProtoBufTransformer(DefaultStateCodeMapper)
