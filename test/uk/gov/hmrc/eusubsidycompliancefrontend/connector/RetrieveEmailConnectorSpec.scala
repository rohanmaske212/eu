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

package uk.gov.hmrc.eusubsidycompliancefrontend.connector

import com.typesafe.config.ConfigFactory
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should.Matchers
import play.api.Configuration
import uk.gov.hmrc.eusubsidycompliancefrontend.connectors.RetrieveEmailConnector
import uk.gov.hmrc.eusubsidycompliancefrontend.test.BaseSpec
import uk.gov.hmrc.eusubsidycompliancefrontend.test.CommonTestData.eori1
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import scala.concurrent.ExecutionContext.Implicits.global

class RetrieveEmailConnectorSpec
    extends BaseSpec
    with Matchers
    with MockFactory
    with HttpSupport
    with EmailConnectorSpec {

  private val (protocol, host, port) = ("http", "host", "123")

  private val config = Configuration(
    ConfigFactory.parseString(s"""
                                 | microservice.services.cds {
                                 |    protocol = "$protocol"
                                 |    host     = "$host"
                                 |    port     = $port
                                 |  }
                                 |""".stripMargin)
  )

  private val connector = new RetrieveEmailConnector(mockHttp, new ServicesConfig(config))

  val expectedUrl = s"$protocol://$host:$port/customs-data-store/eori/$eori1/verified-email"

  "RetrieveEmailConnector" when {
    "handling request to retrieve email address by eori" must {
      behave like connectorBehaviourForRetrieveEmail(
        mockGet(expectedUrl)(_),
        () => connector.retrieveEmailByEORI(eori1)
      )
    }

  }

}
