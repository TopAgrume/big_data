import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.DataFrame

object DataProcessing {

  def main(args: Array[String]): Unit = {

    val spark = SparkSession.builder()
      .appName("DataIngestion")
      .master("local[*]") // Use all cores
      .getOrCreate()
    spark.sparkContext.setLogLevel("WARN") // Disable for debugging

    val dataPath = "ecommerce_data_with_trends.csv"

    val rawDf: DataFrame = loadData(spark, dataPath)
    rawDf.show(10)

    rawDf.createOrReplaceTempView("raw_transactions")

    val cleanedDf = cleanData(spark)
    cleanedDf.createOrReplaceTempView("cleaned_transactions")
    cleanedDf.show(10)

    cleanedDf.createOrReplaceTempView("cleaned_transactions")
    getTestMetrics(spark)

    // Save processed data to a Parquet file
    cleanedDf.write
      .mode("overwrite")
      .partitionBy("transaction_date", "main_category")
      .parquet("cleaned_ecommerce_data.parquet")

    spark.stop()
  }

  /**
   * A function to load data from a CSV file into a DataFrame.
   *
   * @param spark - The active Spark session
   * @param path  - Path to the data file
   * @return DataFrame containing the loaded data
   */
  def loadData(spark: SparkSession, path: String): DataFrame = {
    spark.read
      .option("header", "true")
      .option("inferSchema", "true")
      .csv(path)
  }

  /**
   * Cleans and processes raw transaction data.
   *
   * This function performs several data cleaning and transformation steps on the raw transaction data:
   * - Parses and cleans timestamps and dates.
   * - Adds time-based metrics such AS transaction hour, day of the week, month, and year.
   * - Trims and standardizes customer and product information.
   * - Extracts main and sub-categories from the product category field.
   * - Cleans and validates numerical values: price, quantity, and total amount.
   * - Adds quality checks and flags for price calculation issues.
   * - Validates customer types and
   * - handles duplicated data.
   * @param spark The active Spark session.
   * @return DataFrame containing the cleaned and processed transaction data.
   */
  def cleanData(spark: SparkSession): DataFrame = {
    spark.sql("""
      -- Pre-process the data and replaces bad values by NULL
      WITH parsed_categories AS (
        SELECT
          transaction_id,
          -- Clean timestamp / date
          CASE
            WHEN timestamp IS NULL THEN NULL
            ELSE to_timestamp(timestamp)
          END AS transaction_timestamp,
          to_date(timestamp) AS transaction_date,

          -- Clean customer information
          customer_id,
          INITCAP(TRIM(customer_name)) AS customer_name,
          INITCAP(TRIM(city)) AS city,
          UPPER(TRIM(customer_type)) AS customer_type,

          -- Clean product information
          TRIM(product_name) AS product_name,

          -- Extract main and sub categories
          TRIM(SPLIT(category, '>')[0]) AS main_category,
          CASE
            WHEN SIZE(SPLIT(category, '>')) > 1 THEN TRIM(SPLIT(category, '>')[1])
            ELSE NULL
          END AS sub_category,

          -- Clean numerical values (irrelevant data)
          CASE
            WHEN price <= 0 OR price IS NULL THEN NULL
            ELSE ROUND(price, 2)
          END AS unit_price,
          CASE
            WHEN quantity <= 0 OR quantity IS NULL THEN NULL
            ELSE quantity
          END AS quantity,
          CASE
            WHEN total_amount <= 0 OR total_amount IS NULL THEN NULL
            ELSE ROUND(total_amount, 2)
          END AS total_amount,

          -- Validate total amount calculation
          ABS(ROUND(price * quantity, 2) - total_amount) AS price_calculation_diff

        FROM raw_transactions
      ),

      -- Add quality checks for filtering + new metrics
      validated_dataset AS (
        SELECT
          *,
          -- Flag price quality issues
          CASE
            WHEN price_calculation_diff > 1.0 THEN true
            ELSE false
          END AS has_price_problem,

          -- Add time-based metrics
          HOUR(transaction_timestamp) AS transaction_hour,
          DAYOFWEEK(transaction_date) AS transaction_day_of_week,
          MONTH(transaction_date) AS transaction_month,
          YEAR(transaction_date) AS transaction_year,

          -- Validate customer type
          CASE
            WHEN customer_type IN ('B2B', 'B2C') THEN customer_type
            ELSE 'UNKNOWN'
          END AS validated_customer_type,

          -- Add row number to handle duplicated data
          ROW_NUMBER() OVER (
            PARTITION BY
              transaction_id,
              customer_id,
              product_name,
              total_amount
            ORDER BY
              transaction_timestamp DESC  -- keep the most recent row
          ) AS row_number

        FROM parsed_categories
      )

      -- Final selection / filtering with valid data
      SELECT
        transaction_id,
        transaction_timestamp,
        transaction_date,
        transaction_hour,
        transaction_day_of_week,
        transaction_month,
        transaction_year,

        -- Customer data
        customer_id,
        customer_name,
        city,
        validated_customer_type AS customer_type,

        -- Product data
        product_name,
        main_category,
        sub_category,

        -- Price data
        unit_price,
        quantity,
        total_amount,
        has_price_problem -- To delete later

      FROM validated_dataset
      WHERE
        -- Filter out data with issues
        transaction_id IS NOT NULL
        AND transaction_timestamp IS NOT NULL
        AND customer_id IS NOT NULL
        AND unit_price IS NOT NULL
        AND quantity IS NOT NULL
        AND total_amount IS NOT NULL
        -- AND has_price_problem IS FALSE

        -- Filter duplicated row
        AND row_number = 1
      ORDER BY transaction_timestamp
    """)
  }

