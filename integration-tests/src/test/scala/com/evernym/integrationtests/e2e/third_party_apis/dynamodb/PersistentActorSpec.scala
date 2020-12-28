package com.evernym.integrationtests.e2e.third_party_apis.dynamodb

import java.io.File

import akka.actor.{ActorRef, Props}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import com.evernym.verity.actor.base.{Done, Ping}
import com.evernym.verity.actor.persistence.object_code_mapper.ObjectCodeMapperBase
import com.evernym.verity.actor.{ActorMessageClass, ActorMessageObject, ForIdentifier, MockEvent4, MockState, ShardIdExtractor, ShardUtil}
import com.evernym.verity.actor.persistence.{BasePersistentActor, DefaultPersistenceEncryption, SnapshotterExt}
import com.evernym.verity.actor.testkit.ActorSpec
import com.evernym.verity.actor.testkit.checks.IgnoreAkkaEvents
import com.evernym.verity.config.AppConfig
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.{Config, ConfigFactory}
import scalapb.GeneratedMessageCompanion


class PersistentActorSpec
  extends ActorSpec
    with BasicSpec {

  val MOCK_ACTOR_TYPE_NAME = "MockPersistentActor"
  val MOCK_ACTOR_1 = 4

  "MockPersistentActor" - {
    "when sent PersistEvent command" - {
      "should persist event and save snapshot" taggedAs IgnoreAkkaEvents in {
        sendToMockActor(MOCK_ACTOR_1, Ping(sendBackConfirmation = true))
        expectMsgType[Done.type]

        //send first event which should force snapshot
        forceSnapshot(MOCK_ACTOR_1)

        //send second event which should force snapshot
        forceSnapshot(MOCK_ACTOR_1)

        //send third event which should force snapshot
        forceSnapshot(MOCK_ACTOR_1)  //because keep snapshots size is 2

        //send fourth event which should force snapshot
        forceSnapshot(MOCK_ACTOR_1)  //because keep snapshots size is 2

        //send fifth event which should force snapshot
        forceSnapshot(MOCK_ACTOR_1)  //because keep snapshots size is 2
      }
    }
  }

  def forceSnapshot(entityId: Int): Unit = {
    sendToMockActor(entityId, PersistEvent(Array.range(0, 35000).map(_.toString).mkString(",")))
    expectMsgType[Done.type]
    Thread.sleep(2000)
  }


  val mockActorRegion: ActorRef =
    ClusterSharding(system).start(
      typeName          = MOCK_ACTOR_TYPE_NAME,
      entityProps       = Props(new MockPersistentActor(appConfig)),
      settings          = ClusterShardingSettings(system),
      extractEntityId   = ShardUtil.forIdentifierEntityIdExtractor,
      extractShardId    = ShardUtil.forIdentifierShardIdExtractor(ShardIdExtractor(appConfig, MOCK_ACTOR_TYPE_NAME))
    )

  def sendToMockActor(id: Int, cmd: Any): Unit = {
    mockActorRegion ! ForIdentifier(id.toString, cmd)
  }

  override def overrideConfig: Option[Config] = Option {
    akkaPersistenceConfig
      .withFallback(dynamodbConfig)
      .withFallback(mockActorSnapshotConfig)
  }

  def akkaPersistenceConfig: Config = ConfigFactory parseString {
    s"""
       akka {
         persistence {
           journal {
             plugin = "verity.dynamodb-journal"
           }

           snapshot-store {
             plugin = "verity.dynamodb-snapshot-store"
           }
         }
       }
       verity.dynamodb-journal.journal-table = "agency_akka_cas"
       verity.dynamodb-snapshot-store.snapshot-table = "agency_akka_snapshot_cas"
       """
  }

  def mockActorSnapshotConfig: Config = ConfigFactory parseString {
    """
      verity.persistent-actor.base.MockPersistentActor.snapshot {
        after-n-events = 1
        keep-n-snapshots = 2
        delete-events-on-snapshots = false
      }
      """
  }

  def dynamodbConfig: Config =
    ConfigFactory.parseFile(new File("verity/src/main/resources/dynamodb.conf"))
      .resolve()

}

class MockPersistentActor(val appConfig: AppConfig)
  extends BasePersistentActor
    with DefaultPersistenceEncryption
    with SnapshotterExt[MockState]
    with HasTestBasePersistence {

  override def receiveCmd: Receive = {
    case Reset =>
      deleteMessages(lastSequenceNr)

    case PersistEvent(data) =>
      val me = MockEvent4(data)
      writeAndApply(me)
      sender ! Done
  }

  override def receiveEvent: Receive = {
    case MockEvent4(data) =>
      stateData = data
  }

  override def receiveSnapshot: PartialFunction[Any, Unit] = {
    case ms: MockState =>
  }

  override def snapshotState: Option[MockState] = {
    Option(MockState(stateData))
  }

  var stateData: String = ""

  self ! Reset
}

case object Reset extends ActorMessageObject
case class PersistEvent(data: String) extends ActorMessageClass


trait HasTestBasePersistence { this: BasePersistentActor with SnapshotterExt[_] =>

  override val legacyEventObjectMapper = TestObjectCodeMapper
  override val persistentObjectMapper = TestObjectCodeMapper

  object TestObjectCodeMapper extends ObjectCodeMapperBase {

    lazy val objectCodeMapping: Map[Int, GeneratedMessageCompanion[_]] = Map (
      -1 -> MockEvent4,
      -2 -> MockState
    )
  }
}