package com.github.tototoshi.play.social.core

import java.util.UUID

import jp.t2v.lab.play2.auth.{ AuthConfig, OptionalAuthElement }
import play.api._
import play.api.data.Form
import play.api.data.Forms._
import play.api.mvc._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.control.NonFatal

trait OAuth2Controller extends Controller { self: OptionalAuthElement with AuthConfig =>

  val authenticator: OAuth2Authenticator

  type AccessToken = authenticator.AccessToken

  val OAUTH2_STATE_KEY = "play.auth.social.oauth2.state"

  // TODO scope is optional in some services
  // TODO some services have more optional parameter
  def login(scope: String) = AsyncStack { implicit request =>
    loggedIn match {
      case Some(u) =>
        loginSucceeded(request)
      case None =>
        // should be more random ?
        val state = UUID.randomUUID().toString
        Future.successful(
          Redirect(authenticator.getAuthorizationUrl(scope, state)).withSession(
            OAUTH2_STATE_KEY -> state
          )
        )
    }
  }

  // TODO scope is optional in some services
  // TODO some services have more optional parameter
  def link(scope: String) = AsyncStack { implicit request =>
    loggedIn match {
      case Some(u) =>
        // TODO should it be more random ?
        val state = UUID.randomUUID().toString
        Future.successful(
          Redirect(authenticator.getAuthorizationUrl(scope, state)).withSession(
            OAUTH2_STATE_KEY -> state
          )
        )
      case None =>
        Future.successful(Unauthorized)
    }
  }

  def authorize = AsyncStack { implicit request =>
    val form = Form(
      tuple(
        "code" -> nonEmptyText,
        "state" -> nonEmptyText.verifying { state =>
          state == request.session.get(OAUTH2_STATE_KEY).getOrElse("")
        }
      )
    )

    form.bindFromRequest.fold({
      formWithError => Future.successful(BadRequest)
    }, {
      case (code, _) =>
        val action: AccessToken => Future[Result] = loggedIn match {
          case Some(consumerUser) => gotoLinkSucceeded(_, consumerUser)
          case None => gotoLoginSucceeded
        }

        (for {
          token <- authenticator.retrieveAccessToken(code)
          result <- action(token)
        } yield result).recover {
          case NonFatal(e) =>
            Logger(getClass).error(e.getMessage, e)
            InternalServerError
        }
    })
  }

  def gotoLoginSucceeded(token: AccessToken)(implicit request: RequestHeader): Future[Result]

  def gotoLinkSucceeded(token: AccessToken, consumerUser: User)(implicit request: RequestHeader): Future[Result]

}
