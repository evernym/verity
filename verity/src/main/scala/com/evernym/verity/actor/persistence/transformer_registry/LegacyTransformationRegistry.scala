package com.evernym.verity.actor.persistence.transformer_registry

import com.evernym.verity.actor.persistence.object_code_mapper.{DefaultObjectCodeMapper, DEPRECATED_StateCodeMapper, ObjectCodeMapperBase}
import com.evernym.verity.actor.{DeprecatedEventMsg, DeprecatedStateMsg}
import com.evernym.verity.transformations.transformers.<=>
import com.evernym.verity.transformations.transformers.legacy._

/**
 * unless we 're-persist' existing events with "new transformer" we can't deprecate this.
 * Once we implement snapshots for "all" persistent actors and
 * enable "new transformer" for events of that actor at the same time,
 * then we may re-assess the situation to determine if there is a way to deprecate this.
 *
 */
trait LegacyTransformationRegistry { this: HasTransformationRegistry =>

  val legacyEventObjectMapper: ObjectCodeMapperBase = DefaultObjectCodeMapper
  val legacyStateObjectMapper: ObjectCodeMapperBase = DEPRECATED_StateCodeMapper

  lazy val legacyEventTransformer: Any <=> DeprecatedEventMsg =
    createLegacyEventTransformer(persistenceEncryptionKey, legacyEventObjectMapper)

  lazy val legacyStateTransformer: Any <=> DeprecatedStateMsg =
    createLegacyStateTransformer(persistenceEncryptionKey, legacyStateObjectMapper)

  /**
   * These constants should only be used to lookup legacy event/state transformers
   */
  val LEGACY_PERSISTENT_OBJECT_TYPE_EVENT = "event"
  val LEGACY_PERSISTENT_OBJECT_TYPE_STATE = "state"

  /**
   * Transformation id 0 was used for 'event' and 'state' transformations both.
   * Now, in this new transformation logic, we want to lookup different transformers by just a unique id
   * Hence, we are separating out legacy 'event' and 'state' transformer by assigning them
   * a negative unique id to make the transformationRegistry logic work.
   */
  val LEGACY_EVENT_TRANSFORMATION_ID: Int = - 1
  val LEGACY_STATE_TRANSFORMATION_ID: Int = - 2

}
