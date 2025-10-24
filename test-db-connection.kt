import java.sql.DriverManager

fun main() {
    val url = "jdbc:postgresql://93.115.172.14:5432/wordwaverisedb"
    val user = "wordwaveriseadmin"
    val password = "6vDRfoZGkn6BNR"

    try {
        Class.forName("org.postgresql.Driver")
        val conn = DriverManager.getConnection(url, user, password)
        println("✅ Connection successful!")
        println("Database: ${conn.catalog}")
        println("User: ${conn.metaData.userName}")

        // Test query
        val stmt = conn.createStatement()
        val rs = stmt.executeQuery("SELECT version()")
        if (rs.next()) {
            println("PostgreSQL version: ${rs.getString(1)}")
        }

        conn.close()
    } catch (e: Exception) {
        println("❌ Connection failed:")
        println("Error: ${e.message}")
        e.printStackTrace()
    }
}
