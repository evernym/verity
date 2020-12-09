package com.evernym.verity.actor

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.cluster.singleton.{ClusterSingletonProxy, ClusterSingletonProxySettings}
import akka.testkit.{ImplicitSender, TestKitBase}
import com.evernym.verity.ReqId
import com.evernym.verity.actor.MetricsFilterCriteria
import com.evernym.verity.actor.testkit.AkkaTestBasic
import com.evernym.verity.actor.testkit.actor.ProvidesMockPlatform
import com.evernym.verity.metrics.MetricsReader
import com.evernym.verity.testkit.BasicSpec
import org.scalatest.concurrent.Eventually
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.Main.platform.createRegion
import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.actor.agent.user.UserAgent
import com.evernym.verity.actor.wallet.{DeleteWallet, WalletActor, WalletDeleted}
import com.evernym.verity.config.CommonConfig.ACTOR_DISPATCHER_NAME_USER_AGENT
import com.evernym.verity.constants.ActorNameConstants.{CLUSTER_SINGLETON_MANAGER_PROXY, USER_AGENT_REGION_ACTOR_NAME}

class WalletActorSpec extends TestKitBase
  with ProvidesMockPlatform
  with BasicSpec
  with ImplicitSender
  with Eventually {

  implicit lazy val system: ActorSystem = AkkaTestBasic.system()
//  lazy val agentActorContext: AgentActorContext = platform.agentActorContext
//  lazy val clientIpAddress: String = "127.0.0.1"
//  lazy val reqId: ReqId = UUID.randomUUID().toString

  "WalletActor" - {

    "creating WalletActor" - {
      "should create and open wallet" in {
//        val walletActorRegion: ActorRef = createRegion(
//          USER_AGENT_REGION_ACTOR_NAME,
//          buildProp(Props(new WalletActor(agentActorContext)), Option(ACTOR_DISPATCHER_NAME_USER_AGENT)))

        val walletActor =
          agentActorContext.system.actorOf(Props(new WalletActor(agentActorContext.appConfig)), name = "WALLET_ACTOR")

        walletActor ! DeleteWallet()
        expectMsgType[WalletDeleted]

      }
    }
  }


}
