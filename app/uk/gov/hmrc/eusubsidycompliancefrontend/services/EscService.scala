/*
 * Copyright 2022 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.eusubsidycompliancefrontend.services

import cats.data.EitherT
import cats.implicits.{catsSyntaxEq, catsSyntaxOptionId}
import com.google.inject.{Inject, Singleton}
import play.api.http.Status.{NOT_FOUND, OK}
import play.api.libs.json.{JsResult, JsSuccess, JsValue, Reads}
import uk.gov.hmrc.eusubsidycompliancefrontend.connectors.EscConnector
import uk.gov.hmrc.eusubsidycompliancefrontend.models._
import uk.gov.hmrc.eusubsidycompliancefrontend.models.types.{EORI, UndertakingRef}
import uk.gov.hmrc.eusubsidycompliancefrontend.syntax.FutureSyntax.FutureOps
import uk.gov.hmrc.eusubsidycompliancefrontend.syntax.HttpResponseSyntax.HttpResponseOps
import uk.gov.hmrc.http.UpstreamErrorResponse.WithStatusCode
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse, UpstreamErrorResponse}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class EscService @Inject() (escConnector: EscConnector)(implicit ec: ExecutionContext) {

  def createUndertaking(undertaking: Undertaking)(implicit hc: HeaderCarrier): Future[UndertakingRef] =
    escConnector
      .createUndertaking(undertaking)
      .map(handleResponse[UndertakingRef](_, "create undertaking"))

  def updateUndertaking(undertaking: Undertaking)(implicit hc: HeaderCarrier): Future[UndertakingRef] =
    escConnector
      .updateUndertaking(undertaking)
      .map(handleResponse[UndertakingRef](_, "update undertaking"))

  def retrieveUndertaking(eori: EORI)(implicit hc: HeaderCarrier): Future[Option[Undertaking]] =
    retrieveUndertakingAndHandleErrors(eori).map {
      case Right(undertakingOpt) => undertakingOpt
      case Left(ex) => throw ex
    }

  def retrieveUndertakingAndHandleErrors(eori: EORI)(implicit hc: HeaderCarrier): Future[Either[UpstreamErrorResponse, Option[Undertaking]]] = {

    def parseResponse(response: HttpResponse) =
      response
        .parseJSON[Undertaking]
        .map(j => Right(j.some))
        .getOrElse(sys.error("Error parsing Undertaking in ESC response"))

    EitherT(escConnector.retrieveUndertaking(eori))
      .flatMapF(r => parseResponse(r).toFuture)
      .recover {
        case WithStatusCode(NOT_FOUND) => None
      }
      .value
  }

  def addMember(undertakingRef: UndertakingRef, businessEntity: BusinessEntity)(implicit
    hc: HeaderCarrier
  ): Future[UndertakingRef] =
    escConnector
      .addMember(undertakingRef, businessEntity)
      .map(handleResponse[UndertakingRef](_, "add member"))

  def removeMember(undertakingRef: UndertakingRef, businessEntity: BusinessEntity)(implicit
    hc: HeaderCarrier
  ): Future[UndertakingRef] =
    escConnector
      .removeMember(undertakingRef, businessEntity)
      .map(handleResponse[UndertakingRef](_, "remove member"))

  def createSubsidy(subsidyUpdate: SubsidyUpdate)(implicit hc: HeaderCarrier): Future[UndertakingRef] =
    escConnector
      .createSubsidy(subsidyUpdate)
      .map(handleResponse[UndertakingRef](_, "create subsidy"))

  def retrieveSubsidy(
    subsidyRetrieve: SubsidyRetrieve
  )(implicit hc: HeaderCarrier): Future[UndertakingSubsidies] =
    escConnector
      .retrieveSubsidy(subsidyRetrieve)
      .map(handleResponse[UndertakingSubsidies](_, "retrieve subsidy"))

  def removeSubsidy(undertakingRef: UndertakingRef, nonHmrcSubsidy: NonHmrcSubsidy)(implicit
    hc: HeaderCarrier
  ): Future[UndertakingRef] =
    escConnector
      .removeSubsidy(undertakingRef, nonHmrcSubsidy)
      .map(handleResponse[UndertakingRef](_, "remove subsidy"))

  private def handleResponse[A](r: Either[ConnectorError, HttpResponse], action: String)(implicit reads: Reads[A]) =
    r.fold(
      _ => sys.error(s"Error executing $action"),
      response =>
        if (response.status =!= OK) sys.error(s"Error executing $action - Got response status: ${response.status}")
        else
          response
            .parseJSON[A]
            .getOrElse(sys.error(s"Error parsing response for $action"))
    )
}

object EscService {
  implicit val reads: Reads[UpstreamErrorResponse] = new Reads[UpstreamErrorResponse] {
    override def reads(json: JsValue): JsResult[UpstreamErrorResponse] =
      JsSuccess(
        UpstreamErrorResponse(
          (json \ "message").as[String],
          (json \ "statusCode").as[Int]
        )
      )
  }
}
