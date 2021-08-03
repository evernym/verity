package com.evernym.verity.protocol.container.actor.base

import akka.actor.{ActorRef, Props}
import akka.cluster.sharding.ClusterSharding
import akka.testkit.TestKitBase
import com.evernym.verity.actor.base.Done
import com.evernym.verity.actor.testkit.PersistentActorSpec
import com.evernym.verity.actor.{ForIdentifier, ShardUtil}
import com.evernym.verity.did.DID
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine.{PinstIdPair, ThreadId}
import com.evernym.verity.testkit.{BasicSpec, HasTestWalletAPI}
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.BeforeAndAfterAll

import java.util.UUID
import scala.concurrent.duration._
import scala.reflect.ClassTag

/**
 * a base class to be extended to test actor protocol container
 */
trait BaseProtocolActorSpec
  extends PersistentActorSpec
    with BasicSpec
    with HasTestWalletAPI
    with BeforeAndAfterAll
    with ShardUtil {

  override def beforeAll(): Unit = {
    super.beforeAll()
    routeRegion   //touching it to start the routing agent region actor
  }

  /**
   * to be overridden by test for overriding test specific configuration
   * @return
   */
  def overrideSpecificConfig: Option[Config] = None

  /**
   * this is temporary to make the mock controller flow working
   * we should refactor it and not required to override below config
   *
   * @return
   */
  final override def overrideConfig: Option[Config] = Option {
    val baseConfig = ConfigFactory parseString {
      """
         akka.actor.serialize-messages = off
         verity.metrics.enabled = N
        """.stripMargin
    }
    overrideSpecificConfig match {
      case Some(sc) => baseConfig.withFallback(sc)
      case None     => baseConfig
    }
  }

  /**
   * builds SetupController data to be used to 'setup' a controller who
   *  * sends 'control' messages to protocol actor and
   *  * receives 'signal' messages from protocol actor and
   *  * receives 'protocol' messages sent by other party
   *
   * @param myDID my DID
   * @param theirDIDOpt their DID, needed if protocol is executed between two different domains
   */
  def buildSetupController(myDID: DID, theirDIDOpt: Option[DID]): SetupController = {
    SetupController(
      ControllerData(
        myDID,
        theirDIDOpt)
    )
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
  override val actorTypeToRegions = Map(
    MOCK_CONTROLLER_ACTOR_TYPE -> createNonPersistentRegion(MOCK_CONTROLLER_REGION_NAME, mockControllerActorProps)
  )

  /**
   * to be supplied by implementing class to create mock controller actor
   * @return
   */
  def mockControllerActorProps: Props

  def buildMockController(myDID: DID,
                          theirDID: DID): MockController = {
    buildMockController(myDID, Option(theirDID))
  }

  def buildMockController(myDID: DID,
                          theirDIDOpt: Option[DID] = None): MockController = {
    MockController(UUID.randomUUID().toString,
      myDID, theirDIDOpt, mockControllerRegion, this)
  }
}

case class MockController(walletId: String,
                          myDID: DID,
                          theirDIDOpt: Option[DID] = None,
                          mockControllerRegion: ActorRef,
                          testKit: TestKitBase) {

  var isSetupCmdSent = false

  /**
   * setup the mock controller actor
   * @param sndr
   */
  def startSetup()(implicit sndr: ActorRef): Unit = {
    val cmd = SetupController(
      ControllerData(
        walletId,
        myDID,
        theirDIDOpt))

    sendCmd(cmd)
    isSetupCmdSent = true
    expectMsg(Done)
  }

  def theirDID: DID = theirDIDOpt.getOrElse(throw new RuntimeException("their DID not supplied"))

  /**
   * sends given command to mock controller actor
   * @param cmd
   * @param sndr
   */
  def sendCmd(cmd: Any)(implicit sndr: ActorRef): Unit = {
    cmd match {
      case _: SendControlMsg => throw new RuntimeException("use 'sendControlCmd' method")
      case _                 => //nothing to do
    }
    mockControllerRegion ! ForIdentifier(myDID, cmd)
  }

  def sendControlCmd(msg: Control, threadId: ThreadId = "thread-id-1")(implicit sndr: ActorRef): PinstIdPair = {
    mockControllerRegion ! ForIdentifier(myDID, SendControlMsg(msg, threadId))
    expectMsgType[PinstIdPair]()
  }

  def checkIfAlreadySetup(): Unit = {
    if (!isSetupCmdSent) {
      throw new RuntimeException(s"controller '$myDID' is not yet setup")
    }
  }

  def expectMsgType[T](max: FiniteDuration = Duration(30, SECONDS))(implicit t: ClassTag[T]): T = {
    checkIfAlreadySetup()
    val m = testKit.expectMsgType(max)
    assert(testKit.lastSender.toString().contains(myDID), s"msg received from different controller")
    m
  }

  def expectMsg[T](obj: T)(implicit max: FiniteDuration = Duration(30, SECONDS)): T = {
    checkIfAlreadySetup()
    val m = testKit.expectMsg(max, obj)
    assert(testKit.lastSender.toString().contains(myDID), s"msg received from different controller")
    m
  }
}