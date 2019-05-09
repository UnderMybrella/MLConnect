package dev.eternalbox.mlconnect

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers
import dev.eternalbox.kjukebox.InfiniteJukeboxTrack
import dev.eternalbox.kjukebox.dynamicCalculateNearestNeighbors
import dev.eternalbox.kjukebox.preprocess
import dev.eternalbox.lavabox.LavaBox
import io.vertx.core.Vertx
import io.vertx.ext.web.client.WebClient
import io.vertx.kotlin.ext.web.client.sendAwait
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream
import java.io.PrintStream
import java.util.*

object MLConnect {
    val BASE_URL = "https://eternalbox.dev/api"
    val ANALYSIS_URL = "%s/analysis/analyse/%s"
    val AUDIO_URL = "%s/audio/jukebox/%s"

    val GANGNAM_STYLE = "03UrZgTINDqvnUMbbIMhql"

    val JSON_MAPPER: ObjectMapper = ObjectMapper()
        .registerKotlinModule()
        .registerModules(Jdk8Module(), JavaTimeModule(), ParameterNamesModule())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET)
        .enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
        .setSerializationInclusion(JsonInclude.Include.NON_ABSENT)

    val vertx = Vertx.vertx()
    val webClient = WebClient.create(vertx)

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val playerManager = DefaultAudioPlayerManager()
        //AudioSourceManagers.registerRemoteSources(playerManager)
        AudioSourceManagers.registerLocalSource(playerManager)
        val player = playerManager.createPlayer()

        val songID = args.getOrNull(0) ?: GANGNAM_STYLE

        println("Downloading analysis...")
        //Save the analysis
        File("$songID.json")
            .takeUnless(File::exists)
            ?.writeBytes(
                webClient.getAbs(String.format(ANALYSIS_URL, BASE_URL, songID))
                    .sendAwait()
                    .body()
                    .bytes
            )

        println("Downloading audio...")
        //Save the audio
        File("$songID.m4a")
            .takeUnless(File::exists)
            ?.writeBytes(
                webClient.getAbs(String.format(AUDIO_URL, BASE_URL, songID))
                    .sendAwait()
                    .body()
                    .bytes
            )

        val track = JSON_MAPPER.readValue<InfiniteJukeboxTrack>(File("$songID.json"))
        val audio = File("$songID.m4a")

        println("Preprocessing ${track.info.title}...")

        preprocess(track)
        dynamicCalculateNearestNeighbors(track.analysis.beatsArray)

        val beats = track.analysis.beats

        //Separate the audio

        println("Separating audio...")
        val (frames, frameMap) = LavaBox.separateAndGroupTrack(playerManager, player, audio.absolutePath, track)

        println("Printing data...")
        val rowsSource = beats.flatMap { a -> beats.map(a::to) }
            .map { (a, b) ->
                Triple(
                    a.neighbors?.any { edge ->
                        (edge.src == a && edge.dest == b) || (edge.src == b && edge.dest == a)
                    } ?: false,
                    a,
                    b
                )
            }

        val countEach = minOf(rowsSource.count { it.first }, rowsSource.count { !it.first })
        val rows = rowsSource.filter { it.first }
            .shuffled()
            .take(countEach)
            .plus(rowsSource.filter { !it.first }.shuffled().take(countEach))
            .shuffled()

        val out = PrintStream(FileOutputStream("branches.csv", true))
        val b64 = Base64.getEncoder()
        val csvFormatString = "%s,%s,%s"
        rows.forEach { (branch, a, b) ->
            out.println(
                String.format(
                    csvFormatString,
                    branch,
                    frameMap.getValue(a).joinToString("") { frame -> b64.encodeToString(frame.data) },
                    frameMap.getValue(b).joinToString("") { frame -> b64.encodeToString(frame.data) }
                )
            )
        }
        out.close()

        println("Done!")
    }

    init {

    }
}