package com.evernym.verity.actor.persistence.recovery.legacy.verity1.specific

import akka.testkit.EventFilter
import com.evernym.verity.actor.ForIdentifier
import com.evernym.verity.actor.base.{Done, Ping}
import com.evernym.verity.actor.persistence.recovery.base.{BaseRecoveryActorSpec, PersistenceIdParam}
import com.evernym.verity.actor.testkit.WithAdditionalLogs
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.CredSentState
import com.typesafe.config.{Config, ConfigFactory}

import java.util.UUID

class IssueCredSnapshotRecovery
  extends BaseRecoveryActorSpec {

  val persIdParam = PersistenceIdParam("issue-credential-1.0-protocol", UUID.randomUUID().toString)
  val credSentState = CredSentState("myPwDID", Option("theirPwDID"))
  val issueCredRegion = platform.protocolRegions(persIdParam.entityTypeName)

  "IssueCred Protocol Actor" - {

    "got snapshotted when credential is sent" in {
      addSnapshotToPersistentStorage(persIdParam, credSentState)
    }

    "when started" - {
      "should be successful" in {
        EventFilter.debug(pattern = ".*snapshot state received and applied \\(issue-credential\\[1.0\\]\\).*", occurrences = 1) intercept {
          issueCredRegion ! ForIdentifier(persIdParam.entityId, Ping(sendBackConfirmation = true))
          expectMsgType[Done.type]
        }
      }
    }
  }

  override def overrideSpecificConfig: Option[Config] = Option {
    ConfigFactory.parseString {
      """
         akka.loglevel = DEBUG
         akka.test.filter-leeway = 20s   # to make the event filter run for longer time
         akka.logging-filter = "com.evernym.verity.actor.testkit.logging.TestFilter"
        """
    }
  }
}
