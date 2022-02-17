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

package uk.gov.hmrc.eusubsidycompliancefrontend.controllers

import cats.data.OptionT
import play.api.mvc.{Action, AnyContent, MessagesControllerComponents}
import uk.gov.hmrc.eusubsidycompliancefrontend.actions.EscActionBuilders
import uk.gov.hmrc.eusubsidycompliancefrontend.config.AppConfig
import uk.gov.hmrc.eusubsidycompliancefrontend.models.types.EORI
import uk.gov.hmrc.eusubsidycompliancefrontend.models.{SubsidyRetrieve, Undertaking, UndertakingSubsidies}
import uk.gov.hmrc.eusubsidycompliancefrontend.services.{EscService, Store}
import uk.gov.hmrc.eusubsidycompliancefrontend.util.TaxYearSyntax.LocalDateTaxYearOps
import uk.gov.hmrc.eusubsidycompliancefrontend.util.TimeProvider
import uk.gov.hmrc.eusubsidycompliancefrontend.views.html.FinancialDashboardPage
import uk.gov.hmrc.eusubsidycompliancefrontend.views.models.FinancialDashboardSummary

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import cats.implicits._

@Singleton
class FinancialDashboardController @Inject()(
  escActionBuilders: EscActionBuilders,
  escService: EscService,
  financialDashboardPage: FinancialDashboardPage,
  mcc: MessagesControllerComponents,
  store: Store,
  timeProvider: TimeProvider,
)(implicit val appConfig: AppConfig, ec: ExecutionContext) extends BaseController(mcc) {

  import escActionBuilders._

  def getFinancialDashboard: Action[AnyContent] = escAuthentication.async { implicit request =>
    implicit val eori: EORI = request.eoriNumber

    // THe search period covers the current tax year to date, and the previous 2 tax years.
    val searchDateStart = timeProvider.today.minusYears(2).toTaxYearStart
    val searchDateEnd = timeProvider.today
    val currentTaxYearEnd = timeProvider.today.toTaxYearEnd

    val searchRange = Some((searchDateStart, searchDateEnd))

    val subsidies: Future[(Undertaking, UndertakingSubsidies)] = for {
      undertaking <- OptionT(store.get[Undertaking]).getOrElse(handleMissingSessionData("Undertaking"))
      r = undertaking.reference.getOrElse(handleMissingSessionData("Undertaking reference"))
      s = SubsidyRetrieve(r, searchRange)
      subsidies <- escService.retrieveSubsidy(s)
    } yield (undertaking, subsidies)

    subsidies.map {
      case (undertaking, subsidies) =>
        Ok(financialDashboardPage(
            FinancialDashboardSummary
              .fromUndertakingSubsidies(undertaking, subsidies, searchDateStart, currentTaxYearEnd))
        )
      }

  }

}