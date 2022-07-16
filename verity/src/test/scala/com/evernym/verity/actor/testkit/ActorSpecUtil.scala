package com.evernym.verity.actor.testkit

import akka.actor.typed
import akka.persistence.testkit.scaladsl.{PersistenceTestKit, SnapshotTestKit}
import akka.testkit.{ImplicitSender, TestEventListener, TestKit, TestKitBase}
import akka.{actor => classic}
import com.evernym.verity.actor.AgencyPublicDid
import com.evernym.verity.actor.testkit.actor.{ActorSystemConfig, MockAppConfig, OverrideConfig, ProvidesMockPlatform}
import com.evernym.verity.actor.testkit.checks.ChecksAkkaEvents
import com.evernym.verity.config.AppConfig
import com.evernym.verity.testkit.{BasicSpecBase, CleansUpIndyClientFirst}
import com.typesafe.config.{Config, ConfigValueFactory}
import org.iq80.leveldb.util.FileUtils
import org.scalatest.{BeforeAndAfterAll, Suite, TestSuite}

import java.util.concurrent.TimeUnit
import com.evernym.verity.did.{DidPair, DidStr, VerKeyStr}
import com.evernym.verity.util2.ActorErrorResp
import com.evernym.verity.observability.metrics.{MetricsWriterExtension, TestMetricsBackend}

import java.time.LocalDate
import scala.concurrent.duration.Duration
import scala.reflect.ClassTag


class QuietTestEventListener extends TestEventListener {
  override def print(event: Any): Unit = {}
}

object AkkaTestBasic extends ActorSystemConfig

/**
 *
 * @param name agent's name
 * @param DIDSeed seed used to create the did
 * @param did DID
 * @param verKey ver key
 */
case class AgentDIDDetail(name: String, DIDSeed: String, did: DidStr, verKey: VerKeyStr) {
  def prepareAgencyIdentity: AgencyPublicDid = AgencyPublicDid(did, verKey)
  def didPair: DidPair = DidPair(did, verKey)
}


class TestAppConfig(newConfig: Option[Config] = None,
                    clearValidators: Boolean = false,
                    baseAsFallback: Boolean = true)
  extends AppConfig {
  if (clearValidators) {
    validatorCreators = List.empty
  }
  if (baseAsFallback) {
    setConfig(newConfig.map(cfg => cfg.withFallback(baseConfig)).getOrElse(baseConfig))
  } else {
    setConfig(newConfig.getOrElse(baseConfig))
  }

  def withFallback(fallback: Config): TestAppConfig = {
    setConfig(config.withFallback(fallback))
    this
  }

  lazy val baseConfig: Config =
    getLoadedConfig
      .withValue("verity.lib-vdrtools.ledger.indy.transaction_author_agreement.agreements.\"1.0.0\".time-of-acceptance",
        ConfigValueFactory.fromAnyRef(LocalDate.now().toString))

  override var config: Config = _
}
object TestAppConfig {
  def apply(newConfig: Option[Config] = None, clearValidators: Boolean = false) =
    new TestAppConfig(newConfig, clearValidators)
}

sealed trait CleansUpPersistence { this: CleansUpActorSystem =>

  override def cleanupStorage(as: classic.ActorSystem): Unit = {
    FileUtils.deleteRecursively {
      new java.io.File(AkkaTestBasic.tmpdir(as.name))
    }
  }

}

sealed trait CleansUpActorSystem extends BeforeAndAfterAll { this: TestKitBase with Suite =>

  override protected def afterAll(): Unit = {
    //after upgrading to akka 2.6, unit test were always failing on CI/CD pipeline (locally it always worked)
    //so increased the default timeout from 10 seconds to 20 seconds, which made it passing on CI/CD
    TestKit.shutdownActorSystem(system,  Duration.create(20, TimeUnit.SECONDS), verifySystemShutdown=true)
    cleanupStorage(system)
    super.afterAll()
  }

  def cleanupStorage(as: classic.ActorSystem): Unit = ()

}

/**
  * To help ensure persistent actor-based tests are cleaned up properly, we should extend EPersistenceTestKit over TestKit
  */
trait PersistentActorSpec
  extends ActorSpec
    with CleansUpPersistence {
  this: BasicSpecBase =>
}

trait HasActorSystem {
  implicit val system: classic.ActorSystem
  import akka.actor.typed.scaladsl.adapter._
  implicit lazy val typedSystem: typed.ActorSystem[Nothing] = system.toTyped
}

trait HasTestActorSystem extends HasActorSystem {
  import akka.actor.testkit.typed.scaladsl.ActorTestKit
  lazy val typedTestKit: ActorTestKit = ActorTestKit(typedSystem)
  lazy val persTestKit: PersistenceTestKit = PersistenceTestKit(system)
  lazy val snapTestKit: SnapshotTestKit = SnapshotTestKit(system)
}

trait HasBasicActorSystem extends OverrideConfig with MockAppConfig {
  lazy val (as, conf) = AkkaTestBasic.systemWithConfig(
    overrideConfig
  )
  implicit lazy val system: classic.ActorSystem = {
    MetricsWriterExtension(as).updateMetricsBackend(new TestMetricsBackend)
    as
  }
  implicit override lazy val appConfig: AppConfig = new TestAppConfig(Option(conf), baseAsFallback = false)
}

/**
  * To help ensure actor-based tests are cleaned up properly, we should use ActorSpec and not TestKit directly
  */
trait ActorSpec extends TestSuite with ActorSpecLike with HasBasicActorSystem {
  this: BasicSpecBase =>
}

sealed trait ActorSpecLike
  extends TestKitBase
    with ImplicitSender
    with CommonSpecUtil  //TODO:JAL this is bit heavy and maybe too specific for a general purpose spec; also it should be renamed to something more specific as its name doesn't tell me what it does
    with ExpectsErrors
    with ChecksAkkaEvents
    with ProvidesMockPlatform
    with HasTestActorSystem
    with CleansUpActorSystem
    with CleansUpIndyClientFirst {
  this: TestSuite with BasicSpecBase =>
}

trait ExpectsErrors { this: TestKitBase =>

  def expectErrorType[T <: ActorErrorResp](respCode: String)(implicit t: ClassTag[T]): T = {
    val e = expectMsgType[T]
    assert(e.statusCode == respCode, s"expected respCode: $respCode, actual respCode: ${e.statusCode}")
    e
  }

  def expectError(respCode: String): ActorErrorResp = expectErrorType[ActorErrorResp](respCode)

}
