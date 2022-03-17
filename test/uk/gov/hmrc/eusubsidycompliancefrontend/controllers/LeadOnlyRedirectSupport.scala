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

import cats.implicits.catsSyntaxOptionId
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should.Matchers
import play.api.http.Status.SEE_OTHER
import play.api.mvc.Result
import play.api.test.Helpers.{defaultAwaitTimeout, redirectLocation, status}
import uk.gov.hmrc.eusubsidycompliancefrontend.models.Undertaking
import utils.CommonTestData.{eori3, undertaking}

import scala.concurrent.Future

trait LeadOnlyRedirectSupport extends MockFactory with Matchers {
  this: AuthAndSessionDataBehaviour with JourneyStoreSupport =>

  protected def testLeadOnlyRedirect(f: () => Future[Result]) = {
    inSequence {
      mockAuthWithEnrolment(eori3)
      mockGet[Undertaking](eori3)(Right(undertaking.some))
    }

    val result = f()

    status(result) shouldBe SEE_OTHER
    redirectLocation(result) should contain(routes.AccountController.getAccountPage().url)
  }

}