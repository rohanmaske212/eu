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

package uk.gov.hmrc.eusubsidycompliancefrontend.persistence

import com.google.inject.ImplementedBy
import play.api.libs.json.{Format, Reads, Writes}
import uk.gov.hmrc.eusubsidycompliancefrontend.models.types.EORI

import scala.concurrent.Future
import scala.reflect.ClassTag

@ImplementedBy(classOf[JourneyStore])
trait Store {

  def get[A : ClassTag](implicit eori: EORI, reads: Reads[A]): Future[Option[A]]

  def getOrCreate[A : ClassTag](default: A)(implicit eori: EORI, format: Format[A]): Future[A]

  def put[A](in: A)(implicit eori: EORI, writes: Writes[A]): Future[A]

  def update[A : ClassTag](f: A => A)(implicit eori: EORI, format: Format[A]): Future[A]

  def delete[A : ClassTag](implicit eori: EORI): Future[Unit]

  def deleteAll(implicit eori: EORI): Future[Unit]

}
