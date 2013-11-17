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

import scala.math.sqrt
import scala.collection.JavaConverters._

import cascading.pipe.Pipe
import cascading.pipe.joiner.LeftJoin
import com.twitter.scalding._
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import org.kiji.express._
import org.kiji.express.flow._

import org.kiji.express.item_item_cf.avro._

/**
 * Calculate the score for an item for a user by taking the weighted average of that user's rating
 * for the K most similar items to the item in question.
 *
 * @param args passed in from the command line.
 */
class ItemScorer(args: Args) extends ItemItemJob(args) {

  val user = args("user").toLong
  val userEntityId = EntityId(user)
  val item = args("item").toLong
  val itemEntityId = EntityId(item)

  def extractItemIdAndSimilarity(slice: Seq[Cell[AvroSortedSimilarItems]]): Seq[(Long, Double)] = {
    slice.flatMap { cell => {
      // Get a Scala List of the similar items and similarities
      val topItems = cell.datum.getTopItems.asScala

      topItems.map { sim: AvroItemSimilarity => (sim.getItemId.toLong, sim.getSimilarity.toDouble) }
    }}}

  // Get the most similar items to this item
  // Extract them out of the AvroSortedSimilarItems
  val mostSimilarPipe = KijiInput(
      tableUri = args("similarity-table-uri"),
      columns = Map(
        QualifiedColumnInputSpec(
            "most_similar",
            "most_similar",
            specificRecord = classOf[AvroSortedSimilarItems]
      ) -> 'most_similar))
      // We care about only the data for one item
      .filter('entityId) { eid: EntityId => eid == itemEntityId }
      //.map('entityId -> 'itemId) { eid: EntityId => eid.components(0) }
      // Extract out the itemId and similarity score for the similar items
      .flatMap('most_similar -> ('similarItem, 'similarity)) { extractItemIdAndSimilarity }
      //.project('itemId, 'similarItem, 'similarity)
      .project('similarItem, 'similarity)


  // Read in user ratings for various items
  val userRatingsPipe = createUserRatingsPipe(Some(args("user").toLong))

  // Select only the most similar items that the user has rated
  val mostSimilarItemsThatUserHasRatedPipe = mostSimilarPipe
      .joinWithSmaller('similarItem -> 'itemId, userRatingsPipe)
      .project('similarItem, 'similarity, 'rating)

  // Sort, and then take the top K
  val neighborsPipe = mostSimilarItemsThatUserHasRatedPipe
      .groupAll { _.sortedReverseTake[(Double, Long, Double)] (
          ('similarity, 'similarItem, 'rating) -> 'res, args("k").toInt) }
      .flattenTo[(Double, Long, Double)] ('res -> ('similarity, 'similarItem, 'rating))

  // Sum of all of the similarities is the denominator
  val denom = neighborsPipe
      .groupAll {
        _.sum('similarity -> 'denom)
      }

  val numer = neighborsPipe
      .mapTo(('similarity, 'rating) -> ('scoreTerm)) { x: (Double, Double) => x._1 * x._2 }
      .groupAll { _.sum('scoreTerm -> 'numer) }

  denom
      .crossWithTiny(numer)
      .mapTo(('numer, 'denom) -> 'score) { x: (Double, Double) => x._1 / x._2 }
      .write(Tsv("foo"))


}
