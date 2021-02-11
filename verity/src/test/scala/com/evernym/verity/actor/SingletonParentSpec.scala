package com.evernym.verity.actor

import com.evernym.verity.actor.cluster_singleton.{AddMapping, ForKeyValueMapper, GetValue}
import com.evernym.verity.actor.testkit.PersistentActorSpec
import com.evernym.verity.constants.Constants.AGENCY_DID_KEY
import com.evernym.verity.metrics.AllNodeMetricsData
import com.evernym.verity.testkit.BasicSpec


class SingletonParentSpec extends PersistentActorSpec with BasicSpec {

  singletonParentSpecs()

  def singletonParentSpecs(): Unit = {

    "SingletonParent" - {

      "KeyValueMapper" - {
        "when sent AddMapping command" - {
          "should respond with MappingAdded" in {
            singletonParentProxy ! ForKeyValueMapper(AddMapping(AGENCY_DID_KEY, "someDIDForAgency"))
            expectMsgPF() {
              case ma: MappingAdded if ma.key == AGENCY_DID_KEY && ma.value == "someDIDForAgency" =>
            }
          }
        }

        "when sent GetValue command" - {
          "should respond with previously added value for that key" in {
            singletonParentProxy ! ForKeyValueMapper(GetValue(AGENCY_DID_KEY))
            expectMsgPF() {
              case Some("someDIDForAgency") =>
            }
          }
        }
      }

      "Other" - {
        "when sent RefreshConfigOnAllNodes command" - {
          "should respond with ConfigRefreshed" in {
            singletonParentProxy ! RefreshConfigOnAllNodes
            expectMsgPF() {
              case ConfigRefreshed =>
            }
          }
        }

        "when sent GetMetricsOfAllNodes command" - {
          "should respond with AllNodeMetricsData" in {
            singletonParentProxy ! GetMetricsOfAllNodes(MetricsFilterCriteria())
            expectMsgPF() {
              case _: AllNodeMetricsData =>
            }
          }
        }
      }

    }
  }

}
