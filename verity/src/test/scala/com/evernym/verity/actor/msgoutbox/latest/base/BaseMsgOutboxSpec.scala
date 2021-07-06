package com.evernym.verity.actor.msgoutbox.latest.base

import akka.Done
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import com.evernym.verity.Status
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.Status.StatusDetail
import com.evernym.verity.actor.msgoutbox.{ComMethod, Packaging}
import com.evernym.verity.actor.msgoutbox.adapters.HttpTransporter.Commands.{SendBinary, SendJson}
import com.evernym.verity.actor.msgoutbox.adapters.HttpTransporter.Replies.SendResponse
import com.evernym.verity.actor.msgoutbox.adapters.RelationshipResolver.Commands.SendOutboxParam
import com.evernym.verity.actor.msgoutbox.adapters.RelationshipResolver.Replies.OutboxParam
import com.evernym.verity.actor.msgoutbox.adapters.RelationshipResolver.Reply
import com.evernym.verity.actor.msgoutbox.adapters.WalletOpExecutor.Replies.PackagedPayload
import com.evernym.verity.actor.msgoutbox.adapters._
import com.evernym.verity.actor.msgoutbox.outbox._
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.actor.typed.BehaviourSpecBase
import com.evernym.verity.actor.wallet.{CreateNewKey, NewKeyCreated, PackMsg, PackedMsg}
import com.evernym.verity.constants.Constants.COM_METHOD_TYPE_HTTP_ENDPOINT
import com.evernym.verity.storage_services.StorageAPI
import com.evernym.verity.testkit.TestWallet
import com.evernym.verity.testkit.mock.blob_store.MockBlobStore
import com.evernym.verity.urlshortener.UrlInfo
import com.evernym.verity.vault.{KeyParam, WalletAPIParam}
import com.evernym.verity.vault.wallet_api.WalletAPI
import com.evernym.{PolicyElements, RetentionPolicy}
import com.typesafe.config.{Config, ConfigFactory}

import scala.concurrent.duration.{DAYS, Duration}


trait BaseMsgOutboxSpec { this: BehaviourSpecBase =>

  lazy val BUCKET_NAME = "bucket-name"

  lazy val retentionPolicy: RetentionPolicy = RetentionPolicy(
    """{"expire-after-days":20 days,"expire-after-terminal-state":true}""",
    PolicyElements(Duration.apply(20, DAYS), expireAfterTerminalState = true)
  )

  lazy val APP_CONFIG: Config = ConfigFactory.parseString (
    s"""
       |verity.blob-store.storage-service = "com.evernym.verity.testkit.mock.blob_store.MockBlobStore"
       |verity.blob-store.bucket-name = "$BUCKET_NAME"
       |""".stripMargin
  )

  lazy val appConfig = new TestAppConfig(Option(APP_CONFIG), clearValidators = true)
  lazy val storageAPI: MockBlobStore = StorageAPI.loadFromConfig(appConfig)(system.classicSystem).asInstanceOf[MockBlobStore]
  lazy val sharding: ClusterSharding = ClusterSharding(system)

  lazy val testWallet = new TestWallet(createWallet = true)
  lazy val myKey1: NewKeyCreated = testWallet.executeSync[NewKeyCreated](CreateNewKey())
  lazy val recipKey1: NewKeyCreated = testWallet.executeSync[NewKeyCreated](CreateNewKey())

  val testWalletOpExecutor: Behavior[WalletOpExecutor.Cmd] = TestWalletOpExecutor(testWallet.testWalletAPI)
  val testTransports: Transports = new Transports {
    override val httpTransporter: Behavior[HttpTransporter.Cmd] = TestHttpTransport.apply()
  }
  val testRelResolverBehavior: Behavior[RelationshipResolver.Cmd] = {
    val destParams = Map("default" -> DestParam(testWallet.walletId, myKey1.verKey, defaultDestComMethods))
    TestRelResolver(destParams)
  }
  lazy val defaultDestComMethods = Map(
    "1" -> ComMethod(COM_METHOD_TYPE_HTTP_ENDPOINT, "http://indy.webhook.com", Option(Packaging("1.0", Seq(recipKey1.verKey))))
  )

  val testMsgStore: ActorRef[MsgStore.Cmd] = spawn(MsgStore(BUCKET_NAME, storageAPI))
}


object TestWalletOpExecutor {

