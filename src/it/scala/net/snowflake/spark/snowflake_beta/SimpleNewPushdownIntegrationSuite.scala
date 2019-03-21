/*
 * Copyright 2015-2016 Snowflake Computing
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

package net.snowflake.spark.snowflake_beta

import net.snowflake.spark.snowflake_beta.Utils.SNOWFLAKE_SOURCE_NAME
import org.apache.spark.sql.Row

class SimpleNewPushdownIntegrationSuite extends IntegrationSuiteBase {

  private val test_table: String = s"test_table_simple_$randomSuffix"
  private val test_table2        = s"test_table_simple2_$randomSuffix"

  // Values used for comparison
  private val row1 = Row(null, "Hello")
  private val row2 = Row(2, "Snowflake")
  private val row3 = Row(3, "Spark")
  private val row4 = Row(4, null)

  // Join Table
  private val row1b = Row(null, 1)
  private val row2b = Row(2, 2)
  private val row3b = Row(3, 2)
  private val row4b = Row(4, 3)

  override def beforeAll(): Unit = {
    super.beforeAll()

    jdbcUpdate(s"create or replace table $test_table(i int, s string)")
    jdbcUpdate(s"create or replace table $test_table2(o int, p int)")
    jdbcUpdate(
      s"insert into $test_table values(null, 'Hello'), (2, 'Snowflake'), (3, 'Spark'), (4, null)")
    jdbcUpdate(
      s"insert into $test_table2 values(null, 1), (2, 2), (3, 2), (4, 3)")

    SnowflakeConnectorUtils.enablePushdownSession(sparkSession)

    val df1 = sparkSession.read
      .format(SNOWFLAKE_SOURCE_NAME)
      .options(connectorOptionsNoTable)
      .option("dbtable", s"$test_table")
      .load()

    val df2 = sparkSession.read
      .format(SNOWFLAKE_SOURCE_NAME)
      .options(connectorOptionsNoTable)
      .option("dbtable", s"$test_table2")
      .load()

    df1.createOrReplaceTempView("df1")
    df2.createOrReplaceTempView("df2")
  }

  test("Basic join") {

    val result = sparkSession.sql("""
  SELECT first.s,
         second.p
  FROM df1 first
  JOIN df2 second
  ON first.i = second.p""".stripMargin)

    testPushdown(
      s"""SELECT ("subquery_5"."subquery_5_col_1") AS "subquery_6_col_0", ("subquery_5"."subquery_5_col_2") AS "subquery_6_col_1" FROM
	(SELECT ("subquery_1"."I") AS "subquery_5_col_0", ("subquery_1"."S") AS "subquery_5_col_1", ("subquery_4"."subquery_4_col_0") AS "subquery_5_col_2" FROM
		(SELECT * FROM
			(SELECT * FROM ($test_table) AS "sf_connector_query_alias"
		) AS "subquery_0"
	 WHERE ("subquery_0"."I" IS NOT NULL)
	) AS "subquery_1"
 INNER JOIN
	(SELECT ("subquery_3"."P") AS "subquery_4_col_0" FROM
		(SELECT * FROM
			(SELECT * FROM ($test_table2) AS "sf_connector_query_alias"
			) AS "subquery_2"
		 WHERE ("subquery_2"."P" IS NOT NULL)
		) AS "subquery_3"
	) AS "subquery_4"
 ON ("subquery_1"."I" = "subquery_4"."subquery_4_col_0")
) AS "subquery_5"""".stripMargin,
      result,
      Seq(Row("Snowflake", 2), Row("Snowflake", 2), Row("Spark", 3)))
  }

  test("Basic aggregation") {
    val result =
      sparkSession.sql("""
        select p, count(distinct o) as avg from df2
        group by p
      """.stripMargin)

    testPushdown(
      s"""SELECT ("subquery_0"."P") AS "subquery_1_col_0", (COUNT(DISTINCT "subquery_0"."O")) AS "subquery_1_col_1"
          |FROM
          |	(SELECT * FROM ($test_table2) AS "sf_connector_query_alias"
          |) AS "subquery_0"
          | GROUP BY "subquery_0"."P"
      """.stripMargin,
      result,
      Seq(Row(1, 0), Row(2, 2), Row(3, 1)))
  }

  test("Basic filters") {
    val result =
      sparkSession.sql("""
        select * from df2
        where p > 1 AND p < 3
      """.stripMargin)

    testPushdown(s"""SELECT * FROM
                     |	(SELECT * FROM ($test_table2) AS "sf_connector_query_alias"
                     |) AS "subquery_0"
                     | WHERE ((("subquery_0"."P" IS NOT NULL) AND ("subquery_0"."P" > 1)) AND ("subquery_0"."P" < 3))
      """.stripMargin,
                 result,
                 Seq(Row(2, 2), Row(3, 2)))
  }

  test("LIMIT and SORT") {
    val result =
      sparkSession.sql("""
        select * from df2
        where p > 1 AND p < 3 order by p desc limit 1
      """.stripMargin)

    testPushdown(s"""SELECT * FROM
                     |	(SELECT * FROM
                     |		(SELECT * FROM
                     |			(SELECT * FROM ($test_table2) AS "sf_connector_query_alias"
                     |		) AS "subquery_0"
                     |	 WHERE ((("subquery_0"."P" IS NOT NULL) AND ("subquery_0"."P" > 1)) AND ("subquery_0"."P" < 3))
                     |	) AS "subquery_1"
                     | ORDER BY ("subquery_1"."P") DESC
                     |) AS "subquery_2"
                     | LIMIT 1
      """.stripMargin,
                 result,
                 Seq(Row(2, 2)))
  }

  test("Nested query with column alias") {
    val result =
      sparkSession.sql("""
        select f, o from (select o, p as f from df2
        where p > 1 AND p < 3) as foo order by f,o desc
      """.stripMargin)

    testPushdown(s"""SELECT * FROM
                     |	(SELECT ("subquery_1"."P") AS "subquery_2_col_0", ("subquery_1"."O") AS "subquery_2_col_1"
                     | FROM
                     |		(SELECT * FROM
                     |			(SELECT * FROM ($test_table2) AS "sf_connector_query_alias"
                     |		) AS "subquery_0"
                     |	 WHERE ((("subquery_0"."P" IS NOT NULL) AND ("subquery_0"."P" > 1)) AND ("subquery_0"."P" < 3))
                     |	) AS "subquery_1"
                     |) AS "subquery_2"
                     | ORDER BY ("subquery_2"."subquery_2_col_0") ASC, ("subquery_2"."subquery_2_col_1") DESC
      """.stripMargin,
                 result,
                 Seq(Row(2, 3), Row(2, 2)))
  }

  test("Sum and RPAD") {
    val result =
      sparkSession.sql(
        """
        select sum(i) as hi, rpad(s,10,"*") as ho from df1 group by s
                       """.stripMargin)

    testPushdown(
      s"""SELECT (SUM("subquery_0"."I")) AS "subquery_1_col_0", (RPAD("subquery_0"."S", 10, '*')) AS
          |"subquery_1_col_1" FROM
          |	(SELECT * FROM ($test_table) AS "sf_connector_query_alias"
          |) AS "subquery_0"
          | GROUP BY "subquery_0"."S"
      """.stripMargin,
      result,
      Seq(Row(null, "Hello*****"),
          Row(2, "Snowflake*"),
          Row(3, "Spark*****"),
          Row(4, null)))

  }

  override def beforeEach(): Unit = {
    super.beforeEach()
  }

  override def afterAll(): Unit = {
    try {
      jdbcUpdate(s"drop table if exists $test_table")
      jdbcUpdate(s"drop table if exists $test_table2")
    } finally {
      super.afterAll()
      SnowflakeConnectorUtils.disablePushdownSession(sqlContext.sparkSession)
    }
  }
}
