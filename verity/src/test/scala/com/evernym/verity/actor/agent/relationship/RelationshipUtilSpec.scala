package com.evernym.verity.actor.agent.relationship

import com.evernym.verity.actor.agent.relationship.RelationshipUtil._
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.ledger.LedgerPoolConnManager
import com.evernym.verity.libindy.{IndyLedgerPoolConnManager, LibIndyWalletProvider}
import com.evernym.verity.testkit.BasicSpecWithIndyCleanup
import com.evernym.verity.util.Util
import com.evernym.verity.vault.WalletUtil.buildWalletConfig
import com.evernym.verity.vault.{WalletAPI, WalletAccessParam, WalletConfig}
import Endpoints._
import com.evernym.verity.actor.agent.WalletVerKeyCacheHelper
import com.evernym.verity.actor.agent.relationship.Tags.{AGENT_KEY_TAG, EDGE_AGENT_KEY}
import org.scalatest.OptionValues

class RelationshipUtilSpec extends BasicSpecWithIndyCleanup with OptionValues {

  lazy val relUtilParamDuringRecovery: RelUtilParam =
    RelUtilParam(new TestAppConfig(), Option("thisAgentKeyId"), None)

  lazy val relUtilParamPostRecovery: RelUtilParam =
    RelUtilParam(new TestAppConfig(), Option("SpUiyicXonPRdaJre4S1TJ"), Option(walletVerKeyCacheHelper))

