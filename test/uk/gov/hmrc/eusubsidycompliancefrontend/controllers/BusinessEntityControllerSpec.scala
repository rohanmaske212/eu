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
import com.typesafe.config.ConfigFactory
import play.api.Configuration
import play.api.inject.bind
import play.api.mvc.Cookie
import play.api.test.FakeRequest
import play.api.test.Helpers._
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.eusubsidycompliancefrontend.controllers.BusinessEntityControllerSpec.CheckYourAnswersRowBE
import uk.gov.hmrc.eusubsidycompliancefrontend.models.Language.{English, Welsh}
import uk.gov.hmrc.eusubsidycompliancefrontend.models.email.EmailParameters.{DoubleEORIAndDateEmailParameter, DoubleEORIEmailParameter, SingleEORIAndDateEmailParameter, SingleEORIEmailParameter}
import uk.gov.hmrc.eusubsidycompliancefrontend.models.email.{EmailParameters, EmailSendResult}
import uk.gov.hmrc.eusubsidycompliancefrontend.models.{BusinessEntity, Error, Language, Undertaking}
import uk.gov.hmrc.eusubsidycompliancefrontend.models.types.{EORI, UndertakingRef}
import uk.gov.hmrc.eusubsidycompliancefrontend.services.{BusinessEntityJourney, EscService, FormPage, JourneyTraverseService, RetrieveEmailService, SendEmailService, Store}
import uk.gov.hmrc.eusubsidycompliancefrontend.util.TimeProvider
import uk.gov.hmrc.http.HeaderCarrier
import utils.CommonTestData.{undertaking, _}

import java.time.LocalDate
import scala.collection.JavaConverters._
import scala.concurrent.Future

