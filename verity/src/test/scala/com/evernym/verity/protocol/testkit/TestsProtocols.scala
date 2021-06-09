package com.evernym.verity.protocol.testkit

import java.util.UUID
import com.evernym.verity.protocol.engine.ProtocolRegistry.DriverGen
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.segmentedstate.SegmentStoreStrategy
import org.scalatest.Outcome
import org.scalatest.FixtureTestSuite
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}

import scala.reflect.ClassTag


abstract class TestsProtocolsImpl[P,R,M,E,S,I](val protoDef: ProtocolDefinition[P,R,M,E,S,I],
                                               val segmentStoreStrategy: Option[SegmentStoreStrategy]=None)(implicit val mtag: ClassTag[M])
  extends TestsProtocols[P,R,M,E,S,I]
    with ProtocolTestKitLike[P,R,M,E,S,I]
    with FixtureTestSuite

trait TestsProtocols[P,R,M,E,S,I] extends Eventually {
  ptk: ProtocolTestKitLike[P,R,M,E,S,I] with FixtureTestSuite =>

  type ContainerName = String

  val protoDef: ProtocolDefinition[P,R,M,E,S,I]
  val segmentStoreStrategy: Option[SegmentStoreStrategy]

  type FixtureParam = Scenario

  /**
    * Tests can override this if Alice, Bob, and Carol are not appropriate
    * @return
    */
  def containerNames: Set[ContainerName] = Set.empty

  //TODO JL this is not needed since in ProtocolTestKitLike we have
  // member defaultControllerProvider that can be overridden, as
  // well as the second parameter in `setup`
//  def drivers: Map[ContainerName, Driver] = Map.empty

  def withFixture(test: OneArgTest): Outcome = {
    val fixt = {
      Scenario(containerNames.toSeq: _*)
    }

    try {
      withFixture(test.toNoArgTest(fixt))
    }
    finally {
      // clean up goes here
    }
  }


  def sendProtoMsg(sender: Container, to: Container, msg: M): Unit = {
    sender.submit(Envelope1(msg, to.participantId, sender.participantId, None))
  }

  def newContainer(system: SimpleProtocolSystem,
                   partiId: ParticipantId=UUID.randomUUID.toString,
                   pinstId: PinstId=UUID.randomUUID.toString,
                   recorder: Option[RecordsEvents]=None,
                   driver: Option[Driver]=None): Container = {
    val pce = ProtocolContainerElements(system, partiId, pinstId, None, protoDef, new TestSystemInitProvider, recorder, driver)
    new Container(pce)
  }

  trait ProtocolScenario

  object Scenario {
    def apply(names: ContainerName *): Scenario = {
      implicit val sys: TestSystem = new TestSystem()
      val setups = names map { n => n -> setup(n) }
      new Scenario(setups.toMap)
    }
  }

  class Scenario(var testEnvirs: Map[String,TestEnvir] = Map.empty)(implicit val sys: TestSystem) extends ProtocolScenario {

    lazy val alice: TestEnvir = getOrCreate("alice")
    lazy val bob: TestEnvir = getOrCreate("bob")
    lazy val charlie: TestEnvir = getOrCreate("charlie")

    def apply(key: String): TestEnvir = testEnvirs(key)

    def getOrCreate(name: String): TestEnvir = {
      testEnvirs.getOrElse(name, setup(name))
    }

    def setup(name: String, odg: DriverGen[SimpleControllerProviderInputType]=None, it: InteractionType=defaultInteractionType): TestEnvir = {
      val te = ptk.setup(name, odg, it)
      testEnvirs = testEnvirs + (name -> te)
      te
    }

    def checkTotalSegments(expectedSize: Int, waitMillisBeforeCheck: Int = 0): Unit = {
      Thread.sleep(waitMillisBeforeCheck)
      eventually(timeout(Span(3, Seconds)), interval(Span(300, Millis))) {
        assert (sys.totalStoredSegments == expectedSize,
          s" (stored segment size was expected to be '$expectedSize', but it was '${sys.totalStoredSegments}'")
      }
    }
  }

}

sealed trait InteractionType
object InteractionType {
  object OneParty extends InteractionType
  object TwoParty extends InteractionType
}
