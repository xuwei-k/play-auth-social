package com.github.tototoshi.play.social.core

import jp.t2v.lab.play2.auth.{ AuthConfig, OptionalAuthElement }
import play.api._
import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.oauth._
import play.api.mvc._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

trait OAuth10aController extends Controller {
  self: OptionalAuthElement with AuthConfig =>

  val authenticator: OAuth10aAuthenticator

  type AccessToken = authenticator.AccessToken

  def login = AsyncStack { implicit request =>
    loggedIn match {
      case Some(_) => loginSucceeded(request)
      case None => authenticator.oauth.retrieveRequestToken(authenticator.callbackURL) match {
        case Right(token) =>
          Future.successful(
            Redirect(authenticator.oauth.redirectUrl(token.token)).withSession(
              request.session + ("play.social.requestTokenSecret" -> token.secret)
            )
          )
        case Left(e) =>
          Logger(getClass).error(e.getMessage)
          Future.successful(InternalServerError(e.getMessage))
      }
    }
  }

  def link = AsyncStack { implicit request =>
    loggedIn match {
      case Some(_) =>
        authenticator.oauth.retrieveRequestToken(authenticator.callbackURL) match {
          case Right(token) =>
            Future.successful(
              Redirect(authenticator.oauth.redirectUrl(token.token)).withSession(
                request.session + ("play.social.requestTokenSecret" -> token.secret)
              )
            )
          case Left(e) =>
            Logger(getClass).error(e.getMessage)
            Future.successful(InternalServerError(e.getMessage))
        }
      case None =>
        loginSucceeded(request)
    }
  }

  def authorize = AsyncStack { implicit request =>
    val form = Form(
      tuple(
        "oauth_token" -> optional(nonEmptyText),
        "oauth_verifier" -> optional(nonEmptyText),
        "denied" -> optional(nonEmptyText)
      )
    )

    form.bindFromRequest.fold({
      formWithError => Future.successful(BadRequest)
    }, {
      case (Some(oauthToken), Some(oauthVerifier), None) =>
        val action: AccessToken => Future[Result] = loggedIn match {
          case Some(consumerUser) => gotoLinkSucceeded(_, consumerUser)
          case None => gotoLoginSucceeded
        }
        (for {
          tokenSecret <- request.session.get("play.social.requestTokenSecret")
          requestToken = RequestToken(oauthToken, tokenSecret)
          token <- authenticator.oauth.retrieveAccessToken(
            requestToken, oauthVerifier
          ).right.toOption
        } yield {
          action(requestTokenToAccessToken(token))
        }).getOrElse(Future.successful(BadRequest))

      case (None, None, Some(denied)) => Future.successful(Unauthorized)
      case _ => Future.successful(BadRequest)
    })

  }

  def requestTokenToAccessToken(requestToken: RequestToken): AccessToken

  def gotoLoginSucceeded(accessToken: AccessToken)(implicit request: RequestHeader): Future[Result]

  def gotoLinkSucceeded(accessToken: AccessToken, consumerUser: User)(implicit request: RequestHeader): Future[Result]

}

