package com.evernym.verity.msgoutbox.base

import akka.Done
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.adapter._
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity}
import com.evernym.verity.util2.{HasExecutionContextProvider, HasWalletExecutionContextProvider, PolicyElements, RetentionPolicy, Status}
import com.evernym.verity.util2.Status.StatusDetail
import com.evernym.verity.msgoutbox.message_meta.MessageMeta
import com.evernym.verity.msgoutbox.message_meta.MessageMeta.Replies.{AddMsgReply, MsgAdded}
import com.evernym.verity.msgoutbox.{Authentication, ComMethod, ComMethodId, DestId, MessageRepository, OutboxId, RecipId, RecipPackaging, RelId, RelResolver, WalletId}
import com.evernym.verity.msgoutbox.outbox.msg_transporter.HttpTransporter.Commands.{SendBinary, SendJson}
import com.evernym.verity.msgoutbox.outbox.msg_transporter.HttpTransporter.Replies.SendResponse
import com.evernym.verity.msgoutbox.rel_resolver.RelationshipResolver.Commands.SendOutboxParam
import com.evernym.verity.msgoutbox.rel_resolver.RelationshipResolver.Replies.OutboxParam
import com.evernym.verity.msgoutbox.outbox.msg_packager.didcom_v1.WalletOpExecutor.Replies.PackagedPayload
import com.evernym.verity.msgoutbox.outbox.msg_store.MsgStore
import com.evernym.verity.msgoutbox.outbox.msg_packager.MsgPackagers
import com.evernym.verity.msgoutbox.outbox.msg_packager.didcom_v1.{DIDCommV1Packager, WalletOpExecutor}
import com.evernym.verity.msgoutbox.outbox.msg_transporter.{HttpTransporter, MsgTransports}
import com.evernym.verity.msgoutbox.rel_resolver.RelationshipResolver
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.actor.typed.BehaviourSpecBase
import com.evernym.verity.actor.wallet.{CreateNewKey, NewKeyCreated, PackMsg, PackedMsg}
import com.evernym.verity.agentmsg.msgpacker.AgentMsgTransformer
import com.evernym.verity.constants.Constants.COM_METHOD_TYPE_HTTP_ENDPOINT
import com.evernym.verity.did.VerKeyStr
import com.evernym.verity.did.didcomm.v1.messages.MsgId
import com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.oauth.access_token_refresher.{AccessTokenRefreshers, OAuthAccessTokenRefresher}
import com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.oauth.access_token_refresher.OAuthAccessTokenRefresher.Replies.GetTokenSuccess
import com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.oauth.access_token_refresher.OAuthAccessTokenRefresher.OAUTH2_VERSION_1
import com.evernym.verity.observability.metrics.{MetricsWriter, MetricsWriterExtension}
import com.evernym.verity.storage_services.{BucketLifeCycleUtil, StorageAPI}
import com.evernym.verity.testkit.TestWallet
import com.evernym.verity.testkit.mock.blob_store.MockBlobStore
import com.evernym.verity.vault.{KeyParam, WalletAPIParam}
import com.evernym.verity.vault.wallet_api.WalletAPI
import com.typesafe.config.{Config, ConfigFactory}
import org.json.JSONObject
import java.util.UUID

import com.evernym.verity.actor.agent.msgrouter.AgentMsgRouter
import com.evernym.verity.msgoutbox.outbox.OutboxIdParam

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._


trait BaseMsgOutboxSpec extends HasExecutionContextProvider with HasWalletExecutionContextProvider { this: BehaviourSpecBase =>
  implicit val executionContext: ExecutionContext = futureExecutionContext

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
  lazy val storageAPI: MockBlobStore = StorageAPI.loadFromConfig(appConfig, executionContext)(system.classicSystem).asInstanceOf[MockBlobStore]
  lazy val sharding: ClusterSharding = ClusterSharding(system)
  lazy val metricsWriter: MetricsWriter = MetricsWriterExtension(system).get()

  lazy val testWallet = new TestWallet(futureWalletExecutionContext, createWallet = true)
  lazy val myKey1: NewKeyCreated = testWallet.executeSync[NewKeyCreated](CreateNewKey())
  lazy val recipKey1: NewKeyCreated = testWallet.executeSync[NewKeyCreated](CreateNewKey())
  lazy val routingKey1: NewKeyCreated = testWallet.executeSync[NewKeyCreated](CreateNewKey())