  "RelationshipUtil" - {

    "before actor recovery completed" - {

      implicit val relUtilParam: RelUtilParam = relUtilParamDuringRecovery

      "when called 'prepareMyDidDoc' with 'agentKeyDID' same as provided in relUtilParam" - {
        "should create correct MyDidDic" in {
          val myDidDoc = buildMyDidDoc("relDID", "thisAgentKeyId", Set.empty)

          myDidDoc.did shouldBe "relDID"
          myDidDoc.authorizedKeys.value shouldBe AuthorizedKeys(Seq(AuthorizedKey("thisAgentKeyId", "", Set.empty)))
          myDidDoc.endpoints.value shouldBe Endpoints.init(
            Vector(RoutingServiceEndpoint("localhost:9000/agency/msg", Vector.empty)),
            Set("thisAgentKeyId"))
        }
      }

      "when called 'prepareMyDidDoc' with 'agentKeyDID' different than provided in relUtilParam" - {
        "should create correct my DidDic" in {
          val myDidDoc = buildMyDidDoc("relDID", "otherAgentKeyId", Set(EDGE_AGENT_KEY))
          myDidDoc.did shouldBe "relDID"
          myDidDoc.authorizedKeys.value shouldBe AuthorizedKeys(Seq(AuthorizedKey("otherAgentKeyId", "", Set(EDGE_AGENT_KEY))))
          myDidDoc.endpoints.value shouldBe Endpoints.empty
        }
      }

      "when called 'prepareTheirDidDoc'" - {
        "should create correct their DidDoc" in {
          val theirDidDoc = buildTheirDidDoc("relDID", "theirAgentKeyId")
          theirDidDoc.did shouldBe "relDID"
          theirDidDoc.authorizedKeys.value shouldBe AuthorizedKeys(Seq(AuthorizedKey("theirAgentKeyId", "", Set(AGENT_KEY_TAG))))
          theirDidDoc.endpoints.value shouldBe Endpoints.empty
        }
      }
    }

    "post actor recovery completed" - {

      implicit lazy val relUtilParam: RelUtilParam = relUtilParamPostRecovery

      "when called 'prepareMyDidDoc' with 'agentKeyDID' same as provided in relUtilParam" - {
        "should create correct MyDidDic" in {
          val myDidDoc = buildMyDidDoc("relDID", "SpUiyicXonPRdaJre4S1TJ", Set.empty)

          myDidDoc.did shouldBe "relDID"
          myDidDoc.authorizedKeys.value shouldBe AuthorizedKeys(Seq(
            AuthorizedKey("SpUiyicXonPRdaJre4S1TJ", "F5BERxEyX6uDhgXCbizxJB1z3SGnjHbjfzwuTytuK4r5", Set.empty)))
          myDidDoc.endpoints.value shouldBe Endpoints(Vector(
            RoutingServiceEndpoint("localhost:9000/agency/msg", Seq.empty)),
            Map("0" -> KeyIds(Set("SpUiyicXonPRdaJre4S1TJ")))
          )
        }
      }

      "when called 'prepareMyDidDoc' with 'agentKeyDID' different than provided in relUtilParam" - {
        "should create correct MyDidDic" in {
          val myDidDoc = buildMyDidDoc("relDID", "M34tyavAr1ZQYmARN4Gt5D", Set(EDGE_AGENT_KEY))

          myDidDoc.did shouldBe "relDID"
          myDidDoc.authorizedKeys.value shouldBe AuthorizedKeys(Seq(
            AuthorizedKey("M34tyavAr1ZQYmARN4Gt5D", "BvNEb6sdZofpMXkDCeXt4RAf6ZDEUN7ayhdokgYgrk3C", Set(EDGE_AGENT_KEY))))
          myDidDoc.endpoints.value shouldBe Endpoints.empty
        }
      }

      "when called 'prepareTheirDidDoc'" - {
        "should create correct their DidDoc" in {
          val theirDidDoc = buildTheirDidDoc("relDID", "LQiamtmRRmSugTBWxQmxdE")

          theirDidDoc.did shouldBe "relDID"
          theirDidDoc.authorizedKeys.value shouldBe AuthorizedKeys(
            Seq(AuthorizedKey("LQiamtmRRmSugTBWxQmxdE", "BaZ8deKgw7cdgewzhc9661kJEdngg2ZnvLqg2MnDRDAP", Set(AGENT_KEY_TAG))))
          theirDidDoc.endpoints.value shouldBe Endpoints.empty
        }
      }

      "when called 'updatedDidDocWithMigratedAuthKeys' with DidDoc with legacy auth keys" - {
        "should migrate LegacyAuthorizedKey to AuthorizedKey" in {
          val myDidDoc = buildMyDidDoc("relDID", "SpUiyicXonPRdaJre4S1TJ", Set.empty)(relUtilParamDuringRecovery)
          myDidDoc.authorizedKeys_!.keys shouldBe Seq(AuthorizedKey("SpUiyicXonPRdaJre4S1TJ", "", Set.empty))
          val updatedDidDoc = updatedDidDocWithMigratedAuthKeys(Option(myDidDoc))(relUtilParamPostRecovery)
          updatedDidDoc.isDefined shouldBe true
          updatedDidDoc.foreach { dd =>
            dd.authorizedKeys.value shouldBe AuthorizedKeys(Seq(
              AuthorizedKey("SpUiyicXonPRdaJre4S1TJ", "F5BERxEyX6uDhgXCbizxJB1z3SGnjHbjfzwuTytuK4r5", Set.empty)))
          }
        }
      }

      "when called 'updatedDidDocWithMigratedAuthKeys' with DidDoc with legacy and other duplicate keys" - {
        "should migrate LegacyAuthorizedKey to AuthorizedKey" in {
          val myDidDoc = buildMyDidDoc("relDID", "SpUiyicXonPRdaJre4S1TJ", Set.empty)(relUtilParamDuringRecovery)
          val updatedDidDoc = myDidDoc.updatedWithNewAuthKey("F5BERxEyX6uDhgXCbizxJB1z3SGnjHbjfzwuTytuK4r5", "F5BERxEyX6uDhgXCbizxJB1z3SGnjHbjfzwuTytuK4r5", Set.empty)
          val updatedDidDocWithEndpoints = updatedDidDoc.updatedWithEndpoint(PushEndpoint("1", "123"), Set("F5BERxEyX6uDhgXCbizxJB1z3SGnjHbjfzwuTytuK4r5"))

          updatedDidDocWithEndpoints.authorizedKeys_!.keys shouldBe Seq(
            AuthorizedKey("SpUiyicXonPRdaJre4S1TJ", "", Set.empty),
            AuthorizedKey("F5BERxEyX6uDhgXCbizxJB1z3SGnjHbjfzwuTytuK4r5", "F5BERxEyX6uDhgXCbizxJB1z3SGnjHbjfzwuTytuK4r5", Set.empty)
          )

          updatedDidDocWithEndpoints.endpoints_!.endpoints shouldBe Vector(EndpointADT(PushEndpoint("1", "123")))
          updatedDidDocWithEndpoints.endpoints_!.endpointsToAuthKeys shouldBe Map("1" -> KeyIds(Set("F5BERxEyX6uDhgXCbizxJB1z3SGnjHbjfzwuTytuK4r5")))

          val migratedDidDoc = updatedDidDocWithMigratedAuthKeys(Option(updatedDidDocWithEndpoints))(relUtilParamPostRecovery)
          migratedDidDoc.isDefined shouldBe true

          migratedDidDoc.foreach { dd =>
            dd.authorizedKeys.value shouldBe AuthorizedKeys(Seq(AuthorizedKey("SpUiyicXonPRdaJre4S1TJ", "F5BERxEyX6uDhgXCbizxJB1z3SGnjHbjfzwuTytuK4r5", Set.empty)))
            dd.endpoints.value shouldBe Endpoints(Vector(PushEndpoint("1", "123")), Map("1" -> KeyIds(Set("SpUiyicXonPRdaJre4S1TJ"))))
          }
        }
      }

      "when called 'updatedDidDocWithMigratedAuthKeys' with DidDoc with standard auth keys" - {
        "should provide unmodified did doc" in {
          val myDidDoc = buildMyDidDoc("relDID", "SpUiyicXonPRdaJre4S1TJ", Set.empty)(relUtilParamPostRecovery)
          myDidDoc.authorizedKeys_!.keys shouldBe Seq(AuthorizedKey("SpUiyicXonPRdaJre4S1TJ", "F5BERxEyX6uDhgXCbizxJB1z3SGnjHbjfzwuTytuK4r5", Set.empty))
          val updatedDidDoc = updatedDidDocWithMigratedAuthKeys(Option(myDidDoc))(relUtilParamPostRecovery)
          updatedDidDoc.isDefined shouldBe true
          updatedDidDoc.foreach { dd =>
            dd shouldBe myDidDoc
          }
        }
      }
    }
  }

  lazy val walletVerKeyCacheHelper: WalletVerKeyCacheHelper = {
    val appConfig = new TestAppConfig()
    val poolConnManager: LedgerPoolConnManager = new IndyLedgerPoolConnManager(appConfig)
    val walletAPI: WalletAPI = new WalletAPI(new LibIndyWalletProvider(appConfig), Util, poolConnManager)
    val walletConfig: WalletConfig = buildWalletConfig(appConfig)
    val wap = WalletAccessParam("encryption-key-seed", walletAPI, walletConfig, appConfig, closeAfterUse = false)
    val wh = walletAPI.createAndOpenWallet(wap)
    wh.createNewKey(seedOption = Option("0000000000000000000000000000TEST"))          //key to represent current/this agent
    wh.createNewKey(seedOption = Option("000000000000000000000000000OTHER"))          //key to represent some other agent
    wh.createNewKey(seedOption = Option("000000000000000000000000000THEIR"))          //key to represent their agent
    new WalletVerKeyCacheHelper(wap, walletAPI, appConfig)
  }
}