class BusinessEntityControllerSpec  extends ControllerSpec
  with AuthSupport
  with JourneyStoreSupport
  with AuthAndSessionDataBehaviour
  with JourneySupport
  with RetrieveEmailSupport
  with SendEmailSupport  {

  val mockEscService = mock[EscService]
  val mockTimeProvider = mock[TimeProvider]

  override def overrideBindings = List(
    bind[AuthConnector].toInstance(mockAuthConnector),
    bind[Store].toInstance(mockJourneyStore),
    bind[EscService].toInstance(mockEscService),
    bind[JourneyTraverseService].toInstance(mockJourneyTraverseService),
    bind[RetrieveEmailService].toInstance(mockRetrieveEmailService),
    bind[SendEmailService].toInstance(mockSendEmailService),
    bind[TimeProvider].toInstance(mockTimeProvider)
  )

  override def additionalConfig = super.additionalConfig.withFallback(
    Configuration(
      ConfigFactory.parseString(s"""
                                   |
                                   |play.i18n.langs = ["en", "cy", "fr"]
                                   | email-send {
                                   |     add-member-to-be-template-en = "template_add_be_EN"
                                   |     add-member-to-be-template-cy = "template_add__be_CY"
                                   |     add-member-to-lead-template-en = "template_add_lead_EN"
                                   |     add-member-to-lead-template-cy = "template_add_lead_CY"
                                   |     remove-member-to-be-template-en = "template_remove_be_EN"
                                   |     remove-member-to-be-template-cy = "template_remove_be_CY"
                                   |     remove-member-to-lead-template-en = "template_remove_lead_EN"
                                   |     remove-member-to-lead-template-cy = "template_remove_lead_CY"
                                   |     member-remove-themself-email-to-be-template-en = "template_remove_yourself_be_EN"
                                   |     member-remove-themself-email-to-be-template-cy = "template_remove_yourself_be_CY"
                                   |     member-remove-themself-email-to-lead-template-en = "template_remove_yourself_lead_EN"
                                   |     member-remove-themself-email-to-lead-template-cy = "template_remove_yourself_lead_CY"
                                   |  }
                                   |""".stripMargin)
    )
  )


  val controller = instanceOf[BusinessEntityController]

  val invalidEOris = List("GB1234567890", "AB1234567890", "GB1234567890123")
  val currentDate = LocalDate.of(2022, 10,9)

  val contact = List(contactDetails.phone, contactDetails.mobile).flatten.mkString(" ")

  val expectedRows = List(
    CheckYourAnswersRowBE(
      messageFromMessageKey("businessEntity.cya.eori.label"),
      eori1,
      routes.BusinessEntityController.getEori().url
    ),
    CheckYourAnswersRowBE(
      messageFromMessageKey("businessEntity.cya.contact.label"),
      contact,
      routes.BusinessEntityController.getContact().url
    )
  )

  def mockRetreiveUndertaking(eori: EORI)(result: Future[Option[Undertaking]]) =
    (mockEscService
      .retrieveUndertaking(_: EORI)(_: HeaderCarrier))
      .expects(eori, *)
      .returning(result)

  def mockRemoveMember(undertakingRef: UndertakingRef, businessEntity: BusinessEntity)(result: Either[Error, UndertakingRef]) =
    (mockEscService
      .removeMember(_: UndertakingRef, _: BusinessEntity)(_: HeaderCarrier))
      .expects(undertakingRef, businessEntity, *)
      .returning(result.fold(e => Future.failed(e.value.fold(s => new Exception(s), identity)),Future.successful))

  def mockAddMember(undertakingRef: UndertakingRef, businessEntity: BusinessEntity)(result: Either[Error, UndertakingRef]) = {
    (mockEscService
      .addMember(_: UndertakingRef, _: BusinessEntity)(_: HeaderCarrier))
      .expects(undertakingRef, businessEntity, *)
      .returning(result.fold(e => Future.failed(e.value.fold(s => new Exception(s), identity)),Future.successful(_)))
  }

  def mockTimeToday(now: LocalDate) =
    (mockTimeProvider.today _).expects().returning(now)

  "BusinessEntityControllerSpec" when {

    "handling request to get add Business Page" must {

      def performAction() = controller.getAddBusinessEntity(FakeRequest())

      "throw technical error" when {
        val exception = new Exception("oh no")

        "call to get business entity journey fails" in {
          inSequence {
            mockAuthWithNecessaryEnrolment()
            mockGet[BusinessEntityJourney](eori1)(Left(Error(exception)))
          }
          assertThrows[Exception](await(performAction()))
        }

        "call to fetch undertaking fails" in {
          inSequence {
            mockAuthWithNecessaryEnrolment()
            mockGet[BusinessEntityJourney](eori1)(Right(businessEntityJourney.some))
            mockRetreiveUndertaking(eori1)(Future.failed(exception))
          }
          assertThrows[Exception](await(performAction()))
        }

        "call to fetch undertaking retrieve no undertaking" in {
          inSequence {
            mockAuthWithNecessaryEnrolment()
            mockGet[BusinessEntityJourney](eori1)(Right(businessEntityJourney.some))
            mockRetreiveUndertaking(eori1)(Future.successful(None))

          }
          assertThrows[Exception](await(performAction()))
        }

        "call to store undertaking fails" in {
          inSequence {
            mockAuthWithNecessaryEnrolment()
            mockGet[BusinessEntityJourney](eori1)(Right(businessEntityJourney.some))
            mockRetreiveUndertaking(eori1)(Future.successful(undertaking.some))
            mockPut[Undertaking](undertaking, eori1)(Left(Error(exception)))
          }
          assertThrows[Exception](await(performAction()))
        }
      }

      "display the page" when {

        def test(input: Option[String], businessEntityJourney: BusinessEntityJourney) = {
          inSequence {
            mockAuthWithNecessaryEnrolment()
            mockGet[BusinessEntityJourney](eori1)(Right(businessEntityJourney.some))
            mockRetreiveUndertaking(eori1)(Future.successful(undertaking.some))
            mockPut[Undertaking](undertaking, eori1)(Right(undertaking))
          }

          checkPageIsDisplayed(
            performAction(),
            messageFromMessageKey("addBusiness.businesses-added.title", undertaking.name),
            {doc =>
              val selectedOptions = doc.select(".govuk-radios__input[checked]")

              input match {
                case Some(value) => selectedOptions.attr("value") shouldBe value
                case None => selectedOptions.isEmpty       shouldBe true
              }


              val button = doc.select("form")
              button.attr("action") shouldBe routes.BusinessEntityController.postAddBusinessEntity().url
            }
          )
        }

        "user hasn't already answered the question" in {
         test(None, BusinessEntityJourney())
        }

        "user has already answered the question" in {
          test(Some("true"), BusinessEntityJourney(addBusiness = FormPage("add-member", true.some)))
        }
      }

    }

    "handling request to post add Business Page" must {

      def performAction(data: (String, String)*) = controller.postAddBusinessEntity(FakeRequest("POST",routes.BusinessEntityController.getAddBusinessEntity().url).withFormUrlEncodedBody(data: _*))

      "throw technical error" when {
        val exception = new Exception("oh no")

        "call to fetch undertaking fails" in {
          inSequence {
            mockAuthWithNecessaryEnrolment()
            mockGet[Undertaking](eori)(Left(Error(exception)))
          }
          assertThrows[Exception](await(performAction()))
        }

        "call to fetch undertaking passes but the undertaking came back as None" in {
          inSequence {
            mockAuthWithNecessaryEnrolment()
            mockGet[Undertaking](eori)(Right(None))
          }
          assertThrows[Exception](await(performAction()))
        }

        "call to update BusinessEntityJourney fails" in {

          def updateFunc(beOpt: Option[BusinessEntityJourney]) =
            beOpt.map(x => x.copy(addBusiness  = x.addBusiness.copy(value = Some(true))))
          inSequence {
            mockAuthWithNecessaryEnrolment()
            mockGet[Undertaking](eori)(Right(undertaking.some))
            mockUpdate[BusinessEntityJourney](_ => updateFunc(businessEntityJourney.some), eori)(Left(Error(exception)))
          }
          assertThrows[Exception](await(performAction("addBusiness" -> "true")))
        }

      }

      "show a form error" when {

        def displayErrorTest(data: (String, String)*)(errorMessage: String) = {

          inSequence {
            mockAuthWithNecessaryEnrolment()
            mockGet[Undertaking](eori)(Right(undertaking.some))
          }

          checkFormErrorIsDisplayed(
            performAction(data: _*),
            messageFromMessageKey("addBusiness.businesses-added.title", undertaking.name),
            messageFromMessageKey(errorMessage, undertaking.name)
          )
        }

        "nothing has been submitted" in {
          displayErrorTest()("addBusiness.error.required")
        }


      }

      "redirect to the next page" when {

        "user selected No" in {
          inSequence {
            mockAuthWithNecessaryEnrolment()
            mockGet[Undertaking](eori)(Right(undertaking.some))
          }
          checkIsRedirect(performAction("addBusiness" -> "false"), routes.AccountController.getAccountPage().url)
        }

        "user selected Yes" in {
          def updateFunc(beOpt: Option[BusinessEntityJourney]) =
            beOpt.map(x => x.copy(addBusiness  = x.addBusiness.copy(value = Some(true))))
          inSequence {
            mockAuthWithNecessaryEnrolment()
            mockGet[Undertaking](eori)(Right(undertaking.some))
            mockUpdate[BusinessEntityJourney](_ => updateFunc(BusinessEntityJourney().some), eori)(Right(BusinessEntityJourney(addBusiness = FormPage("add-member", true.some))))
          }
          checkIsRedirect(performAction("addBusiness" -> "true"), "add-business-entity-eori")
        }

      }


    }

    "handling request to get EORI Page" must {
      def performAction() = controller.getEori(FakeRequest())

      "throw technical error" when {
        val exception = new Exception("oh no")

        "call to get previous uri fails" in {
          inSequence{
            mockAuthWithNecessaryEnrolment()
            mockGetPrevious[BusinessEntityJourney](eori1)(Left(Error(exception)))
          }
          assertThrows[Exception](await(performAction()))

        }

        "call to get business entity journey fails" in {
          inSequence{
            mockAuthWithNecessaryEnrolment()
            mockGetPrevious[BusinessEntityJourney](eori1)(Right("/add-member"))
            mockGet[BusinessEntityJourney](eori1)(Left(Error(exception)))
          }
          assertThrows[Exception](await(performAction()))

        }

        "call to get business entity journey came back empty" in {
          inSequence{
            mockAuthWithNecessaryEnrolment()
            mockGetPrevious[BusinessEntityJourney](eori1)(Right("/add-member"))
            mockGet[BusinessEntityJourney](eori1)(Right(None))
          }
          assertThrows[Exception](await(performAction()))

        }


      }

      "display the page" when {

        def test(businessEntityJourney: BusinessEntityJourney) = {
          val previousUrl  = "add-member"
          inSequence{
            mockAuthWithNecessaryEnrolment()
            mockGetPrevious[BusinessEntityJourney](eori1)(Right(previousUrl))
            mockGet[BusinessEntityJourney](eori1)(Right(businessEntityJourney.some))
          }

          checkPageIsDisplayed(
            performAction(),
            messageFromMessageKey("businessEntityEori.title"),
            {doc =>

              doc.select(".govuk-back-link").attr("href") shouldBe(previousUrl)

              val input = doc.select(".govuk-input").attr("value")
              input shouldBe businessEntityJourney.eori.value.map(_.drop(2)).getOrElse("")

              val button = doc.select("form")
              button.attr("action") shouldBe routes.BusinessEntityController.postEori().url
            }
          )
        }

        "user hasn't already answered the question" in {
          test(BusinessEntityJourney().copy(
            addBusiness = FormPage("add-member", true.some)
          ))
        }

        "user has already answered the question" in {
          test(BusinessEntityJourney().copy(
            addBusiness = FormPage("add-member", true.some),
            eori = FormPage("add-business-entity-eori", eori1.some)
          ))
        }

      }

    }

    "handling request to Post EORI page" must {

      def performAction(data: (String, String)*) = controller
        .postEori(
          FakeRequest("POST",routes.BusinessEntityController.getEori().url)
          .withFormUrlEncodedBody(data: _*))

      "throw technical error" when {

        val exception = new Exception("oh no")

        "call to get previous uri fails" in {
          inSequence{
            mockAuthWithNecessaryEnrolment()
            mockGetPrevious[BusinessEntityJourney](eori1)(Left(Error(exception)))
          }
          assertThrows[Exception](await(performAction()))

        }

        "call to retrieve undertaking fails" in {
          inSequence{
            mockAuthWithNecessaryEnrolment()
            mockGetPrevious[BusinessEntityJourney](eori1)(Right("add-member"))
            mockRetreiveUndertaking(eori4)(Future.failed(exception))
          }
          assertThrows[Exception](await(performAction("businessEntityEori" -> "123456789010")))

        }

        "call to update business entity journey fails" in {

          def update(opt: Option[BusinessEntityJourney]) =  opt.map { businessEntity =>
            businessEntity.copy(eori = businessEntity.eori.copy(value = Some(EORI("123456789010"))))
          }

          val businessEntityJourney =  BusinessEntityJourney().copy(
            addBusiness = FormPage("add-member", true.some),
            eori = FormPage("add-business-entity-eori", eori1.some)
          ).some
          inSequence{
            mockAuthWithNecessaryEnrolment()
            mockGetPrevious[BusinessEntityJourney](eori1)(Right("add-member"))
            mockRetreiveUndertaking(eori4)(Future.successful(None))
            mockUpdate[BusinessEntityJourney](_ => update(businessEntityJourney), eori1)(Left(Error(exception)))
          }

          assertThrows[Exception](await(performAction("businessEntityEori" -> "123456789010")))

        }



      }

      "show a form error" when {

        def test(data: (String, String)*)(errorMessageKey: String) = {
          inSequence{
            mockAuthWithNecessaryEnrolment()
            mockGetPrevious[BusinessEntityJourney](eori1)(Right("add-member"))
          }
          checkFormErrorIsDisplayed(
            performAction(data: _*),
            messageFromMessageKey("businessEntityEori.title"),
            messageFromMessageKey(errorMessageKey)
          )
        }

        "No eori is submitted" in {
          test("businessEntityEori" -> "")("error.businessEntityEori.required")
        }

        "invalid eori is submitted" in {
          invalidEOris.foreach { eori =>
            withClue(s" For eori :: $eori") {
              test("businessEntityEori" -> eori)("businessEntityEori.regex.error")
            }

          }
        }
      }

    }

    "handling request to get contact page" must {
      def performAction() = controller.getContact(
        FakeRequest("GET",routes.BusinessEntityController.getContact().url))

      "throw technical error" when {
        val exception = new Exception("oh no")
        "call to get business entity journey fails" in {
          inSequence {
            mockAuthWithNecessaryEnrolment()
            mockGet[BusinessEntityJourney](eori1)(Left(Error(exception)))
          }
          assertThrows[Exception](await(performAction()))
        }

        "call to get business entity journey return with None" in {
          inSequence {
            mockAuthWithNecessaryEnrolment()
            mockGet[BusinessEntityJourney](eori1)(Right(None))
          }
          assertThrows[Exception](await(performAction()))
        }
      }

      "display the page" when {

        def test(businessEntityJourney: BusinessEntityJourney) = {

          val previousUrl = "add-business-entity-eori"
          inSequence {
            mockAuthWithNecessaryEnrolment()
            mockGet[BusinessEntityJourney](eori1)(Right(businessEntityJourney.some))
          }

          checkPageIsDisplayed(
            performAction(),
            messageFromMessageKey("businessEntityContact.title"),
            {doc =>
              doc.select(".govuk-back-link").attr("href") shouldBe(previousUrl)

              val inputPhone = doc.select("#phone").attr("value")
              val inputMobile = doc.select("#mobile").attr("value")

              inputPhone shouldBe businessEntityJourney.contact.value.flatMap(_.phone).getOrElse("")
              inputMobile shouldBe businessEntityJourney.contact.value.flatMap(_.mobile).getOrElse("")

              val button = doc.select("form")
              button.attr("action") shouldBe routes.BusinessEntityController.postContact.url
            }
          )
        }

        "user hasn't already answered the questions" in {
          test(
            businessEntityJourney.copy(contact = FormPage("add-business-entity-contact", None),
              cya = FormPage("check-your-answers-businesses", None))
          )
        }

        "user has already answered the questions" in {
          test(
            businessEntityJourney.copy(contact = FormPage("add-business-entity-contact", contactDetails1.some),
              cya = FormPage("check-your-answers-businesses", None))
          )
        }

      }
    }

    "handling request to post contact page" must {

      def performAction(data: (String, String)*) = controller
        .postContact(
          FakeRequest("POST",routes.BusinessEntityController.getContact().url)
            .withFormUrlEncodedBody(data: _*))

      "throw technical error" when {
        val exception = new Exception("oh no")
        "call to get previous fails" in {
          inSequence {
            mockAuthWithNecessaryEnrolment()
            mockGetPrevious[BusinessEntityJourney](eori1)(Left(Error(exception)))
          }
          assertThrows[Exception](await(performAction()))
        }

      }

      "show form errors" when {

        def test(data: (String, String)*)(errorMessageKey: List[String]) = {
          inSequence{
            mockAuthWithNecessaryEnrolment()
            mockGetPrevious[BusinessEntityJourney](eori1)(Right("add-business-entity-contact"))
          }
          checkFormErrorsAreDisplayed(
            performAction(data: _*),
            messageFromMessageKey("businessEntityContact.title"),
            errorMessageKey
          )

        }

        "nothing is submitted" in {
          test()(List(
            messageFromMessageKey("one.or.other.mustbe.present"),
            messageFromMessageKey("one.or.other.mustbe.present")
          ))
        }

      }

      "redirect to next page" when {

        "user has answered the questions correctly" in {
          def updatedDef(beJourneyOpt: Option[BusinessEntityJourney]): Option[BusinessEntityJourney] = {
            beJourneyOpt.map ( _.copy(contact = FormPage("add-business-entity-contact", contactDetails.some)))
          }
          val currentBEJourney = businessEntityJourney1.copy(contact = FormPage("add-business-entity-contact", None),
            cya= FormPage("check-your-answers-businesses", None)).some

          val updatedBEJourney = businessEntityJourney1.copy(contact = FormPage("add-business-entity-contact", contactDetails.some),
            cya= FormPage("check-your-answers-businesses", None))

          inSequence {
            mockAuthWithNecessaryEnrolment()
            mockGetPrevious[BusinessEntityJourney](eori1)(Right("add-business-entity-contact"))
            mockUpdate[BusinessEntityJourney](_ => updatedDef(currentBEJourney), eori1)(Right(updatedBEJourney))
          }

          checkIsRedirect(performAction("phone" -> "1234567890"), "check-your-answers-businesses")

        }
      }

    }

    "handling request to get check your answers page" must {

      def performAction() = controller.getCheckYourAnswers(
        FakeRequest("GET",routes.BusinessEntityController.getCheckYourAnswers().url))

      "throw technical error" when {

        val exception = new Exception("oh no!")
        "Call to fetch Business Entity journey fails" in {
          inSequence {
            mockAuthWithNecessaryEnrolment()
            mockGet[BusinessEntityJourney](eori1)(Left(Error(exception)))
          }
          assertThrows[Exception](await(performAction()))

        }

        "call to fetch Business Entity journey returns Nothing" in {
          inSequence {
            mockAuthWithNecessaryEnrolment()
            mockGet[BusinessEntityJourney](eori1)(Right(None))
          }
          assertThrows[Exception](await(performAction()))
        }

        "call to fetch Business Entity journey returns journey without eori" in {
          inSequence {
            mockAuthWithNecessaryEnrolment()
            mockGet[BusinessEntityJourney](eori1)(Right(businessEntityJourney.copy(eori = FormPage("add-business-entity-eori")).some))
          }
          assertThrows[Exception](await(performAction()))
        }

        "call to fetch Business Entity journey returns journey without contact details" in {
          inSequence {
            mockAuthWithNecessaryEnrolment()
            mockGet[BusinessEntityJourney](eori1)(Right(businessEntityJourney.copy(contact = FormPage("contact")).some))
          }
          assertThrows[Exception](await(performAction()))
        }
      }

      "display the page" in {
        inSequence {
          mockAuthWithNecessaryEnrolment()
          mockGet[BusinessEntityJourney](eori1)(Right(businessEntityJourney.some))
        }

        checkPageIsDisplayed(
          performAction(),
          messageFromMessageKey("businessEntity.cya.title"),
          {doc =>

            val rows =
              doc.select(".govuk-summary-list__row").iterator().asScala.toList.map { element =>
                val question  = element.select(".govuk-summary-list__key").text()
                val answer    = element.select(".govuk-summary-list__value").text()
                val changeUrl = element.select(".govuk-link").attr("href")
                CheckYourAnswersRowBE(question, answer, changeUrl)
              }
            rows shouldBe expectedRows
          }
        )
      }

    }

    "handling request to post check yor answers" must {
      def performAction(data: (String, String)*)(lang: String) = controller
        .postCheckYourAnswers(
          FakeRequest("POST",routes.BusinessEntityController.getCheckYourAnswers().url)
            .withCookies(Cookie("PLAY_LANG", lang))
            .withFormUrlEncodedBody(data: _*))

      "throw technical error" when {
        val exception = new Exception("oh no")

        "call to get undertaking fails" in {
          inSequence{
            mockAuthWithNecessaryEnrolment()
            mockGet[Undertaking](eori1)(Left(Error(exception)))
          }
          assertThrows[Exception](await(performAction("cya" -> "true")(English.code)))
        }

        "call to get undertaking returns nothing" in {
          inSequence{
            mockAuthWithNecessaryEnrolment()
            mockGet[Undertaking](eori1)(Right(None))
          }
          assertThrows[Exception](await(performAction("cya" -> "true")(English.code)))
        }

        "call to get undertaking return undertaking without undertaking ref" in {
          inSequence{
            mockAuthWithNecessaryEnrolment()
            mockGet[Undertaking](eori1)(Right(undertaking.copy(reference = None).some))
          }
          assertThrows[Exception](await(performAction("cya" -> "true")(English.code)))
        }

        "call to get business entity fails" in {
          inSequence{
            mockAuthWithNecessaryEnrolment()
            mockGet[Undertaking](eori1)(Right(undertaking.some))
            mockGet[BusinessEntityJourney](eori1)(Left(Error(exception)))
          }
          assertThrows[Exception](await(performAction("cya" -> "true")(English.code)))
        }

        "call to get business entity returns nothing" in {
          inSequence{
            mockAuthWithNecessaryEnrolment()
            mockGet[Undertaking](eori1)(Right(undertaking.some))
            mockGet[BusinessEntityJourney](eori1)(Right(None))
          }
          assertThrows[Exception](await(performAction("cya" -> "true")(English.code)))
        }

        "call to get business entity  return  without EORI" in {
          inSequence{
            mockAuthWithNecessaryEnrolment()
            mockGet[Undertaking](eori1)(Right(undertaking.some))
            mockGet[BusinessEntityJourney](eori1)(Right(businessEntityJourney1.copy(eori = FormPage("add-business-entity-eori", None)).some))
          }
          assertThrows[Exception](await(performAction("cya" -> "true")(English.code)))
        }

        "call to get business entity  return  without contact Details" in {
          inSequence{
            mockAuthWithNecessaryEnrolment()
            mockGet[Undertaking](eori1)(Right(undertaking.some))
            mockGet[BusinessEntityJourney](eori1)(Right(businessEntityJourney1.copy(contact = FormPage("add-business-entity-contact", None)).some))
          }
          assertThrows[Exception](await(performAction("cya" -> "true")(English.code)))
        }

        "call to add member to BE undertaking fails" in {

          val businessEntity = BusinessEntity(eori2, leadEORI = false, contactDetails.some)
          inSequence{
            mockAuthWithNecessaryEnrolment()
            mockGet[Undertaking](eori1)(Right(undertaking.some))
            mockGet[BusinessEntityJourney](eori1)(Right(businessEntityJourney1.some))
            mockAddMember(undertakingRef, businessEntity)(Left(Error(exception)))
          }
          assertThrows[Exception](await(performAction("cya" -> "true")(English.code)))
        }

        "call to retrieve email for BE EORI fails" in {

          val businessEntity = BusinessEntity(eori2, false, contactDetails.some)
          inSequence{
            mockAuthWithNecessaryEnrolment()
            mockGet[Undertaking](eori1)(Right(undertaking.some))
            mockGet[BusinessEntityJourney](eori1)(Right(businessEntityJourney1.some))
            mockAddMember(undertakingRef, businessEntity)(Right(undertakingRef))
            mockRetrieveEmail(eori2)(Left(Error(exception)))
          }

          assertThrows[Exception](await(performAction("cya" -> "true")(Language.English.code)))
        }

        "call to retrieve email for BE EORI returns None" in {

          val businessEntity = BusinessEntity(eori2, false, contactDetails.some)
          inSequence{
            mockAuthWithNecessaryEnrolment()
            mockGet[Undertaking](eori1)(Right(undertaking.some))
            mockGet[BusinessEntityJourney](eori1)(Right(businessEntityJourney1.some))
            mockAddMember(undertakingRef, businessEntity)(Right(undertakingRef))
            mockRetrieveEmail(eori2)(Right(None))
          }
          assertThrows[Exception](await(performAction("cya" -> "true")(Language.English.code)))
        }

        "call to retrieve email for lead EORI fails" in {

          val businessEntity = BusinessEntity(eori2, false, contactDetails.some)
          inSequence{
            mockAuthWithNecessaryEnrolment()
            mockGet[Undertaking](eori1)(Right(undertaking.some))
            mockGet[BusinessEntityJourney](eori1)(Right(businessEntityJourney1.some))
            mockAddMember(undertakingRef, businessEntity)(Right(undertakingRef))
            mockRetrieveEmail(eori2)(Right(validEmailAddress.some))
            mockRetrieveEmail(eori1)(Left(Error(exception)))
          }
          assertThrows[Exception](await(performAction("cya" -> "true")(Language.English.code)))
        }

        "call to retrieve Lead EORI email address returns None" in {

          val businessEntity = BusinessEntity(eori2, false, contactDetails.some)
          inSequence{
            mockAuthWithNecessaryEnrolment()
            mockGet[Undertaking](eori1)(Right(undertaking.some))
            mockGet[BusinessEntityJourney](eori1)(Right(businessEntityJourney1.some))
            mockAddMember(undertakingRef, businessEntity)(Right(undertakingRef))
            mockRetrieveEmail(eori2)(Right(validEmailAddress.some))
            mockRetrieveEmail(eori1)(Right(None))
          }
          assertThrows[Exception](await(performAction("cya" -> "true")(Language.English.code)))
        }

        "language is other than english /welsh" in {
          val businessEntity = BusinessEntity(eori2, false, contactDetails.some)
          inSequence{
            mockAuthWithNecessaryEnrolment()
            mockGet[Undertaking](eori1)(Right(undertaking.some))
            mockGet[BusinessEntityJourney](eori1)(Right(businessEntityJourney1.some))
            mockAddMember(undertakingRef, businessEntity)(Right(undertakingRef))
          }
          assertThrows[Exception](await(performAction("cya" -> "true")("fr")))
        }

      }

      "redirects to next page" when {

        def testRedirection(businessEntityJourney: BusinessEntityJourney, nextCall: String, resettedBusinessJourney: BusinessEntityJourney) = {
          val businessEntity = BusinessEntity(eori2, leadEORI = false, contactDetails.some)
          val emailParametersBE =  SingleEORIEmailParameter(eori2, undertaking.name, undertakingRef,  "Email to BE for being added as a member")
          val emailParametersLead =  DoubleEORIEmailParameter(eori1, eori2, undertaking.name, undertakingRef,  "Email to Lead  for adding a new member")
          inSequence{
            mockAuthWithNecessaryEnrolment()
            mockGet[Undertaking](eori1)(Right(undertaking.some))
            mockGet[BusinessEntityJourney](eori1)(Right(businessEntityJourney.some))
            mockAddMember(undertakingRef, businessEntity)(Right(undertakingRef))
            mockRetrieveEmail(eori2)(Right(validEmailAddress.some))
            mockRetrieveEmail(eori1)(Right(validEmailAddress.some))
            mockSendEmail(validEmailAddress, emailParametersBE, "template_add_be_EN")(Right(EmailSendResult.EmailSent))
            mockSendEmail(validEmailAddress, emailParametersLead, "template_add_lead_EN")(Right(EmailSendResult.EmailSent))
            mockPut[BusinessEntityJourney](resettedBusinessJourney, eori1)(Right(BusinessEntityJourney()))
          }
          checkIsRedirect(performAction("cya" -> "true")(English.code), nextCall)
        }

        def testRedirectionLang(lang: String, templateIdBE: String, templateIdLead: String) = {

          val emailParametersBE =  SingleEORIEmailParameter(eori2, undertaking.name, undertakingRef,  "Email to BE for being added as a member")
          val emailParametersLead =  DoubleEORIEmailParameter(eori1, eori2, undertaking.name, undertakingRef,  "Email to Lead  for adding a new member")

          val businessEntity = BusinessEntity(eori2, false, contactDetails.some)
          inSequence{
            mockAuthWithNecessaryEnrolment()
            mockGet[Undertaking](eori1)(Right(undertaking.some))
            mockGet[BusinessEntityJourney](eori1)(Right(businessEntityJourney1.some))
            mockAddMember(undertakingRef, businessEntity)(Right(undertakingRef))
            mockRetrieveEmail(eori2)(Right(validEmailAddress.some))
            mockRetrieveEmail(eori1)(Right(validEmailAddress.some))
            mockSendEmail(validEmailAddress, emailParametersBE, templateIdBE)(Right(EmailSendResult.EmailSent))
            mockSendEmail(validEmailAddress, emailParametersLead, templateIdLead)(Right(EmailSendResult.EmailSent))
            mockPut[BusinessEntityJourney](BusinessEntityJourney(), eori1)(Right(BusinessEntityJourney()))
          }
          checkIsRedirect(performAction("cya" -> "true")(lang), routes.BusinessEntityController.getAddBusinessEntity().url)
        }

        "all api calls are successful and English language is selected" in {
          testRedirectionLang(Language.English.code, "template_add_be_EN", "template_add_lead_EN")
        }

        "all api calls are successful and Welsh language is selected" in {
          testRedirectionLang(Language.Welsh.code, "template_add__be_CY", "template_add_lead_CY")
        }

        "all api calls are successful and is Select lead journey " in {
          testRedirection(businessEntityJourneyLead, routes.SelectNewLeadController.getSelectNewLead().url, BusinessEntityJourney(isLeadSelectJourney = true.some))
        }

        "all api calls are successful and is normal add business entity journey " in {
          testRedirection(businessEntityJourney1, routes.BusinessEntityController.getAddBusinessEntity().url, BusinessEntityJourney())
        }
      }

    }

    "handling request to edit business entity" must {

      "throw technical error" when {

        def performAction(eori: String) = controller.editBusinessEntity(eori)(FakeRequest())
        val exception = new Exception("oh no!")
        "call to retrieve undertaking fails" in {
          inSequence {
            mockAuthWithNecessaryEnrolment()
            mockRetreiveUndertaking(eori1)(Future.failed(exception))
          }
          assertThrows[Exception](await(performAction(eori1)))
        }

        "call to put business entity journey fails" in {

          val businessEntityJourney = BusinessEntityJourney.businessEntityJourneyForEori(undertaking1.some, eori1)
          inSequence {
            mockAuthWithNecessaryEnrolment()
            mockRetreiveUndertaking(eori1)(Future.successful(undertaking1.some))
            mockPut[BusinessEntityJourney](businessEntityJourney, eori1)(Left(Error(exception)))
          }
          assertThrows[Exception](await(performAction(eori1)))
        }

        "call to put business entity journey came back without contact details" in {

          val businessEntityJourney = BusinessEntityJourney.businessEntityJourneyForEori(undertaking1.some, eori1)
          inSequence {
            mockAuthWithNecessaryEnrolment()
            mockRetreiveUndertaking(eori1)(Future.successful(undertaking1.some))
            mockPut[BusinessEntityJourney](businessEntityJourney, eori1)(Right(businessEntityJourney))
          }
          assertThrows[Exception](await(performAction(eori1)))
        }

        "display the page" in {

          val be: BusinessEntity = undertaking1.undertakingBusinessEntity.filter(_.leadEORI).head.copy(contacts = contactDetails.some)
          val businessEntityJourney = BusinessEntityJourney.businessEntityJourneyForEori(undertaking1.copy(undertakingBusinessEntity = List(be)).some, eori1)
          inSequence {
            mockAuthWithNecessaryEnrolment()
            mockRetreiveUndertaking(eori1)(Future.successful(undertaking1.copy(undertakingBusinessEntity = List(be)).some))
            mockPut[BusinessEntityJourney](businessEntityJourney, eori1)(Right(businessEntityJourney))
          }
          checkPageIsDisplayed(
            performAction(eori1),
            messageFromMessageKey("businessEntity.cya.title"),
            {doc =>

              val rows =
                doc.select(".govuk-summary-list__row").iterator().asScala.toList.map { element =>
                  val question  = element.select(".govuk-summary-list__key").text()
                  val answer    = element.select(".govuk-summary-list__value").text()
                  val changeUrl = element.select(".govuk-link").attr("href")
                  CheckYourAnswersRowBE(question, answer, changeUrl)
                }
              rows shouldBe expectedRows
            }
          )


        }

      }



    }

    "handling request to get remove yourself Business entity" must {
      def performAction() = controller.getRemoveYourselfBusinessEntity(FakeRequest())

      "throw technical error" when {
        val exception = new Exception("oh no!")

        "call to retrieved undertaking fails" in {
          inSequence {
            mockAuthWithEnrolment(eori4)
            mockRetreiveUndertaking(eori4)(Future.failed(exception))
          }
          assertThrows[Exception](await(performAction()))
        }

        "call to retrieved undertaking came back with empty response" in {
          inSequence {
            mockAuthWithEnrolment(eori4)
            mockRetreiveUndertaking(eori4)(Future.successful(None))
          }
          assertThrows[Exception](await(performAction()))
        }

        "call to retrieved undertaking came back with undertaking having no BE with that eori" in {
          inSequence {
            mockAuthWithEnrolment(eori4)
            mockRetreiveUndertaking(eori4)(Future.successful(undertaking.some))
          }
          assertThrows[Exception](await(performAction()))
        }

      }

      "display the page" when {
        def test(undertaking: Undertaking, inputDate: Option[String]) = {
          inSequence {
            mockAuthWithEnrolment(eori4)
            mockRetreiveUndertaking(eori4)(Future.successful(undertaking.some))
          }
          checkPageIsDisplayed(
            performAction(),
            messageFromMessageKey("removeYourselfBusinessEntity.title", undertaking.name),
            { doc =>
              doc.select(".govuk-back-link").attr("href") shouldBe(routes.AccountController.getExistingUndertaking().url)

              val selectedOptions = doc.select(".govuk-radios__input[checked]")
              inputDate match {
                case Some(value) => selectedOptions.attr("value") shouldBe value
                case None => selectedOptions.isEmpty       shouldBe true
              }
              val button = doc.select("form")
              button.attr("action") shouldBe routes.BusinessEntityController.postRemoveYourselfBusinessEntity().url
            }
          )

        }

        "the user hasn't previously answered the question" in {
          test(undertaking1, None)
        }

      }


    }

    "handling request to post remove yourself business entity" must {

      def performAction(data: (String, String)*)(lang: String) = controller
        .postRemoveYourselfBusinessEntity(
          FakeRequest("POST",routes.BusinessEntityController.getRemoveYourselfBusinessEntity().url)
            .withCookies(Cookie("PLAY_LANG", lang))
            .withFormUrlEncodedBody(data: _*))

      "throw a technical error" when {
        val exception = new Exception("oh no!")

        "call to retrieved undertaking fails" in {
          inSequence {
            mockAuthWithEnrolment(eori4)
            mockRetreiveUndertaking(eori4)(Future.failed(exception))
          }
          assertThrows[Exception](await(performAction()(English.code)))
        }

        "call to retrieved undertaking came back with empty response" in {
          inSequence {
            mockAuthWithEnrolment(eori4)
            mockRetreiveUndertaking(eori4)(Future.successful(None))
          }
          assertThrows[Exception](await(performAction()(English.code)))
        }

        "call to retrieved undertaking came back with undertaking having no BE with that eori" in {
          inSequence {
            mockAuthWithEnrolment(eori4)
            mockRetreiveUndertaking(eori4)(Future.successful(undertaking.some))
          }
          assertThrows[Exception](await(performAction()(English.code)))
        }

        "call to remove BE fails" in {
          inSequence {
            mockAuthWithEnrolment(eori4)
            mockRetreiveUndertaking(eori4)(Future.successful(undertaking1.some))
            mockTimeToday(currentDate)
            mockRemoveMember(undertakingRef, businessEntity4)(Left(Error(exception)))
          }
          assertThrows[Exception](await(performAction("removeYourselfBusinessEntity" -> "true")(English.code)))
        }

        "call to retrieve email address of the EORI, to be removed, fails" in {
          inSequence {
            mockAuthWithEnrolment(eori4)
            mockRetreiveUndertaking(eori4)(Future.successful(undertaking1.some))
            mockTimeToday(currentDate)
            mockRemoveMember(undertakingRef, businessEntity4)(Right(undertakingRef))
            mockRetrieveEmail(eori4)(Left(Error(exception)))
          }
          assertThrows[Exception](await(performAction("removeYourselfBusinessEntity" -> "true")(English.code)))
        }

        "call to retrieve email address of the lead EORI, to be removed, fails" in {
          inSequence {
            mockAuthWithEnrolment(eori4)
            mockRetreiveUndertaking(eori4)(Future.successful(undertaking1.some))
            mockTimeToday(currentDate)
            mockRemoveMember(undertakingRef, businessEntity4)(Right(undertakingRef))
            mockRetrieveEmail(eori4)(Right(validEmailAddress.some))
            mockRetrieveEmail(eori1)(Left(Error(exception)))
          }
          assertThrows[Exception](await(performAction("removeYourselfBusinessEntity" -> "true")(English.code)))
        }

        "language is other than english /welsh" in {
          inSequence {
            mockAuthWithEnrolment(eori4)
            mockRetreiveUndertaking(eori4)(Future.successful(undertaking1.some))
            mockTimeToday(currentDate)
            mockRemoveMember(undertakingRef, businessEntity4)(Right(undertakingRef))
            mockRetrieveEmail(eori4)(Right(validEmailAddress.some))
            mockRetrieveEmail(eori1)(Right(validEmailAddress.some))
          }
          assertThrows[Exception](await(performAction("removeYourselfBusinessEntity" -> "true")("fr")))
        }


      }

      "display the form error" when {

        "nothing is selected" in {
          inSequence {
            mockAuthWithEnrolment(eori4)
            mockRetreiveUndertaking(eori4)(Future.successful(undertaking1.some))
          }
          checkFormErrorIsDisplayed(
            performAction()(English.code),
            messageFromMessageKey("removeYourselfBusinessEntity.title", undertaking1.name),
            messageFromMessageKey("removeYourselfBusinessEntity.error.required", undertaking1.name)
          )

        }

      }

      "redirect to next page" when {

        "user select yes as input" when {

         def  testRedirection(lang: String, templateIdBe: String, templateIdLead: String, effectiveRemovalDate: String)= {

           val emailParamBE = SingleEORIAndDateEmailParameter(eori4, undertaking.name, undertakingRef, effectiveRemovalDate, "Email to BE for removing themself from undertaking" )
           val emailParamLead = DoubleEORIAndDateEmailParameter(eori1, eori4, undertaking.name, undertakingRef, effectiveRemovalDate, "Email to Lead  informing that a Business Entity has removed itself from Undertaking" )
           inSequence {
             mockAuthWithEnrolment(eori4)
             mockRetreiveUndertaking(eori4)(Future.successful(undertaking1.some))
             mockTimeToday(currentDate)
             mockRemoveMember(undertakingRef, businessEntity4)(Right(undertakingRef))
             mockRetrieveEmail(eori4)(Right(validEmailAddress.some))
             mockRetrieveEmail(eori1)(Right(validEmailAddress.some))
             mockSendEmail(validEmailAddress, emailParamBE, templateIdBe)(Right(EmailSendResult.EmailSent))
             mockSendEmail(validEmailAddress, emailParamLead, templateIdLead)(Right(EmailSendResult.EmailSent))
           }
           checkIsRedirect(performAction("removeYourselfBusinessEntity" -> "true")(lang), routes.SignOutController.signOut().url)

         }
          "the language of the applicaton is English" in {
            testRedirection(English.code, "template_remove_yourself_be_EN", "template_remove_yourself_lead_EN", "9 October 2022")
          }

          "the language of the applicaton is Welsh" in {
            testRedirection(Welsh.code, "template_remove_yourself_be_CY", "template_remove_yourself_lead_CY", "9 Hydref 2022")
          }
        }


        "user selects No as input" in {
          inSequence {
            mockAuthWithEnrolment(eori4)
            mockRetreiveUndertaking(eori4)(Future.successful(undertaking1.some))
          }
          checkIsRedirect(performAction("removeYourselfBusinessEntity" -> "false")(English.code), routes.AccountController.getAccountPage().url)
        }
      }

    }

    "handling request to get remove Business entity by Lead" must {
      def performAction() = controller.getRemoveBusinessEntity(eori4)(FakeRequest())

      "throw technical error" when {
        val exception = new Exception("oh no!")

        "call to retrieved undertaking fails" in {
          inSequence {
            mockAuthWithEnrolment(eori4)
            mockRetreiveUndertaking(eori4)(Future.failed(exception))
          }
          assertThrows[Exception](await(performAction()))
        }

        "call to retrieved undertaking came back with empty response" in {
          inSequence {
            mockAuthWithEnrolment(eori4)
            mockRetreiveUndertaking(eori4)(Future.successful(None))
          }
          assertThrows[Exception](await(performAction()))
        }

        "call to retrieved undertaking came back with undertaking having no BE with that eori" in {
          inSequence {
            mockAuthWithEnrolment(eori4)
            mockRetreiveUndertaking(eori4)(Future.successful(undertaking.some))
          }
          assertThrows[Exception](await(performAction()))
        }

      }

      "display the page" when {
        def test(undertaking: Undertaking, inputDate: Option[String]) = {
          inSequence {
            mockAuthWithEnrolment(eori4)
            mockRetreiveUndertaking(eori4)(Future.successful(undertaking.some))
          }
          checkPageIsDisplayed(
            performAction(),
            messageFromMessageKey("removeBusinessEntity.title"),
            { doc =>

              val selectedOptions = doc.select(".govuk-radios__input[checked]")
              inputDate match {
                case Some(value) => selectedOptions.attr("value") shouldBe value
                case None => selectedOptions.isEmpty       shouldBe true
              }
              val button = doc.select("form")
              button.attr("action") shouldBe routes.BusinessEntityController.postRemoveBusinessEntity(eori4).url

            }
          )

        }

        "the user hasn't previously answered the question" in {
          test(undertaking1, None)
        }

      }

    }

    "handling request to post remove  business entity" must {

      def performAction(data: (String, String)*)(eori: EORI, language: String = English.code) = controller
        .postRemoveBusinessEntity(eori)(
          FakeRequest("POST",routes.BusinessEntityController.postRemoveBusinessEntity(eori4).url)
            .withCookies(Cookie("PLAY_LANG", language))
            .withFormUrlEncodedBody(data: _*))

      val effectiveDate = LocalDate.of(2022, 10, 9)

      "throw a technical error" when {
        val exception = new Exception("oh no!")

        "call to retrieved undertaking fails" in {
          inSequence {
            mockAuthWithEnrolment(eori1)
            mockRetreiveUndertaking(eori4)(Future.failed(exception))
          }
          assertThrows[Exception](await(performAction()(eori4)))
        }

        "call to retrieved undertaking came back with empty response" in {
          inSequence {
            mockAuthWithEnrolment(eori1)
            mockRetreiveUndertaking(eori4)(Future.successful(None))
          }
          assertThrows[Exception](await(performAction()(eori4)))
        }

        "call to retrieved undertaking came back with undertaking having no BE with that eori" in {
          inSequence {
            mockAuthWithEnrolment(eori1)
            mockRetreiveUndertaking(eori4)(Future.successful(undertaking.some))
          }
          assertThrows[Exception](await(performAction()(eori4)))
        }

        "call to remove BE fails" in {
          inSequence {
            mockAuthWithEnrolment(eori1)
            mockRetreiveUndertaking(eori4)(Future.successful(undertaking1.some))
            mockTimeToday(effectiveDate)
            mockRemoveMember(undertakingRef, businessEntity4)(Left(Error(exception)))
          }
          assertThrows[Exception](await(performAction("removeBusiness" -> "true")(eori4)))
        }

        "call to fetch business entity email address fails" in {
          inSequence {
            mockAuthWithEnrolment(eori1)
            mockRetreiveUndertaking(eori4)(Future.successful(undertaking1.some))
            mockTimeToday(effectiveDate)
            mockRemoveMember(undertakingRef, businessEntity4)(Right(undertakingRef))
            mockRetrieveEmail(eori4)(Left(Error(exception)))
          }
          assertThrows[Exception](await(performAction("removeBusiness" -> "true")(eori4)))
        }

        "call to fetch LeadEORI email address fails" in {
          inSequence {
            mockAuthWithEnrolment(eori1)
            mockRetreiveUndertaking(eori4)(Future.successful(undertaking1.some))
            mockTimeToday(effectiveDate)
            mockRemoveMember(undertakingRef, businessEntity4)(Right(undertakingRef))
            mockRetrieveEmail(eori4)(Right(validEmailAddress.some))
            mockRetrieveEmail(eori1)(Left(Error(exception)))
          }
          assertThrows[Exception](await(performAction("removeBusiness" -> "true")(eori4)))
        }

      }

      "display the form error" when {

        "nothing is selected" in {
          inSequence {
            mockAuthWithEnrolment(eori1)
            mockRetreiveUndertaking(eori4)(Future.successful(undertaking1.some))
          }
          checkFormErrorIsDisplayed(
            performAction()(eori4),
            messageFromMessageKey("removeBusinessEntity.title"),
            messageFromMessageKey("removeBusinessEntity.error.required")
          )

        }

      }

      "redirect to next page" when {

        def testRedirection(emailParametersBE: EmailParameters, emailParametersLead: EmailParameters, templateIdBE: String, templateIdLead: String, lang: String) = {
          inSequence {
            mockAuthWithEnrolment(eori1)
            mockRetreiveUndertaking(eori4)(Future.successful(undertaking1.some))
            mockTimeToday(effectiveDate)
            mockRemoveMember(undertakingRef, businessEntity4)(Right(undertakingRef))
            mockRetrieveEmail(eori4)(Right(validEmailAddress.some))
            mockRetrieveEmail(eori1)(Right(validEmailAddress.some))
            mockSendEmail(validEmailAddress, emailParametersBE, templateIdBE)(Right(EmailSendResult.EmailSent))
            mockSendEmail(validEmailAddress, emailParametersLead, templateIdLead)(Right(EmailSendResult.EmailSent))
          }
          checkIsRedirect(performAction("removeBusiness" -> "true")(eori4, lang), routes.BusinessEntityController.getAddBusinessEntity().url)
        }

        "user select yes as input" when {

          "User has selected English language" in {

            val emailParameterBE = SingleEORIAndDateEmailParameter(
              eori4,
              undertaking.name,
              undertakingRef,
              "9 October 2022",  "Email to BE for being removed as a member")
            val emailParameterLead = DoubleEORIAndDateEmailParameter(
              eori1,
              eori4,
              undertaking.name,
              undertakingRef,
              "9 October 2022",  "Email to Lead  for removing a new member")
            testRedirection(emailParameterBE, emailParameterLead, "template_remove_be_EN", "template_remove_lead_EN", English.code)

          }

          "User has selected Welsh language" in {

            val emailParameterBE = SingleEORIAndDateEmailParameter(
              eori4,
              undertaking.name,
              undertakingRef,
              "9 Hydref 2022",  "Email to BE for being removed as a member")
            val emailParameterLead = DoubleEORIAndDateEmailParameter(
              eori1,
              eori4,
              undertaking.name,
              undertakingRef,
              "9 Hydref 2022",  "Email to Lead  for removing a new member")
            testRedirection(emailParameterBE, emailParameterLead, "template_remove_be_CY", "template_remove_lead_CY", Welsh.code)

          }

        }

        "user selects No as input" in {
          inSequence {
            mockAuthWithEnrolment(eori1)
            mockRetreiveUndertaking(eori4)(Future.successful(undertaking1.some))
          }
          checkIsRedirect(performAction("removeBusiness" -> "false")(eori4), routes.BusinessEntityController.getAddBusinessEntity().url)
        }
      }

    }

  }

}

object BusinessEntityControllerSpec {
  final case class CheckYourAnswersRowBE(question: String, answer: String, changeUrl: String)
}
