package com.evernym.verity.vault.service

import akka.pattern.ask
import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.sharding.ClusterSharding
import com.evernym.verity.actor.ForIdentifier
import com.evernym.verity.constants.ActorNameConstants.WALLET_REGION_ACTOR_NAME

import scala.concurrent.Future


class ActorWalletService(system: ActorSystem) extends WalletService {
  lazy val walletActorRegion: ActorRef = ClusterSharding(system).shardRegion(WALLET_REGION_ACTOR_NAME)

  override def execute(walletId: String, cmd: Any): Future[Any] = {
    walletActorRegion ? ForIdentifier(walletId, cmd)
  }
}
