package com.evernym.verity.actor.testkit

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestEventListener, TestKit, TestKitBase}
import com.evernym.verity.actor.AgencyPublicDid
import com.evernym.verity.actor.testkit.actor.{ActorSystemConfig, OverrideConfig, ProvidesMockPlatform}
import com.evernym.verity.actor.testkit.checks.ChecksAkkaEvents
import com.evernym.verity.config.AppConfig
import com.evernym.verity.protocol.engine.{DID, VerKey}
import com.evernym.verity.testkit.{BasicSpecBase, CleansUpIndyClientFirst}
import com.evernym.verity.ActorErrorResp
import com.typesafe.config.Config
import org.iq80.leveldb.util.FileUtils
import org.scalatest.{BeforeAndAfterAll, Suite, TestSuite}

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
case class AgentDIDDetail(name: String, DIDSeed: String, did: DID, verKey: VerKey) {
  def prepareAgencyIdentity: AgencyPublicDid = AgencyPublicDid(did, verKey)
}


class TestAppConfig(newConfig: Option[Config] = None, clearValidators: Boolean = false) extends AppConfig {
  if(clearValidators) {
    validatorCreators = List.empty
  }
  setConfig(newConfig.getOrElse(getLoadedConfig))
}

sealed trait CleansUpPersistence { this: CleansUpActorSystem =>

  override def cleanupStorage(as: ActorSystem): Unit = {
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

  def cleanupStorage(as: ActorSystem): Unit = ()

}

/**
  * To help ensure persistent actor-based tests are cleaned up properly, we should extend EPersistenceTestKit over TestKit
  */
trait PersistentActorSpec
  extends ActorSpec
    with CleansUpPersistence {
  this: BasicSpecBase =>
}


/**
  * To help ensure actor-based tests are cleaned up properly, we should use ActorSpec and not TestKit directly
  */
trait ActorSpec extends TestSuite with ActorSpecLike with OverrideConfig {
  this: BasicSpecBase =>

  lazy val (as, conf) = AkkaTestBasic.systemWithConfig(overrideConfig)
  implicit lazy val system: ActorSystem = as
  implicit override lazy val appConfig: AppConfig = new TestAppConfig(Option(conf))
}

sealed trait ActorSpecLike
  extends TestKitBase
    with ImplicitSender
    with CommonSpecUtil  //TODO:JAL this is bit heavy and maybe too specific for a general purpose spec; also it should be renamed to something more specific as its name doesn't tell me what it does
    with ExpectsErrors
    with ChecksAkkaEvents
    with ProvidesMockPlatform
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
