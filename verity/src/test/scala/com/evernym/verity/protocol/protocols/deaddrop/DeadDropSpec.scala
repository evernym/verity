package com.evernym.verity.protocol.protocols.deaddrop

import akka.actor.ActorSystem
import com.evernym.verity.actor.testkit.actor.ActorSystemVanilla
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.testkit.{ActorSpec, CommonSpecUtil, TestAppConfig}
import com.evernym.verity.protocol.engine.registry.PinstIdResolution
import com.evernym.verity.protocol.engine.segmentedstate.SegmentStoreStrategy.Bucket_2_Legacy
import com.evernym.verity.protocol.testkit.{ContainerNotFoundException, TestsProtocolsImpl}
import com.evernym.verity.testkit.{BasicFixtureSpec, BasicSpecBase}
import com.evernym.verity.util._
import org.scalatest.concurrent.Eventually

import java.util.UUID
import scala.concurrent.ExecutionContext


class DeadDropSpec
  extends TestsProtocolsImpl(DeadDropProtoDef, Option(Bucket_2_Legacy))
    with BasicFixtureSpec
    with CommonSpecUtil
    with DeadDropSpecUtil
    with Eventually {

  "A protocol" - {

    "should be able to specify that its state is segmented"  in { _ =>
      DeadDropProtoDef.segmentStoreStrategy shouldBe Some(Bucket_2_Legacy)
    }

    "should be able to store and retrieve data" in { f =>

      val interactor = f.setup(INTERACTOR)
      val persister = f.setup(PERSISTER)

      //prepare dead drop payloads to be stored
      val storedPayloads = 5 times { generatePayload() }

      //FIXME play with two is required due to the fact that dead drop is a two party protocol, but should probably evolve to a 1-to-many anonymous protocol
      playExt (persister -> PERSISTER_DID, interactor -> INTERACTOR_DID) {

        //persist dead drops
        storedPayloads.foreach { pl =>
          persister ~ StoreData(pl.buildDeadDropPayload)
          persister.state shouldBe DeadDropState.Ready()
        }

      }

      //retrieve payload test by recoverer-1
      testRetrievePayload(storedPayloads.head, persister -> PERSISTER_DID)(f)

      //retrieve payload test by recoverer-2
      testRetrievePayload(storedPayloads.last, persister -> PERSISTER_DID)(f)
    }

    "should be able to query if wrong data is given" in { f =>

      val persister = f.setup(PERSISTER)
      val interactor = f.setup(INTERACTOR)

      //prepare dead drop payloads to be stored
      val payload = generatePayload()

      intercept[InvalidPayloadAddress] {
        interaction (interactor, persister) {
          interactor ~ GetData(payload.recoveryVerKey, "dummy-address", payload.locator, payload.locatorSignature)
        }
      }
    }
  }

  def testRetrievePayload(ddd: DeadDropData, persister: (TestEnvir, String))(f: FixtureParam): Unit = {

    try {
      f.sys.deregister(f.sys.containerForParti_!(INTERACTOR_DID))
    } catch {
      case _: ContainerNotFoundException => //nothing to do
    }
    f.sys.removeRoute(INTERACTOR_DID)
    f.sys.removeRoute(INTERACTOR)
    persister._1.domain.removeRelationship(INTERACTOR_DID)

    val interactor = f.setup(INTERACTOR)
    playExt (interactor -> INTERACTOR_DID, persister._1 -> persister._2) {

      //retrieve dead drop payload
      interactor ~ ddd.buildGetData()

      interactor.state shouldBe a[DeadDropState.ItemRetrieved]

      val dds = interactor.state.asInstanceOf[DeadDropState.ItemRetrieved]
      dds.ddp.exists(_.data sameElements ddd.data) shouldBe true

    }
  }

  override val defaultPinstIdResolver = PinstIdResolution.DEPRECATED_V0_1

  lazy val appConfig = new TestAppConfig()

  val PERSISTER = "persister"
  val PERSISTER_DID = s"$PERSISTER-did"
  val INTERACTOR = "interactor"
  val INTERACTOR_DID = s"$INTERACTOR-did"
  lazy val ecp: ExecutionContextProvider = TestExecutionContextProvider.ecp


  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext

  val system: ActorSystem = ActorSystemVanilla(UUID.randomUUID().toString)
}

