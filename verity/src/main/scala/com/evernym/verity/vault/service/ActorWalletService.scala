package com.evernym.verity.vault.service

import java.util.concurrent.TimeUnit

import akka.pattern.ask
import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.sharding.ClusterSharding
import akka.util.Timeout
import com.evernym.verity.actor.ForIdentifier
import com.evernym.verity.constants.ActorNameConstants.WALLET_REGION_ACTOR_NAME

import scala.concurrent.Future
import scala.concurrent.duration._


class ActorWalletService(system: ActorSystem) extends WalletService {
  lazy val walletActorRegion: ActorRef = ClusterSharding(system).shardRegion(WALLET_REGION_ACTOR_NAME)

  override def execute(walletId: String, cmd: Any): Future[Any] = {
    //TODO: finalize timeout
    walletActorRegion.ask(ForIdentifier(walletId, cmd))(Timeout(FiniteDuration(40, TimeUnit.SECONDS)))
  }
}
