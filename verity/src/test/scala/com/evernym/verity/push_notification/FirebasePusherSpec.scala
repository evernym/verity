package com.evernym.verity.push_notification

import com.evernym.verity.constants.Constants.PUSH_COM_METHOD_MISMATCH_SENDER_ID_ERROR
import com.evernym.verity.util2.Status._
import com.evernym.verity.config.AppConfigWrapper
import com.evernym.verity.config.CommonConfig.{FCM_API_HOST, FCM_API_KEY, FCM_API_PATH}
import com.evernym.verity.testkit.CancelGloballyAfterFailure
import com.evernym.verity.testkit.BasicSpec


class FirebasePusherSpec extends BasicSpec with CancelGloballyAfterFailure {

  val serverKey: String = AppConfigWrapper.getStringReq(FCM_API_KEY)
  val serverHost: String = AppConfigWrapper.getStringReq(FCM_API_HOST)
  val serverPath: String = AppConfigWrapper.getStringReq(FCM_API_PATH)
  val pusher = new FirebasePusher(FirebasePushServiceParam(serverKey, serverHost, serverPath))
  val cm = "foo"
  val regId = "This is an id registered with Firebase for Connect.Me"
  val failureResponse = "{\"multicast_id\":1545725540981982271,\"success\":0,\"failure\":1,\"canonical_ids\":0,\"results\":[{\"error\":\"MismatchSenderId\"}]}"
  val successResponse = "{\"multicast_id\":1545725540981982271,\"success\":1,\"failure\":0,\"canonical_ids\":0}"

  "FirebasePusher" - {
    "should be able to parse a success response without an exception" in {
      val pnr : PushNotifResponse  = pusher.handleResponse(cm, successResponse, regId)
      pnr shouldBe PushNotifResponse(regId, MSG_DELIVERY_STATUS_SENT.statusCode, None, None)
    }
    "should be able to parse a failure response without an exception" in {
      val pnr : PushNotifResponse  = pusher.handleResponse(cm, failureResponse, regId)
      pnr shouldBe PushNotifResponse(cm, MSG_DELIVERY_STATUS_FAILED.statusCode, Some(MSG_DELIVERY_STATUS_FAILED.statusMsg),
        Some(PUSH_COM_METHOD_MISMATCH_SENDER_ID_ERROR))
    }
  }

}

