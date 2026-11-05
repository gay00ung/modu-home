import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import javax.inject.Inject
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val routingBaseUrl = providers.gradleProperty("routingBaseUrl").orNull
val proposalRelayBaseUrl = providers.gradleProperty("proposalRelayBaseUrl").orNull
val vehicleVoiceEnabled = providers.gradleProperty("enableVehicleVoice").map(String::toBooleanStrict)
val packageRelease = providers.gradleProperty("packageRelease").map(String::toBooleanStrict).getOrElse(false)
fun stringConstant(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

fun isHttpsOrigin(value: String?): Boolean = runCatching {
    if (value == null || value.length !in 1..2048 || value.any { it.code !in 0x21..0x7e }) return@runCatching false
    val uri = URI(value)
    uri.scheme == "https" && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null &&
        uri.rawQuery == null && uri.rawFragment == null && uri.rawPath in listOf("", "/") &&
        (uri.port == -1 || uri.port in 1..65535)
}.getOrDefault(false)

val releaseSigning = if (packageRelease) {
    val invalidUrls = listOf("routingBaseUrl" to routingBaseUrl, "proposalRelayBaseUrl" to proposalRelayBaseUrl)
        .filterNot { isHttpsOrigin(it.second) }.map { it.first }
    check(invalidUrls.isEmpty()) { "HTTPS 원점 주소가 필요한 설정: ${invalidUrls.joinToString()}" }
    val location = providers.gradleProperty("releaseSigningFile").orNull
    check(!location.isNullOrEmpty()) { "releaseSigningFile로 서명 설정 파일을 지정해주세요" }
    val signingFile = file(location)
    check(signingFile.isFile) { "releaseSigningFile에 읽을 수 있는 파일을 지정해주세요" }
    val text = providers.fileContents(layout.projectDirectory.file(location)).asText.get()
    val settings = Properties().apply { load(text.reader()) }
    val missing = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
        .filter { settings.getProperty(it).isNullOrEmpty() }
    check(missing.isEmpty()) { "누락된 서명 설정: ${missing.joinToString()}" }
    val store = signingFile.parentFile.resolve(settings.getProperty("storeFile")).canonicalFile
    check(store.isFile) { "storeFile에 기존 키 저장소를 지정해주세요" }
    settings to store
} else null
val releaseApkSigner = objects.fileProperty()

@DisableCachingByDefault(because = "The Android packaging task caches the signed APK")
abstract class ExportReleasePackage : DefaultTask() {
    @get:Input abstract val packagingEnabled: Property<Boolean>
    @get:Input abstract val routingUrl: Property<String>
    @get:Input abstract val relayUrl: Property<String>
    @get:Input abstract val voiceEnabled: Property<Boolean>
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apkDirectory: DirectoryProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val signerJar: RegularFileProperty
    @get:OutputDirectory abstract val destination: DirectoryProperty
    @get:Inject abstract val processes: ExecOperations

    @TaskAction
    fun export() {
        check(packagingEnabled.get()) { "출품 패키지는 -PpackageRelease=true로 생성해주세요" }
        val directory = apkDirectory.get().asFile
        val metadata = JsonSlurper().parse(directory.resolve("output-metadata.json")) as Map<*, *>
        check(metadata["applicationId"] == "io.moduhome.app" && metadata["variantName"] == "release") {
            "release APK 메타데이터를 확인해주세요"
        }
        val elements = metadata["elements"] as List<*>
        check(elements.size == 1) { "단일 release APK 출력이 필요해요" }
        val element = elements.single() as Map<*, *>
        val filename = element["outputFile"] as String
        check(filename.endsWith(".apk") && filename == File(filename).name) { "APK 파일 이름이 올바르지 않아요" }
        val apk = directory.resolve(filename)
        check(apk.isFile) { "release APK가 없어요" }
        val output = ByteArrayOutputStream()
        val verified = processes.javaexec {
            classpath(signerJar.get().asFile)
            mainClass.set("com.android.apksigner.ApkSignerTool")
            args("verify", "--verbose", "--print-certs", apk.absolutePath)
            standardOutput = output
            errorOutput = output
            isIgnoreExitValue = true
        }
        check(verified.exitValue == 0) { "release APK 서명을 확인하지 못했어요" }
        val certificates = Regex("Signer #\\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})")
            .findAll(output.toString(Charsets.UTF_8)).map { it.groupValues[1].lowercase() }.toList()
        check(certificates.size == 1) { "단일 APK 서명 인증서가 필요해요" }
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
        val target = destination.get().asFile.apply { mkdirs() }
        val exported = target.resolve("modu-home-release.apk")
        apk.copyTo(exported, overwrite = true)
        target.resolve("release.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(mapOf(
            "applicationId" to metadata["applicationId"], "versionCode" to element["versionCode"],
            "versionName" to element["versionName"], "apk" to exported.name, "sha256" to sha256,
            "certificateSha256" to certificates.single(), "signatureVerified" to true,
            "routingBaseUrl" to routingUrl.get(), "proposalRelayBaseUrl" to relayUrl.get(),
            "vehicleVoiceEnabled" to voiceEnabled.get(),
        ))) + "\n")
        logger.lifecycle("서명을 확인한 APK와 release.json을 내보냈어요")
    }
}

