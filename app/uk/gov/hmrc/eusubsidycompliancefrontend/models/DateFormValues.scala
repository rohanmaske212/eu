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

package uk.gov.hmrc.eusubsidycompliancefrontend.models

import play.api.data.Forms.{text, tuple}
import play.api.data.Mapping
import play.api.libs.json.{Json, OFormat}

import java.time.format.DateTimeFormatter
import java.time.{LocalDate, ZoneId}
import scala.util.Try

case class DateFormValues(day: String, month: String, year: String) {
  def isValidDate: Boolean = try {
    val dateText = s"${"%02d".format(day.toInt)}/${"%02d".format(month.toInt)}/$year"
    LocalDate.parse(dateText, DateTimeFormatter.ofPattern("dd/MM/yyyy"))
    true
  }
  catch {
    case _: Exception => false
  }

  def toFormat: String = {
    day + "/" + month + "/" + year
  }

}

case object DateFormValues {

  lazy val dateValueMapping: Mapping[DateFormValues] = tuple(
    "day"   -> text,
    "month" -> text,
    "year"  -> text
  ).transform(
    { case (d, m, y) => (d.trim, m.trim, y.trim) },
    { v: (String, String, String) => v }
  ).verifying(
    "error.date.emptyfields",
    x =>
      x match {
        case ("", "", "") => false
        case _ => true
      })
    .verifying(
      "error.date.invalidentry",
      x =>
        x match {
          case x: (String, String, String) => valuesAreInt(x)
          case _ => false
        }
    )
    .verifying(
      "error.day.missing",
      x =>
        x match {
          case ("", _, _) => false
          case _ => true
        })
    .verifying(
      "error.month.missing",
      x =>
        x match {
          case (_, "", _) => false
          case _ => true
        })
    .verifying(
      "error.year.missing",
      x =>
        x match {
          case (_, _, "") => false
          case _ => true
        })
    .verifying(
      "error.day-and-month.missing",
      x =>
        x match {
          case ("", "", _) => false
          case _ => true
        })
    .verifying(
      "error.month-and-year.missing",
      x =>
        x match {
          case (_, "", "") => false
          case _ => true
        })
    .verifying(
      "error.day-and-year.missing",
      x =>
        x match {
          case ("", _, "") => false
          case _ => true
        })
    .verifying(
      "error.date.invalid",
      x =>
        x match {
          case (d: String, m: String, y: String)  => Try(LocalDate.of(y.toInt, m.toInt, d.toInt)).isSuccess
          case _ => true
        }
    )
    .verifying(
      "error.date.in-future",
      x =>
        x match {
          case (d: String, m: String, y: String) =>
              Try(LocalDate.of(y.toInt, m.toInt, d.toInt).isBefore(LocalDate.now(ZoneId.of("Europe/London"))))
                .getOrElse(true)
          case _ => true
        }
    )
    .transform(
      { case (d, m, y) => DateFormValues(d,m,y) },
      d => (d.day, d.month, d.year)
    )

  implicit val format: OFormat[DateFormValues] = Json.format[DateFormValues]

  // TODO - revisit this
  //  o clean up
  //  o do we need to pass a tuple?
  private def valuesAreInt(formInput: (String, String, String)): Boolean =
    formInput match  {
      case (d: String, m: String, y: String) =>
        val res = (d.forall(char => Character.isDigit(char)) || d == "") &&
          (m.forall(char => Character.isDigit(char)) || m == "")  &&
          (y.forall(char => Character.isDigit(char)) || y == "")
        res
      case _ => false
    }
}
