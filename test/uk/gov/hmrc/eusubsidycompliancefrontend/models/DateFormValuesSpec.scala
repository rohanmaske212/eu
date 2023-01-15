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

package uk.gov.hmrc.eusubsidycompliancefrontend.models

import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.LocalDate

class DateFormValuesSpec extends AnyWordSpecLike with Matchers {

  "DateFormValues" when {

    "toFormattedString is called" must {
      "return a formatted date string" in {
        DateFormValues("1", "1", "2022").toFormattedString mustBe "1/1/2022"
      }
    }

    "toLocalDate is called" must {
      "return a valid LocalDate instance" in {
        DateFormValues("1", "1", "2022").toLocalDate mustBe LocalDate.of(2022, 1, 1)
      }
    }
  }

}
