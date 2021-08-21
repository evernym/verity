package com.evernym.verity.actor.typed.base

import akka.persistence.typed.{EventAdapter, EventSeq}
import com.evernym.verity.actor.PersistentMsg
import com.evernym.verity.actor.persistence.object_code_mapper.ObjectCodeMapperBase

//does event transformation (serialization, encryption etc) during persistence
// and un-transformation during recovery
case class PersistentEventAdapter[E](encryptionKey: String,
                                     objectCodeMapper: ObjectCodeMapperBase,
                                     eventEncryptionSalt: String)
  extends EventAdapter[E,PersistentMsg]
    with PersistentAdapterBase {

  override def toJournal(event: E): PersistentMsg = {
    persistenceTransformer.execute(event)
  }

  override def fromJournal(pm: PersistentMsg, manifest: String): EventSeq[E] = {
    EventSeq(scala.collection.immutable.Seq(
      lookupTransformer(pm.transformationId)
      .undo(pm)
      .asInstanceOf[E]
    ))
  }

  override def manifest(event: E): String = ""
}
