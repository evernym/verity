package com.evernym.verity.actor.persistence.recovery.base

import com.evernym.verity.actor.persistence.ActorDetail

trait BaseRecoverySpec extends BasePersistentStore {

  def assertActorDetail(ad: ActorDetail,
                        expectedPersistenceId: PersistenceIdParam,
                        expectedRecoveredEvents: Int): Unit = {
    assertActorDetail(ad, expectedPersistenceId.toString, expectedRecoveredEvents)
  }

  def assertActorDetail(ad: ActorDetail,
                        expectedPersistenceId: String,
                        expectedRecoveredEvents: Int): Unit = {

    //NOTE: this below persistence Id check should NEVER fail
    // if fails, means some logic around how 'persistenceId' is calculated is changed in
    // main code (mostly in BasePersistentActor or its super class)
    // and that means it won't be able to recover previously persisted actor anymore.
    // so we should either revert back the related main code code changes
    // or it should be discussed with team and then decide how to move forward.
    ad.persistenceId shouldBe expectedPersistenceId
    ad.totalRecoveredEvents shouldBe expectedRecoveredEvents
  }

}