android {
    useLibrary("android.car", false)
    namespace = "io.moduhome.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.moduhome.app"
        minSdk = 28
        targetSdk = 36
        versionCode = 4
        versionName = "0.3.1"
        buildConfigField("boolean", "ENABLE_VEHICLE_VOICE", vehicleVoiceEnabled.getOrElse(false).toString())
        buildConfigField("String", "ROUTING_BASE_URL", stringConstant(routingBaseUrl.orEmpty()))
        buildConfigField("String", "PROPOSAL_RELAY_BASE_URL", stringConstant(proposalRelayBaseUrl.orEmpty()))
    }

    if (releaseSigning != null) {
        signingConfigs.create("packagedRelease") {
            val (settings, store) = releaseSigning
            storeFile = store
            storePassword = settings.getProperty("storePassword")
            keyAlias = settings.getProperty("keyAlias")
            keyPassword = settings.getProperty("keyPassword")
            storeType = settings.getProperty("storeType", "JKS")
        }
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "ENABLE_VEHICLE_VOICE", vehicleVoiceEnabled.getOrElse(true).toString())
            buildConfigField("String", "ROUTING_BASE_URL", stringConstant(routingBaseUrl ?: "http://10.0.2.2:5001"))
            buildConfigField("String", "PROPOSAL_RELAY_BASE_URL", stringConstant(proposalRelayBaseUrl ?: "http://10.0.2.2:48680"))
        }
        release {
            if (releaseSigning != null) signingConfig = signingConfigs.getByName("packagedRelease")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
    releaseApkSigner.set(androidComponents.sdkComponents.sdkDirectory.get().file("build-tools/$buildToolsVersion/lib/apksigner.jar"))
}

tasks.register<ExportReleasePackage>("exportRelease") {
    group = "distribution"
    description = "서명과 HTTPS 설정을 확인한 release APK와 메타데이터 생성"
    dependsOn("assembleRelease")
    packagingEnabled.set(packageRelease)
    routingUrl.set(routingBaseUrl.orEmpty())
    relayUrl.set(proposalRelayBaseUrl.orEmpty())
    voiceEnabled.set(vehicleVoiceEnabled.getOrElse(false))
    apkDirectory.set(layout.buildDirectory.dir("outputs/apk/release"))
    signerJar.set(releaseApkSigner)
    destination.set(layout.buildDirectory.dir("outputs/distribution"))
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.pleos.speech)
    // Keep the Java bridge and native library together on the 16 KB-compatible AAR.
    implementation(libs.jna) { artifact { type = "aar"; extension = "aar" } }
    implementation(libs.jsr305)
    implementation(libs.zxing.core)
    implementation(libs.pleos.navi.helper)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.json)
}
