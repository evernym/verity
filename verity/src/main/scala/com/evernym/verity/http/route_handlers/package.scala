package com.evernym.verity.http

import com.evernym.verity.http.common.HttpRouteBase

package object route_handlers {
  type HttpRouteWithPlatform = HttpRouteBase with PlatformServiceProvider
}
