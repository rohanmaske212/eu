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
import cats.implicits._
import play.api.data.Form
import play.api.data.Forms.{bigDecimal, mapping, optional, text}
import play.api.mvc.{Action, AnyContent, MessagesControllerComponents, Request}
import uk.gov.hmrc.eusubsidycompliancefrontend.actions.EscActionBuilders
import uk.gov.hmrc.eusubsidycompliancefrontend.config.AppConfig
import uk.gov.hmrc.eusubsidycompliancefrontend.forms.ClaimDateFormProvider
import uk.gov.hmrc.eusubsidycompliancefrontend.models._
import uk.gov.hmrc.eusubsidycompliancefrontend.models.types.{EORI, TraderRef, UndertakingRef}
import uk.gov.hmrc.eusubsidycompliancefrontend.services._
import uk.gov.hmrc.eusubsidycompliancefrontend.util.FutureSyntax.FutureOps
import uk.gov.hmrc.eusubsidycompliancefrontend.util.TaxYearSyntax._
import uk.gov.hmrc.eusubsidycompliancefrontend.util.TimeProvider
import uk.gov.hmrc.eusubsidycompliancefrontend.views.html._
import uk.gov.hmrc.http.HeaderCarrier

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SubsidyController @Inject()(
  mcc: MessagesControllerComponents,
  escActionBuilders: EscActionBuilders,
  store: Store,
  escService: EscService,
  journeyTraverseService: JourneyTraverseService,
  reportPaymentPage: ReportPaymentPage,
  addClaimEoriPage: AddClaimEoriPage,
  addClaimAmountPage: AddClaimAmountPage,
  addClaimDatePage: AddClaimDatePage,
  addPublicAuthorityPage: AddPublicAuthorityPage,
  addTraderReferencePage: AddTraderReferencePage,
  cyaPage: ClaimCheckYourAnswerPage,
  confirmRemovePage: ConfirmRemoveClaim,
  claimDateFormProvider: ClaimDateFormProvider,
  timeProvider: TimeProvider
)(
  implicit val appConfig: AppConfig,
  executionContext: ExecutionContext
) extends
  BaseController(mcc) {

  import escActionBuilders._

  def getReportPayment: Action[AnyContent] = escAuthentication.async { implicit request =>
    implicit val eori: EORI = request.eoriNumber

    val result: OptionT[Future, (UndertakingRef, Undertaking)] = for {
      _           <- OptionT(store.get[SubsidyJourney]).orElseF(store.put(SubsidyJourney().some))
      undertaking <- OptionT(store.get[Undertaking])
      reference   <- OptionT.fromOption[Future](undertaking.reference)
    } yield (reference, undertaking)

    result.foldF(handleMissingSessionData("Subsidy journey")) {
      case (reference, undertaking) =>
        retrieveSubsidiesOrNone(reference).map { subsidies =>
          Ok(reportPaymentPage(
            subsidies,
            undertaking,
            timeProvider.today.toEarliestTaxYearStart,
            timeProvider.today.toTaxYearEnd.minusYears(1),
            timeProvider.today.toTaxYearStart,
          ))
        }
    }
  }

  private def retrieveSubsidiesOrNone(r: UndertakingRef)(implicit hc: HeaderCarrier) =
    escService
      .retrieveSubsidy(SubsidyRetrieve(r, None))
      .map(Option(_))
      .fallbackTo(Option.empty.toFuture)

  def postReportPayment: Action[AnyContent] = escAuthentication.async { implicit request =>
    implicit val eori: EORI = request.eoriNumber

    reportPaymentForm.bindFromRequest().fold(throw new IllegalStateException("value hard-coded, form hacking?"),
      (form: FormValues) => for {
        journey <- store.update[SubsidyJourney](updateReportPayment(form))
        redirect <- getJourneyNext(journey)
      } yield redirect
    )
  }

  private def updateReportPayment(f: FormValues)(os: Option[SubsidyJourney]) =
    os.map { subsidyJourney =>
      subsidyJourney.copy(
        reportPayment = subsidyJourney.reportPayment.copy(value = Some(f.value.toBoolean))
      )
    }

  def getClaimAmount: Action[AnyContent] = escAuthentication.async { implicit request =>
    implicit val eori: EORI = request.eoriNumber
    // TODO - add 'getPrevious to all'
    store.get[SubsidyJourney].flatMap {
      case Some(journey) => journeyTraverseService.getPrevious[SubsidyJourney].flatMap { p =>
        val form = journey.claimAmount.value.fold(claimAmountForm)(claimAmountForm.fill)
        Ok(addClaimAmountPage(form, p)).toFuture
      }
      case _ => handleMissingSessionData("Subsidy journey")
    }
  }

  def postAddClaimAmount: Action[AnyContent] = escAuthentication.async { implicit request =>
    implicit val eori: EORI = request.eoriNumber
    journeyTraverseService.getPrevious[SubsidyJourney].flatMap { previous =>
      claimAmountForm.bindFromRequest().fold(
        formWithErrors => Future.successful(BadRequest(addClaimAmountPage(formWithErrors, previous))),
        form => for {
          journey <- store.update[SubsidyJourney](updateClaimAmount(form))
          redirect <- getJourneyNext(journey)
        } yield redirect
      )
    }
  }

  private def updateClaimAmount(b: BigDecimal)(os: Option[SubsidyJourney]) =
    os.map { subsidyJourney =>
      subsidyJourney.copy(
        claimAmount = subsidyJourney.claimAmount.copy(value = Some(b))
      )
    }

  def getClaimDate: Action[AnyContent] = escAuthentication.async { implicit request =>
    implicit val eori: EORI = request.eoriNumber

    store.get[SubsidyJourney].flatMap {
        case Some(journey) => journeyTraverseService.getPrevious[SubsidyJourney].flatMap { previous =>
          val form = journey.claimDate.value.fold(claimDateForm)(claimDateForm.fill)
          Ok(addClaimDatePage(form, previous)).toFuture
        }
      case _ => handleMissingSessionData("Subsidy journey")
    }
  }

  def postClaimDate: Action[AnyContent] = escAuthentication.async { implicit request =>
    implicit val eori: EORI = request.eoriNumber

    getPrevious[SubsidyJourney](store).flatMap { previous =>
      claimDateForm.bindFromRequest().fold(
        formWithErrors => BadRequest(addClaimDatePage(formWithErrors, previous)).toFuture,
        form => for {
          journey <- store.update[SubsidyJourney](updateClaimDate(form))
          redirect <- getJourneyNext(journey)
        } yield redirect)
      }
    }

  private def updateClaimDate(d: DateFormValues)(os: Option[SubsidyJourney]): Option[SubsidyJourney] =
    os.map { subsidyJourney =>
      subsidyJourney.copy(
        claimDate = subsidyJourney.claimDate.copy(value = Some(d))
      )
    }

  def getAddClaimEori: Action[AnyContent] = escAuthentication.async { implicit request =>
    implicit val eori: EORI = request.eoriNumber

    store.get[SubsidyJourney].flatMap {
      case Some(journey) =>
        journeyTraverseService.getPrevious[SubsidyJourney].flatMap { previous =>
          val form = journey.addClaimEori.value.fold(claimEoriForm) { optionalEORI =>
            claimEoriForm.fill(OptionalEORI(optionalEORI.setValue, optionalEORI.value))
          }
          Ok(addClaimEoriPage(form, previous)).toFuture
        }
      case _ => handleMissingSessionData("Subsidy journey")
    }
  }

  def postAddClaimEori: Action[AnyContent] = escAuthentication.async { implicit request =>
    implicit val eori: EORI = request.eoriNumber

    journeyTraverseService.getPrevious[SubsidyJourney].flatMap { previous =>
      claimEoriForm.bindFromRequest().fold(
        formWithErrors => Future.successful(BadRequest(addClaimEoriPage(formWithErrors, previous))),
        (form: OptionalEORI) => {
          for {
            journey <- store.update[SubsidyJourney](updateClaimEori(form))
            redirect <- getJourneyNext(journey)
          } yield redirect
        }
      )
    }
  }

  private def updateClaimEori(oe: OptionalEORI)(os: Option[SubsidyJourney]) =
    os.map { subsidyJourney =>
      subsidyJourney.copy(
        addClaimEori = FormPage(SubsidyJourney.FormUrls.AddClaimEori, oe.some)
      )
    }

  def getAddClaimPublicAuthority: Action[AnyContent] = escAuthentication.async { implicit request =>
    implicit val eori: EORI = request.eoriNumber

    store.get[SubsidyJourney].flatMap {
      case Some(journey) => journeyTraverseService.getPrevious[SubsidyJourney].flatMap { previous =>
        val form = journey.publicAuthority.value.fold(claimPublicAuthorityForm)(claimPublicAuthorityForm.fill)
        Ok(addPublicAuthorityPage(form, previous)).toFuture
      }
      case _ => handleMissingSessionData("Subsidy journey")
    }
  }

  def postAddClaimPublicAuthority: Action[AnyContent] = escAuthentication.async { implicit request =>
    implicit val eori: EORI = request.eoriNumber
    getPrevious[SubsidyJourney](store).flatMap { previous =>
      claimPublicAuthorityForm.bindFromRequest().fold(
        errors => Future.successful(BadRequest(addPublicAuthorityPage(errors, previous))),
        form => {
          for {
            journey <- store.update[SubsidyJourney]({ x =>
                  x.map { y =>
                y.copy(publicAuthority = y.publicAuthority.copy(value = Some(form)))
              }
              })
            redirect <- getJourneyNext(journey)
          } yield redirect
        }
      )
    }
  }

  def getAddClaimReference: Action[AnyContent] = escAuthentication.async { implicit request =>

    implicit val eori: EORI = request.eoriNumber
    store.get[SubsidyJourney].flatMap {
      case Some(journey) =>
        val form = journey.traderRef.value.fold(claimTraderRefForm
        )(optionalTraderRef => claimTraderRefForm.fill(OptionalTraderRef(optionalTraderRef.setValue, optionalTraderRef.value)))
        Future.successful(Ok(addTraderReferencePage(form, journey.previous)))
      case _ => handleMissingSessionData("Subsidy journey")
    }
  }

  def postAddClaimReference: Action[AnyContent] = escAuthentication.async { implicit request =>

    implicit val eori: EORI = request.eoriNumber
    journeyTraverseService.getPrevious[SubsidyJourney].flatMap { previous =>
      claimTraderRefForm.bindFromRequest().fold(
        errors => Future.successful(BadRequest(addTraderReferencePage(errors, previous))),
        form => {
          for {
            updatedSubsidyJourney <- store.update[SubsidyJourney]{ _.map { subsidyJourney =>
              val updatedTraderRef = subsidyJourney.traderRef.copy(value = OptionalTraderRef(form.setValue, form.value).some)
              subsidyJourney.copy(traderRef = updatedTraderRef)
              }
            }
            redirect <- getJourneyNext(updatedSubsidyJourney)
          } yield redirect
        }
      )
    }
  }

  def getCheckAnswers: Action[AnyContent] = escAuthentication.async { implicit request =>
    implicit val eori: EORI = request.eoriNumber
    store.get[SubsidyJourney].flatMap {
      case Some(journey) => {
        Future.successful(
          Ok(
            cyaPage(
              journey.claimDate.value.getOrElse(throw new IllegalStateException("Claim date should be defined")),
              journey.claimAmount.value.getOrElse(throw new IllegalStateException("Claim amount payment should be defined")),
              journey.addClaimEori.value.fold(handleMissingSessionData("Claim EORI payment"))(_.value.map(EORI(_))),
              journey.publicAuthority.value.getOrElse(throw new IllegalStateException("Public Authority payment should be defined")),
              journey.traderRef.value.fold(handleMissingSessionData("Trader Ref"))(_.value.map(TraderRef(_))),
              journey.previous
            )
          )
        )
      }
      case _ => handleMissingSessionData("Subsidy journey")
    }
  }
  def postCheckAnswers: Action[AnyContent] = escAuthentication.async { implicit request =>
    implicit val eori: EORI = request.eoriNumber
    cyaForm.bindFromRequest().fold(
      _ => throw new IllegalStateException("value hard-coded, form hacking?"),
      form => {
        store.update[SubsidyJourney]({ x =>
          x.map { y =>
            y.copy(cya = y.cya.copy(value = Some(form.value.toBoolean)))
          }
        })
          .flatMap { journey: SubsidyJourney =>
            journey.publicAuthority.value.getOrElse(handleMissingSessionData("publicAuthority"))
            journey.traderRef.value.getOrElse(handleMissingSessionData("trader ref"))
            journey.claimAmount.value.getOrElse(handleMissingSessionData("claimAmount"))
            journey.addClaimEori.value.getOrElse(handleMissingSessionData("addClaimEori"))
            for {
              underTaking <- store.get[Undertaking]
              ref = underTaking.getOrElse(throw new IllegalStateException("")).reference.getOrElse(throw new IllegalStateException(""))
              _ <- escService.createSubsidy(ref, journey)
              _ <- store.put(SubsidyJourney())
            } yield {
              Redirect(routes.SubsidyController.getReportPayment())
            }
          }
      }
    )
  }

  def getRemoveSubsidyClaim(transactionId: String): Action[AnyContent] = escAuthentication.async { implicit request =>
    implicit val eori: EORI = request.eoriNumber
    for {
      undertaking <- store.get[Undertaking]
      reference = undertaking.getOrElse(throw new IllegalStateException("")).reference.getOrElse(throw new IllegalStateException(""))
      subsidies <- escService.retrieveSubsidy(SubsidyRetrieve(reference, None)).map(e => Some(e)).recoverWith({case _ => Future.successful(Option.empty[UndertakingSubsidies])})
      sub = subsidies.get.nonHMRCSubsidyUsage.find(_.subsidyUsageTransactionID.contains(transactionId)).get
    } yield {
      Ok(confirmRemovePage(removeSubsidyClaimForm, sub))
    }
  }

  def postRemoveSubsidyClaim(transactionId: String): Action[AnyContent] = escAuthentication.async { implicit request =>
    implicit val eori: EORI = request.eoriNumber
    removeSubsidyClaimForm.bindFromRequest().fold(formWithErrors =>
      for {
        undertaking <- store.get[Undertaking]
        reference = undertaking.getOrElse(throw new IllegalStateException("")).reference.getOrElse(throw new IllegalStateException(""))
        subsidies <- escService.retrieveSubsidy(SubsidyRetrieve(reference, None)).map(e => Some(e)).recoverWith({case _ => Future.successful(Option.empty[UndertakingSubsidies])})
        sub = subsidies.get.nonHMRCSubsidyUsage.find(_.subsidyUsageTransactionID.contains(transactionId)).get
      } yield {
        BadRequest(confirmRemovePage(formWithErrors, sub))
      }, formValue => {
      if(formValue.value == "true")
        for {
          undertaking <- store.get[Undertaking]
          reference = undertaking.getOrElse(throw new IllegalStateException("")).reference.getOrElse(throw new IllegalStateException(""))
          subsidies <- escService.retrieveSubsidy(SubsidyRetrieve(reference, None)).map(e => Some(e)).recoverWith({ case _ => Future.successful(Option.empty[UndertakingSubsidies]) })
          sub = subsidies.get.nonHMRCSubsidyUsage.find(_.subsidyUsageTransactionID.contains(transactionId)).get
          _ <- escService.removeSubsidy(reference, sub)
        } yield {
          Redirect(routes.SubsidyController.getReportPayment())
        }
      else {
        Future(Redirect(routes.SubsidyController.getReportPayment()))
      }
    }
    )
  }

  def getChangeSubsidyClaim(transactionId: String): Action[AnyContent] = escAuthentication.async { implicit request =>
    implicit val eori: EORI = request.eoriNumber
    for {
      undertaking <- store.get[Undertaking]
      reference = undertaking.flatMap(_.reference).getOrElse(handleMissingSessionData("Reference"))
      subsidies <- escService.retrieveSubsidy(SubsidyRetrieve(reference, None)).map(e => Some(e)).recoverWith({case _ => Future.successful(Option.empty[UndertakingSubsidies])})
      sub = subsidies.get.nonHMRCSubsidyUsage.find(_.subsidyUsageTransactionID.contains(transactionId)).get
      _ = store.put(
        SubsidyJourney.fromNonHmrcSubsidy(sub)
      )
    } yield {
      Redirect(routes.SubsidyController.getCheckAnswers())
    }
  }

  private def getJourneyNext(journey: SubsidyJourney)(implicit request: Request[_]) =
    if(journey.isAmend) Future.successful(Redirect(routes.SubsidyController.getCheckAnswers()))
    else journey.next

  lazy val reportPaymentForm: Form[FormValues] = Form(
    mapping("reportPayment" -> mandatory("reportPayment"))(FormValues.apply)(FormValues.unapply))

  // TODO validate the EORI matches regex
  val claimEoriForm: Form[OptionalEORI] = Form(
    mapping(
      "should-claim-eori" -> mandatory("should-claim-eori"),
      "claim-eori" -> optional(text)
    )((radioSelected, eori) => claimEoriFormApply(radioSelected, eori)
    )(optionalEORI => Some((optionalEORI.setValue, optionalEORI.value.fold(Option.empty[String])(e => Some(e.drop(2))))))
      .transform[OptionalEORI](
        optionalEORI => if (optionalEORI.setValue == "false") optionalEORI.copy(value = None) else optionalEORI,
        identity
    ).verifying(
      "error.format", a => a.setValue == "false" || a.value.fold(false)(entered => s"GB${entered.drop(2)}".matches(EORI.regex))
    )
  )

  def claimEoriFormApply(input: String, eoriOpt: Option[String]) =
    (input, eoriOpt) match {
      case (radioSelected, Some(eori)) => OptionalEORI(radioSelected, Some(s"GB$eori"))
      case (radioSelected, other) => OptionalEORI(radioSelected, other)
    }

  val claimTraderRefForm: Form[OptionalTraderRef] = Form(
    mapping(
      "should-store-trader-ref" -> mandatory("should-store-trader-ref"),
      "claim-trader-ref" -> optional(text)
    )(OptionalTraderRef.apply)(OptionalTraderRef.unapply)
    .transform[OptionalTraderRef](
      optionalTraderRef => if (optionalTraderRef.setValue == "false") optionalTraderRef.copy(value = None) else optionalTraderRef,
      identity
    ).verifying(
      "error.isempty", optionalTraderRef => optionalTraderRef.setValue == "false" || optionalTraderRef.value.nonEmpty
    )
  )

  lazy val claimPublicAuthorityForm: Form[String] = Form(
    "claim-public-authority" -> mandatory("claim-public-authority")
  )

  lazy val claimAmountForm : Form[BigDecimal] = Form(
    mapping("claim-amount" -> bigDecimal
      .verifying("error.amount.incorrectFormat", e => e.scale == 2 || e.scale == 0)
      .verifying("error.amount.tooBig", e => e.toString().length < 17)
      .verifying("error.amount.tooSmall", e => e > 0.01)
  )
    (identity)(Some(_)))

  private val claimDateForm = claimDateFormProvider.form

  lazy val removeSubsidyClaimForm: Form[FormValues] = Form(
    mapping("removeSubsidyClaim" -> mandatory("removeSubsidyClaim"))(FormValues.apply)(FormValues.unapply))

  lazy val cyaForm: Form[FormValues] = Form(
    mapping("cya" -> mandatory("cya"))(FormValues.apply)(FormValues.unapply))

}
