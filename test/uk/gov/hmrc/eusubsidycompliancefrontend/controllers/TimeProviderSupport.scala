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

package uk.gov.hmrc.eusubsidycompliancefrontend.controllers

import org.scalamock.handlers.CallHandler0
import uk.gov.hmrc.eusubsidycompliancefrontend.util.TimeProvider

import java.time.{LocalDate, LocalDateTime}

trait TimeProviderSupport { this: ControllerSpec =>

  val mockTimeProvider: TimeProvider = mock[TimeProvider]

  def mockTimeProviderNow(now: LocalDateTime): CallHandler0[LocalDateTime] =
    (() => mockTimeProvider.now).expects().returning(now)

  def mockTimeProviderToday(now: LocalDate): CallHandler0[LocalDate] =
    (() => mockTimeProvider.today).expects().returning(now)

}
