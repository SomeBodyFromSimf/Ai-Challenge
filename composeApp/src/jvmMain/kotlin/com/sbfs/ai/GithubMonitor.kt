package com.sbfs.ai

import com.sbfs.ai.data.Model
import com.sbfs.ai.repository.ConfigRepository
import com.sbfs.ai.repository.PullRequestRepository
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64

class GithubMonitor(
    private val configRepository: ConfigRepository,
    private val pullRequestRepository: PullRequestRepository,
    private val openRouterClient: OpenRouterClient,
    private val getCurrentModel: () -> Model?,
) {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    suspend fun checkNewPullRequests() = withContext(Dispatchers.IO) {
        val config = configRepository.getConfig()
        val repo = config.githubRepo ?: return@withContext
        val token = config.githubToken ?: return@withContext

        val prs: List<PullRequestResponse> = client.get("https://api.github.com/repos/$repo/pulls") {
            header("Authorization", "token $token")
            header("Accept", "application/vnd.github.v3+json")
        }.body()

        val knownNumbers = pullRequestRepository.getAllNumbers().map { it.toInt() }.toSet()

        val prsToReview = prs.filter { it.state == "open" && it.number !in knownNumbers }
        val model = getCurrentModel() ?: return@withContext
        prsToReview.forEach { pr ->
            //pullRequestRepository.insert(pr.number, pr.state)
            reviewPullRequest(model, pr.number, repo, token)
        }
    }

    private suspend fun reviewPullRequest(model: Model, prNumber: Int, repo: String, token: String) {
        try {
            // Get diff
            val files: List<PrFile> = client.get("https://api.github.com/repos/$repo/pulls/$prNumber/files") {
                header("Authorization", "token $token")
                header("Accept", "application/vnd.github.v3+json")
            }.body()

            val diffString = files.joinToString("\n\n") { file ->
                "File: ${file.filename}\nStatus: ${file.status}\nPatch: ${file.patch ?: ""}"
            }

            // Send to LLM
            val prompt = "Сделай ревью кода. Опиши потенциальные баги, архитектурные проблемы, а также рекомендации:\n$diffString"
            val review = openRouterClient.generateResponse(prompt, model) // Assume method exists or adapt

            // Post comment
            client.post("https://api.github.com/repos/$repo/issues/$prNumber/comments") {
                header("Authorization", "token $token")
                header("Accept", "application/vnd.github.v3+json")
                contentType(ContentType.Application.Json)
                setBody(mapOf("body" to review))
            }

            // Mark as reviewed
            val prId = pullRequestRepository.getUnreviewed().find { it.number.toInt() == prNumber }?.id ?: return
            pullRequestRepository.markReviewed(prId)
        } catch (e: Exception) {
            println("Error reviewing PR $prNumber: ${e.message}")
        }
    }
}

@Serializable
data class PullRequestResponse(
    val number: Int,
    val state: String
)

@Serializable
data class PrFile(
    val filename: String,
    val status: String,
    val patch: String? = null
)
