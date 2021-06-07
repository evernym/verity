package com.evernym.integrationtests.e2e.sdk

import java.util.concurrent.LinkedBlockingDeque
import com.evernym.verity.protocol.engine.Constants._
import com.evernym.verity.testkit.listener.Listener
import com.evernym.integrationtests.e2e.env.SdkConfig
import com.evernym.integrationtests.e2e.scenario.Scenario
import com.evernym.verity.sdk.handlers.Handlers
import com.evernym.verity.sdk.utils.Context
import org.json.JSONObject
import org.scalatest.concurrent.Eventually
import org.scalatest.concurrent.PatienceConfiguration.Interval
import org.scalatest.time.{Millis, Span}

import scala.concurrent.duration.Duration
import scala.language.postfixOps

trait MsgReceiver extends Eventually {
  def shouldExpectMsg(): Boolean = true
  def shouldCheckMsg(): Boolean = true

  final def expectMsgOnly(expectedName: String)
                         (implicit scenario: Scenario): Unit = {
    expectMsg(expectedName, None){ _ => ()}
  }

  // Don't return the message but instead will run the check function that
  // can be used for asserting things about the message. Do not assert outside
  // of the check function so that the ManualSdkProvider which don't provide
  // messages can still work.
  final def expectMsg(expectedName: String,
                      max: Option[Duration] = None)
                     (check: JSONObject => Unit)
                     (implicit scenario: Scenario): Unit = {
    checkAndExpectMsg(Some(expectedName), max)(check)
  }
  final def checkMsg(max: Option[Duration] = None)
                    (check: JSONObject => Unit)
                    (implicit scenario: Scenario): Unit = {
    checkAndExpectMsg(None, max)(check)
  }
  private def checkAndExpectMsg(expectedName: Option[String],
                                max: Option[Duration] = None)
                               (check: JSONObject => Unit)
                               (implicit scenario: Scenario): Unit = {
    def expecting: String = expectedName.map(x=>s"(expecting: $x) ").getOrElse("")
    def lastReceived(msg: JSONObject): String = s"(last received: ${msg.getString(`@TYPE`)})"

    if(shouldExpectMsg()){
      val waitTimeout = max.getOrElse(scenario.timeout)

      var msg = new JSONObject().put(`@TYPE`, "UnreceivedType")

      eventually(timeout(waitTimeout), Interval(Span(200, Millis))) {
        try {
          msg = expectMsg(waitTimeout)
        }
        catch {
          case e: Exception =>
            throw new Exception(
              s"msg not received $expecting-- ${e.getMessage} ${lastReceived(msg)}"
            )
        }

        if(shouldCheckMsg()) {
          val msgType = try {
            msg.getString(`@TYPE`)
          } catch {
            case e: Exception =>
              throw new Exception(s"Unable to get message type for $expecting-- ${msg.toString()} ${lastReceived(msg)}")
          }
          expectedName.foreach { x =>
            assert (
              msgType.endsWith(x),
              s"Unexpected message name -- $msgType is not $expecting-- ${msg.toString(2)} ${lastReceived(msg)}"
            )
          }
          check(msg)
        }
      }
    }
  }
  protected def expectMsg(max: Duration): JSONObject
  def context: Context
}

trait ListeningSdkProvider extends MsgReceiver {
  protected var listener: Listener = _
  private val queue: LinkedBlockingDeque[JSONObject] = new LinkedBlockingDeque[JSONObject]()

  def receiveMsg(msg: JSONObject): Unit = queue.offerFirst(msg)

  def expectMsg(max: Duration): JSONObject = {
    val m = Option {
      if (max == Duration.Zero) {
        queue.pollFirst
      } else if (max.isFinite) {
        queue.pollFirst(max.length, max.unit)
      } else {
        queue.takeFirst
      }
    }

    sdkConfig.receiveSpacing.foreach{ d =>
      Thread.sleep(d.toMillis)
    }
    m.getOrElse(throw new Exception(s"timeout ($max) during expectMsg while waiting for message"))
  }

  def sdkConfig: SdkConfig
  def port: Int = sdkConfig.port.getOrElse(throw new Exception("Port must be defined for ListeningSdkProvider"))
  def endpointUrl: String = {
    sdkConfig
      .endpoint
      .getOrElse(throw new Exception("Endpoint must be defined for ListeningSdkProvider"))
      .url
  }

  def context: Context

  def startListener(): Unit = {
    val handles = new Handlers()

    handles.addDefaultHandler({msg: JSONObject =>
      receiveMsg(msg)
    })

    listener = new Listener(port, { encryptMsg  =>
      handles.handleMessage(context, encryptMsg)
    })

    listener.listen()
  }

  def stopListener: Unit = {
    listener.stop()
  }
}
