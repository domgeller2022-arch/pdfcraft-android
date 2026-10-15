import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        FileInputStream(keystorePropertiesFile).use { load(it) }
    }
}

android {
    namespace = "com.domgeller.pdfcraft"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.domgeller.pdfcraft"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1-fold7"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val storePath = keystoreProperties.getProperty("storeFile")
                ?: System.getenv("PDFCRAFT_KEYSTORE_FILE")
            if (!storePath.isNullOrBlank()) {
                storeFile = rootProject.file(storePath)
                storePassword = keystoreProperties.getProperty("storePassword")
                    ?: System.getenv("PDFCRAFT_KEYSTORE_PASSWORD")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                    ?: System.getenv("PDFCRAFT_KEY_ALIAS")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                    ?: System.getenv("PDFCRAFT_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null && releaseSigning.storeFile!!.exists()) {
                signingConfig = releaseSigning
            }
        }
        debug {
            // Debug build uses a separate application ID suffix for coexistence
            // with release builds (debug and release signing keys differ)
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            excludes.addAll(
                listOf(
                    "META-INF/DEPENDENCIES"
                )
            )
        }
    }

    lint {
        xmlReport = true
        htmlReport = true
        abortOnError = true
        warningsAsErrors = false
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.jvmArgs("-Xmx2g")
            }
        }
    }
}

/**
 * Verifies the provenance and SRI of the bundled web build before packaging.
 * Only scripts/build-web.sh may replace web assets; an ignored stale build/web
 * directory must never overwrite a newer bundled build.
 */
val verifyAndSyncWebAssets by tasks.registering {
    group = "verification"
    description = "Verifies bundled PdfCraft web assets, phone patch provenance and SRI."

    doLast {
        val assetsPdfCraftDir = project.file("src/main/assets/pdfcraft")

        // A successful Kotlin build must not silently package the old desktop WASM.
        val provenanceFile = project.file("src/main/assets/provenance.json")
        if (!provenanceFile.isFile) throw GradleException("Run scripts/build-web.sh to build the Fold7 UI and provenance first")
        val provenance = groovy.json.JsonSlurper().parse(provenanceFile) as Map<*, *>
        val patchFile = rootProject.file("patches/pdfcraft-fold7.patch")
        fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        if (provenance["phone_patch_sha256"] != sha256(patchFile)) {
            throw GradleException("Phone patch changed since the web build; rebuild with scripts/build-web.sh")
        }
        val assetHashes = provenance["assets"] as? Map<*, *> ?: throw GradleException("Missing asset hashes in provenance.json")
        for ((name, expected) in assetHashes) {
            val asset = File(assetsPdfCraftDir, name.toString())
            if (!asset.isFile || sha256(asset) != expected) throw GradleException("Stale or modified web asset: $name")
        }
        val indexFile = File(assetsPdfCraftDir, "index.html")
        if (!indexFile.exists() || indexFile.length() == 0L) {
            throw GradleException("Missing or empty PdfCraft web entry point: ${indexFile.absolutePath}")
        }

        val files = assetsPdfCraftDir.listFiles()?.filter { it.isFile }.orEmpty()
        val jsFile = files.firstOrNull { it.name.endsWith(".js") }
            ?: throw GradleException("Missing bundled PdfCraft .js asset in ${assetsPdfCraftDir.absolutePath}")
        val wasmFile = files.firstOrNull { it.name.endsWith(".wasm") }
            ?: throw GradleException("Missing bundled PdfCraft .wasm asset in ${assetsPdfCraftDir.absolutePath}")

        val indexText = indexFile.readText(Charsets.UTF_8)
        for (asset in listOf(jsFile, wasmFile)) {
            if (!indexText.contains(asset.name)) {
                throw GradleException("index.html does not reference bundled asset ${asset.name}")
            }
            val digest = MessageDigest.getInstance("SHA-384").digest(asset.readBytes())
            val sri = "sha384-" + Base64.getEncoder().encodeToString(digest)
            if (indexText.contains("integrity=") && !indexText.contains(sri)) {
                throw GradleException("SRI hash mismatch in index.html for ${asset.name}: expected $sri")
            }
        }
    }
}

tasks.named("preBuild") {
    dependsOn(verifyAndSyncWebAssets)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.window)
    implementation(libs.material)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
