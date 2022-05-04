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

import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.Configuration
import play.api.test.Helpers._
import uk.gov.hmrc.eusubsidycompliancefrontend.config.AppConfig
import uk.gov.hmrc.eusubsidycompliancefrontend.connectors.SendEmailConnector
import uk.gov.hmrc.eusubsidycompliancefrontend.models.ConnectorError
import uk.gov.hmrc.eusubsidycompliancefrontend.models.email.{EmailSendRequest, EmailSendResult}
import uk.gov.hmrc.eusubsidycompliancefrontend.syntax.FutureSyntax.FutureOps
import uk.gov.hmrc.eusubsidycompliancefrontend.test.CommonTestData._
import uk.gov.hmrc.hmrcfrontend.config.ContactFrontendConfig
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}

import scala.concurrent.ExecutionContext.Implicits.global

class SendEmailHelperServiceSpec extends AnyWordSpec with Matchers with MockFactory {

  private val mockSendEmailConnector: SendEmailConnector = mock[SendEmailConnector]

  // TODO - if needed spin up an application to avoid jumping through hoops like this
  private val fakeConfig = new AppConfig(
    Configuration.empty,
    new ContactFrontendConfig(Configuration.empty)
  )

  private def mockSendEmail(emailSendRequest: EmailSendRequest)(result: Either[ConnectorError, HttpResponse]) =
    (mockSendEmailConnector
      .sendEmail(_: EmailSendRequest)(_: HeaderCarrier))
      .expects(emailSendRequest, *)
      .returning(result.toFuture)

  private val service = new SendEmailHelperService(
    fakeConfig,
    mock[RetrieveEmailService],
    mockSendEmailConnector,
    Configuration.empty
  )

  private implicit val hc: HeaderCarrier = HeaderCarrier()

  private val templatedId = "templateId1"

  "SendEmailHelperService" when {

    " handling request to send email" must {

      "return an error" when {

        "the http call fails" in {
          mockSendEmail(emailSendRequest)(Left(ConnectorError("")))
          val result = service.sendEmail(validEmailAddress, emailParameter, templatedId)
          assertThrows[RuntimeException](await(result))
        }
      }

      "return Email sent successfully" when {

        "request came back with status Accepted and request can be parsed" in {
          mockSendEmail(emailSendRequest)(Right(HttpResponse(ACCEPTED, "")))
          val result = service.sendEmail(validEmailAddress, emailParameter, templatedId)
          await(result) shouldBe EmailSendResult.EmailSent
        }
      }

      "return Email sent failure" when {

        "request came back with status != Accepted " in {
          mockSendEmail(emailSendRequest)(Right(HttpResponse(OK, "")))
          val result = service.sendEmail(validEmailAddress, emailParameter, templatedId)
          await(result) shouldBe EmailSendResult.EmailSentFailure
        }

      }

    }

  }

}