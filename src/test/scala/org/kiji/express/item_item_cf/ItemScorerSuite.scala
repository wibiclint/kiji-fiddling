/**
 * (c) Copyright 2013 WibiData, Inc.
 *
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
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

package org.kiji.express.item_item_cf

import scala.collection.mutable
import scala.math.abs
import scala.collection.JavaConverters._

import cascading.tuple.Fields
import com.twitter.scalding.Args
import com.twitter.scalding.JobTest
import com.twitter.scalding.TextLine
import com.twitter.scalding.Tsv
import org.apache.avro.generic.GenericRecord
import org.apache.avro.generic.GenericRecordBuilder
import org.apache.avro.specific.SpecificRecord
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.apache.commons.io.IOUtils
import java.io.InputStream

import org.kiji.express._
import org.kiji.express.flow._
import org.kiji.express.flow.util.Resources.doAndRelease
import org.kiji.schema.KijiTable
import org.kiji.schema.util.InstanceBuilder

import org.kiji.schema.Kiji
import org.kiji.schema.KijiURI
import org.kiji.schema.layout.KijiTableLayout

import org.kiji.schema.shell.api.Client
import org.kiji.express.item_item_cf.avro._

@RunWith(classOf[JUnitRunner])
class ItemScorerSuite extends ItemItemSuite {
  val logger: Logger = LoggerFactory.getLogger(classOf[ItemSimilarityCalculatorSuite])

  val myUser: Long = 100L
  val myItem: Long = 10L

  // Example in which user 100 and user 101 have both given item 0 5 stars.
  // Each has also reviewed another item and given that item a different rating, (this ensures
  // that the mean-adjusted rating is not a zero).
  val avroSortedSimilarItems = new AvroSortedSimilarItems(
      List(new AvroItemSimilarity(11, 0.5)).asJava
  )

  val itemSimSlices: List[(EntityId, Seq[Cell[AvroSortedSimilarItems]])] = List(
    (EntityId(myItem), List(
        Cell[AvroSortedSimilarItems](
            "most_similar",
            "most_similar",
            version,
            avroSortedSimilarItems)
          )))

  //def validateOutput(output: mutable.Buffer[(Long, Double, Double)]): Unit = {
  def validateOutput(output: mutable.Buffer[Any]): Unit = {
    println(output)
  }

  test("Hmmmm") {
    val jobTest = JobTest(new ItemScorer(_))
        .arg("similarity-table-uri", itemItemSimilaritiesUri)
        .arg("ratings-table-uri", userRatingsUri)
        .arg("user", myUser.toString)
        .arg("item", myItem.toString)
        .arg("k", "30")
        .source(KijiInput(
            itemItemSimilaritiesUri,
            columns = Map(
              QualifiedColumnInputSpec(
                  "most_similar",
                  "most_similar",
                  specificRecord = classOf[AvroSortedSimilarItems]
            ) -> 'most_similar)), itemSimSlices)
        .source(kijiInputUserRatings, userRatingsSlices)
        .sink(Tsv("foo")) { validateOutput }

    // Run in local mode.
    jobTest.run.finish
    // Run in hadoop mode.
    //jobTest.runHadoop.finish
  }
}