  // TODO remove tests for production (and the has_price_problem filter comment)
  def getTestMetrics(spark: SparkSession): Unit = {
    println("\n========== Transaction metrics ==========")

    spark.sql("""
      SELECT
        COUNT(*) AS total_transactions,
        COUNT(DISTINCT *) AS distinct_transactions,
        COUNT(DISTINCT customer_id) AS unique_customers,
        COUNT(DISTINCT product_name) AS unique_products,
        COUNT(DISTINCT main_category) AS unique_main_categories,

        -- Time range
        MIN(transaction_date) AS earliest_date,
        MAX(transaction_date) AS latest_date,

        -- Priec metrics
        ROUND(AVG(total_amount), 2) AS avg_transaction_value,
        ROUND(MIN(total_amount), 2) AS min_transaction_value,
        ROUND(MAX(total_amount), 2) AS max_transaction_value,

        -- Price quality metrics
        SUM(CASE WHEN has_price_problem THEN 1 ELSE 0 END) AS price_problem_count
      FROM cleaned_transactions
    """).show(false)

    println("\n========== Category distribution ==========")
    spark.sql("""
      SELECT
        main_category,
        COUNT(*) AS transaction_count,
        COUNT(DISTINCT customer_id) AS unique_customers,
        ROUND(AVG(total_amount), 2) AS avg_transaction_value,
        ROUND(SUM(total_amount), 2) AS total_revenue
      FROM cleaned_transactions
      GROUP BY main_category
      ORDER BY total_revenue DESC
    """).show(false)

    println("\n========== Customer analysis ==========")
    spark.sql("""
      SELECT
        customer_type,
        COUNT(*) AS transaction_count,
        COUNT(DISTINCT customer_id) AS unique_customers,
        ROUND(AVG(quantity), 2) AS avg_quantity,
        ROUND(AVG(total_amount), 2) AS avg_transaction_value,
        ROUND(SUM(total_amount), 2) AS total_revenue
      FROM cleaned_transactions
      GROUP BY customer_type
      ORDER BY total_revenue DESC
    """).show(false)

    println("\n========== Hourly patterns ==========")
    spark.sql("""
      SELECT
        transaction_hour,
        COUNT(*) AS transaction_count,
        ROUND(AVG(total_amount), 2) AS avg_transaction_value
      FROM cleaned_transactions
      GROUP BY transaction_hour
      ORDER BY transaction_hour
    """).show(false)

    println("\n========== Daily patterns ==========")
    spark.sql("""
      SELECT
        transaction_day_of_week,
        COUNT(*) AS transaction_count,
        ROUND(AVG(total_amount), 2) AS avg_transaction_value
      FROM cleaned_transactions
      GROUP BY transaction_day_of_week
      ORDER BY transaction_day_of_week
    """).show(false)
  }
}

