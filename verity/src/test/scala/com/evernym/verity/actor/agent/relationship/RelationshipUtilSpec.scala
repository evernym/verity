package com.evernym.verity.actor.agent.relationship

import java.util.UUID

import com.evernym.verity.actor.agent.relationship.RelationshipUtil._
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.ledger.LedgerPoolConnManager
import com.evernym.verity.testkit.BasicSpecWithIndyCleanup
import com.evernym.verity.vault.WalletAPIParam
import com.evernym.verity.actor.agent.{WalletApiBuilder, WalletVerKeyCacheHelper}
import com.evernym.verity.actor.agent.relationship.Tags.{AGENT_KEY_TAG, EDGE_AGENT_KEY}
import com.evernym.verity.actor.wallet.{CreateNewKey, NewKeyCreated}
import com.evernym.verity.libindy.ledger.IndyLedgerPoolConnManager
import com.evernym.verity.libindy.wallet.LibIndyWalletProvider
import com.evernym.verity.util.TestWalletService
import com.evernym.verity.vault.wallet_api.WalletAPI
import org.scalatest.OptionValues


class RelationshipUtilSpec
  extends BasicSpecWithIndyCleanup
    with OptionValues {

  lazy val relDidPair: NewKeyCreated = walletAPI.createNewKey(CreateNewKey())

  lazy val relUtilParamDuringRecovery: RelUtilParam =
    RelUtilParam(new TestAppConfig(), Option("thisAgentKeyId"), None)

  lazy val relUtilParamPostRecovery: RelUtilParam =
    RelUtilParam(new TestAppConfig(), Option("SpUiyicXonPRdaJre4S1TJ"), Option(walletVerKeyCacheHelper))

  "RelationshipUtil" - {

    "before actor recovery completed" - {

      implicit val relUtilParam: RelUtilParam = relUtilParamDuringRecovery

      "when called 'prepareMyDidDoc' with 'agentKeyDID' same as provided in relUtilParam" - {
        "should create correct MyDidDic" in {
          val myDidDoc = prepareMyDidDoc(relDidPair.did, "thisAgentKeyId", Set.empty)

          myDidDoc.did shouldBe relDidPair.did
          myDidDoc.authorizedKeys.value shouldBe AuthorizedKeys(Seq(
            AuthorizedKey("thisAgentKeyId", "", Set.empty), AuthorizedKey(relDidPair.did, "", Set(EDGE_AGENT_KEY))
          ))
          myDidDoc.endpoints.value shouldBe Endpoints.init(
            Vector(RoutingServiceEndpoint("localhost:9000/agency/msg", Vector.empty, Seq("thisAgentKeyId"))))
        }
      }

      "when called 'prepareMyDidDoc' with 'agentKeyDID' different than provided in relUtilParam" - {
        "should create correct my DidDic" in {
          val myDidDoc = prepareMyDidDoc(relDidPair.did, "otherAgentKeyId", Set(EDGE_AGENT_KEY))
          myDidDoc.did shouldBe relDidPair.did
          myDidDoc.authorizedKeys.value shouldBe AuthorizedKeys(Seq(
            AuthorizedKey("otherAgentKeyId", "", Set(EDGE_AGENT_KEY)), AuthorizedKey(relDidPair.did, "", Set(EDGE_AGENT_KEY))
          ))
          myDidDoc.endpoints.value shouldBe Endpoints.empty
        }
      }

      "when called 'prepareTheirDidDoc'" - {
        "should create correct their DidDoc" in {
          val theirDidDoc = buildTheirDidDoc(relDidPair.did, "theirAgentKeyId")
          theirDidDoc.did shouldBe relDidPair.did
          theirDidDoc.authorizedKeys.value shouldBe AuthorizedKeys(Seq(
            AuthorizedKey("theirAgentKeyId", "", Set(AGENT_KEY_TAG)), AuthorizedKey(relDidPair.did, "", Set(EDGE_AGENT_KEY))
          ))
          theirDidDoc.endpoints.value shouldBe Endpoints.empty
        }
      }
    }

    "post actor recovery completed" - {

      implicit lazy val relUtilParam: RelUtilParam = relUtilParamPostRecovery

      "when called 'prepareMyDidDoc' with 'agentKeyDID' same as provided in relUtilParam" - {
        "should create correct MyDidDic" in {
          val myDidDoc = prepareMyDidDoc(relDidPair.did, "SpUiyicXonPRdaJre4S1TJ", Set.empty)

          myDidDoc.did shouldBe relDidPair.did
          myDidDoc.authorizedKeys.value shouldBe AuthorizedKeys(Seq(
            AuthorizedKey("SpUiyicXonPRdaJre4S1TJ", "F5BERxEyX6uDhgXCbizxJB1z3SGnjHbjfzwuTytuK4r5", Set.empty),
            AuthorizedKey(relDidPair.did, relDidPair.verKey, Set(EDGE_AGENT_KEY))))
          myDidDoc.endpoints.value shouldBe Endpoints.init(Vector(
            RoutingServiceEndpoint("localhost:9000/agency/msg", Seq.empty, Seq("SpUiyicXonPRdaJre4S1TJ")))
          )
        }
      }

      "when called 'prepareMyDidDoc' with 'agentKeyDID' different than provided in relUtilParam" - {
        "should create correct MyDidDic" in {
          val myDidDoc = prepareMyDidDoc(relDidPair.did, "M34tyavAr1ZQYmARN4Gt5D", Set(EDGE_AGENT_KEY))

          myDidDoc.did shouldBe relDidPair.did
          myDidDoc.authorizedKeys.value shouldBe AuthorizedKeys(Seq(
            AuthorizedKey("M34tyavAr1ZQYmARN4Gt5D", "BvNEb6sdZofpMXkDCeXt4RAf6ZDEUN7ayhdokgYgrk3C", Set(EDGE_AGENT_KEY)),
            AuthorizedKey(relDidPair.did, relDidPair.verKey, Set(EDGE_AGENT_KEY))))
          myDidDoc.endpoints.value shouldBe Endpoints.empty
        }
      }

      "when called 'prepareTheirDidDoc'" - {
        "should create correct their DidDoc" in {
          val theirDidDoc = buildTheirDidDoc(relDidPair.did, "LQiamtmRRmSugTBWxQmxdE")

          theirDidDoc.did shouldBe relDidPair.did
          theirDidDoc.authorizedKeys.value shouldBe AuthorizedKeys(
            Seq(AuthorizedKey("LQiamtmRRmSugTBWxQmxdE", "BaZ8deKgw7cdgewzhc9661kJEdngg2ZnvLqg2MnDRDAP", Set(AGENT_KEY_TAG)),
            AuthorizedKey(relDidPair.did, relDidPair.verKey, Set(EDGE_AGENT_KEY))))
          theirDidDoc.endpoints.value shouldBe Endpoints.empty
        }
      }

      "when called 'updatedDidDocWithMigratedAuthKeys' with DidDoc with legacy auth keys" - {
        "should migrate LegacyAuthorizedKey to AuthorizedKey" in {
          val myDidDoc = prepareMyDidDoc(relDidPair.did, "SpUiyicXonPRdaJre4S1TJ", Set.empty)(relUtilParamDuringRecovery)
          myDidDoc.authorizedKeys_!.keys shouldBe Seq(
            AuthorizedKey("SpUiyicXonPRdaJre4S1TJ", "", Set.empty),
            AuthorizedKey(relDidPair.did, "", Set(EDGE_AGENT_KEY)))
          val updatedDidDoc = updatedDidDocWithMigratedAuthKeys(Option(myDidDoc))(relUtilParamPostRecovery)
          updatedDidDoc.isDefined shouldBe true
          updatedDidDoc.foreach { dd =>
            dd.authorizedKeys.value shouldBe AuthorizedKeys(Seq(
              AuthorizedKey("SpUiyicXonPRdaJre4S1TJ", "F5BERxEyX6uDhgXCbizxJB1z3SGnjHbjfzwuTytuK4r5", Set.empty),
              AuthorizedKey(relDidPair.did, relDidPair.verKey, Set(EDGE_AGENT_KEY))))
          }
        }
      }

      "when called 'updatedDidDocWithMigratedAuthKeys' with DidDoc with legacy and other duplicate auth keys" - {
        "should migrate LegacyAuthorizedKey to AuthorizedKey" in {
          val myDidDoc = prepareMyDidDoc(relDidPair.did, "SpUiyicXonPRdaJre4S1TJ", Set.empty)(relUtilParamDuringRecovery)
          val updatedDidDoc = myDidDoc.updatedWithNewAuthKey("F5BERxEyX6uDhgXCbizxJB1z3SGnjHbjfzwuTytuK4r5", "F5BERxEyX6uDhgXCbizxJB1z3SGnjHbjfzwuTytuK4r5", Set.empty)
          val updatedDidDocWithEndpoints = updatedDidDoc.updatedWithEndpoint(
            HttpEndpoint("1", "http://abc.xyz.com", Seq("F5BERxEyX6uDhgXCbizxJB1z3SGnjHbjfzwuTytuK4r5")))

          updatedDidDocWithEndpoints.authorizedKeys_!.keys shouldBe Seq(
            AuthorizedKey("SpUiyicXonPRdaJre4S1TJ", "", Set.empty),
            AuthorizedKey(relDidPair.did, "", Set(EDGE_AGENT_KEY)),
            AuthorizedKey("F5BERxEyX6uDhgXCbizxJB1z3SGnjHbjfzwuTytuK4r5", "F5BERxEyX6uDhgXCbizxJB1z3SGnjHbjfzwuTytuK4r5", Set.empty)
          )
          updatedDidDocWithEndpoints.endpoints_!.endpoints shouldBe Vector(EndpointADT(
            HttpEndpoint("1", "http://abc.xyz.com", Seq("F5BERxEyX6uDhgXCbizxJB1z3SGnjHbjfzwuTytuK4r5"))))

          val migratedDidDoc = updatedDidDocWithMigratedAuthKeys(Option(updatedDidDocWithEndpoints))(relUtilParamPostRecovery)
          migratedDidDoc.isDefined shouldBe true

          migratedDidDoc.foreach { dd =>
            dd.authorizedKeys.value shouldBe AuthorizedKeys(Seq(
              AuthorizedKey("SpUiyicXonPRdaJre4S1TJ", "F5BERxEyX6uDhgXCbizxJB1z3SGnjHbjfzwuTytuK4r5", Set.empty),
              AuthorizedKey(relDidPair.did, relDidPair.verKey, Set(EDGE_AGENT_KEY))))
            dd.endpoints.value shouldBe Endpoints.init(Vector(HttpEndpoint("1", "http://abc.xyz.com", Seq("SpUiyicXonPRdaJre4S1TJ"))))
          }
        }
      }

      "when called 'updatedDidDocWithMigratedAuthKeys' with DidDoc with standard auth keys" - {
        "should provide unmodified did doc" in {
          val myDidDoc = prepareMyDidDoc(relDidPair.did, "SpUiyicXonPRdaJre4S1TJ", Set.empty)(relUtilParamPostRecovery)
          myDidDoc.authorizedKeys_!.keys shouldBe Seq(
            AuthorizedKey("SpUiyicXonPRdaJre4S1TJ", "F5BERxEyX6uDhgXCbizxJB1z3SGnjHbjfzwuTytuK4r5", Set.empty),
            AuthorizedKey(relDidPair.did, relDidPair.verKey, Set(EDGE_AGENT_KEY))
          )
          val updatedDidDoc = updatedDidDocWithMigratedAuthKeys(Option(myDidDoc))(relUtilParamPostRecovery)
          updatedDidDoc.isDefined shouldBe true
          updatedDidDoc.foreach { dd =>
            dd shouldBe myDidDoc
          }
        }
      }
    }
  }

  val appConfig = new TestAppConfig()
  val poolConnManager: LedgerPoolConnManager = new IndyLedgerPoolConnManager(appConfig)
  val walletProvider = new LibIndyWalletProvider(appConfig)
  val walletService = new TestWalletService(appConfig, walletProvider)
  implicit lazy val walletAPI: WalletAPI = WalletApiBuilder.createWalletAPI(
    appConfig, walletService, walletProvider)
  implicit lazy val wap: WalletAPIParam = {
    val wp = WalletAPIParam(UUID.randomUUID().toString)
    walletAPI.createWallet(wp)
    wp
  }

  lazy val walletVerKeyCacheHelper: WalletVerKeyCacheHelper = {
    walletAPI.createNewKey(CreateNewKey(seed = Option("0000000000000000000000000000TEST")))          //key to represent current/this agent
    walletAPI.createNewKey(CreateNewKey(seed = Option("000000000000000000000000000OTHER")))          //key to represent some other agent
    walletAPI.createNewKey(CreateNewKey(seed = Option("000000000000000000000000000THEIR")))          //key to represent their agent
    new WalletVerKeyCacheHelper(wap, walletAPI, appConfig)
  }
}
