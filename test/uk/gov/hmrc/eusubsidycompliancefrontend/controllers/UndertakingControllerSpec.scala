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
import com.mongodb.client.result.UpdateResult
import org.bson.BsonBoolean
import play.api.Configuration
import play.api.inject.bind
import play.api.inject.guice.GuiceableModule
import play.api.libs.json.JsObject
import play.api.mvc.Results.Redirect
import play.api.test.FakeRequest
import play.api.test.Helpers._
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.eusubsidycompliancefrontend.controllers.UndertakingControllerSpec.ModifyUndertakingRow
import uk.gov.hmrc.eusubsidycompliancefrontend.models.audit.AuditEvent.{UndertakingDisabled, UndertakingUpdated}
import uk.gov.hmrc.eusubsidycompliancefrontend.models.email.EmailSendResult.EmailSent
import uk.gov.hmrc.eusubsidycompliancefrontend.models.email.EmailTemplate.{CreateUndertaking, DisableUndertakingToBusinessEntity, DisableUndertakingToLead}
import uk.gov.hmrc.eusubsidycompliancefrontend.models.email.{EmailType, RetrieveEmailResponse}
import uk.gov.hmrc.eusubsidycompliancefrontend.models.types.{Sector, UndertakingName}
import uk.gov.hmrc.eusubsidycompliancefrontend.models.{ConnectorError, VerifiedEmail, EmailVerificationResponse}
import uk.gov.hmrc.eusubsidycompliancefrontend.services.UndertakingJourney.Forms._
import uk.gov.hmrc.eusubsidycompliancefrontend.services._
import uk.gov.hmrc.eusubsidycompliancefrontend.syntax.FutureSyntax.FutureOps
import uk.gov.hmrc.eusubsidycompliancefrontend.test.CommonTestData._
import uk.gov.hmrc.eusubsidycompliancefrontend.util.TimeProvider
import uk.gov.hmrc.mongo.cache.CacheItem

import java.time.{Instant, LocalDate}
import scala.collection.JavaConverters._
import scala.concurrent.Future

