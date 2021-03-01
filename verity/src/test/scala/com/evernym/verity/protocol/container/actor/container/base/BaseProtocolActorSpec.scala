package com.evernym.verity.protocol.container.actor.container.base

import akka.actor.{ActorRef, Props}
import akka.cluster.sharding.ClusterSharding
import akka.testkit.TestKitBase
import com.evernym.verity.actor.agent.ThreadContextDetail
import com.evernym.verity.actor.testkit.PersistentActorSpec
import com.evernym.verity.actor.{ForIdentifier, ShardUtil}
import com.evernym.verity.constants.ActorNameConstants.ACTOR_TYPE_USER_AGENT_ACTOR
import com.evernym.verity.protocol.engine.{DID, PinstIdPair, ProtoDef}
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.{Config, ConfigFactory}

import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag

trait BaseProtocolActorSpec
  extends PersistentActorSpec
    with BasicSpec
    with ShardUtil {

  def overrideSpecificConfig: Option[Config] = None

  /**
   * this is temporary to make the mock controller flow working
   * we should refactor it and not required to override below config
   *
   * @return
   */
  final override def overrideConfig: Option[Config] = Option {
    val baseConfig = ConfigFactory parseString {
      s"akka.actor.serialize-messages = off"
    }
    overrideSpecificConfig match {
      case Some(sc) => baseConfig.withFallback(sc)
      case None     => baseConfig
    }
  }

  /**
   *
   * @param myDID my DID
   * @param theirDIDOpt their DID, needed if protocol is executed between two different domains
   * @param protoDef protocol def
   * @param threadContextDetailOpt optional thread context detail
   */
  def buildSetupController(myDID: DID,
                           theirDIDOpt: Option[DID],
                           protoDef: ProtoDef,
                           threadContextDetailOpt: Option[ThreadContextDetail]=None): SetupController = {
    SetupController(
      ControllerData(
        myDID,
        theirDIDOpt,
        PinstIdPair(myDID, protoDef),
        threadContextDetailOpt))
  }

  val MOCK_CONTROLLER_REGION_NAME = "MockControllerActor"

  lazy val mockControllerRegion: ActorRef = {
    ClusterSharding(system).shardRegion(MOCK_CONTROLLER_REGION_NAME)
  }

  def sendToMockController(id: String, cmd: Any): Unit = {
    mockControllerRegion ! ForIdentifier(id, cmd)
  }

  //overriding agent msg routing mapping to make the flow working
  // (from actor protocol container to the 'mock controller')
  override lazy val mockRouteStoreActorTypeToRegions = Map(
    ACTOR_TYPE_USER_AGENT_ACTOR -> mockActorRegionActor
  )

  val mockActorRegionActor: ActorRef = createNonPersistentRegion(MOCK_CONTROLLER_REGION_NAME, mockControllerActorProps)
  agentRouteStoreRegion

  def mockControllerActorProps: Props

  def buildMockController(protoDef: ProtoDef,
                          myDID: DID,
                          theirDID: DID): MockController = {
    buildMockController(protoDef, myDID, Option(theirDID), None)
  }

  def buildMockController(protoDef: ProtoDef,
                          myDID: DID,
                          theirDIDOpt: Option[DID] = None,
                          threadContextDetailOpt: Option[ThreadContextDetail]=None): MockController = {
    MockController(protoDef, myDID, theirDIDOpt, threadContextDetailOpt, mockControllerRegion, this)
  }
}

case class MockController(protoDef: ProtoDef,
                          myDID: DID,
                          theirDIDOpt: Option[DID] = None,
                          threadContextDetailOpt: Option[ThreadContextDetail]=None,
                          mockControllerRegion: ActorRef,
                          testKit: TestKitBase) {

  def startSetup()(implicit sndr: ActorRef): Unit = {
    //agentActorContext //to initialize platform
    val cmd = SetupController(
      ControllerData(
        myDID,
        theirDIDOpt,
        PinstIdPair(myDID, protoDef),
        threadContextDetailOpt))

    sendCmd(cmd)
  }

  def theirDID: DID = theirDIDOpt.getOrElse(throw new RuntimeException("their DID not supplied"))

  def sendCmd(cmd: Any)(implicit sndr: ActorRef): Unit = {
    mockControllerRegion ! ForIdentifier(myDID, cmd)
  }

  def expectMsgType[T](implicit t: ClassTag[T], max: Option[FiniteDuration]=None): T = {
    val m = max.map(testKit.expectMsgType(_)).getOrElse(testKit.expectMsgType)
    assert(testKit.lastSender.toString().contains(myDID), s"msg received from different controller")
    m
  }

  def expectMsg[T](obj: T)(implicit max: Option[FiniteDuration]=None): T = {
    val m = max.map(_ => testKit.expectMsg(obj)).getOrElse(testKit.expectMsg(obj))
    assert(testKit.lastSender.toString().contains(myDID), s"msg received from different controller")
    m
  }
}