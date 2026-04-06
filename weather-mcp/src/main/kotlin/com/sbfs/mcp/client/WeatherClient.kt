package com.sbfs.mcp.client

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.headers
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class WeatherClient {
    private val httpClient = HttpClient(CIO) {
        defaultRequest {
            url("https://api.weather.gov")
            headers {
                append("Accept", "application/geo+json")
                append("User-Agent", "WeatherApiClient/1.0")
            }
            contentType(ContentType.Application.Json)
        }
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    // Extension function to fetch weather alerts for a given state
    suspend fun getAlerts(state: String): List<String> {
        val alerts = httpClient.get("/alerts/active/area/$state").body<AlertsResponse>()
        return alerts.features.map { feature ->
            """
            Event: ${feature.properties.event}
            Area: ${feature.properties.areaDesc}
            Severity: ${feature.properties.severity}
            Status: ${feature.properties.status}
            Headline: ${feature.properties.headline}
        """.trimIndent()
        }
    }

    // Extension function to fetch forecast information for given latitude and longitude
    suspend fun getForecast(latitude: Double, longitude: Double): List<String> {
        val points = httpClient.get("/points/$latitude,$longitude").body<PointsResponse>()
        val forecastUrl = points.properties.forecast ?: error("No forecast URL available")
        val forecast = httpClient.get(forecastUrl).body<ForecastResponse>()
        return forecast.properties.periods.map { period ->
            """
            ${period.name}:
            Temperature: ${period.temperature}°${period.temperatureUnit}
            Wind: ${period.windSpeed} ${period.windDirection}
            ${period.shortForecast}
        """.trimIndent()
        }
    }

    @Serializable
    data class PointsResponse(val properties: PointsProperties)

    @Serializable
    data class PointsProperties(val forecast: String? = null)

    @Serializable
    data class ForecastResponse(val properties: ForecastProperties)

    @Serializable
    data class ForecastProperties(val periods: List<ForecastPeriod> = emptyList())

    @Serializable
    data class ForecastPeriod(
        val name: String? = null,
        val temperature: Int? = null,
        val temperatureUnit: String? = null,
        val windSpeed: String? = null,
        val windDirection: String? = null,
        val shortForecast: String? = null,
    )

    @Serializable
    data class AlertsResponse(val features: List<AlertFeature> = emptyList())

    @Serializable
    data class AlertFeature(val properties: AlertProperties)

    @Serializable
    data class AlertProperties(
        val event: String? = null,
        val areaDesc: String? = null,
        val severity: String? = null,
        val status: String? = null,
        val headline: String? = null,
    )
}