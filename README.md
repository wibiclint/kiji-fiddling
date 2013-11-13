Kiji Express item/item collaborative filtering example
======================================================

Introduction
------------

In this example, we implement a top-N recommendation system using item-based
collaborative filtering.

Overview of the process:

  * Import the movie database into a kiji table
    - Group everything by user
  * Compute item similarities
    - Compute the average rating for each user
    - Use cosine similarity of items (after adjusting scores for average
      per-user ratings)
    - Here we may want to remember only the M most similar items for each item
      (don't keep entire similarity matrix)
  * Get a list of the k nearest neighbors for every item
    - Eventually this would be the "training" phase (?)
    - Store the results in this table or in another table?
  * Write score function
    - Input: An active user and the item in question
    - Get the list of the k nearest neighbors for the item in question
    - Not clear exactly what to do here
    - Output: A score for this item

Inspired by Mahout implementation: http://isabel-drost.de/hadoop/slides/collabMahout.pdf


Download & Start Bento
----------------------
    cd kiji-bento-<whatever>
    source bin/kiji-env.sh
    bento start


Setup
------
    export DOGFOOD_CF_HOME = <path/to/tutorial/root/dir>

Install a Kiji instance.

    export KIJI=kiji://.env/item_item_cf
    kiji install --kiji=${KIJI}


Create the movie table
----------------------

    kiji-schema-shell --kiji=${KIJI} --file=$DOGFOOD_CF_HOME/src/main/resources/org/kiji/express/item_item_cf/movies.ddl


Inspect the movie table
-----------------------

    ☁  ~DOGFOOD_CF_HOME [master] ⚡ ../kiji-bento-chirashi/schema-shell/bin/kiji-schema-shell --kiji=$KIJI                
    Kiji schema shell v1.3.0
    Enter 'help' for instructions (without quotes).
    Enter 'quit' to quit.
    DDL statements must be terminated with a ';'
    schema> show tables;
    13/11/11 20:59:57 WARN org.apache.hadoop.util.NativeCodeLoader: Unable to load native-hadoop library for your platform... using builtin-java classes where applicable
    Table   Description
    ======  ========================================
    movies  User ratings of movies (movies are rows)
    schema> describe movies


Upload data to HDFS
-------------------

    hadoop fs -mkdir item_item_cf
    hadoop fs -copyFromLocal $DOGFOOD_CF_HOME/src/main/resources/org/kiji/express/item_item_cf/*.csv item_item_cf/

To check that the data actually got copied:

    ☁  ~DOGFOOD_CF_HOME [master] ⚡ hadoop fs -ls item_item_cf
    13/11/11 21:04:18 WARN util.NativeCodeLoader: Unable to load native-hadoop library for your platform... using builtin-java classes where applicable
    Found 3 items
    -rw-r--r--   3 clint supergroup       3100 2013-11-11 21:03 item_item_cf/movie-titles.csv
    -rw-r--r--   3 clint supergroup    4388258 2013-11-11 21:03 item_item_cf/ratings.csv
    -rw-r--r--   3 clint supergroup     186304 2013-11-11 21:03 item_item_cf/users.csv


Hack the bento box JAR files!
-----------------------------

This is necessary if you are using non-bento versions of any Kiji components.
Should not be necessary after new BentoBox release.


Import data into the tables
---------------------------

Use an importer script to load all of the data into a single table.

    express job target/kiji-express-item-item-cf-XXX.jar \
        org.kiji.express.item_item_cf.MovieImporter \
        --titles item_item_cf/movie-titles.csv \
        --ratings item_item_cf/ratings.csv \
        --table-uri ${KIJI}/user_ratings \
        --hdfs


Inspect the data in the table
-----------------------------

    kiji scan ${KIJI}/user_ratings --max-rows=1


Create the item-item similarity list
------------------------------------



Debugging
---------

Check system logs, something like:

    http://localhost:50030/logs/userlogs/job_20131112153730549_0008/attempt_20131112153730549_0008_m_000000_0/syslog

Get the table layout in JSON:

    kiji layout --table=$KIJI/user_ratings --do=dump --write-to=layout.json



Stop the BentoBox
-----------------

    bento stop


TODO
----
* Might want to mention that Scalding does *not* let you put "=" in
  command-line arguments
* How to best debug?  How to print out streams?  Or parts of streams?
* Hard to debug stuff
    - I can write code that compiles, but fails because of what my schema is
    - How to get good debug messages?
    - Would be useful just to see what your streams look like
    - Scalding does have this - [pipe].debug
    - How to use log4j from Scalding?
* Can I always get a key back from an entity id?
* Would be good to get more clarity on exactly what I get from KijiInput when I
  use qualified and family specs
* Could not figure out how to operate on mixture of stuff from qualified column
  and column family
* Might be useful to have a series of small tutorials that get slowly more
  complicated (like in Cascading)
* Definitely need examples of how to do unit tests for Express!!!
    - Critical for users
    - Not really much documentation
    - Could use just some trivial examples: table (with map-type) -> table
      (with map-type), CSV -> CSV, etc.
    - Having to retype all of the source/sink stuff is terrible, need a way to
      get around this...
* Would be nice to have some examples of log statements