  val testWalletOpExecutor: Behavior[WalletOpExecutor.Cmd] = TestWalletOpExecutor(testWallet.testWalletAPI, executionContext)
  val testDIDCommV1Packager: Behavior[DIDCommV1Packager.Cmd] =
    DIDCommV1Packager(new AgentMsgTransformer(testWallet.testWalletAPI, appConfig, executionContext), testWalletOpExecutor, metricsWriter, executionContext)

  val testMsgTransports: MsgTransports = new MsgTransports {
    override val httpTransporter: Behavior[HttpTransporter.Cmd] = TestHttpTransport.apply()
  }

  val testMsgPackagers: MsgPackagers = new MsgPackagers {
    override def didCommV1Packager: Behavior[DIDCommV1Packager.Cmd] = testDIDCommV1Packager
  }

  val testAccessTokenRefreshers: AccessTokenRefreshers  = new AccessTokenRefreshers {
    override def refreshers: Map[Version, Behavior[OAuthAccessTokenRefresher.Cmd]] = {
      Map(OAUTH2_VERSION_1 -> MockOAuthAccessTokenRefresher())
    }
  }

  val testRelationshipResolver: Behavior[RelationshipResolver.Cmd] = {
    val destParams = Map("default" -> DestParam(testWallet.walletId, myKey1.verKey, defaultDestComMethods))
    TestRelResolver(destParams)
  }

  lazy val defaultDestComMethods = Map(
    "1" -> plainIndyWebhookComMethod
  )
  lazy val plainIndyWebhookComMethod = ComMethod(
    COM_METHOD_TYPE_HTTP_ENDPOINT,
    "http://indy.webhook.com",
    Option(RecipPackaging("1.0", Seq(recipKey1.verKey)))
  )

  lazy val oAuthIndyWebhookComMethod = ComMethod(
    COM_METHOD_TYPE_HTTP_ENDPOINT,
    "http://indy.webhook.com",
    Option(RecipPackaging("1.0", Seq(recipKey1.verKey))),
    authentication = Option(Authentication(
      "OAuth2",
      "v1",
      Map(
        "tokenExpiresInSeconds" -> "10",
        "shallTimeout" -> "N",
        "shallFail" -> "N"
      )
    ))
  )

  val testMsgStore: ActorRef[MsgStore.Cmd] = spawn(MsgStore(BUCKET_NAME, storageAPI, executionContext))

  val testMsgRepository: MessageRepository = MessageRepository(testMsgStore, executionContext, system)

  class TestRelResolver extends RelResolver {
    override def resolveOutboxParam(relId: RelId, recipId: RecipId): Future[OutboxIdParam] = {
      Future.successful(OutboxIdParam(relId, recipId, "destId"))
    }

    override def getWalletParam(relId: RelId, destId: DestId): Future[(WalletId, VerKeyStr, Map[ComMethodId, ComMethod])] = {
      Future.successful(testWallet.walletId, myKey1.verKey, Map("1" -> plainIndyWebhookComMethod))
    }
  }

  val testRelResolver: RelResolver = new TestRelResolver

  def storeAndAddToMsgMetadataActor(msgType: String,
                                    outboxIds: Set[OutboxId]): MsgId = {
    val msgId = UUID.randomUUID().toString
    val msgLifecycleAddress = BucketLifeCycleUtil.lifeCycleAddress(
      Option(retentionPolicy.elements.expiryDaysStr), msgId)

    val storePayload = storageAPI.put(
      BUCKET_NAME,
      msgLifecycleAddress,
      msgType.getBytes
    )
    Await.result(storePayload, 5.seconds)

    val probe = createTestProbe[AddMsgReply]()
    messageMetaRegion ! ShardingEnvelope(msgId,
      MessageMeta.Commands.Add(msgType, retentionPolicy.configString, outboxIds, None, None, probe.ref))
    probe.expectMessage(MsgAdded)

    storedMsgs = storedMsgs :+ msgId
    msgId
  }

  var storedMsgs = List.empty[String]