class UndertakingControllerSpec
    extends ControllerSpec
    with AuthSupport
    with JourneyStoreSupport
    with AuthAndSessionDataBehaviour
    with EmailSupport
    with AuditServiceSupport
    with EscServiceSupport
    with EmailVerificationSupport
    with TimeProviderSupport {


  override def overrideBindings: List[GuiceableModule] = List(
    bind[AuthConnector].toInstance(mockAuthConnector),
    bind[Store].toInstance(mockJourneyStore),
    bind[EscService].toInstance(mockEscService),
    bind[EmailService].toInstance(mockEmailService),
    bind[AuditService].toInstance(mockAuditService),
    bind[TimeProvider].toInstance(mockTimeProvider),
    bind[EmailVerificationService].toInstance(mockEmailVerificationService)
  )

  override def additionalConfig: Configuration = super.additionalConfig.withFallback(
    Configuration.from(
      Map(
        "play.i18n.langs" -> Seq("en", "cy", "fr"),
        "email-send.create-undertaking-template-en" -> "template_EN",
        "email-send.create-undertaking-template-cy" -> "template_CY"
      )
    )
  )

  private val controller = instanceOf[UndertakingController]
  val exception = new Exception("oh no")

  "UndertakingController" when {

    "handling request to first empty page" must {

      def performAction() =
        controller.firstEmptyPage(FakeRequest())

      "throw technical error" when {

        "call to Get Or Create undertaking journey fails" in {
          inSequence {
            mockAuthWithNecessaryEnrolmentNoEmailVerification()
            mockGetOrCreate[UndertakingJourney](eori1)(Left(ConnectorError(exception)))
          }
          assertThrows[Exception](await(performAction()))
        }
      }

      "redirects to next page" when {

        "undertaking journey is present and  is not None and is complete" in {
          inSequence {
            mockAuthWithNecessaryEnrolmentNoEmailVerification()
            mockGetOrCreate[UndertakingJourney](eori1)(Right(undertakingJourneyComplete))
          }
          checkIsRedirect(performAction(), routes.BusinessEntityController.getAddBusinessEntity().url)
        }

        "undertaking journey is present and  is not None and is not complete" when {

          def testRedirect(undertakingJourney: UndertakingJourney, redirectTo: String): Unit = {
            inSequence {
              mockAuthWithNecessaryEnrolmentNoEmailVerification()
              mockGetOrCreate[UndertakingJourney](eori1)(Right(undertakingJourney))
            }
            checkIsRedirect(performAction(), redirectTo)
          }

          "undertaking journey only contains undertaking name" in {
            testRedirect(
              UndertakingJourney(
                about = AboutUndertakingFormPage("TestUndertaking".some)
              ),
              routes.UndertakingController.getSector().url
            )
          }

          "undertaking journey contains undertaking name and sector" in {
            testRedirect(
              UndertakingJourney(
                about = AboutUndertakingFormPage("TestUndertaking".some),
                sector = UndertakingSectorFormPage(Sector(1).some)
              ),
              routes.UndertakingController.getConfirmEmail().url
            )
          }

          "undertaking journey contains undertaking name, sector and verified email" in {
            testRedirect(
              UndertakingJourney(
                about = AboutUndertakingFormPage("TestUndertaking".some),
                sector = UndertakingSectorFormPage(Sector(1).some),
                verifiedEmail = UndertakingConfirmEmailFormPage("joe.bloggs@something.com".some)
              ),
              routes.UndertakingController.getCheckAnswers().url
            )
          }

          "undertaking journey contains cya" in {
            testRedirect(
              UndertakingJourney(
                about = AboutUndertakingFormPage("TestUndertaking".some),
                sector = UndertakingSectorFormPage(Sector(1).some),
                verifiedEmail = UndertakingConfirmEmailFormPage("joe.bloggs@something.com".some),
                cya = UndertakingCyaFormPage(true.some)
              ),
              routes.UndertakingController.postConfirmation().url
            )
          }

          "undertaking journey contains confirmation" in {
            testRedirect(
              UndertakingJourney(
                about = AboutUndertakingFormPage("TestUndertaking".some),
                sector = UndertakingSectorFormPage(Sector(1).some),
                cya = UndertakingCyaFormPage(true.some),
                verifiedEmail = UndertakingConfirmEmailFormPage("joe.bloggs@something.com".some),
                confirmation = UndertakingConfirmationFormPage(true.some)
              ),
              routes.BusinessEntityController.getAddBusinessEntity().url
            )
          }

        }

      }

    }

    "handling request to get About Undertaking" must {

      def performAction() =
        controller.getAboutUndertaking(FakeRequest(GET, routes.UndertakingController.getAboutUndertaking().url))

      "display the page" when {

        def testDisplay(undertakingJourney: UndertakingJourney, backUrl: String): Unit = {

          inSequence {
            mockAuthWithNecessaryEnrolmentNoEmailVerification()
            mockGetOrCreate[UndertakingJourney](eori1)(Right(undertakingJourney))
          }
          checkPageIsDisplayed(
            performAction(),
            messageFromMessageKey("undertakingName.title"),
            { doc =>
              doc.select(".govuk-back-link").attr("href") shouldBe backUrl
              doc.select("form").attr("action") shouldBe routes.UndertakingController.postAboutUndertaking().url
            }
          )

        }

        "no undertaking journey is there in store" in {
          inSequence {
            mockAuthWithNecessaryEnrolmentNoEmailVerification()
            mockGetOrCreate[UndertakingJourney](eori1)(Right(UndertakingJourney()))
          }
          checkPageIsDisplayed(
            performAction(),
            messageFromMessageKey("undertakingName.title"),
            { doc =>
              doc.select(".govuk-back-link").attr("href") shouldBe routes.EligibilityController
                .getEoriCheck()
                .url
              val input = doc.select(".govuk-input").attr("value")
              input shouldBe ""

              val button = doc.select("form")
              button.attr("action") shouldBe routes.UndertakingController.postAboutUndertaking().url
            }
          )
        }

        "undertaking journey is there in store and user has already answered the questions and all answers are complete" in {
          testDisplay(undertakingJourneyComplete, routes.EligibilityController.getEoriCheck().url)
        }

        "undertaking journey is there in store and user hasn't  answered any questions" in {
          testDisplay(UndertakingJourney(), routes.EligibilityController.getEoriCheck().url)
        }

        "undertaking journey is there in store and user has answered the question but journey is not complete" in {
          testDisplay(
            UndertakingJourney(
              about = AboutUndertakingFormPage("TestUndertaking".some)
            ),
            routes.EligibilityController.getEoriCheck().url
          )
        }

        "page appeared via amend undertaking journey" in {
          testDisplay(undertakingJourneyComplete1, routes.UndertakingController.getAmendUndertakingDetails().url)
        }
      }

    }

    "handling request to post About Undertaking" must {

      def performAction(data: (String, String)*) = controller
        .postAboutUndertaking(
          FakeRequest(POST, routes.UndertakingController.getAboutUndertaking().url)
            .withFormUrlEncodedBody(data: _*)
        )

      "throw technical error" when {
        val exception = new Exception("oh no")

        "call to  get undertaking journey fails" in {
          inSequence {
            mockAuthWithNecessaryEnrolmentNoEmailVerification()
            mockGet[UndertakingJourney](eori1)(Left(ConnectorError(exception)))
          }
          assertThrows[Exception](await(performAction("undertakingName" -> "TestUndertaking123")))
        }

        "call to  get undertaking journey passes but com back with empty response" in {
          inSequence {
            mockAuthWithNecessaryEnrolmentNoEmailVerification()
            mockGet[UndertakingJourney](eori1)(Right(None))
          }
          assertThrows[Exception](await(performAction("undertakingName" -> "TestUndertaking123")))
        }

        "call to update undertaking journey fails" in {
          def update(u: UndertakingJourney) = u.copy(about = AboutUndertakingFormPage("TestUndertaking123".some))

          inSequence {
            mockAuthWithNecessaryEnrolmentNoEmailVerification()
            mockGet[UndertakingJourney](eori1)(Right(undertakingJourneyComplete.some))
            mockUpdate[UndertakingJourney](_ => update(undertakingJourneyComplete), eori1)(
              Left(ConnectorError(exception))
            )
          }
          assertThrows[Exception](await(performAction("continue" -> "true")))
        }

        "submitted form does not contain expected data" in {
          inSequence {
            mockAuthWithNecessaryEnrolmentNoEmailVerification()
            mockGet[UndertakingJourney](eori1)(Right(undertakingJourneyComplete.some))
          }

          assertThrows[IllegalStateException](await(performAction("this is not" -> "valid")))
        }
      }

      "redirect to next page" when {

        def test(undertakingJourney: UndertakingJourney, nextCall: String): Unit = {
          val updatedUndertaking = undertakingJourney.copy(about = AboutUndertakingFormPage("TestUndertaking123".some))
          inSequence {
            mockAuthWithNecessaryEnrolmentNoEmailVerification()
            mockGet[UndertakingJourney](eori1)(Right(undertakingJourney.some))
            mockUpdate[UndertakingJourney](identity, eori1)(
              Right(updatedUndertaking)
            )
          }
          checkIsRedirect(performAction("continue" -> "true"), nextCall)
        }

        "page is reached via amend details page " in {
          test(undertakingJourneyComplete1, routes.UndertakingController.getAmendUndertakingDetails().url)

        }

        "page is reached via normal undertaking creation process" in {
          test(UndertakingJourney(), routes.UndertakingController.getSector().url)
        }

        "page is reached via normal undertaking creation process when all answers have been provided" in {
          test(undertakingJourneyComplete, routes.UndertakingController.getCheckAnswers().url)
        }

      }

    }

    "handling request to get sector" must {

      def performAction() = controller.getSector(FakeRequest(GET, routes.UndertakingController.getSector().url))

      "throw technical error" when {

        val exception = new Exception("oh no")
        "call to fetch undertaking journey fails" in {
          inSequence {
            mockAuthWithNecessaryEnrolmentNoEmailVerification()
            mockGet[UndertakingJourney](eori1)(Left(ConnectorError(exception)))
          }
          assertThrows[Exception](await(performAction()))
        }

      }

      "display the page" when {

        val allRadioTexts: List[String] = List(
          s"${messageFromMessageKey("sector.label.3")}" +
            s" ${messageFromMessageKey("sector.hint.3")}",
          s"${messageFromMessageKey("sector.label.2")}" +
            s" ${messageFromMessageKey("sector.hint.2")}",
          s"${messageFromMessageKey("sector.label.1")}" +
            s" ${messageFromMessageKey("sector.hint.1")}",
          messageFromMessageKey("sector.label.0") +
            s" ${messageFromMessageKey("sector.hint.0")}"
        )

        def test(undertakingJourney: UndertakingJourney, previousCall: String, inputValue: Option[String]): Unit = {

          inSequence {
            mockAuthWithNecessaryEnrolmentNoEmailVerification()
            mockGet[UndertakingJourney](eori1)(Right(undertakingJourney.some))
          }
          checkPageIsDisplayed(
            performAction(),
            messageFromMessageKey("undertakingSector.title", undertakingJourney.about.value.getOrElse("")),
            { doc =>
              doc.select(".govuk-back-link").attr("href") shouldBe previousCall

              val selectedOptions = doc.select(".govuk-radios__input[checked]")
              inputValue match {
                case Some(value) => selectedOptions.attr("value") shouldBe value
                case None => selectedOptions.isEmpty shouldBe true
              }

              testRadioButtonOptions(doc, allRadioTexts)

              val form = doc.select("form")
              form
                .attr("action") shouldBe routes.UndertakingController.postSector().url
            }
          )

        }

        "user has not already answered the question (normal add undertaking journey)" in {
          test(
            undertakingJourney = UndertakingJourney(about = AboutUndertakingFormPage("TestUndertaking1".some)),
            previousCall = routes.UndertakingController.getAboutUndertaking().url,
            inputValue = None
          )
        }

        "user has already answered the question (normal add undertaking journey)" in {
          test(
            undertakingJourney = UndertakingJourney(
              about = AboutUndertakingFormPage("TestUndertaking1".some),
              sector = UndertakingSectorFormPage(Sector(2).some)
            ),
            previousCall = routes.UndertakingController.getAboutUndertaking().url,
            inputValue = "2".some
          )
        }

        "user has already answered the question and is on Amend journey" in {
          test(
            undertakingJourney = undertakingJourneyComplete1,
            previousCall = routes.UndertakingController.getAmendUndertakingDetails().url,
            inputValue = "2".some
          )
        }

      }

      "redirect to journey start page" when {

        "call to fetch undertaking journey passes  but return no undertaking journey" in {
          inSequence {
            mockAuthWithNecessaryEnrolmentNoEmailVerification()
            mockGet[UndertakingJourney](eori1)(Right(None))
          }
          checkIsRedirect(performAction(), routes.UndertakingController.getAboutUndertaking().url)
        }
      }

    }

    "handling request to post sector" must {
      def performAction(data: (String, String)*) = controller
        .postSector(
          FakeRequest(POST, routes.UndertakingController.getSector().url)
            .withFormUrlEncodedBody(data: _*)
        )

      def update(u: UndertakingJourney) = u.copy(sector = UndertakingSectorFormPage(Sector(1).some))

      "throw technical error" when {
        val exception = new Exception("oh no")

        "call to get previous url fails" in {
          inSequence {
            mockAuthWithNecessaryEnrolmentNoEmailVerification()
            mockGet[UndertakingJourney](eori1)(Left(ConnectorError(exception)))
          }
          assertThrows[Exception](await(performAction("undertakingSector" -> "2")))
        }

        "call to fetch undertaking journey fails" in {
          inSequence {
            mockAuthWithNecessaryEnrolmentNoEmailVerification()
            mockGet[UndertakingJourney](eori1)(Left(ConnectorError(exception)))
          }
          assertThrows[Exception](await(performAction()))
        }

        "call to fetch undertaking journey passes  buy fetches nothing" in {
          inSequence {
            mockAuthWithNecessaryEnrolmentNoEmailVerification()
            mockGet[UndertakingJourney](eori1)(Right(None))
          }
          assertThrows[Exception](await(performAction()))
        }

        "call to update undertaking journey fails" in {
          val currentUndertaking = UndertakingJourney(about = AboutUndertakingFormPage("TestUndertaking".some))
          inSequence {
            mockAuthWithNecessaryEnrolmentNoEmailVerification()
            mockGet[UndertakingJourney](eori1)(Right(currentUndertaking.some))
            mockUpdate[UndertakingJourney](_ => update(currentUndertaking), eori1)(Left(ConnectorError(exception)))
          }
          assertThrows[Exception](await(performAction("undertakingSector" -> "2")))
        }

      }

      "display form error" when {

        "nothing is submitted" in {
          inSequence {
            mockAuthWithNecessaryEnrolmentNoEmailVerification()
            mockGet[UndertakingJourney](eori1)(
              Right(undertakingJourneyComplete.copy(cya = UndertakingCyaFormPage()).some)
            )
          }
          checkFormErrorIsDisplayed(
            performAction(),
            messageFromMessageKey("undertakingSector.title", undertakingJourneyComplete.about.value.getOrElse("")),
            messageFromMessageKey("undertakingSector.error.required")
          )

        }

      }

      "redirect to next page" when {

        def test(undertakingJourney: UndertakingJourney, nextCall: String): Unit = {

          val newSector = UndertakingSectorFormPage(Sector(3).some)

          def update(u: UndertakingJourney) = u.copy(sector = newSector)

          val updatedUndertaking = undertakingJourney.copy(sector = newSector)
          inSequence {
            mockAuthWithNecessaryEnrolmentNoEmailVerification()
            mockGet[UndertakingJourney](eori1)(Right(undertakingJourney.some))
            mockUpdate[UndertakingJourney](_ => update(undertakingJourney), eori1)(Right(updatedUndertaking))
          }
          checkIsRedirect(performAction("undertakingSector" -> "3"), nextCall)
        }

        "page is reached via amend details page " in {
          test(undertakingJourneyComplete1, routes.UndertakingController.getAmendUndertakingDetails().url)

        }

        "page is reached via normal undertaking creation process" in {
          test(UndertakingJourney(), routes.UndertakingController.getConfirmEmail().url)
        }

        "page is reached via normal undertaking creation process when all answers have been provided" in {
          test(undertakingJourneyComplete, routes.UndertakingController.getCheckAnswers().url)
        }

      }

    }

    "handling request to get verify email" must {

      def performAction(pendingVerificationId: String) = controller.getVerifyEmail(pendingVerificationId)(FakeRequest(GET, routes.UndertakingController.getVerifyEmail(pendingVerificationId).url))

      "throw technical error" when {

        val exception = new Exception("oh no")
        "call to fetch undertaking journey fails" in {
          inSequence {
            mockAuthWithNecessaryEnrolmentNoEmailVerification()
            mockGet[UndertakingJourney](eori1)(Left(ConnectorError(exception)))
          }
          assertThrows[Exception](await(performAction("abcdefh")))
        }

      }

      "200 OK" when {

        "User has verified email in CDS" in {
          val undertakingJourney = UndertakingJourney(
            about = AboutUndertakingFormPage("TestUndertaking1".some),
            sector = UndertakingSectorFormPage(Sector(2).some),
            verifiedEmail = UndertakingConfirmEmailFormPage("joe.bloggs@something.com".some)
          )

          inSequence {
            mockAuthWithNecessaryEnrolmentNoEmailVerification()
            mockGet[UndertakingJourney](eori1)(Right(undertakingJourney.some))
            mockApproveVerification(eori1, "id")(Right(UpdateResult.acknowledged(1, 1, BsonBoolean.TRUE)))
            mockGetEmailVerification(eori1)(Right(VerifiedEmail("", "", verified = true).some))
            mockUpdate[UndertakingJourney](identity, eori1)(Right(undertakingJourney))
          }

          redirectLocation(performAction("id")) shouldBe Some(routes.UndertakingController.getCheckAnswers().url)
        }
      }

    }

    "handling request to get confirm email" must {

      def performAction() = controller.getConfirmEmail(FakeRequest(GET, routes.UndertakingController.getConfirmEmail().url))

      "throw technical error" when {

        val exception = new Exception("oh no")
        "call to fetch undertaking journey fails" in {
          inSequence {
            mockAuthWithNecessaryEnrolmentNoEmailVerification()
            mockGet[UndertakingJourney](eori1)(Left(ConnectorError(exception)))
          }
          assertThrows[Exception](await(performAction()))
        }

      }

      "display the page" when {

        "User has verified email in CDS" in {
            val undertakingJourney = UndertakingJourney(
              about = AboutUndertakingFormPage("TestUndertaking1".some),
              sector = UndertakingSectorFormPage(Sector(2).some)
            )
            val previousCall = routes.UndertakingController.getSector().url

            inSequence {
              mockAuthWithNecessaryEnrolmentNoEmailVerification()
              mockGet[UndertakingJourney](eori1)(Right(undertakingJourney.some))
              mockGetEmailVerification(eori1)(Right(VerifiedEmail("", "", verified = true).some))
            }
            checkPageIsDisplayed(
              performAction(),
              messageFromMessageKey("confirmEmail.title", undertakingJourney.about.value.getOrElse("")),
              { doc =>
                doc.select(".govuk-back-link").attr("href") shouldBe previousCall

                val form = doc.select("form")
                form
                  .attr("action") shouldBe routes.UndertakingController.postConfirmEmail().url
              }
            )

          }

        "User does not have verified email in CDS" in {
          val undertakingJourney = UndertakingJourney(
            about = AboutUndertakingFormPage("TestUndertaking1".some),
            sector = UndertakingSectorFormPage(Sector(2).some)
          )
          val previousCall = routes.UndertakingController.getSector().url

          inSequence {
            mockAuthWithNecessaryEnrolmentNoEmailVerification()
            mockGet[UndertakingJourney](eori1)(Right(undertakingJourney.some))
            mockGetEmailVerification(eori1)(Right(VerifiedEmail("", "", verified = true).some))
          }
          checkPageIsDisplayed(
            performAction(),
            messageFromMessageKey("confirmEmail.title", undertakingJourney.about.value.getOrElse("")),
            { doc =>
              doc.select(".govuk-back-link").attr("href") shouldBe previousCall

              val form = doc.select("form")
              form
                .attr("action") shouldBe routes.UndertakingController.postConfirmEmail().url
            }
          )

        }
      }

      "redirect to journey start page" when {

        "call to fetch undertaking journey passes  but return no undertaking journey" in {
          inSequence {
            mockAuthWithNecessaryEnrolmentNoEmailVerification()
            mockGet[UndertakingJourney](eori1)(Right(None))
          }
          checkIsRedirect(performAction(), routes.UndertakingController.getAboutUndertaking().url)
        }
      }

    }

    "handling request to post confirm email call" must {

      def performAction(data: (String, String)*) =
        controller.postConfirmEmail(
          FakeRequest(POST, routes.UndertakingController.postConfirmEmail().url)
            .withFormUrlEncodedBody(data: _*)
        )

      "throw technical error" when {

        "email submitted is empty" in {

          inSequence {
            mockAuthWithNecessaryEnrolmentNoEmailVerification()
          }
          assertThrows[Exception](await(performAction(
            "using-stored-email" -> "false"
          )))
        }

        "email submitted is invalid" in {

          inSequence {
            mockAuthWithNecessaryEnrolmentNoEmailVerification()
          }
          assertThrows[Exception](await(performAction(
            "using-stored-email" -> "false",
            "email" -> "joe bloggs"
          )))
        }

      }

     "redirect to CYA Page" when {

       "all api calls are successful" in {
         inSequence {
           mockAuthWithNecessaryEnrolmentWithValidEmail()
           mockRetrieveEmail(eori1)(Right(RetrieveEmailResponse(EmailType.VerifiedEmail, validEmailAddress.some)))
           mockAddEmailVerification(eori1)(Right(""))
           mockEmailVerification(eori1)(Right(CacheItem("id", JsObject.empty, Instant.now(), Instant.now())))
           mockUpdate[UndertakingJourney](identity, eori1)(Right(undertakingJourneyComplete))
         }
         checkIsRedirect(
           performAction("using-stored-email" -> "true"),
           routes.UndertakingController.getCheckAnswers().url
         )
       }

       "No verification found, but cds" in {
         inSequence {
           mockAuthWithNecessaryEnrolmentWithValidEmail()
           mockRetrieveEmail(eori1)(Right(RetrieveEmailResponse(EmailType.VerifiedEmail, validEmailAddress.some)))
           mockAddEmailVerification(eori1)(Right(""))
           mockEmailVerification(eori1)(Right(CacheItem("id", JsObject.empty, Instant.now(), Instant.now())))
           mockUpdate[UndertakingJourney](identity, eori1)(Right(undertakingJourneyComplete))
         }
         checkIsRedirect(
           performAction("using-stored-email" -> "true"),
           routes.UndertakingController.getCheckAnswers().url
         )
       }

      "No verification found or cds with valid form should redirect" in {
        inSequence {
          mockAuthWithNecessaryEnrolmentWithValidEmail()
          mockRetrieveEmail(eori1)(Right(RetrieveEmailResponse(EmailType.UnVerifiedEmail, validEmailAddress.some)))
          mockAddEmailVerification(eori1)(Right("pendingVerificationId"))
          mockVerifyEmail("something@aol.com")(Right(Some(EmailVerificationResponse("redirectUri"))))
          mockEmailVerificationRedirect(Some(EmailVerificationResponse("redirectUri")))(Redirect("email-verification-redirect"))
        }
        redirectLocation(performAction("using-stored-email" -> "false", "email" -> "something@aol.com")) shouldBe "email-verification-redirect".some
      }

       "No verification found or cds with invalid form should be bad request" in {
         inSequence {
           mockAuthWithNecessaryEnrolmentWithValidEmail()
           mockRetrieveEmail(eori1)(Right(RetrieveEmailResponse(EmailType.UnVerifiedEmail, validEmailAddress.some)))
         }
         status(performAction("using-stored-email" -> "false", "email" -> "somethingl.com")) shouldBe BAD_REQUEST
       }

       "Add verification if has cds email but submits different" in {
         inSequence {
           mockAuthWithNecessaryEnrolmentWithValidEmail()
           mockRetrieveEmail(eori1)(Right(RetrieveEmailResponse(EmailType.VerifiedEmail, validEmailAddress.some)))
           mockAddEmailVerification(eori1)(Right("pendingVerificationId"))
           mockVerifyEmail("something@aol.com")(Right(Some(EmailVerificationResponse("redirectUri"))))
           mockEmailVerificationRedirect(Some(EmailVerificationResponse("redirectUri")))(Redirect("email-verification-redirect"))
         }
         redirectLocation(performAction("using-stored-email" -> "false", "email" -> "something@aol.com")) shouldBe "email-verification-redirect".some
       }

     }

    }

    "handling request to get check your answers page" must {

      def performAction() = controller.getCheckAnswers(
        FakeRequest(GET, routes.UndertakingController.getCheckAnswers().url)
      )

      "display the page" in {

        val expectedRows = List(
          ModifyUndertakingRow(
            messageFromMessageKey("undertaking.cya.summary-list.eori.key"),
            eori1,
            "" // User cannot change the EORI on the undertaking
          ),
          ModifyUndertakingRow(
            messageFromMessageKey("undertaking.amendUndertaking.summary-list.sector.key"),
            messageFromMessageKey(s"sector.label.${undertaking.industrySector.id.toString}"),
            routes.UndertakingController.getSector().url
          ),
          ModifyUndertakingRow(
            messageFromMessageKey("undertaking.amendUndertaking.summary-list.verified-email"),
            "joebloggs@something.com",
            routes.UndertakingController.getConfirmEmail().url
          ),
        )
        inSequence {
          mockAuthWithNecessaryEnrolmentWithValidEmail()
          mockGet[UndertakingJourney](eori1)(Right(undertakingJourneyComplete.some))
        }

        checkPageIsDisplayed(
          performAction(),
          messageFromMessageKey("undertaking.cya.title"),
          { doc =>
            doc.select(".govuk-back-link").attr("href") shouldBe routes.UndertakingController.getConfirmEmail().url
            val rows =
              doc.select(".govuk-summary-list__row").iterator().asScala.toList.map { element =>
                val question = element.select(".govuk-summary-list__key").text()
                val answer = element.select(".govuk-summary-list__value").text()
                val changeUrl = element.select(".govuk-link").attr("href")
                ModifyUndertakingRow(question, answer, changeUrl)
              }

            rows shouldBe expectedRows
          }
        )

      }

      "throw technical error" when {
        val exception = new Exception("oh no")

        "call to get undertaking journey fails" in {
          inSequence {
            mockAuthWithNecessaryEnrolmentWithValidEmail()
            mockGet[UndertakingJourney](eori1)(Left(ConnectorError(exception)))
          }
          assertThrows[Exception](await(performAction()))
        }

      }

      "redirect" when {
        "call to get undertaking journey fetches the journey without verified email" in {
          inSequence {
            mockAuthWithNecessaryEnrolmentWithValidEmail()
            mockGet[UndertakingJourney](eori1)(
              Right(undertakingJourneyComplete.copy(verifiedEmail = UndertakingConfirmEmailFormPage()).some)
            )
          }
          redirectLocation(performAction()) shouldBe Some(routes.UndertakingController.getConfirmEmail().url)
        }

        "to journey start when call to get undertaking journey fetches nothing" in {
          inSequence {
            mockAuthWithNecessaryEnrolmentWithValidEmail()
            mockGet[UndertakingJourney](eori1)(Right(None))
          }
          checkIsRedirect(performAction(), routes.UndertakingController.getAboutUndertaking().url)
        }
      }

    }

    "handling request to Post Check your Answers call" must {

      def performAction(data: (String, String)*) =
        controller.postCheckAnswers(
          FakeRequest(POST, routes.UndertakingController.getCheckAnswers().url)
            .withFormUrlEncodedBody(data: _*)
        )

      "throw technical error" when {

        val exception = new Exception("oh no !")

        "cya form is empty, nothing is submitted" in {

          inSequence {
            mockAuthWithNecessaryEnrolmentWithValidEmail()
          }
          assertThrows[Exception](await(performAction()))
        }

        "call to update undertaking journey fails" in {

          inSequence {
            mockAuthWithNecessaryEnrolmentWithValidEmail()
            mockUpdate[UndertakingJourney](identity, eori1)(Left(ConnectorError(exception)))
          }
          assertThrows[Exception](await(performAction("cya" -> "true")))
        }

        "updated undertaking journey don't have undertaking name" in {

          inSequence {
            mockAuthWithNecessaryEnrolmentWithValidEmail()
            mockUpdate[UndertakingJourney](identity, eori1)(Left(ConnectorError(exception)))
          }
          assertThrows[Exception](await(performAction("cya" -> "true")))
        }

        "updated undertaking journey don't have undertaking sector" in {

          inSequence {
            mockAuthWithNecessaryEnrolmentWithValidEmail()
            mockUpdate[UndertakingJourney](identity, eori1)(Left(ConnectorError(exception)))
          }
          assertThrows[Exception](await(performAction("cya" -> "true")))
        }

        "call to create undertaking fails" in {

          val updatedUndertakingJourney = undertakingJourneyComplete.copy(cya = UndertakingCyaFormPage(false.some))

          inSequence {
            mockAuthWithNecessaryEnrolmentWithValidEmail()
            mockUpdate[UndertakingJourney](identity, eori1)(Right(updatedUndertakingJourney))
            mockCreateUndertaking(undertakingCreated)(Left(ConnectorError(exception)))
          }
          assertThrows[Exception](await(performAction("cya" -> "true")))

        }

        "call to send email fails" in {

          val updatedUndertakingJourney =
            undertakingJourneyComplete.copy(cya = UndertakingCyaFormPage(false.some))

          inSequence {
            mockAuthWithNecessaryEnrolmentWithValidEmail()
            mockUpdate[UndertakingJourney](identity, eori1)(Right(updatedUndertakingJourney))
            mockCreateUndertaking(undertakingCreated)(Right(undertakingRef))
            mockSendEmail(eori1, CreateUndertaking, undertakingCreated.toUndertakingWithRef(undertakingRef))(
              Left(ConnectorError(exception))
            )
          }
          assertThrows[Exception](await(performAction("cya" -> "true")))

        }
      }

      "redirect to confirmation page" when {

        def testRedirection(): Unit = {

          val updatedUndertakingJourney = undertakingJourneyComplete.copy(cya = UndertakingCyaFormPage(false.some))

          inSequence {
            mockAuthWithNecessaryEnrolmentWithValidEmail()
            mockUpdate[UndertakingJourney](identity, eori1)(Right(updatedUndertakingJourney))
            mockCreateUndertaking(undertakingCreated)(Right(undertakingRef))
            mockSendEmail(eori1, CreateUndertaking, undertakingCreated.toUndertakingWithRef(undertakingRef))(
              Right(EmailSent)
            )
            mockTimeProviderNow(timeNow)
            mockSendAuditEvent(createUndertakingAuditEvent)
          }
          checkIsRedirect(
            performAction("cya" -> "true"),
            routes.UndertakingController.getConfirmation(undertakingRef).url
          )
        }

        "all api calls are successful" in {
          testRedirection()
        }

      }

    }

    "handling request to get confirmation" must {

      def performAction() = controller.getConfirmation(undertakingRef)(
        FakeRequest(GET, routes.UndertakingController.getConfirmation(undertakingRef).url)
      )

      "display the page" in {
        inSequence {
          mockAuthWithNecessaryEnrolmentWithValidEmail()
        }

        checkPageIsDisplayed(
          performAction(),
          messageFromMessageKey("undertaking.confirmation.title"),
          { doc =>
            val heading2 = doc.select(".govuk-body").text()
            heading2 should include regex messageFromMessageKey("undertaking.confirmation.p2")
          }
        )

      }

    }

    "handling request to Post Confirmation page" must {

      def performAction(data: (String, String)*) =
        controller.postConfirmation(FakeRequest().withFormUrlEncodedBody(data: _*))
      def update(u: UndertakingJourney) = u.copy(confirmation = UndertakingConfirmationFormPage(value = true.some))

      val undertakingJourney =
        undertakingJourneyComplete.copy(confirmation = UndertakingConfirmationFormPage(value = None))

      "throw technical error" when {

        "confirmation form is empty" in {
          inSequence {
            mockAuthWithNecessaryEnrolmentWithValidEmail()
          }
          assertThrows[Exception](await(performAction()))
        }

        "call to update undertaking confirmation fails" in {
          inSequence {
            mockAuthWithNecessaryEnrolmentWithValidEmail()
            mockUpdate[UndertakingJourney](_ => update(undertakingJourney), eori1)(Left(ConnectorError(exception)))
          }
          assertThrows[Exception](await(performAction("confirm" -> "true")))
        }
      }

      "redirect to next page" in {
        inSequence {
          mockAuthWithNecessaryEnrolmentWithValidEmail()
          mockUpdate[UndertakingJourney](_ => update(undertakingJourney), eori1)(Right(undertakingJourneyComplete))
        }

        checkIsRedirect(performAction("confirm" -> "true"), routes.BusinessEntityController.getAddBusinessEntity().url)
      }

    }

    "handling request to get Amend Undertaking Details" must {

      def performAction() = controller.getAmendUndertakingDetails(FakeRequest())

      def update(u: UndertakingJourney) = u.copy(isAmend = true)

      val expectedRows = List(
        ModifyUndertakingRow(
          messageFromMessageKey("undertaking.amendUndertaking.summary-list.sector.key"),
          messageFromMessageKey(s"sector.label.${undertaking.industrySector.id.toString}"),
          routes.UndertakingController.getSector().url
        )
      )

      "throw technical error" when {

        val exception = new Exception("oh no")
        "call to get undertaking journey fails" in {
          inSequence {
            mockAuthWithNecessaryEnrolmentWithValidEmail()
            mockRetrieveUndertaking(eori1)(undertaking.some.toFuture)
            mockGet[UndertakingJourney](eori1)(Left(ConnectorError(exception)))
          }
          assertThrows[Exception](await(performAction()))
        }

        "call to update the undertaking journey fails" in {

          inSequence {
            mockAuthWithNecessaryEnrolmentWithValidEmail()
            mockRetrieveUndertaking(eori1)(undertaking.some.toFuture)
            mockGet[UndertakingJourney](eori1)(Right(undertakingJourneyComplete.some))
            mockUpdate[UndertakingJourney](_ => update(undertakingJourneyComplete), eori1)(
              Left(ConnectorError(exception))
            )
          }
          assertThrows[Exception](await(performAction()))
        }
      }

      "display the page" when {

        "is Amend is true" in {
          inSequence {
            mockAuthWithNecessaryEnrolmentWithValidEmail()
            mockRetrieveUndertaking(eori1)(undertaking.some.toFuture)
            mockGet[UndertakingJourney](eori1)(Right(undertakingJourneyComplete.copy(isAmend = true).some))
          }

          checkPageIsDisplayed(
            performAction(),
            messageFromMessageKey("undertaking.amendUndertaking.title"),
            { doc =>
              doc.select(".govuk-back-link").attr("href") shouldBe routes.AccountController.getAccountPage().url

              val rows =
                doc.select(".govuk-summary-list__row").iterator().asScala.toList.map { element =>
                  val question = element.select(".govuk-summary-list__key").text()
                  val answer = element.select(".govuk-summary-list__value").text()
                  val changeUrl = element.select(".govuk-link").attr("href")
                  ModifyUndertakingRow(question, answer, changeUrl)
                }
              rows shouldBe expectedRows
            }
          )
        }

        "is Amend flag is false" in {
          inSequence {
            mockAuthWithNecessaryEnrolmentWithValidEmail()
            mockRetrieveUndertaking(eori1)(undertaking.some.toFuture)
            mockGet[UndertakingJourney](eori1)(Right(undertakingJourneyComplete.some))
            mockUpdate[UndertakingJourney](_ => update(undertakingJourneyComplete), eori1)(
              Right(undertakingJourneyComplete.copy(isAmend = true))
            )
          }

          checkPageIsDisplayed(
            performAction(),
            messageFromMessageKey("undertaking.amendUndertaking.title"),
            { doc =>
              doc.select(".govuk-back-link").attr("href") shouldBe routes.AccountController.getAccountPage().url

              val rows =
                doc.select(".govuk-summary-list__row").iterator().asScala.toList.map { element =>
                  val question = element.select(".govuk-summary-list__key").text()
                  val answer = element.select(".govuk-summary-list__value").text()
                  val changeUrl = element.select(".govuk-link").attr("href")
                  ModifyUndertakingRow(question, answer, changeUrl)
                }
              rows shouldBe expectedRows
            }
          )
        }

      }

      "redirect to journey start page" when {

        "call to get undertaking journey fetches nothing" in {
          inSequence {
            mockAuthWithNecessaryEnrolmentWithValidEmail()
            mockRetrieveUndertaking(eori1)(undertaking.some.toFuture)
            mockGet[UndertakingJourney](eori1)(Right(None))
          }
          checkIsRedirect(performAction(), routes.UndertakingController.getAboutUndertaking().url)
        }

      }

    }

    "handling request to post Amend undertaking" must {

      def performAction(data: (String, String)*) = controller
        .postAmendUndertaking(
          FakeRequest(POST, routes.UndertakingController.getAmendUndertakingDetails().url)
            .withFormUrlEncodedBody(data: _*)
        )

      def update(u: UndertakingJourney) = u.copy(isAmend = false)

      "throw technical error" when {
        val exception = new Exception("oh no")

        "call to update undertaking journey fails" in {
          inSequence {
            mockAuthWithNecessaryEnrolmentWithValidEmail()
            mockRetrieveUndertaking(eori1)(undertaking.some.toFuture)
            mockUpdate[UndertakingJourney](_ => update(undertakingJourneyComplete), eori1)(
              Left(ConnectorError(exception))
            )
          }
          assertThrows[Exception](await(performAction("amendUndertaking" -> "true")))

        }

        "call to update undertaking journey passes but return undertaking with no name" in {
          inSequence {
            mockAuthWithNecessaryEnrolmentWithValidEmail()
            mockRetrieveUndertaking(eori1)(undertaking.some.toFuture)
            mockUpdate[UndertakingJourney](_ => update(undertakingJourneyComplete), eori1)(
              Right(undertakingJourneyComplete.copy(about = AboutUndertakingFormPage()))
            )
          }
          assertThrows[Exception](await(performAction("amendUndertaking" -> "true")))

        }

        "call to update undertaking journey passes but return undertaking with no secctor" in {
          inSequence {
            mockAuthWithNecessaryEnrolmentWithValidEmail()
            mockRetrieveUndertaking(eori1)(undertaking.some.toFuture)
            mockUpdate[UndertakingJourney](_ => update(undertakingJourneyComplete), eori1)(
              Right(undertakingJourneyComplete.copy(about = AboutUndertakingFormPage()))
            )
          }
          assertThrows[Exception](await(performAction("amendUndertaking" -> "true")))

        }

        "call to retrieve undertaking fails" in {
          inSequence {
            mockAuthWithNecessaryEnrolmentWithValidEmail()
            mockRetrieveUndertaking(eori1)(undertaking.some.toFuture)
            mockUpdate[UndertakingJourney](_ => update(undertakingJourneyComplete), eori1)(
              Right(undertakingJourneyComplete.copy(about = AboutUndertakingFormPage("true".some)))
            )
            mockRetrieveUndertaking(eori1)(Future.failed(exception))
          }
          assertThrows[Exception](await(performAction("amendUndertaking" -> "true")))
        }

        "call to retrieve undertaking passes but no undertaking was fetched" in {
          inSequence {
            mockAuthWithNecessaryEnrolmentWithValidEmail()
            mockRetrieveUndertaking(eori1)(undertaking.some.toFuture)
            mockUpdate[UndertakingJourney](_ => update(undertakingJourneyComplete), eori1)(
              Right(undertakingJourneyComplete.copy(about = AboutUndertakingFormPage("true".some)))
            )
            mockRetrieveUndertaking(eori1)(None.toFuture)
          }
          assertThrows[Exception](await(performAction("amendUndertaking" -> "true")))
        }

        "call to update undertaking fails" in {
          val updatedUndertaking =
            undertaking1.copy(name = UndertakingName("TestUndertaking"), industrySector = Sector(1))
          inSequence {
            mockAuthWithNecessaryEnrolmentWithValidEmail()
            mockRetrieveUndertaking(eori1)(undertaking.some.toFuture)
            mockUpdate[UndertakingJourney](_ => update(undertakingJourneyComplete), eori1)(
              Right(undertakingJourneyComplete.copy(isAmend = true))
            )
            mockRetrieveUndertaking(eori1)(undertaking1.some.toFuture)
            mockUpdateUndertaking(updatedUndertaking)(Left(ConnectorError(exception)))
          }
          assertThrows[Exception](await(performAction("amendUndertaking" -> "true")))
        }

      }

      "redirect to next page" in {
        val updatedUndertaking =
          undertaking1.copy(name = UndertakingName("TestUndertaking"), industrySector = Sector(1))
        inSequence {
          mockAuthWithNecessaryEnrolmentWithValidEmail()
          mockRetrieveUndertaking(eori1)(undertaking.some.toFuture)
          mockUpdate[UndertakingJourney](_ => update(undertakingJourneyComplete), eori1)(
            Right(undertakingJourneyComplete.copy(isAmend = true))
          )
          mockRetrieveUndertaking(eori1)(undertaking1.some.toFuture)
          mockUpdateUndertaking(updatedUndertaking)(Right(undertakingRef))
          mockSendAuditEvent(
            UndertakingUpdated("1123", eori1, undertakingRef, undertaking1.name, undertaking1.industrySector)
          )
        }
        checkIsRedirect(performAction("amendUndertaking" -> "true"), routes.AccountController.getAccountPage().url)
      }

    }

    "handling request to get Disable undertaking warning" must {
      def performAction() = controller.getDisableUndertakingWarning(FakeRequest())

      "display the page" in {
        inSequence {
          mockAuthWithNecessaryEnrolmentWithValidEmail()
          mockRetrieveUndertaking(eori1)(undertaking.some.toFuture)
        }
        checkPageIsDisplayed(
          performAction(),
          messageFromMessageKey("disableUndertakingWarning.title", undertaking.name),
          doc => doc.select(".govuk-back-link").attr("href") shouldBe routes.AccountController.getAccountPage().url
        )
      }

    }

    "handling request to get Disable undertaking confirm" must {
      def performAction() = controller.getDisableUndertakingConfirm(FakeRequest())

      "display the page" in {
        inSequence {
          mockAuthWithNecessaryEnrolmentWithValidEmail()
          mockRetrieveUndertaking(eori1)(undertaking.some.toFuture)
        }
        checkPageIsDisplayed(
          performAction(),
          messageFromMessageKey("disableUndertakingConfirm.title", undertaking.name),
          { doc =>
            doc.select(".govuk-back-link").attr("href") shouldBe routes.UndertakingController
              .getDisableUndertakingWarning()
              .url
            val form = doc.select("form")
            form
              .attr("action") shouldBe routes.UndertakingController.postDisableUndertakingConfirm().url
          }
        )
      }

    }

    "handling request to post Disable undertaking confirm" must {
      def performAction(data: (String, String)*) = controller
        .postDisableUndertakingConfirm(
          FakeRequest(POST, routes.UndertakingController.getDisableUndertakingConfirm().url)
            .withFormUrlEncodedBody(data: _*)
        )

      val currentDate = LocalDate.of(2022, 10, 9)
      val formattedDate = "9 October 2022"

      "throw technical error" when {
        "call to remove disable fails" in {
          inSequence {
            mockAuthWithNecessaryEnrolmentWithValidEmail()
            mockRetrieveUndertaking(eori1)(undertaking1.some.toFuture)
            mockDisableUndertaking(undertaking1)(Left(ConnectorError(exception)))
          }
          assertThrows[Exception](await(performAction("disableUndertakingConfirm" -> "true")))
        }
      }

      "display the error" when {

        "Nothing is submitted" in {
          inSequence {
            mockAuthWithNecessaryEnrolmentWithValidEmail()
            mockRetrieveUndertaking(eori1)(undertaking.some.toFuture)
          }
          checkFormErrorIsDisplayed(
            performAction(),
            messageFromMessageKey("disableUndertakingConfirm.title", undertaking1.name),
            messageFromMessageKey("disableUndertakingConfirm.error.required")
          )

        }
      }

      "redirect to next page" when {

        "user select Yes" in {
          inSequence {
            mockAuthWithNecessaryEnrolmentWithValidEmail()
            mockRetrieveUndertaking(eori1)(undertaking1.some.toFuture)
            mockDisableUndertaking(undertaking1)(Right(undertakingRef))
            mockDelete[EligibilityJourney](eori1)(Right(()))
            mockDelete[UndertakingJourney](eori1)(Right(()))
            mockDelete[NewLeadJourney](eori1)(Right(()))
            mockDelete[NilReturnJourney](eori1)(Right(()))
            mockDelete[BusinessEntityJourney](eori1)(Right(()))
            mockDelete[BecomeLeadJourney](eori1)(Right(()))
            mockDelete[SubsidyJourney](eori1)(Right(()))
            mockDelete[EligibilityJourney](eori4)(Right(()))
            mockDelete[UndertakingJourney](eori4)(Right(()))
            mockDelete[NewLeadJourney](eori4)(Right(()))
            mockDelete[NilReturnJourney](eori4)(Right(()))
            mockDelete[BusinessEntityJourney](eori4)(Right(()))
            mockDelete[BecomeLeadJourney](eori4)(Right(()))
            mockDelete[SubsidyJourney](eori4)(Right(()))
            mockTimeToday(currentDate)
            mockSendAuditEvent[UndertakingDisabled](UndertakingDisabled("1123", undertakingRef, currentDate))
            mockTimeToday(currentDate)
            mockSendEmail(eori1, DisableUndertakingToLead, undertaking1, formattedDate)(Right(EmailSent))
            mockSendEmail(eori1, DisableUndertakingToBusinessEntity, undertaking1, formattedDate)(Right(EmailSent))
          }
          checkIsRedirect(
            performAction("disableUndertakingConfirm" -> "true"),
            routes.UndertakingController.getUndertakingDisabled().url
          )
        }

        "user selected No" in {
          inSequence {
            mockAuthWithNecessaryEnrolmentWithValidEmail()
            mockRetrieveUndertaking(eori1)(undertaking.some.toFuture)
          }
          checkIsRedirect(
            performAction("disableUndertakingConfirm" -> "false"),
            routes.AccountController.getAccountPage().url
          )
        }
      }

    }

    "handling request to get Undertaking Disabled" must {
      def performAction() = controller.getUndertakingDisabled(FakeRequest())

      "display the page" in {
        inSequence {
          mockAuthWithNecessaryEnrolmentWithValidEmail()
        }
        checkPageIsDisplayed(
          performAction(),
          messageFromMessageKey("undertakingDisabled.title")
        )
      }
    }
  }
}

object UndertakingControllerSpec {
  final case class ModifyUndertakingRow(question: String, answer: String, changeUrl: String)
}
