package com.evernym.verity.http.route_handlers.restricted

import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.evernym.verity.actor.appStateManager.AppStateConstants._
import com.evernym.verity.actor.appStateManager._
import com.evernym.verity.constants.Constants._
import com.evernym.verity.http.HttpUtil.optionalEntityAs
import com.evernym.verity.http.common.BaseRequestHandler
import com.evernym.verity.http.common.CustomResponseHandler._
import com.evernym.verity.http.route_handlers.PlatformWithExecutor
import com.evernym.verity.util2.Status._
import com.typesafe.config.ConfigException.{BadPath, Missing}
import com.typesafe.config.ConfigValueType._
import com.typesafe.config.{Config, ConfigRenderOptions}

import scala.concurrent.Future
import scala.jdk.CollectionConverters._


trait HealthCheckEndpointHandler extends BaseRequestHandler {
  this: PlatformWithExecutor =>

  protected def buildConfigRenderOptions(origComments: String, comments: String,
                                         formatted: String, json: String): ConfigRenderOptions = {
    ConfigRenderOptions.defaults().
      setOriginComments(origComments.toUpperCase == YES).
      setComments(comments.toUpperCase == YES).
      setFormatted(formatted.toUpperCase == YES).
      setJson(json.toUpperCase == YES)
  }

  protected def getProperlyRenderedConfigValue(path: String, config: Config, cro: ConfigRenderOptions): String = {
    try {
      config.getValue(path).valueType() match {
        case LIST => "[" + config.getList(path).asScala.map(e => e.render(cro)).mkString(",") + "]"
        case OBJECT =>
          config.getConfig(path).root().asScala.map(e => e._1 + " -> " + e._2.render(cro)).mkString("\n\n")
        case NUMBER => config.getNumber(path).toString
        case BOOLEAN => config.getBoolean(path).toString
        case STRING => config.getString(path)
        case NULL => s"no config found at give path: '$path'"
      }
    } catch {
      case _@(_: Missing | _: BadPath) =>
        s"no config found at give path: '$path'"
    }
  }

  protected def buildGetConfigsResp(pathOpt: Option[String], cro: ConfigRenderOptions): String = {
    val config = platform.agentActorContext.appConfig.getLoadedConfig
    pathOpt.map { path =>
      getProperlyRenderedConfigValue(path, config, cro)
    }.getOrElse {
      config.root().asScala.map(e => e._1 + " -> " + e._2.render(cro)).mkString("\n\n")
    }
  }

  protected def getConfigs(origComments: String, comments: String, formatted: String,
                           json: String, pathOpt: Option[String]): Future[String] = {
    Future {
      val cro = buildConfigRenderOptions(origComments, comments, formatted, json)
      buildGetConfigsResp(pathOpt, cro)
    }
  }

  protected def updateAppStatus(uas: UpdateAppStatus): Future[Any] = {
    val causeDetail = CauseDetail(APP_STATUS_UPDATE_MANUAL.statusCode, uas.reason.getOrElse("manual-update"))
    publishAppStateEvent(SuccessEvent(ManualUpdate(uas.newStatus), uas.context.getOrElse(CONTEXT_MANUAL_UPDATE), causeDetail))
    Future("Done")
  }

  protected val healthCheckRoute: Route = {
    handleRestrictedRequest(exceptionHandler) { (_, _) =>
      path("agency" / "internal" / "health-check" / "application-state") {
        (get & pathEnd) {
          parameters(Symbol("detail").?) { detailOpt =>
            complete {
              val fut = if (detailOpt.map(_.toUpperCase).contains(YES)) {
                askAppStateManager(GetDetailedAppState)
              } else {
                askAppStateManager(GetEvents)
              }
              fut.map[ToResponseMarshallable] {
                case allEvents: AllEvents =>
                  handleExpectedResponse(allEvents.events.map(x => s"${x.state.toString}"))
                case detailedState: AppStateDetailed =>
                  handleExpectedResponse(detailedState.toResp)
                case e => handleUnexpectedResponse(e)
              }
            }
          }
        } ~
          (put & pathEnd & optionalEntityAs[UpdateAppStatus]) { uasOpt =>
            complete {
              updateAppStatus(uasOpt.getOrElse(UpdateAppStatus())).map[ToResponseMarshallable] {
                _ => OK
              }
            }
          }
      }
    }
  }
}


case class UpdateAppStatus(newStatus: String = STATUS_LISTENING, context: Option[String] = None, reason: Option[String] = None)