  val messageMetaRegion: ActorRef[ShardingEnvelope[MessageMeta.Cmd]] =
    sharding.init(Entity(MessageMeta.TypeKey) { entityContext =>
      MessageMeta(entityContext, testMsgStore, appConfig)
    })
}


object TestWalletOpExecutor {

  def apply(walletAPI: WalletAPI, executionContext: ExecutionContext): Behavior[WalletOpExecutor.Cmd] = {
    Behaviors.setup { actorContext =>
      initialized(walletAPI)(actorContext, executionContext)
    }
  }

  private def initialized(walletApi: WalletAPI)
                         (
                           implicit actorContext: ActorContext[WalletOpExecutor.Cmd],
                           executionContext: ExecutionContext
                         ):
  Behavior[WalletOpExecutor.Cmd] = Behaviors.receiveMessage[WalletOpExecutor.Cmd] {

    case WalletOpExecutor.Commands.PackMsg(payload, recipKeys, senderVerKey, walletId, replyTo) =>
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
      case SendOutboxParam(relId, destId, replyTo: ActorRef[RelationshipResolver.SendOutboxParamReply]) =>
        destParams.get(destId).foreach { destParam =>
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

  def initialized(purposefullyFailed: Map[String, FailedCount] = Map.empty)
                 (implicit actorContext: ActorContext[HttpTransporter.Cmd]): Behavior[HttpTransporter.Cmd] =
    Behaviors.receiveMessage[HttpTransporter.Cmd] {

      case SendBinary(payload, toUrl, headers, replyTo) =>
        synchronized {
          val curFailedCount = getCurrentFailCount(toUrl, purposefullyFailed)
          val maxFailCount = getMaxFailCount(toUrl)
          if (curFailedCount < maxFailCount) {
            replyTo ! SendResponse(Left(StatusDetail(Status.MSG_DELIVERY_STATUS_FAILED.statusCode, "purposefully failed")))
            initialized(purposefullyFailed = purposefullyFailed ++ Map(toUrl -> (curFailedCount + 1)))
          } else {
            replyTo ! SendResponse(Right(Done))
            Behaviors.same
          }
        }

      case SendJson(payload, toUrl, headers, replyTo) =>
        synchronized {
          val curFailedCount = getCurrentFailCount(toUrl, purposefullyFailed)
          val maxFailCount = getMaxFailCount(toUrl)

          if (curFailedCount < maxFailCount) {
            replyTo ! SendResponse(Left(StatusDetail(Status.MSG_DELIVERY_STATUS_FAILED.statusCode, "purposefully failed")))
            initialized(purposefullyFailed = purposefullyFailed ++ Map(toUrl -> (curFailedCount + 1)))
          } else {
            replyTo ! SendResponse(Right(Done))
            Behaviors.same
          }
        }
  }

  private def getCurrentFailCount(toUrl: String, purposefullyFailed: Map[String, FailedCount]): Int = {
    purposefullyFailed.getOrElse(toUrl, 0)
  }

  private def getMaxFailCount(toUrl: String): Int = {
    val splitted = toUrl.split("\\?failCount=")
    if (splitted.size > 1) {
      splitted.lastOption.map(_.toInt).getOrElse(-1)
    } else -1
  }
}

case class DestParam(walletId: WalletId, myVerKey: VerKeyStr, comMethods: Map[ComMethodId, ComMethod])


object MockOAuthAccessTokenRefresher {

  var tokenRefreshCount = 0

  def apply(): Behavior[OAuthAccessTokenRefresher.Cmd] = {
    Behaviors.setup { _ =>
      Behaviors.receiveMessage {
        case OAuthAccessTokenRefresher.Commands.GetToken(params, prevTokenRefreshResponse, replyTo) =>
          val expiresInSeconds = params("tokenExpiresInSeconds").toInt
          val shallTimeout = params("shallTimeout") == "Y"
          val shallFail = params("shallFail") == "Y"
          if (shallFail) {
            replyTo ! OAuthAccessTokenRefresher.Replies.GetTokenFailed("purposefully failing")
          } else if (! shallTimeout) {
            replyTo ! GetTokenSuccess(UUID.randomUUID().toString, expiresInSeconds, Option(new JSONObject("{}")))
            tokenRefreshCount += 1
          }
          Behaviors.stopped
      }
    }
  }
}
