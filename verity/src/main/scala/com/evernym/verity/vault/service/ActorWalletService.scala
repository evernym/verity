package com.evernym.verity.vault.service

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import com.evernym.verity.actor.{ForIdentifier, ShardUtil}
import com.evernym.verity.actor.wallet.{WalletActor, WalletCommand}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.ConfigConstants.NON_PERSISTENT_WALLET_ACTOR_PASSIVATE_TIME_IN_SECONDS
import com.evernym.verity.constants.ActorNameConstants.WALLET_REGION_ACTOR_NAME
import com.evernym.verity.ledger.LedgerPoolConnManager
import com.evernym.verity.observability.metrics.{MetricsWriter, MetricsWriterExtension}

import java.util.concurrent.TimeUnit
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._


class ActorWalletService(system: ActorSystem,
                         appConfigParam: AppConfig,
                         poolConnManager: LedgerPoolConnManager,
                         executionContext: ExecutionContext)
  extends WalletService with ShardUtil {

  override def appConfig: AppConfig = appConfigParam

  override def futureExecutionContext: ExecutionContext = executionContext
  val walletActorRegion: ActorRef = createNonPersistentRegion(
    WALLET_REGION_ACTOR_NAME,
    Props(
      new WalletActor(
        appConfig,
        poolConnManager,
        executionContext
      )
    ),
    passivateIdleEntityAfter = Some(appConfig.getIntOption(NON_PERSISTENT_WALLET_ACTOR_PASSIVATE_TIME_IN_SECONDS).getOrElse(600).seconds)
  )(system)

  override val metricsWriter: MetricsWriter = MetricsWriterExtension(system).get()

  override def execute(walletId: String, cmd: WalletCommand): Future[Any] = {
    walletActorRegion.ask(ForIdentifier(walletId, cmd))(Timeout(FiniteDuration(200, TimeUnit.SECONDS)))
  }

  override def tell(walletId: String, cmd: WalletCommand)(implicit sender: ActorRef): Unit = {
    walletActorRegion.tell(ForIdentifier(walletId, cmd), sender)
  }
}
