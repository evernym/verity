package com.evernym.verity.actor.typed.base

import akka.persistence.typed.SnapshotAdapter
import com.evernym.verity.actor.PersistentMsg
import com.evernym.verity.actor.persistence.object_code_mapper.ObjectCodeMapperBase


//does state transformation (serialization, encryption etc) during persistence
// and un-transformation during recovery
case class PersistentStateAdapter[S](encryptionKey: String,
                                     objectCodeMapper: ObjectCodeMapperBase)
  extends SnapshotAdapter[S]
    with PersistentAdapterBase {

  override def toJournal(state: S): Any = {
    persistenceTransformer.execute(state)
  }

  override def fromJournal(from: Any): S = {
    from match {
      case pm: PersistentMsg =>
        lookupTransformer(pm.transformationId)
          .undo(pm)
          .asInstanceOf[S]
    }
  }
}
