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

import com.twitter.scalding._
import cascading.pipe.Pipe

import org.kiji.express._
import org.kiji.express.flow._

/**
 * Calculate item-item similarity.
 *
 * Get mean rating / user:
 * - Read in all of the data from the table
 * - Group by user
 * - Get the mean
 * - Create a (user, mean) stream
 *
 * Get the mean-adjusted ratings for all of the items
 * - Read in all of the data from the table
 * - Group by user
 * - Join with the (tiny) (user, mean) stream
 * - Subtract the mean from all of the ratings
 * - Create a (user, movie, normalized-rating) stream
 *
 * Get the cosine similarity of all of the pairs for items
 *
 * @param args passed in from the command line.
 */
class ItemSimilarity(args: Args) extends KijiJob(args) {

  def extractMovieIdAndRating(slice: Seq[Cell[Double]]): Seq[(Long,Double)] = {
    slice.map { cell => (cell.qualifier.toLong, cell.datum) }
  }

  // Read all of the data out of the user ratings table
  val userStream = KijiInput(
      tableUri = args("table-uri"),
      columns = Map(ColumnFamilyInputSpec("ratings") -> 'ratingInfo))
      .read

      // Extract the userIds
      .map('entityId -> 'userId) { eid: EntityId => eid.components(0) }

      // Extract the ratings
      .flatMap('ratingInfo -> ('movieId, 'rating)) { extractMovieIdAndRating }

      .project('userId, 'movieId, 'rating)


  // Group by user and compute the mean ratings
  val userMeansStream = userStream
      .groupBy('userId) { _.average('rating -> 'avgRating) }

  // Create scheme with user ratings adjusted by the user's mean
  val adjustedUserStream = userStream
      .joinWithSmaller('userId -> 'userId, userMeansStream)
      .map(('rating, 'avgRating) -> 'rating) { x: (Double, Double) => {x._1 - x._2 }}

  .write(Tsv("foo"))
}
