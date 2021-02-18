package com.evernym.verity.testkit.mock.agent

import com.evernym.verity.UrlParam
import com.evernym.verity.actor.testkit.{AgentDIDDetail, CommonSpecUtil}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.testkit.AgentWithMsgHelper
import org.json.JSONObject

/**
 * a mocked edge agent (which inherits properties of a mock agent with a message helper)
 */
class MockEdgeAgent(override val agencyEndpoint: UrlParam,
                    override val appConfig: AppConfig,
                    override val myDIDDetail: AgentDIDDetail = CommonSpecUtil.generateNewAgentDIDDetail()
                   ) extends AgentWithMsgHelper {

  import MockEdgeAgent._

  private var data = Map.empty[String, Any]

  def add(key: String, value: Any): Unit = data = data + (key -> value)

  def get[T](key: String): T = data(key).asInstanceOf[T]

  def inviteUrl: String = get[String](INVITE_URL)
  def inviteJsonObject: JSONObject = get[JSONObject](INVITE_JSON_OBJECT)

}

object MockEdgeAgent {
  val INVITE_URL = "inviteUrl"
  val INVITE_JSON_OBJECT = "inviteJsonObject"
}