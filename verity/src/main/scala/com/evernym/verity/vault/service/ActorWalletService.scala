package com.evernym.verity.vault.service

import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.sharding.ClusterSharding
import akka.pattern.ask
import akka.util.Timeout
import com.evernym.verity.actor.ForIdentifier
import com.evernym.verity.actor.wallet.WalletCommand
import com.evernym.verity.config.AppConfig
import com.evernym.verity.constants.ActorNameConstants.WALLET_REGION_ACTOR_NAME
import com.evernym.verity.observability.metrics.{MetricsWriter, MetricsWriterExtension}

import java.util.concurrent.TimeUnit
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._


class ActorWalletService(system: ActorSystem, appConfigParam: AppConfig, walletExecutionContext: ExecutionContext)
  extends WalletService {

  override def futureWalletExecutionContext: ExecutionContext = walletExecutionContext
  lazy val walletActorRegion: ActorRef = ClusterSharding(system).shardRegion(WALLET_REGION_ACTOR_NAME)

  override val metricsWriter: MetricsWriter = MetricsWriterExtension(system).get()

  override def execute(walletId: String, cmd: WalletCommand): Future[Any] = {
    //TODO: finalize timeout
    walletActorRegion.ask(ForIdentifier(walletId, cmd))(Timeout(FiniteDuration(200, TimeUnit.SECONDS)))
  }

  override def tell(walletId: String, cmd: WalletCommand)(implicit sender: ActorRef): Unit = {
    walletActorRegion.tell(ForIdentifier(walletId, cmd), sender)
  }
}
