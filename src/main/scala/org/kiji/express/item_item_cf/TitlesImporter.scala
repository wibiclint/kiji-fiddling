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
 * Populates a table of movie titles.
 *
 * Reads in a CSV file with records of the form `movieId`, `title`.
 *
 * @param args passed in from the command line.
 */
class TitlesImporter(args: Args) extends KijiJob(args) {
  // Get movie titles
  val moviesStream = Csv(args("titles"), fields=('movie, 'title))
      .read
      // Cast the movie id into a Long
      .mapTo(('movie, 'title) -> ('entityId, 'title)) { x: (String, String) => {
          (EntityId(x._1.toLong), x._2)
      }}
      .write(KijiOutput(
          tableUri = args("table-uri"),
          columns = Map('title -> QualifiedColumnOutputSpec("info", "title"))))
}
