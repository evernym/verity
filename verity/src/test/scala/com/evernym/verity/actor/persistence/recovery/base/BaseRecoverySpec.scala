package com.evernym.verity.actor.persistence.recovery.base

import com.evernym.verity.actor.persistence.PersistentActorDetail

trait BaseRecoverySpec extends BasePersistentStore {

  def assertPersistentActorDetail(pad: PersistentActorDetail,
                                  expectedPersistenceId: PersistenceIdParam,
                                  expectedRecoveredEvents: Int): Unit = {
    assertPersistentActorDetail(pad, expectedPersistenceId.toString, expectedRecoveredEvents)
  }

  def assertPersistentActorDetail(pad: PersistentActorDetail,
                                  expectedPersistenceId: String,
                                  expectedRecoveredEvents: Int): Unit = {

    //NOTE: this below persistence Id check should NEVER fail
    // if fails, means some logic around how 'persistenceId' is calculated is changed in
    // main code (mostly in BasePersistentActor or its super class)
    // and that means it won't be able to recover previously persisted actor anymore.
    // so we should either revert back the related main code code changes
    // or it should be discussed with team and then decide how to move forward.
    pad.persistenceId shouldBe expectedPersistenceId
    pad.totalRecoveredEvents shouldBe expectedRecoveredEvents
  }

}