  def apply(walletAPI: WalletAPI): Behavior[WalletOpExecutor.Cmd] = {
    Behaviors.setup { actorContext =>
      initialized(walletAPI)(actorContext)
    }
  }

  private def initialized(walletApi: WalletAPI)
                         (implicit actorContext: ActorContext[WalletOpExecutor.Cmd]):
  Behavior[WalletOpExecutor.Cmd] = Behaviors.receiveMessage[WalletOpExecutor.Cmd] {

    case WalletOpExecutor.Commands.PackMsg(payload, walletId, recipKeys, senderVerKey, replyTo) =>
      val recipKeysParam = recipKeys.map(KeyParam.fromVerKey)
      val senderKeyParam = KeyParam.fromVerKey(senderVerKey)
      implicit val walletApiParam: WalletAPIParam = WalletAPIParam(walletId)
      val fut = walletApi.executeAsync[PackedMsg](PackMsg(payload, recipKeysParam, Option(senderKeyParam)))
      fut.map { pm => replyTo ! PackagedPayload(pm.msg) }
      Behaviors.stopped
  }
}

object TestRelResolver {
  def apply(destParams: Map[DestId, DestParam]): Behavior[RelationshipResolver.Cmd] = {
    Behaviors.setup { actorContext =>
      initialized(destParams)(actorContext)
    }
  }

  def initialized(destParams: Map[DestId, DestParam])
                 (implicit actorContext: ActorContext[RelationshipResolver.Cmd]): Behavior[RelationshipResolver.Cmd] = {
    Behaviors.receiveMessage[RelationshipResolver.Cmd] {
      case SendOutboxParam(forDestId, replyTo: ActorRef[Reply]) =>
        destParams.get(forDestId).foreach { destParam =>
          replyTo ! OutboxParam(destParam.walletId, destParam.myVerKey, destParam.comMethods)
        }
        Behaviors.same
    }
  }
}

object TestHttpTransport {

  def apply(): Behavior[HttpTransporter.Cmd] = {
    Behaviors.setup { actorContext =>
      initialized()(actorContext)
    }
  }

  type FailedCount = Int

  def initialized(purposefullyFailed: Map[UrlInfo, FailedCount] = Map.empty)
                 (implicit actorContext: ActorContext[HttpTransporter.Cmd]): Behavior[HttpTransporter.Cmd] =
    Behaviors.receiveMessage[HttpTransporter.Cmd] {

      case SendBinary(payload: Array[Byte], toUrl: UrlInfo, replyTo) =>
        synchronized {
          val curFailedCount = getCurrentFailCount(toUrl, purposefullyFailed)
          val maxFailCount = getMaxFailCount(toUrl)
          if (curFailedCount <= maxFailCount) {
            replyTo ! SendResponse(Left(StatusDetail(Status.MSG_DELIVERY_STATUS_FAILED.statusCode, "purposefully failed")))
            initialized(purposefullyFailed = purposefullyFailed ++ Map(toUrl -> (curFailedCount + 1)))
          } else {
            replyTo ! SendResponse(Right(Done))
            Behaviors.same
          }
        }

      case SendJson(payload: String, toUrl: UrlInfo, replyTo) =>
        synchronized {
          val curFailedCount = getCurrentFailCount(toUrl, purposefullyFailed)
          val maxFailCount = getMaxFailCount(toUrl)

          if (curFailedCount <= maxFailCount) {
            replyTo ! SendResponse(Left(StatusDetail(Status.MSG_DELIVERY_STATUS_FAILED.statusCode, "purposefully failed")))
            initialized(purposefullyFailed = purposefullyFailed ++ Map(toUrl -> (curFailedCount + 1)))
          } else {
            replyTo ! SendResponse(Right(Done))
            Behaviors.same
          }
        }
  }

  private def getCurrentFailCount(toUrl: UrlInfo, purposefullyFailed: Map[UrlInfo, FailedCount]): Int = {
    purposefullyFailed.getOrElse(toUrl, 0)
  }

  private def getMaxFailCount(toUrl: UrlInfo): Int = {
    val splitted = toUrl.url.split("\\?failCount=")
    if (splitted.size > 1) {
      splitted.lastOption.map(_.toInt).getOrElse(-1)
    } else -1
  }
}

case class DestParam(walletId: WalletId, myVerKey: VerKey, comMethods: Map[ComMethodId, ComMethod])