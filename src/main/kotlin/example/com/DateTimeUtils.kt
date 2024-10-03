package example.com

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter


// Format timestamp in form of a long (epoch since) to MM/dd/yyyy
fun formatTimestampToDateString(timestamp: Long): String {
    val formatter = DateTimeFormatter.ofPattern("MM/dd/yyyy")
    val dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault())
    return dateTime.format(formatter)
}