/*
 * Copyright 2023 HM Revenue & Customs
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

package uk.gov.hmrc.eusubsidycompliancefrontend.util

import java.time.LocalDate

object ReportReminderHelpers {

  private val OneDayBeforeReminderDay = 75
  private val ReportDeMinimisDueDay = 90
  private val OneDayAfterDueDay = ReportDeMinimisDueDay + 1

  def isTimeToReport(od: Option[LocalDate], currentDate: LocalDate): Boolean =
    od.exists { lastUpdated =>
      lastUpdated.plusDays(OneDayBeforeReminderDay).isBefore(currentDate) &&
      lastUpdated.plusDays(OneDayAfterDueDay).isAfter(currentDate)
    }

  def dueDateToReport(od: Option[LocalDate]): Option[LocalDate] = od.map(dueDateToReport)

  def dueDateToReport(d: LocalDate): LocalDate = d.plusDays(ReportDeMinimisDueDay)

  def isOverdue(od: Option[LocalDate], currentDate: LocalDate): Boolean =
    od.fold(false) { date =>
      date.isBefore(currentDate.minusDays(ReportDeMinimisDueDay))
    }

}
