package com.evernym.verity.actor.persistence.recovery.agent

import com.evernym.verity.actor.cluster_singleton.{ForKeyValueMapper, GetValue}
import com.evernym.verity.actor.persistence.{GetPersistentActorDetail, PersistentActorDetail}
import com.evernym.verity.actor.persistence.recovery.base.BaseRecoverySpec
import com.evernym.verity.actor.persistence.recovery.base.eventSetter.legacy.AgencyAgentEventSetter
import com.evernym.verity.constants.Constants.AGENCY_DID_KEY

class AgencyAgentRecoverySpec
   extends BaseRecoverySpec
     with AgencyAgentEventSetter {

  override def beforeAll(): Unit = {
    super.beforeAll()
    setupBasicAgencyAgent()
  }

  "KeyValueMapper actor" - {
    "when sent GetActorDetail" - {
      "should respond with proper actor detail" in {
        platform.singletonParentProxy ! ForKeyValueMapper(GetPersistentActorDetail)
        val ad = expectMsgType[PersistentActorDetail]
        assertPersistentActorDetail(ad, keyValueMapperPersistenceId, 1)
      }
    }
    "when sent GetValue for AGENCY_DID" - {
      "should respond with proper agency DID" in {
        platform.singletonParentProxy ! ForKeyValueMapper(GetValue(AGENCY_DID_KEY))
        val agencyDID = expectMsgType[Option[String]]
        agencyDID.contains(myAgencyAgentDIDPair.DID) shouldBe true
      }
    }
  }

  "AgencyAgent actor" - {
    "when sent GetActorDetail" - {
      "should respond with correct detail" in {
        aaRegion ! GetPersistentActorDetail
        val ad = expectMsgType[PersistentActorDetail]
        assertPersistentActorDetail(ad, myAgencyAgentPersistenceId, basicAgencyAgentEvents.size)
      }
    }
  }
}
