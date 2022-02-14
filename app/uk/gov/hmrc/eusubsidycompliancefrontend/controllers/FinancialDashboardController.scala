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

import play.api.mvc.{Action, AnyContent, MessagesControllerComponents}
import uk.gov.hmrc.eusubsidycompliancefrontend.actions.EscActionBuilders
import uk.gov.hmrc.eusubsidycompliancefrontend.config.AppConfig
import uk.gov.hmrc.eusubsidycompliancefrontend.models.types.EORI
import uk.gov.hmrc.eusubsidycompliancefrontend.models.{SubsidyRetrieve, Undertaking}
import uk.gov.hmrc.eusubsidycompliancefrontend.services.{EscService, Store}
import uk.gov.hmrc.eusubsidycompliancefrontend.util.TaxYearHelpers.{taxYearEndForDate, taxYearStartForDate}
import uk.gov.hmrc.eusubsidycompliancefrontend.util.TimeProvider
import uk.gov.hmrc.eusubsidycompliancefrontend.views.html.FinancialDashboardPage
import uk.gov.hmrc.eusubsidycompliancefrontend.views.models.FinancialDashboardSummary

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

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
    val searchDateStart = taxYearStartForDate(timeProvider.today).minusYears(2)
    val currentTaxYearEnd = taxYearEndForDate(timeProvider.today)

    val searchRange = Some((searchDateStart, searchDateStart))

    val subsidies = for {
      undertaking <- store.get[Undertaking]
      r = undertaking.flatMap(_.reference).getOrElse(throw new IllegalStateException("No undertaking data on session"))
      s = SubsidyRetrieve(r, searchRange)
      subsidies <- escService.retrieveSubsidy(s)
    } yield (undertaking, subsidies)

    // TODO - review error cases that should be handled here
    subsidies.map {
      case (Some(undertaking), subsidies) =>
        Ok(financialDashboardPage(
            FinancialDashboardSummary
              .fromUndertakingSubsidies(undertaking, subsidies, searchDateStart, currentTaxYearEnd))
        )
      }

  }

}
